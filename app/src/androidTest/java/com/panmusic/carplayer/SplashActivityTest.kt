package com.panmusic.carplayer

import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.panmusic.carplayer.data.settings.SettingsRepository
import com.panmusic.carplayer.migration.AuthMigration
import com.panmusic.carplayer.ui.SplashActivity
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import com.panmusic.carplayer.di.AuthModule
import com.panmusic.carplayer.di.SettingsModule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * SplashActivity 启动集成测试
 *
 * 验证 SplashActivity 在 Hilt 环境下正常启动不崩溃。
 * 由于 checkStatus() 使用 Handler.postDelayed 延迟 1s 触发导航，
 * 测试仅验证 Activity 创建成功，不检查后续导航行为。
 */
@HiltAndroidTest
@UninstallModules(AuthModule::class, SettingsModule::class)
class SplashActivityTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(SplashActivity::class.java)

    @BindValue
    @JvmField
    val authMigration: AuthMigration = mockk(relaxed = true)

    @BindValue
    @JvmField
    val sessionManager: SessionManager = mockk(relaxed = true) {
        // 模拟未登录状态 — checkStatus() 会跳转 LoginActivity 然后 finish()
        // Handler.postDelayed(R, 1000) 给了足够时间验证 Activity 已成功创建
        every { isLoggedIn } returns MutableStateFlow(false)
    }

    @BindValue
    @JvmField
    val settingsRepository: SettingsRepository = mockk(relaxed = true)

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun splashActivity_launchesWithoutCrash() {
        activityRule.scenario.onActivity { activity ->
            assertNotNull("SplashActivity 应成功创建", activity)
        }
    }
}
