package com.boringutils.timehud.ui.blocking

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boringutils.timehud.R
import com.boringutils.timehud.blocking.AppBlockRule
import com.boringutils.timehud.blocking.AppBlockSettings
import com.boringutils.timehud.blocking.AppSurface
import com.boringutils.timehud.blocking.X_PACKAGE
import com.boringutils.timehud.ui.theme.TimeHUDTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class XVideoFeedRuleTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalRule: AppBlockRule? = null

    @Before
    fun remember_existing_rule() {
        originalRule = AppBlockSettings.ruleFor(context, X_PACKAGE)
        AppBlockSettings.removeRule(context, X_PACKAGE)
    }

    @After
    fun restore_existing_rule() {
        originalRule?.let { AppBlockSettings.saveRule(context, it) }
            ?: AppBlockSettings.removeRule(context, X_PACKAGE)
    }

    @Test
    fun video_feed_is_optional_and_can_be_saved_without_a_daily_limit() {
        openDialog(null)
        composeRule.onNodeWithText(context.getString(R.string.app_blocking_block_video_feed))
            .assertExists()
        composeRule.onNodeWithText(context.getString(R.string.app_blocking_video_feed_note))
            .assertExists()
        composeRule.onAllNodes(isToggleable())[0].assertIsOff()
        composeRule.onAllNodes(isToggleable())[1].assertIsOff().performClick()
        composeRule.onNodeWithText(context.getString(R.string.app_blocking_save_rule)).performClick()
        composeRule.runOnIdle {
            assertEquals(
                AppBlockRule(X_PACKAGE, blockedSurfaces = setOf(AppSurface.VIDEO_FEED), allowMessages = false),
                AppBlockSettings.ruleFor(context, X_PACKAGE)
            )
        }
    }

    @Test
    fun existing_explore_and_daily_limit_survive_enabling_then_disabling_video_feed() {
        val existing = AppBlockRule(
            X_PACKAGE,
            dailyLimitMinutes = 30,
            blockedSurfaces = setOf(AppSurface.EXPLORE),
            allowMessages = false
        )
        AppBlockSettings.saveRule(context, existing)
        openDialog(AppBlockSettings.ruleFor(context, X_PACKAGE))
        composeRule.onAllNodes(isToggleable())[0].assertIsOn()
        composeRule.onAllNodes(isToggleable())[1].assertIsOff().performClick()
        composeRule.onNodeWithText(context.getString(R.string.app_blocking_save_rule)).performClick()
        composeRule.runOnIdle {
            val saved = AppBlockSettings.ruleFor(context, X_PACKAGE)!!
            assertEquals(existing.copy(blockedSurfaces = setOf(AppSurface.EXPLORE, AppSurface.VIDEO_FEED)), saved)
            AppBlockSettings.saveRule(context, saved.copy(blockedSurfaces = saved.blockedSurfaces - AppSurface.VIDEO_FEED))
            assertEquals(existing, AppBlockSettings.ruleFor(context, X_PACKAGE))
        }
    }

    private fun openDialog(rule: AppBlockRule?) {
        composeRule.setContent {
            TimeHUDTheme {
                AppRuleDialog(
                    app = BlockableAppUi(X_PACKAGE, "X", 0L, rule),
                    onDismiss = {},
                    onSave = { AppBlockSettings.saveRule(context, it) },
                    onRemove = { AppBlockSettings.removeRule(context, X_PACKAGE) }
                )
            }
        }
    }
}
