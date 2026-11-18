package com.boringutils.timehud.blocking

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit manual smoke check: leave the requested Instagram surface open first. */
class InstagramHomeFeedLiveTest {
    @Test fun inspect_requested_instagram_surface() {
        val expected = InstrumentationRegistry.getArguments().getString("instagramSurface")
        assumeTrue(expected in setOf("HOME_FEED", "MESSAGE_INBOX", "MESSAGE_THREAD", "OTHER"))
        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val root = checkNotNull(automation.rootInActiveWindow)
        assertEquals(INSTAGRAM_PACKAGE, root.packageName.toString())
        val signals = AppWindowInspector.inspect(root, null, INSTAGRAM_PACKAGE)
        val surface = AppSurfaceClassifier.classify(INSTAGRAM_PACKAGE, signals)
        // Fixed markers only. Never print labels, identities, or post/message text.
        println("Instagram surface=$surface home=${signals.hasSelectedLabel("home")} " +
            "messages=${signals.hasSelectedLabel("messages", "message")} " +
            "header=${signals.hasViewId("main_feed_action_bar")} " +
            "feedList=${signals.hasViewId("sticky_header_list")} " +
            "feedBounds=${signals.instagramFeedBounds}")
        assertEquals(expected, surface.name)
    }
}
