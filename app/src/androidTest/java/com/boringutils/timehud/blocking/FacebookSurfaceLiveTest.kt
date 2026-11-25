package com.boringutils.timehud.blocking

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in only: leave the requested Facebook section open before running. */
class FacebookSurfaceLiveTest {
    @Test fun inspect_requested_facebook_surface() {
        val expected = InstrumentationRegistry.getArguments().getString("facebookSurface")
        assumeTrue(expected in setOf("HOME_FEED", "STORIES", "REELS", "MARKETPLACE", "OTHER"))
        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val root = checkNotNull(automation.windows.firstNotNullOfOrNull { window ->
            window.root?.takeIf { it.packageName?.toString() == FACEBOOK_PACKAGE }
        })
        val signals = AppWindowInspector.inspect(root, null, FACEBOOK_PACKAGE)
        val bounds = Rect().also(root::getBoundsInScreen)
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        val controls = mutableListOf<String>()
        while (queue.isNotEmpty() && visited < 5_000) {
            val node = queue.removeFirst()
            visited++
            val description = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            val share = description == "share story"
            val reply = description.startsWith("send message, reply to ") && description.endsWith("'s story")
            if (share || reply) {
                val nodeBounds = Rect().also(node::getBoundsInScreen)
                // Only fixed categories, flags, and geometry; never identity or content.
                controls += "${if (share) "share" else "reply"} index=$visited class=${node.className} " +
                    "visible=${node.isVisibleToUser} clickable=${node.isClickable} " +
                    "intersects=${Rect.intersects(bounds, nodeBounds)}"
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        val surface = AppSurfaceClassifier.classify(FACEBOOK_PACKAGE, signals)
        println("Facebook surface=$surface home=${signals.facebookHomeFeedOpen} homeBounds=${signals.facebookHomeBounds} story=${signals.facebookStoryViewerOpen} nodes=$visited controls=$controls")
        assertEquals(expected, surface.name)
    }
}
