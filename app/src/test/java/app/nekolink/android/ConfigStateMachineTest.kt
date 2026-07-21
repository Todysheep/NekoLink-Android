package app.nekolink.android

import app.nekolink.android.domain.ClientConfig
import app.nekolink.android.domain.ConfigStateMachine
import app.nekolink.android.domain.StoredCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigStateMachineTest {
    private fun pairedState(): ConfigStateMachine.State {
        val creds = StoredCredentials(
            deviceId = "dev_1",
            deviceToken = "tok_secret",
            displayName = "Phone",
            serverBase = "http://old.example:8080",
            pairedAt = "2026-07-21T00:00:00Z",
        )
        return ConfigStateMachine.State(
            config = ClientConfig(serverBase = "http://old.example:8080", displayName = "Phone"),
            credentials = creds,
        )
    }

    @Test
    fun changeServerUrl_clearsCredentials() {
        val state = pairedState()
        assertTrue(state.isPaired)
        val next = ConfigStateMachine.changeServerUrlConfirmed(state, "https://new.example/")
        assertFalse(next.isPaired)
        assertNull(next.credentials)
        assertEquals("https://new.example", next.config.serverBase)
        assertEquals("Phone", next.config.displayName)
    }

    @Test
    fun unpair_clearsCredentials_keepsDisplayName() {
        val next = ConfigStateMachine.unpair(pairedState())
        assertFalse(next.isPaired)
        assertNull(next.credentials)
        assertEquals("Phone", next.config.displayName)
    }

    @Test
    fun updateDisplayName_doesNotClearPair() {
        val next = ConfigStateMachine.updateDisplayName(pairedState(), "新手机")
        assertTrue(next.isPaired)
        assertEquals("新手机", next.config.displayName)
        assertEquals("新手机", next.credentials?.displayName)
        assertEquals("tok_secret", next.credentials?.deviceToken)
    }

    @Test
    fun applyPair_storesCreds() {
        val empty = ConfigStateMachine.State(
            config = ClientConfig(serverBase = "http://s", displayName = "X"),
        )
        val creds = StoredCredentials(
            deviceId = "dev_x",
            deviceToken = "tok_x",
            displayName = "X",
            serverBase = "http://s",
            pairedAt = "t",
        )
        val next = ConfigStateMachine.applyPair(empty, creds)
        assertTrue(next.isPaired)
        assertEquals("dev_x", next.credentials?.deviceId)
    }
}
