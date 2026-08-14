package com.panmusic.carplayer.util

import android.content.SharedPreferences
import app.cash.turbine.test
import com.panmusic.carplayer.SessionManager
import com.panmusic.carplayer.SessionEvent
import com.panmusic.carplayer.data.settings.SettingsRepository
import com.panmusic.carplayer.model.AuthInfo
import com.panmusic.carplayer.testutil.MainCoroutineRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionManagerTest {
    @get:Rule val mainCoroutineRule = MainCoroutineRule()

    private val authPrefs = mockk<SharedPreferences>(relaxed = true)
    private val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)

    private fun createSessionManager(): SessionManager {
        every { authPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.putLong(any(), any()) } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockEditor.putInt(any(), any()) } returns mockEditor
        every { mockEditor.clear() } returns mockEditor
        return SessionManager(authPrefs, settingsRepo)
    }

    // ==================== checkLoggedInFast ====================

    @Test fun `checkLoggedInFast returns true when valid token exists`() = runTest {
        every { authPrefs.getString("access_token", null) } returns "valid_token"
        every { authPrefs.getLong("expires_in", 0) } returns 86400
        every { authPrefs.getLong("token_obtained_at", 0) } returns System.currentTimeMillis()

        val manager = createSessionManager()
        assertTrue(manager.checkLoggedInFast())
    }

    @Test fun `checkLoggedInFast returns false when no access_token`() = runTest {
        every { authPrefs.getString("access_token", null) } returns null

        val manager = createSessionManager()
        assertFalse(manager.checkLoggedInFast())
    }

    @Test fun `checkLoggedInFast returns false when token expired`() = runTest {
        every { authPrefs.getString("access_token", null) } returns "expired_token"
        every { authPrefs.getLong("expires_in", 0) } returns 1
        every { authPrefs.getLong("token_obtained_at", 0) } returns 0

        val manager = createSessionManager()
        assertFalse(manager.checkLoggedInFast())
    }

    // ==================== getAccessToken ====================

    @Test fun `getAccessToken returns token when not near expiry`() = runTest {
        every { authPrefs.getString("access_token", null) } returns "fresh_token"
        every { authPrefs.getLong("expires_in", 0) } returns 86400
        every { authPrefs.getLong("token_obtained_at", 0) } returns System.currentTimeMillis()

        val manager = createSessionManager()
        assertEquals("fresh_token", manager.getAccessToken())
    }

    @Test fun `getAccessToken returns null when token within 5-minute buffer`() = runTest {
        every { authPrefs.getString("access_token", null) } returns "old_token"
        every { authPrefs.getLong("expires_in", 0) } returns 300
        every { authPrefs.getLong("token_obtained_at", 0) } returns 0

        val manager = createSessionManager()
        assertNull(manager.getAccessToken())
    }

    @Test fun `getAccessToken returns null when no token`() = runTest {
        every { authPrefs.getString("access_token", null) } returns null

        val manager = createSessionManager()
        assertNull(manager.getAccessToken())
    }

    // ==================== saveToken ====================

    @Test fun `saveToken persists tokens and sets isLoggedIn true`() = runTest {
        val manager = createSessionManager()

        manager.saveToken("access_abc", "refresh_xyz", 3600L)

        verify { mockEditor.putString("access_token", "access_abc") }
        verify { mockEditor.putString("refresh_token", "refresh_xyz") }
        verify { mockEditor.putLong("expires_in", 3600L) }
        verify { mockEditor.putLong("token_obtained_at", any()) }
        verify { mockEditor.apply() }
        verify { settingsRepo.setLoggedIn(true) }
        assertTrue(manager.isLoggedIn.value)
    }

    // ==================== logout ====================

    @Test fun `logout clears prefs sets isLoggedIn false and emits LoggedOut`() = runTest {
        val manager = createSessionManager()

        manager.sessionEvents.test {
            manager.logout()

            verify { mockEditor.clear() }
            verify { mockEditor.apply() }
            verify { settingsRepo.setLoggedIn(false) }
            assertFalse(manager.isLoggedIn.value)
            assertEquals(SessionEvent.LoggedOut, awaitItem())
        }
    }

    // ==================== tryRefreshToken ====================

    @Test fun `tryRefreshToken returns AuthInfo on success without emitting event`() = runTest {
        every { authPrefs.getString("access_token", null) } returns null
        val manager = createSessionManager()

        val authInfo = AuthInfo(accessToken = "new_access", refreshToken = "new_refresh", expiresIn = 7200L)
        val refresher: suspend () -> Result<AuthInfo> = { Result.success(authInfo) }

        manager.sessionEvents.test {
            val result = manager.tryRefreshToken(refresher)

            assertNotNull(result)
            assertEquals("new_access", result!!.accessToken)
            assertEquals("new_refresh", result.refreshToken)
            assertEquals(7200L, result.expiresIn)
            assertTrue(manager.isLoggedIn.value)
        }
    }

    @Test fun `tryRefreshToken returns null and emits AuthExpired on failure`() = runTest {
        every { authPrefs.getString("access_token", null) } returns null
        val manager = createSessionManager()

        val refresher: suspend () -> Result<AuthInfo> = { Result.failure(Exception("refresh failed")) }

        manager.sessionEvents.test {
            val result = manager.tryRefreshToken(refresher)

            assertNull(result)
            verify { mockEditor.clear() }
            verify { mockEditor.apply() }
            verify { settingsRepo.setLoggedIn(false) }
            assertFalse(manager.isLoggedIn.value)
            assertEquals(SessionEvent.AuthExpired, awaitItem())
        }
    }

    @Test fun `tryRefreshToken double-checks and skips refresh when token valid inside lock`() = runTest {
        every { authPrefs.getString("access_token", null) } returns "still_valid"
        every { authPrefs.getLong("expires_in", 0) } returns 86400
        every { authPrefs.getLong("token_obtained_at", 0) } returns System.currentTimeMillis()
        every { authPrefs.getString("refresh_token", "") } returns "stored_refresh"
        val manager = createSessionManager()

        // refresher throws if called — double-check should prevent invocation
        val refresher: suspend () -> Result<AuthInfo> = { error("refresher should not be called") }

        manager.sessionEvents.test {
            val result = manager.tryRefreshToken(refresher)

            assertNotNull(result)
            assertEquals("still_valid", result!!.accessToken)
            assertEquals("stored_refresh", result.refreshToken)
            assertTrue(manager.isLoggedIn.value)
        }
    }

    // ==================== onApiAuthError ====================

    @Test fun `onApiAuthError returns access token on successful refresh`() = runTest {
        every { authPrefs.getString("access_token", null) } returns null
        val manager = createSessionManager()

        val authInfo = AuthInfo(accessToken = "api_new_token", refreshToken = "api_refresh", expiresIn = 3600L)
        val refresher: suspend () -> Result<AuthInfo> = { Result.success(authInfo) }

        manager.sessionEvents.test {
            val token = manager.onApiAuthError(refresher)

            assertEquals("api_new_token", token)
            assertTrue(manager.isLoggedIn.value)
        }
    }

    @Test fun `onApiAuthError returns null on refresh failure`() = runTest {
        every { authPrefs.getString("access_token", null) } returns null
        val manager = createSessionManager()

        val refresher: suspend () -> Result<AuthInfo> = { Result.failure(Exception("refresh failed")) }

        manager.sessionEvents.test {
            val token = manager.onApiAuthError(refresher)

            assertNull(token)
            assertFalse(manager.isLoggedIn.value)
            assertEquals(SessionEvent.AuthExpired, awaitItem())
        }
    }

    // ==================== getRefreshToken ====================

    @Test fun `getRefreshToken returns stored refresh token`() = runTest {
        every { authPrefs.getString("refresh_token", null) } returns "my_refresh_token"

        val manager = createSessionManager()
        assertEquals("my_refresh_token", manager.getRefreshToken())
    }

    @Test fun `getRefreshToken returns null when no refresh token`() = runTest {
        every { authPrefs.getString("refresh_token", null) } returns null

        val manager = createSessionManager()
        assertNull(manager.getRefreshToken())
    }

    // ==================== isLoggedIn initial state ====================

    @Test fun `isLoggedIn initial value is true when valid token exists at construction`() = runTest {
        every { authPrefs.getString("access_token", null) } returns "token_init"
        every { authPrefs.getLong("expires_in", 0) } returns 86400
        every { authPrefs.getLong("token_obtained_at", 0) } returns System.currentTimeMillis()

        val manager = createSessionManager()
        assertTrue(manager.isLoggedIn.value)
    }

    @Test fun `isLoggedIn initial value is false when no token at construction`() = runTest {
        every { authPrefs.getString("access_token", null) } returns null

        val manager = createSessionManager()
        assertFalse(manager.isLoggedIn.value)
    }
}
