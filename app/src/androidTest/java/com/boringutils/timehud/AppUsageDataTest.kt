package com.boringutils.timehud

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUsageDataTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun grant_usage_access() {
        setUsageAccess("allow")
        composeRule.activityRule.scenario.recreate()
    }

    @After
    fun restore_usage_access() {
        setUsageAccess("default")
    }

    @Test
    fun usage_history_renders_refreshes_and_survives_activity_recreation() {
        val context = composeRule.activity
        composeRule.onNodeWithContentDescription(context.getString(R.string.open_navigation_menu))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.nav_app_usage)).performClick()
        waitForHistory()
        composeRule.onNodeWithText(context.getString(R.string.app_usage_screen_time)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.refresh)).performClick()
        waitForHistory()

        val chooser = context.getString(R.string.app_usage_choose_period)
        composeRule.onNodeWithContentDescription(chooser).performClick()
        composeRule.onNodeWithText(context.getString(R.string.app_usage_yesterday)).performClick()
        waitForHistory()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText(context.getString(R.string.app_usage_yesterday)).assertExists()
        waitForHistory()

        composeRule.onNodeWithContentDescription(chooser).performClick()
        composeRule.onNodeWithText(context.getString(R.string.app_usage_week)).performClick()
        waitForHistory()
        composeRule.onNodeWithText(context.getString(R.string.app_usage_screen_time)).assertExists()
    }

    private fun waitForHistory() {
        // A successful provider response sets the refresh timestamp. This avoids
        // mistaking the initial zero-valued summary for loaded usage history.
        val context = composeRule.activity
        val updatePrefix = context.getString(R.string.app_usage_updated, "").trim()
        composeRule.waitUntil(timeoutMillis = 20_000L) {
            composeRule.onAllNodes(hasText(updatePrefix, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun setUsageAccess(mode: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("appops set com.boringutils.timehud GET_USAGE_STATS $mode")
            .use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
    }
}
