package com.bookcon.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.core.SettingsRepository
import com.bookcon.app.data.repo.AuthRepository
import com.bookcon.app.data.repo.AuthResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Form state + validation errors for PRD AUTH-1 (email sign-in / registration). */
data class AuthUiState(
    val serverUrl: String = SettingsRepository.DEFAULT_SERVER,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val deviceName: String = AuthRepository.suggestedDeviceName(),
    val registerMode: Boolean = false,
    val loading: Boolean = false,
    val serverError: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    /**
     * Non-null when the server URL is plain http and is not loopback. The account
     * password, the bearer token and the long-lived refresh token all travel
     * unencrypted in that case, so the user is told before they sign in rather than
     * discovering it on a hostile network. Loopback is excluded because that is the
     * normal `adb reverse` development setup and is not exposed off-device.
     */
    val insecureWarning: String? = null,
)

sealed interface AuthEvent {
    data object SignedIn : AuthEvent
    data class Message(val text: String) : AuthEvent
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state

    private val _events = Channel<AuthEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Prefill server URL from AppSettings.serverUrl (first DataStore emission wins).
        viewModelScope.launch {
            val saved = settingsRepo.settings.first()
            _state.update {
                it.copy(
                    serverUrl = saved.serverUrl.ifBlank { SettingsRepository.DEFAULT_SERVER },
                    deviceName = if (it.deviceName.isBlank()) AuthRepository.suggestedDeviceName() else it.deviceName,
                )
            }
        }
    }

    /** Keeps the transport warning live as the URL is edited, not only on submit. */
    fun onServerUrlChange(value: String) =
        _state.update {
            it.copy(
                serverUrl = value,
                serverError = null,
                insecureWarning = insecureServerWarning(value.trim().trimEnd('/')),
            )
        }

    fun onEmailChange(value: String) =
        _state.update { it.copy(email = value, emailError = null) }

    fun onPasswordChange(value: String) =
        _state.update { it.copy(password = value, passwordError = null) }

    fun onDisplayNameChange(value: String) =
        _state.update { it.copy(displayName = value) }

    fun onDeviceNameChange(value: String) =
        _state.update { it.copy(deviceName = value) }

    /** Local Vault: switch to on-device-only mode and enter the app without an account. */
    fun useLocalOnly() {
        viewModelScope.launch {
            settingsRepo.setStorageMode("local")
            _events.send(AuthEvent.SignedIn) // session stays null; MainActivity flips to library
        }
    }

    fun setRegisterMode(register: Boolean) =
        _state.update {
            it.copy(
                registerMode = register,
                passwordError = null,
                serverError = null,
                insecureWarning = null,
            )
        }

    /** Client-side validation per PRD AUTH-1, then persist server URL *before* the auth call. */
    fun submit() {
        val s = _state.value
        if (s.loading) return

        var valid = true
        val server = s.serverUrl.trim().trimEnd('/')
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            _state.update { it.copy(serverError = "Use an http(s):// URL") }
            valid = false
        }
        val insecure = insecureServerWarning(server)
        if (insecure != null) {
            _state.update { it.copy(insecureWarning = insecure) }
            // Not a hard error: a self-hosted server on a home LAN is a legitimate
            // setup and this app is built for it. But the user gets to know.
        }
        val email = s.email.trim()
        if (!EMAIL_REGEX.matches(email)) {
            _state.update { it.copy(emailError = "Enter a valid email address") }
            valid = false
        }
        if (s.password.length < MIN_PASSWORD_LENGTH) {
            _state.update { it.copy(passwordError = "Password must be at least $MIN_PASSWORD_LENGTH characters") }
            valid = false
        }
        if (!valid) return

        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            settingsRepo.setServerUrl(server) // saved BEFORE the auth call (contract)
            when (val result = auth.emailAuth(
                serverUrl = server,
                email = email,
                password = s.password,
                displayName = s.displayName.trim(),
                deviceName = s.deviceName.trim().ifBlank { AuthRepository.suggestedDeviceName() },
                registerNew = s.registerMode,
            )) {
                is AuthResult.Success -> {
                    _state.update { it.copy(loading = false) }
                    _events.send(AuthEvent.SignedIn)
                }
                is AuthResult.Failure -> {
                    android.util.Log.e("BookConAuth", "submit failure: ${result.message}")
                    _state.update { it.copy(loading = false, serverError = result.message) }
                    _events.send(AuthEvent.Message(result.message))
                }
            }
        }
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
        private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    }
}

/**
 * Warns when the server URL would carry credentials in cleartext.
 *
 * Returns null for https and for loopback. The check is deliberately narrow: this
 * is a self-hosted app and plain http to a server on the user's own LAN is a
 * normal, expected configuration. What is not acceptable is the user not knowing
 * that their password and refresh token are readable by anything on the path.
 */
internal fun insecureServerWarning(serverUrl: String): String? {
    if (!serverUrl.startsWith("http://")) return null
    val host = hostOf(serverUrl.removePrefix("http://"))
    val loopback = host.equals("localhost", ignoreCase = true) ||
        host == "::1" ||
        host.startsWith("127.")
    if (loopback) return null
    return "This connection is not encrypted. Your password and session token " +
        "will be sent over plain HTTP — use https:// or a trusted network."
}

/**
 * Extracts the host from `host[:port][/path]`.
 *
 * The bracketed IPv6 form has to be handled first: splitting "::1]:8000" on ':'
 * yields "[", so a naive `substringBefore(':')` made [::1] look like a remote
 * address and the loopback development server warned about itself.
 */
private fun hostOf(authorityAndPath: String): String {
    val authority = authorityAndPath.substringBefore('/').trim()
    if (authority.startsWith("[")) return authority.substringAfter('[').substringBefore(']')
    return authority.substringBefore(':').trim()
}
