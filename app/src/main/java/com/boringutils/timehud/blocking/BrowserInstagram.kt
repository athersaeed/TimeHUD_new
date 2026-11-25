package com.boringutils.timehud.blocking

import java.net.URI

internal val INSTAGRAM_TEST_BROWSERS = setOf(
    "com.android.chrome", "com.brave.browser", "org.mozilla.firefox"
)

/** Only section categories and fixed UI controls survive inspection; never URLs or page text. */
internal data class BrowserInstagramSignals(
    val route: AppSurface? = null,
    val instagramAddress: Boolean = false,
    val addressObserved: Boolean = false,
    val editingAddress: Boolean = false,
    val controls: Set<String> = emptySet(),
    val hasComposer: Boolean = false,
    val instagramBrand: Boolean = false
)

internal object BrowserInstagramClassifier {
    val controlLabels = setOf(
        "home", "instagram", "messages", "reels", "new message", "requests", "search",
        "conversation information", "voice clip", "add photo or video", "choose a gif or sticker",
        "close", "like", "comment", "share", "save", "direct"
    )

    fun address(value: String, editing: Boolean = false): BrowserInstagramSignals {
        if (editing) return BrowserInstagramSignals(editingAddress = true)
        // Firefox includes the accessible instruction after its displayed address.
        val displayed = value.trim().removeSuffix(". Search or enter address").trim()
        val uri = runCatching {
            URI(if ("://" in displayed) displayed else "https://$displayed")
        }.getOrNull() ?: return BrowserInstagramSignals(addressObserved = true)
        if (uri.scheme !in setOf("http", "https") || uri.rawUserInfo != null) return BrowserInstagramSignals(addressObserved = true)
        val host = uri.host?.lowercase() ?: return BrowserInstagramSignals(addressObserved = true)
        if (host != "instagram.com" && !host.endsWith(".instagram.com")) return BrowserInstagramSignals(addressObserved = true)
        val path = uri.path.orEmpty().trim('/').lowercase()
        val route = when {
            path.isEmpty() -> AppSurface.VIDEO_FEED
            path == "direct" || path == "direct/inbox" -> AppSurface.MESSAGE_INBOX
            path.startsWith("direct/t/") && path.removePrefix("direct/t/").isNotBlank() -> AppSurface.MESSAGE_THREAD
            path == "stories" || path.startsWith("stories/") -> AppSurface.STORIES
            path == "reels" || path.startsWith("reels/") || path.startsWith("reel/") -> AppSurface.REELS
            path == "explore" || path.startsWith("explore/") -> AppSurface.EXPLORE
            else -> AppSurface.OTHER
        }
        return BrowserInstagramSignals(route = route, instagramAddress = true, addressObserved = true)
    }

    fun classify(signals: BrowserInstagramSignals): AppSurface {
        if (signals.editingAddress || (signals.addressObserved && !signals.instagramAddress)) return AppSurface.OTHER
        val controls = signals.controls
        val brandedPage = signals.instagramBrand && controls.containsAll(setOf("home", "reels", "messages"))
        if (!signals.instagramAddress && !brandedPage) return AppSurface.OTHER
        // A viewer can appear above an underlying message page. Never exempt its reply field.
        if (signals.route == AppSurface.STORIES ||
            (signals.hasComposer && controls.containsAll(setOf("close", "like", "direct")))
        ) return AppSurface.STORIES
        if (signals.route != null && signals.route != AppSurface.OTHER) return signals.route
        if (signals.hasComposer && controls.containsAll(setOf(
                "conversation information", "voice clip", "add photo or video"
            ))) return AppSurface.MESSAGE_THREAD
        if (controls.containsAll(setOf("new message", "messages", "requests"))) return AppSurface.MESSAGE_INBOX
        if (brandedPage && controls.containsAll(setOf("like", "comment", "share", "save"))) {
            return AppSurface.VIDEO_FEED
        }
        return AppSurface.OTHER
    }

    fun decision(surface: AppSurface): BlockDecision = when (surface) {
        AppSurface.VIDEO_FEED -> BlockDecision.Block(BlockReason.VIDEO_FEED)
        AppSurface.STORIES -> BlockDecision.Block(BlockReason.STORIES)
        AppSurface.REELS -> BlockDecision.Block(BlockReason.REELS)
        AppSurface.EXPLORE -> BlockDecision.Block(BlockReason.EXPLORE)
        else -> BlockDecision.Allow
    }
}
