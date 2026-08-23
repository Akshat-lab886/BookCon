package com.bookcon.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.core.SessionStore
import com.bookcon.app.data.remote.DeviceDto
import com.bookcon.app.data.repo.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DevicesUiState(
    val loading: Boolean = true,
    val devices: List<DeviceDto> = emptyList(),
    val currentDeviceId: String? = null,
)

sealed interface DevicesEvent {
    data class Message(val text: String) : DevicesEvent
}

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val sessions: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state

    private val _events = Channel<DevicesEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val devices = auth.listDevices()
            _state.update {
                it.copy(loading = false, devices = devices, currentDeviceId = sessions.current()?.deviceId)
            }
        }
    }

    /** PRD AUTH-4: revoking a device invalidates its refresh token server-side. */
    fun revoke(deviceId: String) {
        viewModelScope.launch {
            val ok = auth.removeDevice(deviceId)
            _events.send(
                DevicesEvent.Message(if (ok) "Device removed" else "Could not remove device (offline?)"),
            )
            refresh()
        }
    }
}
