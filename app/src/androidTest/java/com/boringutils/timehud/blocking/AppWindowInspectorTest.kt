package com.boringutils.timehud.blocking

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boringutils.timehud.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppWindowInspectorTest {
    @Test
    fun x_explore_description_inside_selected_tab_is_recognized() {
        val signals = inspectTab(selected = true, navigationDescription = "Explore")
        assertEquals(AppSurface.EXPLORE, AppSurfaceClassifier.classify(X_PACKAGE, signals))
        assertEquals(setOf("explore"), signals.selectedLabels)
    }

    @Test
    fun unselected_explore_tab_does_not_block_home() {
        val signals = inspectTab(selected = false, navigationDescription = "Explore")
        assertEquals(AppSurface.OTHER, AppSurfaceClassifier.classify(X_PACKAGE, signals))
        assertFalse(signals.hasSelectedLabel("explore"))
    }

    @Test
    fun selected_post_text_and_other_packages_do_not_inherit_explore_selection() {
        val postSignals = inspectTab(selected = true, navigationDescription = null)
        assertEquals(AppSurface.OTHER, AppSurfaceClassifier.classify(X_PACKAGE, postSignals))
        val otherSignals = inspectTab(
            selected = true,
            navigationDescription = "Explore",
            packageName = INSTAGRAM_PACKAGE
        )
        assertFalse(otherSignals.hasSelectedLabel("explore"))
    }

    @Test
    fun instagram_inbox_ignores_hidden_story_navigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val screen = FrameLayout(activity)
                screen.addView(TextView(activity).apply {
                    contentDescription = "Message"
                    isSelected = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }, FrameLayout.LayoutParams(300, 150))
                screen.addView(TextView(activity).apply {
                    contentDescription = "Story by"
                    visibility = View.INVISIBLE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }, FrameLayout.LayoutParams(300, 150))
                activity.setContentView(screen)
            }
            instrumentation.waitForIdleSync()
            val automation = instrumentation.uiAutomation
            automation.waitForIdle(100L, 5_000L)
            val signals = AppWindowInspector.inspect(
                checkNotNull(automation.rootInActiveWindow), null, INSTAGRAM_PACKAGE)
            assertFalse(signals.hasLabel("story by"))
            val surface = AppSurfaceClassifier.classify(INSTAGRAM_PACKAGE, signals)
            assertEquals(AppSurface.MESSAGE_INBOX, surface)
            assertEquals(BlockDecision.Allow, AppBlockDecisionEngine.decide(
                AppBlockRule(INSTAGRAM_PACKAGE, blockedSurfaces = setOf(AppSurface.STORIES)),
                0L, surface))
        }
    }

    @Test
    fun instagram_selected_home_icon_identifies_feed_and_preserves_bottom_navigation() {
        val signals = inspectInstagramNavigation("feed_tab", selected = true)
        assertEquals(AppSurface.HOME_FEED, AppSurfaceClassifier.classify(INSTAGRAM_PACKAGE, signals))
        org.junit.Assert.assertTrue(checkNotNull(signals.instagramFeedBounds).height > 0)
        assertEquals(setOf("home"), signals.selectedLabels)
    }

    @Test
    fun instagram_unselected_or_hidden_home_icon_does_not_identify_feed() {
        listOf(inspectInstagramNavigation("feed_tab", selected = false),
            inspectInstagramNavigation("feed_tab", selected = true, hidden = true)).forEach { signals ->
            assertEquals(AppSurface.OTHER, AppSurfaceClassifier.classify(INSTAGRAM_PACKAGE, signals))
        }
    }

    @Test
    fun instagram_selected_messages_icon_beats_retained_home_header() {
        val signals = inspectInstagramNavigation("direct_tab", selected = true)
        assertEquals(AppSurface.MESSAGE_INBOX, AppSurfaceClassifier.classify(INSTAGRAM_PACKAGE, signals))
    }

    private fun inspectInstagramNavigation(tabId: String, selected: Boolean, hidden: Boolean = false): AppUiSignals {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var navigationBar: View
        return ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fun View.marker(id: String) {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.viewIdResourceName = "$INSTAGRAM_PACKAGE:id/$id"
                        }
                    }
                }
                val screen = FrameLayout(activity)
                screen.addView(TextView(activity).apply { marker("main_feed_action_bar") },
                    FrameLayout.LayoutParams(500, 120))
                val bar = FrameLayout(activity).apply { marker("tab_bar") }
                navigationBar = bar
                val tab = FrameLayout(activity).apply { marker(tabId) }
                tab.addView(TextView(activity).apply {
                    marker("tab_icon")
                    isSelected = selected
                    if (hidden) visibility = View.INVISIBLE
                }, FrameLayout.LayoutParams(100, 100))
                bar.addView(tab, FrameLayout.LayoutParams(200, 120))
                screen.addView(bar, FrameLayout.LayoutParams(500, 120).apply { topMargin = 400 })
                activity.setContentView(screen)
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(100L, 5000L)
            val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            val location = IntArray(2)
            scenario.onActivity { navigationBar.getLocationOnScreen(location) }
            AppWindowInspector.inspect(root, null, INSTAGRAM_PACKAGE).also { signals ->
                assertEquals(location[1], checkNotNull(signals.instagramFeedBounds).bottom)
            }
        }
    }

    private fun inspectTab(
        selected: Boolean,
        navigationDescription: String?,
        packageName: String = X_PACKAGE
    ): AppUiSignals {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val screen = FrameLayout(activity)
                val tab = FrameLayout(activity).apply {
                    isFocusable = true
                    isSelected = selected
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                val child = TextView(activity).apply {
                    text = "Explore"
                    contentDescription = navigationDescription
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                tab.addView(child, FrameLayout.LayoutParams(200, 100))
                screen.addView(tab, FrameLayout.LayoutParams(300, 150))
                activity.setContentView(screen)
            }
            instrumentation.waitForIdleSync()
            val automation = instrumentation.uiAutomation
            automation.waitForIdle(100L, 5_000L)
            val root = checkNotNull(automation.rootInActiveWindow)
            AppWindowInspector.inspect(root, null, packageName)
        }
    }
}
