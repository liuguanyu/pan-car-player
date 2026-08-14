package com.panmusic.carplayer.repository

import com.panmusic.carplayer.SessionManager
import com.panmusic.carplayer.data.remote.BaiduOAuthService
import com.panmusic.carplayer.model.AuthInfo
import com.panmusic.carplayer.model.TokenResponse
import com.panmusic.carplayer.testutil.MainCoroutineRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryTest {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private val oauthService: BaiduOAuthService = mockk(relaxed = true)
    private val sessionManager: SessionManager = mockk(relaxed = true)

    // ==================== isLoggedIn ====================

    @Test
    fun `isLoggedIn returns sessionManager isLoggedIn value true`() = runTest {
        every { sessionManager.isLoggedIn } returns MutableStateFlow(true)
        val repository = AuthRepository(oauthService, sessionManager)

        assertTrue(repository.isLoggedIn())
    }

    @Test
    fun `isLoggedIn returns sessionManager isLoggedIn value false`() = runTest {
        every { sessionManager.isLoggedIn } returns MutableStateFlow(false)
        val repository = AuthRepository(oauthService, sessionManager)

        assertFalse(repository.isLoggedIn())
    }

    // ==================== getValidAccessToken ====================

    @Test
    fun `getValidAccessToken delegates to sessionManager getAccessToken`() = runTest {
        every { sessionManager.isLoggedIn } returns MutableStateFlow(true)
        every { sessionManager.getAccessToken() } returns "test-token-123"
        val repository = AuthRepository(oauthService, sessionManager)

        val token = repository.getValidAccessToken()

        assertEquals("test-token-123", token)
        verify(exactly = 1) { sessionManager.getAccessToken() }
    }

    @Test
    fun `getValidAccessToken returns null when sessionManager returns null`() = runTest {
        every { sessionManager.isLoggedIn } returns MutableStateFlow(false)
        every { sessionManager.getAccessToken() } returns null
        val repository = AuthRepository(oauthService, sessionManager)

        val token = repository.getValidAccessToken()

        assertNull(token)
    }

    // ==================== executeTokenRefresh ====================

    @Test
    fun `executeTokenRefresh success calls refreshToken API and saves token`() = runTest {
        every { sessionManager.isLoggedIn } returns MutableStateFlow(true)
        every { sessionManager.getRefreshToken() } returns "valid-refresh-token"

        val mockTokenResponse = TokenResponse(
            accessToken = "new-access-token",
            refreshToken = "new-refresh-token",
            expiresIn = 3600,
            scope = "basic,netdisk"
        )
        val mockResponse: retrofit2.Response<TokenResponse> = mockk()
        every { mockResponse.isSuccessful } returns true
        every { mockResponse.body() } returns mockTokenResponse
        coEvery { oauthService.refreshToken(any(), any(), any()) } returns mockResponse

        val repository = AuthRepository(oauthService, sessionManager)
        val result = repository.executeTokenRefresh()

        assertTrue(result.isSuccess)
        val authInfo = result.getOrNull()!!
        assertEquals("new-access-token", authInfo.accessToken)
        assertEquals("new-refresh-token", authInfo.refreshToken)
        assertEquals(3600, authInfo.expiresIn)
        coVerify(exactly = 1) { oauthService.refreshToken("valid-refresh-token", any(), any()) }
        verify(exactly = 1) {
            sessionManager.saveToken("new-access-token", "new-refresh-token", 3600)
        }
    }

    @Test
    fun `executeTokenRefresh failure when no refresh_token`() = runTest {
        every { sessionManager.isLoggedIn } returns MutableStateFlow(true)
        every { sessionManager.getRefreshToken() } returns null

        val repository = AuthRepository(oauthService, sessionManager)
        val result = repository.executeTokenRefresh()

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()!!
        assertEquals("本地无 refresh_token，需要重新登录", exception.message)
    }

    @Test
    fun `executeTokenRefresh failure when API returns unsuccessful response`() = runTest {
        every { sessionManager.isLoggedIn } returns MutableStateFlow(true)
        every { sessionManager.getRefreshToken() } returns "expired-refresh-token"

        val mockResponse: retrofit2.Response<TokenResponse> = mockk()
        every { mockResponse.isSuccessful } returns false
        every { mockResponse.code() } returns 401
        every { mockResponse.body() } returns null
        coEvery { oauthService.refreshToken(any(), any(), any()) } returns mockResponse

        val repository = AuthRepository(oauthService, sessionManager)
        val result = repository.executeTokenRefresh()

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()!!
        assertTrue(exception.message!!.contains("HTTP 401"))
    }

    // ==================== logout ====================

    @Test
    fun `logout delegates to sessionManager logout`() = runTest {
        every { sessionManager.isLoggedIn } returns MutableStateFlow(true)
        val repository = AuthRepository(oauthService, sessionManager)

        repository.logout()

        verify(exactly = 1) { sessionManager.logout() }
    }

    // ==================== Init state ====================

    @Test
    fun `init state is LoggedIn when sessionManager isLoggedIn is true`() = runTest {
        every { sessionManager.isLoggedIn } returns MutableStateFlow(true)

        val repository = AuthRepository(oauthService, sessionManager)

        assertEquals(AuthState.LoggedIn, repository.authState.value)
    }

    @Test
    fun `init state is Initial when sessionManager isLoggedIn is false`() = runTest {
        every { sessionManager.isLoggedIn } returns MutableStateFlow(false)

        val repository = AuthRepository(oauthService, sessionManager)

        assertEquals(AuthState.Initial, repository.authState.value)
    }
}
