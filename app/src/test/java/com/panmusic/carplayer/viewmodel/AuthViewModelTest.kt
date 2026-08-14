package com.panmusic.carplayer.viewmodel

import app.cash.turbine.test
import com.panmusic.carplayer.model.AuthInfo
import com.panmusic.carplayer.model.DeviceCodeResponse
import com.panmusic.carplayer.repository.AuthRepository
import com.panmusic.carplayer.testutil.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AuthViewModelTest {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private lateinit var authRepository: AuthRepository

    @Before
    fun setUp() {
        authRepository = mockk(relaxed = true)
    }

    @Test
    fun `init checks login status - when isLoggedIn returns true, uiState isLoggedIn should be true`() = runTest {
        every { authRepository.isLoggedIn() } returns true

        val viewModel = AuthViewModel(authRepository)

        assertEquals(true, viewModel.uiState.value.isLoggedIn)
    }

    @Test
    fun `init when not logged in - uiState isLoggedIn should be false`() = runTest {
        every { authRepository.isLoggedIn() } returns false

        val viewModel = AuthViewModel(authRepository)

        assertEquals(false, viewModel.uiState.value.isLoggedIn)
    }

    @Test
    fun `requestDeviceCode success - sets isLoading, deviceCode, userCode, qrUrl, isPolling and eventually isLoggedIn`() =
        runTest {
            every { authRepository.isLoggedIn() } returns false
            coEvery { authRepository.requestDeviceCode() } returns Result.success(
                DeviceCodeResponse(
                    deviceCode = "abc123",
                    userCode = "user456",
                    verificationUrl = "https://example.com/verify"
                )
            )
            coEvery { authRepository.pollForToken("abc123") } returns Result.success(AuthInfo())

            val viewModel = AuthViewModel(authRepository)

            viewModel.uiState.test {
                // skip initial state set in init
                awaitItem()

                viewModel.requestDeviceCode()

                val state = awaitItem()
                assertEquals(false, state.isLoading)
                assertEquals("abc123", state.deviceCode)
                assertEquals("user456", state.userCode)
                assertEquals("https://example.com/verify?code=user456", state.qrUrl)
                assertEquals(false, state.isPolling)
                assertEquals(true, state.isLoggedIn)
                assertNull(state.error)
            }
        }

    @Test
    fun `requestDeviceCode failure - sets error`() = runTest {
        every { authRepository.isLoggedIn() } returns false
        coEvery { authRepository.requestDeviceCode() } returns Result.failure(
            RuntimeException("Network error")
        )

        val viewModel = AuthViewModel(authRepository)

        viewModel.uiState.test {
            awaitItem()

            viewModel.requestDeviceCode()

            val state = awaitItem()
            assertEquals(false, state.isLoading)
            assertEquals("Network error", state.error)
        }
    }

    @Test
    fun `logout - resets uiState to default and delegates to authRepository`() = runTest {
        every { authRepository.isLoggedIn() } returns true
        every { authRepository.logout() } just runs

        val viewModel = AuthViewModel(authRepository)

        // verify initial state reflects logged-in
        assertTrue(viewModel.uiState.value.isLoggedIn)

        viewModel.logout()

        val state = viewModel.uiState.value
        assertEquals(false, state.isLoading)
        assertEquals(false, state.isLoggedIn)
        assertEquals(false, state.isPolling)
        assertEquals("", state.deviceCode)
        assertEquals("", state.userCode)
        assertEquals("", state.qrUrl)
        assertNull(state.error)

        verify(exactly = 1) { authRepository.logout() }
    }
}