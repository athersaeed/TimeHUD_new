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
import com.boringutils.timehud.blocking.SNAPCHAT_PACKAGE
import com.boringutils.timehud.ui.theme.TimeHUDTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnapchatRuleTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalRule: AppBlockRule? = null

    @Before
    fun remember_existing_rule() {
        originalRule = AppBlockSettings.ruleFor(context, SNAPCHAT_PACKAGE)
        AppBlockSettings.removeRule(context, SNAPCHAT_PACKAGE)
    }

    @After
    fun restore_existing_rule() {
        originalRule?.let { AppBlockSettings.saveRule(context, it) }
            ?: AppBlockSettings.removeRule(context, SNAPCHAT_PACKAGE)
    }

    @Test
    fun spotlight_and_discover_switches_save_independently_without_a_daily_limit() {
        openDialog(null)
        composeRule.onNodeWithText(context.getString(R.string.app_blocking_block_spotlight)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.app_blocking_block_discover)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.app_blocking_discover_note)).assertExists()
        composeRule.onAllNodes(isToggleable())[0].assertIsOff().performClick()
        composeRule.onAllNodes(isToggleable())[1].assertIsOff()
        composeRule.onAllNodes(isToggleable())[2].assertIsOff()
        composeRule.onNodeWithText(context.getString(R.string.app_blocking_save_rule)).performClick()
        composeRule.runOnIdle {
            assertEquals(setOf(AppSurface.SPOTLIGHT), AppBlockSettings.ruleFor(context, SNAPCHAT_PACKAGE)!!.blockedSurfaces)
        }
        composeRule.onAllNodes(isToggleable())[0].assertIsOn().performClick()
        composeRule.onAllNodes(isToggleable())[1].performClick()
        composeRule.onNodeWithText(context.getString(R.string.app_blocking_save_rule)).performClick()
        composeRule.runOnIdle {
            val saved = AppBlockSettings.ruleFor(context, SNAPCHAT_PACKAGE)!!
            assertEquals(setOf(AppSurface.DISCOVER), saved.blockedSurfaces)
            assertEquals(null, saved.dailyLimitMinutes)
        }
    }

    private fun openDialog(rule: AppBlockRule?) {
        composeRule.setContent {
            TimeHUDTheme {
                AppRuleDialog(
                    app = BlockableAppUi(SNAPCHAT_PACKAGE, "Snapchat", 0L, rule),
                    onDismiss = {},
                    onSave = { AppBlockSettings.saveRule(context, it) },
                    onRemove = { AppBlockSettings.removeRule(context, SNAPCHAT_PACKAGE) }
                )
            }
        }
    }
}
