package com.boringutils.timehud.blocking

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

internal data class BrowserFacebookObservation(
    val signals: BrowserFacebookSignals,
    val contentBounds: ScreenRect?
)

internal object BrowserFacebookInspector {
    private val addressIds = mapOf(
        "com.android.chrome" to "com.android.chrome:id/url_bar",
        "com.brave.browser" to "com.brave.browser:id/url_bar",
        "org.mozilla.firefox" to "ADDRESSBAR_URL_BOX"
    )

    fun inspect(root: AccessibilityNodeInfo, packageName: String): BrowserFacebookObservation {
        val windowBounds = Rect().also(root::getBoundsInScreen)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(root to false)
        var visited = 0
        var address = BrowserFacebookSignals()
        var contentBounds: ScreenRect? = null
        var navigationBottom: Int? = null
        val controls = mutableSetOf<String>()
        val selected = mutableSetOf<AppSurface>()
        var brand = false
        var title = ""
        while (queue.isNotEmpty() && visited++ < 1200) {
            val (node, parentIsWeb) = queue.removeFirst()
            val bounds = Rect().also(node::getBoundsInScreen)
            val visible = node.isVisibleToUser && !bounds.isEmpty && Rect.intersects(bounds, windowBounds)
            val web = parentIsWeb || node.className?.toString() == "android.webkit.WebView"
            if (visible && !web) {
                if (node.viewIdResourceName == addressIds[packageName]) {
                    address = BrowserFacebookClassifier.address(
                        (if (packageName == "org.mozilla.firefox") node.contentDescription else node.text)
                            ?.toString().orEmpty(), node.isFocused
                    )
                }
                // Firefox switches to a different ID while editing its address.
                if (node.viewIdResourceName == "ADDRESSBAR_SEARCH_BOX" && node.isFocused) {
                    address = BrowserFacebookSignals(editingAddress = true)
                }
            }
            if (web) {
                if (!parentIsWeb && visible) {
                    contentBounds = ScreenRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    title = node.text?.toString()?.trim()?.lowercase()
                        ?.takeIf { it in setOf("facebook", "marketplace", "comments") }.orEmpty()
                    brand = title in setOf("facebook", "marketplace")
                }
                // Do not read edit fields, generic post/comment text, or recipient-bearing labels.
                if (!node.isEditable) {
                    val role = node.className?.toString().orEmpty()
                    if (role == "android.view.View" && node.isClickable) {
                        val nav = BrowserFacebookClassifier.navigationSurface(node.contentDescription?.toString().orEmpty())
                        if (nav != null) {
                            if (visible) navigationBottom = maxOf(navigationBottom ?: bounds.bottom, bounds.bottom)
                            // Chromium retains selected navigation at zero-height bounds after scrolling.
                            if (node.isSelected) selected += nav
                        }
                    }
                    if (visible && role in setOf("android.widget.Button", "android.view.View")) {
                        val description = node.contentDescription?.toString()?.trim()?.lowercase()
                        if (description in BrowserFacebookClassifier.controlLabels) controls += checkNotNull(description)
                        // Button text is only compared to fixed labels; generic text nodes are skipped.
                        if (role == "android.widget.Button") {
                            val text = node.text?.toString()?.trim()?.lowercase()
                            if (text in BrowserFacebookClassifier.controlLabels) controls += checkNotNull(text)
                        }
                    }
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { queue.add(it to web) }
        }
        brand = brand || "facebook logo" in controls
        // Leave Facebook's visible top navigation and the browser toolbar available.
        val shieldBounds = contentBounds?.let { bounds ->
            val top = navigationBottom?.coerceIn(bounds.top, bounds.bottom) ?: bounds.top
            ScreenRect(bounds.left, top, bounds.right, bounds.bottom).takeIf { it.area > 0L }
        }
        return BrowserFacebookObservation(
            address.copy(facebookBrand = brand, selectedNavigation = selected, controls = controls, pageTitle = title),
            shieldBounds
        )
    }
}
