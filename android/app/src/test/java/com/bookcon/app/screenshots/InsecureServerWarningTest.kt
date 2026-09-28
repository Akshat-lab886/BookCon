package com.bookcon.app.screenshots

import com.bookcon.app.ui.auth.insecureServerWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sign-in screen warns when credentials would travel in cleartext.
 *
 * Getting this wrong in either direction matters: warning on a loopback URL trains
 * the user to dismiss the banner, and not warning on a LAN URL hides that their
 * password and refresh token are readable by anything on the path.
 */
class InsecureServerWarningTest {

    @Test
    fun https_is_never_warned_about() {
        listOf(
            "https://books.example.com",
            "https://192.168.1.10:8000",
            "https://bookcon.local",
        ).forEach { url ->
            assertNull("$url must not warn", insecureServerWarning(url))
        }
    }

    @Test
    fun loopback_http_is_not_warned_about() {
        // The normal `adb reverse` development setup. Not exposed off the device,
        // so a warning here would be pure noise.
        listOf(
            "http://127.0.0.1:8000",
            "http://localhost:8000",
            "http://[::1]:8000",
            "http://127.0.0.53:8000",
        ).forEach { url ->
            assertNull("$url must not warn", insecureServerWarning(url))
        }
    }

    @Test
    fun remote_plain_http_is_warned_about() {
        listOf(
            "http://192.168.1.10:8000",
            "http://books.example.com",
            "http://10.0.0.5",
            "http://nas.local:8000",
        ).forEach { url ->
            assertNotNull("$url must warn", insecureServerWarning(url))
        }
    }

    @Test
    fun a_trailing_path_does_not_confuse_host_parsing() {
        // "http://evil.example.com/localhost/" must not be read as loopback.
        assertNotNull(insecureServerWarning("http://evil.example.com/localhost"))
        assertNull(insecureServerWarning("http://127.0.0.1:8000/api"))
    }

    @Test
    fun the_warning_says_what_is_at_risk() {
        val warning = insecureServerWarning("http://192.168.1.10:8000")!!
        assertEquals(true, warning.contains("not encrypted", ignoreCase = true))
        assertEquals(true, warning.contains("password", ignoreCase = true))
    }
}
