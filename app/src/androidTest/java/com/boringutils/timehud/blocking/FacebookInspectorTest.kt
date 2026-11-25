package com.boringutils.timehud.blocking

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.boringutils.timehud.MainActivity
import org.junit.Assert.*
import org.junit.Test

class FacebookInspectorTest {
    @Test fun home_requires_selected_native_tab_and_visible_fixed_toolbar() {
        val markers = listOf(
            Marker("Home, tab 1 of 6", tab = true, selected = true, top = 1200),
            Marker("Facebook logo", view = true, top = 0),
            Marker("Search Facebook", button = true, top = 0)
        )
        val signals = inspect(markers)
        assertTrue(signals.facebookHomeFeedOpen)
        assertEquals(AppSurface.HOME_FEED, AppSurfaceClassifier.classify(FACEBOOK_PACKAGE, signals))
        assertNotNull(signals.facebookHomeBounds)
        assertTrue(signals.facebookHomeBounds!!.height > 0)
        assertTrue(signals.facebookHomeBounds!!.height < 1300)
        for (invalid in listOf(markers.drop(1), markers.take(1),
            listOf(markers[0].copy(tab = false)) + markers.drop(1),
            listOf(markers[0].copy(selected = false)) + markers.drop(1),
            listOf(markers[0]) + markers.drop(1).map { it.copy(hidden = true) },
            listOf(markers[0]) + markers.drop(1).map { it.copy(offscreen = true) })) {
            val other = inspect(invalid)
            assertFalse(other.facebookHomeFeedOpen)
            assertEquals(AppSurface.OTHER, AppSurfaceClassifier.classify(FACEBOOK_PACKAGE, other))
        }
    }

    @Test fun selected_facebook_tabs_with_position_labels_are_recognized() {
        for (packageName in listOf(FACEBOOK_PACKAGE, FACEBOOK_LITE_PACKAGE)) {
            for ((label, surface) in listOf("Reels" to AppSurface.REELS, "Marketplace" to AppSurface.MARKETPLACE)) {
                val signals = inspect(listOf(Marker("$label, tab 2 of 6", tab = true, selected = true)), packageName)
                assertTrue(signals.selectedLabels.contains(label.lowercase()))
                assertEquals(surface, AppSurfaceClassifier.classify(packageName, signals))
            }
        }
    }

    @Test fun unselected_hidden_offscreen_and_non_tab_descriptions_do_not_select_sections() {
        listOf(Marker("Reels, tab 2 of 6", tab = true),
            Marker("Reels, tab 2 of 6", tab = true, selected = true, hidden = true),
            Marker("Marketplace, tab 4 of 6", tab = true, selected = true, offscreen = true),
            Marker("Reels, tab 2 of 6", selected = true),
            Marker("Marketplace, tab 4 of 6", selected = true)).forEach { marker ->
            val signals = inspect(listOf(marker))
            assertFalse(signals.selectedLabels.contains("reels"))
            assertFalse(signals.selectedLabels.contains("marketplace"))
            assertEquals(AppSurface.OTHER, AppSurfaceClassifier.classify(FACEBOOK_PACKAGE, signals))
        }
    }

    @Test fun story_reply_button_identifies_viewer_even_without_share_and_does_not_retain_recipient() {
        val reply = "Send message, reply to PRIVATE_RECIPIENT_FIXTURE's story"
        for (markers in listOf(listOf(Marker(reply, button = true)),
            listOf(Marker("Share story", button = true), Marker(reply, button = true)))) {
            val signals = inspect(markers)
            assertTrue(signals.facebookStoryViewerOpen)
            assertEquals(AppSurface.STORIES, AppSurfaceClassifier.classify(FACEBOOK_PACKAGE, signals))
            assertFalse(signals.labels.any { "private_recipient_fixture" in it })
        }
    }

    @Test fun story_preview_share_alone_and_hidden_reply_do_not_identify_viewer() {
        val reply = "Send message, reply to PRIVATE_RECIPIENT_FIXTURE's story"
        listOf(listOf(Marker("Share story", button = true)),
            listOf(Marker("Share story"), Marker(reply)),
            listOf(Marker("Share story", button = true), Marker(reply, button = true, hidden = true)),
            listOf(Marker("Share story", button = true), Marker(reply, button = true, offscreen = true)))
            .forEach { markers ->
                val signals = inspect(markers)
                assertFalse(signals.facebookStoryViewerOpen)
                assertEquals(AppSurface.OTHER, AppSurfaceClassifier.classify(FACEBOOK_PACKAGE, signals))
            }
    }

    private data class Marker(
        val description: String,
        val tab: Boolean = false,
        val button: Boolean = false,
        val selected: Boolean = false,
        val hidden: Boolean = false,
        val offscreen: Boolean = false,
        val view: Boolean = false,
        val top: Int? = null
    )

    private fun inspect(markers: List<Marker>, packageName: String = FACEBOOK_PACKAGE): AppUiSignals {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val screen = FrameLayout(activity)
                markers.forEachIndexed { index, marker ->
                    screen.addView(TextView(activity).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        contentDescription = marker.description
                        isSelected = marker.selected
                        isClickable = marker.tab || marker.button || marker.view
                        if (marker.hidden) visibility = View.INVISIBLE
                        accessibilityDelegate = object : View.AccessibilityDelegate() {
                            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                                super.onInitializeAccessibilityNodeInfo(host, info)
                                info.className = when {
                                    marker.tab -> "android.app.ActionBar\$Tab"
                                    marker.button -> "android.widget.Button"
                                    marker.view -> "android.view.View"
                                    else -> "android.widget.TextView"
                                }
                            }
                        }
                    }, FrameLayout.LayoutParams(700, 100).apply {
                        topMargin = if (marker.offscreen) 100_000 else marker.top ?: (index * 130)
                    })
                }
                activity.setContentView(screen)
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(100L, 5_000L)
            AppWindowInspector.inspect(checkNotNull(instrumentation.uiAutomation.rootInActiveWindow), null, packageName)
        }
    }
}
