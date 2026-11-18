package com.boringutils.timehud.blocking

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

internal data class BrowserInstagramObservation(
    val signals: BrowserInstagramSignals,
    val contentBounds: ScreenRect?
)

internal object BrowserInstagramInspector {
    private val addressIds = mapOf(
        "com.android.chrome" to "com.android.chrome:id/url_bar",
        "com.brave.browser" to "com.brave.browser:id/url_bar",
        "org.mozilla.firefox" to "ADDRESSBAR_URL_BOX"
    )

    fun inspect(root: AccessibilityNodeInfo, packageName: String): BrowserInstagramObservation {
        val windowBounds = Rect().also(root::getBoundsInScreen)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(root to false)
        var visited = 0
        var address = BrowserInstagramSignals()
        var contentBounds: ScreenRect? = null
        val controls = mutableSetOf<String>()
        var composer = false
        var brand = false
        while (queue.isNotEmpty() && visited++ < 1200) {
            val (node, parentIsWeb) = queue.removeFirst()
            val bounds = Rect().also(node::getBoundsInScreen)
            val visible = node.isVisibleToUser && !bounds.isEmpty && Rect.intersects(bounds, windowBounds)
            val web = parentIsWeb || node.className?.toString() == "android.webkit.WebView"
            if (visible && node.viewIdResourceName == addressIds[packageName]) {
                address = BrowserInstagramClassifier.address(
                    (if (packageName == "org.mozilla.firefox") node.contentDescription else node.text)
                        ?.toString().orEmpty(), node.isFocused
                )
            }
            if (visible && web) {
                if (!parentIsWeb) {
                    contentBounds = ScreenRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    brand = node.text?.toString()?.equals("Instagram", ignoreCase = true) == true
                }
                if (node.isEditable) {
                    // Deliberately do not read the user's typed message.
                    composer = true
                } else {
                    val role = node.className?.toString().orEmpty()
                    val candidates = buildList {
                        node.contentDescription?.toString()?.let(::add)
                        // Only fixed controls/branding, never generic post or message text.
                        if (role in setOf("android.widget.Button", "android.widget.Image")) {
                            node.text?.toString()?.let(::add)
                        }
                    }
                    for (candidate in candidates) {
                        val label = candidate.trim().lowercase()
                        if (label in BrowserInstagramClassifier.controlLabels) controls += label
                        if (label == "instagram") brand = true
                    }
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it to web) }
            }
        }
        return BrowserInstagramObservation(
            address.copy(controls = controls, hasComposer = composer, instagramBrand = brand),
            contentBounds
        )
    }
}
