package com.boringutils.timehud.ui.blocking

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.boringutils.timehud.R
import com.boringutils.timehud.blocking.AppBlockRule
import com.boringutils.timehud.blocking.AppBlockSettings
import com.boringutils.timehud.blocking.AppSurface
import com.boringutils.timehud.blocking.INSTAGRAM_PACKAGE
import com.boringutils.timehud.ui.theme.TimeHUDTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class InstagramHomeFeedRuleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun home_feed_is_opt_in_and_saves_independently_of_other_sections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = AppBlockSettings.ruleFor(context, INSTAGRAM_PACKAGE)
        try {
            composeRule.setContent {
                TimeHUDTheme {
                    AppRuleDialog(
                        app = BlockableAppUi(INSTAGRAM_PACKAGE, "Instagram", 0L, null),
                        onDismiss = {},
                        onSave = { AppBlockSettings.saveRule(context, it) },
                        onRemove = { AppBlockSettings.removeRule(context, INSTAGRAM_PACKAGE) }
                    )
                }
            }
            composeRule.onNodeWithText(context.getString(R.string.app_blocking_block_home_feed)).assertExists()
            composeRule.onAllNodes(isToggleable())[0].assertIsOff().performClick()
            composeRule.onNodeWithText(context.getString(R.string.app_blocking_save_rule)).performClick()
            composeRule.runOnIdle {
                val saved = checkNotNull(AppBlockSettings.ruleFor(context, INSTAGRAM_PACKAGE))
                assertEquals(setOf(AppSurface.HOME_FEED), saved.blockedSurfaces)
                assertNull(saved.dailyLimitMinutes)
                assertTrue(saved.allowMessages)
                assertFalse(saved.browserInstagramTest)
            }
            composeRule.onAllNodes(isToggleable())[0].performScrollTo().performClick()
            composeRule.onNodeWithText(context.getString(R.string.app_blocking_save_rule)).performClick()
            composeRule.runOnIdle {
                assertNull(AppBlockSettings.ruleFor(context, INSTAGRAM_PACKAGE))
            }
        } finally {
            original?.let { AppBlockSettings.saveRule(context, it) }
                ?: AppBlockSettings.removeRule(context, INSTAGRAM_PACKAGE)
            assertTrue(context.getSharedPreferences("timehud_app_blocking", 0).edit().commit())
        }
    }
}
