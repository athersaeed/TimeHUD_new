package com.boringutils.timehud.blocking

import java.net.URI

internal val FACEBOOK_BROWSER_SURFACES = listOf(
    AppSurface.HOME_FEED, AppSurface.REELS, AppSurface.MARKETPLACE, AppSurface.STORIES
)

/** Only categories and fixed UI markers leave inspection, never addresses or page content. */
internal data class BrowserFacebookSignals(
    val route: AppSurface? = null,
    val facebookAddress: Boolean = false,
    val addressObserved: Boolean = false,
    val editingAddress: Boolean = false,
    val facebookBrand: Boolean = false,
    val selectedNavigation: Set<AppSurface> = emptySet(),
    val controls: Set<String> = emptySet(),
    val pageTitle: String = ""
)

internal object BrowserFacebookClassifier {
    val controlLabels = setOf(
        "facebook logo", "facebook menu", "search facebook", "create story",
        "close story", "next story", "previous story", "navigate to your reels profile",
        "marketplace", "comments", "back"
    )
    private val navigation = Regex("^(feed|friends|messages|reels|notifications|marketplace), (?:[0-9,]+ new, )?[1-6] of 6$")

    fun navigationSurface(description: String): AppSurface? =
        navigation.matchEntire(description.trim().lowercase())?.groupValues?.get(1)?.let {
            when (it) {
                "feed" -> AppSurface.HOME_FEED
                "reels" -> AppSurface.REELS
                "marketplace" -> AppSurface.MARKETPLACE
                "messages" -> AppSurface.MESSAGE_INBOX
                else -> AppSurface.OTHER
            }
        }

    fun address(value: String, editing: Boolean = false): BrowserFacebookSignals {
        if (editing) return BrowserFacebookSignals(editingAddress = true)
        val displayed = value.trim().removeSuffix(". Search or enter address").trim()
        val uri = runCatching {
            URI(if ("://" in displayed) displayed else "https://$displayed")
        }.getOrNull() ?: return BrowserFacebookSignals(addressObserved = true)
        val host = uri.host?.lowercase()
        if (uri.scheme !in setOf("http", "https") || uri.rawUserInfo != null ||
            host == null || (host != "facebook.com" && !host.endsWith(".facebook.com"))
        ) return BrowserFacebookSignals(addressObserved = true)
        val path = uri.path.orEmpty().trim('/').lowercase()
        val route = when {
            path.isEmpty() -> AppSurface.HOME_FEED
            path.startsWith("reel/") && path.removePrefix("reel/").isNotBlank() -> AppSurface.REELS
            path == "reels" || path.startsWith("reels/") -> AppSurface.REELS
            // /stories/ alone can redirect to the feed with a broken-link notice.
            path.startsWith("stories/") && path.removePrefix("stories/").isNotBlank() -> AppSurface.STORIES
            path == "marketplace" || path.startsWith("marketplace/") -> AppSurface.MARKETPLACE
            else -> AppSurface.OTHER
        }
        return BrowserFacebookSignals(route = route, facebookAddress = true, addressObserved = true)
    }

    fun classify(signals: BrowserFacebookSignals): AppSurface {
        if (signals.editingAddress || (signals.addressObserved && !signals.facebookAddress)) return AppSurface.OTHER
        if (!signals.facebookAddress && !signals.facebookBrand) return AppSurface.OTHER
        if (signals.pageTitle == "comments" || "comments" in signals.controls) return AppSurface.COMMENTS
        // Explicit non-section routes (profiles, photos, search, messages) beat retained tabs.
        if (signals.route == AppSurface.OTHER) return AppSurface.OTHER
        if ("close story" in signals.controls &&
            signals.controls.any { it == "next story" || it == "previous story" }
        ) return AppSurface.STORIES
        if (signals.route != null && signals.route != AppSurface.HOME_FEED) return signals.route
        if (signals.selectedNavigation.size == 1) return signals.selectedNavigation.single()
        if (signals.selectedNavigation.isNotEmpty()) return AppSurface.OTHER
        // Sparse Firefox pages expose the title and URL, but no selected web navigation.
        if (signals.route == AppSurface.HOME_FEED && signals.pageTitle == "facebook") return AppSurface.HOME_FEED
        if (!signals.addressObserved && "navigate to your reels profile" in signals.controls) return AppSurface.REELS
        return AppSurface.OTHER
    }

    fun decision(surface: AppSurface, blockedSurfaces: Set<AppSurface>): BlockDecision {
        if (surface !in FACEBOOK_BROWSER_SURFACES || surface !in blockedSurfaces) return BlockDecision.Allow
        return AppBlockDecisionEngine.decide(
            AppBlockRule(FACEBOOK_PACKAGE, blockedSurfaces = blockedSurfaces), 0L, surface
        )
    }
}
