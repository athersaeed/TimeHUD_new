package com.boringutils.timehud

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun drawer_opens_app_usage_brick_mode_app_limits_and_permissions_pages() {
        val context = composeRule.activity
        val openMenu = context.getString(R.string.open_navigation_menu)

        composeRule.onNodeWithContentDescription(openMenu).performClick()
        composeRule.onNodeWithText(context.getString(R.string.nav_app_usage)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.app_usage_heading)).assertExists()

        composeRule.onNodeWithContentDescription(openMenu).performClick()
        composeRule.onNodeWithText(context.getString(R.string.nav_brick_mode)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.brick_mode_heading)).assertExists()

        composeRule.onNodeWithContentDescription(openMenu).performClick()
        composeRule.onNodeWithText(context.getString(R.string.nav_app_limits)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.app_blocking_heading)).assertExists()

        composeRule.onNodeWithContentDescription(openMenu).performClick()
        composeRule.onNodeWithText(context.getString(R.string.nav_permissions)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.permissions_description)).assertExists()
    }
}
