package com.boringutils.timehud.blocking

internal const val YOUTUBE_PACKAGE = "com.google.android.youtube"
internal const val INSTAGRAM_PACKAGE = "com.instagram.android"
internal const val FACEBOOK_PACKAGE = "com.facebook.katana"
internal const val FACEBOOK_LITE_PACKAGE = "com.facebook.lite"
internal const val SNAPCHAT_PACKAGE = "com.snapchat.android"
internal const val X_PACKAGE = "com.twitter.android"

internal data class AppBlockRule(
    val packageName: String,
    val dailyLimitMinutes: Int? = null,
    val blockedSurfaces: Set<AppSurface> = emptySet(),
    val allowMessages: Boolean = true
) {
    val isConfigured: Boolean
        get() = dailyLimitMinutes != null || blockedSurfaces.isNotEmpty()
}

internal enum class AppSurface {
    MESSAGE_INBOX,
    MESSAGE_THREAD,
    SHORTS,
    VIDEO_SEARCH,
    PICTURE_IN_PICTURE,
    COMMENTS,
    REELS,
    STORIES,
    EXPLORE,
    MARKETPLACE,
    SPOTLIGHT,
    OTHER,
    UNKNOWN
}

internal fun supportedSurfacesFor(packageName: String): List<AppSurface> = when (packageName) {
    YOUTUBE_PACKAGE -> listOf(
        AppSurface.SHORTS,
        AppSurface.VIDEO_SEARCH,
        AppSurface.PICTURE_IN_PICTURE,
        AppSurface.COMMENTS
    )
    INSTAGRAM_PACKAGE -> listOf(
        AppSurface.STORIES,
        AppSurface.REELS,
        AppSurface.EXPLORE
    )
    FACEBOOK_PACKAGE, FACEBOOK_LITE_PACKAGE -> listOf(
        AppSurface.STORIES,
        AppSurface.REELS,
        AppSurface.MARKETPLACE
    )
    SNAPCHAT_PACKAGE -> listOf(AppSurface.SPOTLIGHT, AppSurface.STORIES)
    X_PACKAGE -> listOf(AppSurface.EXPLORE)
    else -> emptyList()
}

internal enum class BlockReason {
    DAILY_LIMIT,
    MESSAGE_INBOX,
    SHORTS,
    VIDEO_SEARCH,
    PICTURE_IN_PICTURE,
    COMMENTS,
    REELS,
    STORIES,
    EXPLORE,
    MARKETPLACE,
    SPOTLIGHT
}

internal sealed interface BlockDecision {
    data object Allow : BlockDecision

    data class Block(val reason: BlockReason) : BlockDecision
}

internal object AppBlockDecisionEngine {
    fun decide(
        rule: AppBlockRule,
        focusedUsageMs: Long,
        surface: AppSurface
    ): BlockDecision {
        if (rule.allowMessages && surface == AppSurface.MESSAGE_THREAD) {
            return BlockDecision.Allow
        }
        if (rule.packageName == INSTAGRAM_PACKAGE &&
            rule.allowMessages &&
            surface == AppSurface.MESSAGE_INBOX &&
            rule.blockedSurfaces.isNotEmpty()
        ) {
            return BlockDecision.Block(BlockReason.MESSAGE_INBOX)
        }
        if (surface in rule.blockedSurfaces) {
            return BlockDecision.Block(surface.blockReason())
        }

        val limitMs = rule.dailyLimitMinutes?.toLong()?.times(60_000L)
        return if (limitMs != null && focusedUsageMs >= limitMs) {
            BlockDecision.Block(BlockReason.DAILY_LIMIT)
        } else {
            BlockDecision.Allow
        }
    }

    private fun AppSurface.blockReason(): BlockReason = when (this) {
        AppSurface.SHORTS -> BlockReason.SHORTS
        AppSurface.VIDEO_SEARCH -> BlockReason.VIDEO_SEARCH
        AppSurface.PICTURE_IN_PICTURE -> BlockReason.PICTURE_IN_PICTURE
        AppSurface.COMMENTS -> BlockReason.COMMENTS
        AppSurface.REELS -> BlockReason.REELS
        AppSurface.STORIES -> BlockReason.STORIES
        AppSurface.EXPLORE -> BlockReason.EXPLORE
        AppSurface.MARKETPLACE -> BlockReason.MARKETPLACE
        AppSurface.SPOTLIGHT -> BlockReason.SPOTLIGHT
        AppSurface.MESSAGE_INBOX,
        AppSurface.MESSAGE_THREAD,
        AppSurface.OTHER,
        AppSurface.UNKNOWN -> error("$this is not a blockable surface")
    }
}

internal data class AppUiSignals(
    val labels: Set<String> = emptySet(),
    val selectedLabels: Set<String> = emptySet(),
    val focusedLabels: Set<String> = emptySet(),
    val editableLabels: Set<String> = emptySet(),
    val viewIds: Set<String> = emptySet(),
    val windowTitle: String = "",
    val isCompactWindow: Boolean = false
) {
    fun hasLabel(vararg candidates: String): Boolean =
        candidates.any { candidate -> labels.any { it == candidate || it.startsWith("$candidate ") } }

    fun hasSelectedLabel(vararg candidates: String): Boolean =
        candidates.any { candidate -> selectedLabels.any { it == candidate || it.startsWith("$candidate ") } }

    fun hasFocusedLabel(vararg candidates: String): Boolean =
        candidates.any { candidate -> focusedLabels.any { it == candidate || it.startsWith("$candidate ") } }

    fun hasEditableLabel(vararg candidates: String): Boolean =
        candidates.any { candidate -> editableLabels.any { it == candidate || it.startsWith("$candidate ") } }

    fun hasViewId(vararg fragments: String): Boolean =
        fragments.any { fragment -> viewIds.any { fragment in it } }

    fun titleContains(vararg fragments: String): Boolean =
        fragments.any { it in windowTitle }
}

internal object AppSurfaceClassifier {
    fun classify(packageName: String, signals: AppUiSignals): AppSurface = when (packageName) {
        YOUTUBE_PACKAGE -> classifyYouTube(signals)
        INSTAGRAM_PACKAGE -> classifyInstagram(signals)
        FACEBOOK_PACKAGE, FACEBOOK_LITE_PACKAGE -> classifyFacebook(signals)
        SNAPCHAT_PACKAGE -> classifySnapchat(signals)
        X_PACKAGE -> classifyX(signals)
        else -> AppSurface.UNKNOWN
    }

    private fun classifyYouTube(signals: AppUiSignals): AppSurface = when {
        signals.titleContains("picture-in-picture", "picture in picture") ||
            signals.hasViewId("picture_in_picture", "pip_controls", "pip_control") ||
            signals.isCompactWindow -> {
            AppSurface.PICTURE_IN_PICTURE
        }
        signals.hasViewId("comments_panel", "comments_sheet", "comment_replies") ||
            signals.hasSelectedLabel("comments") -> AppSurface.COMMENTS
        signals.hasViewId("search_results", "search_query", "search_edit_text") ||
            signals.hasFocusedLabel("search", "search youtube") -> AppSurface.VIDEO_SEARCH
        signals.hasSelectedLabel("shorts") ||
            signals.hasViewId("shorts_player", "reel_watch", "shorts_watch") -> AppSurface.SHORTS
        else -> AppSurface.OTHER
    }

    private fun classifyInstagram(signals: AppUiSignals): AppSurface {
        val messageThreadOpen = signals.hasViewId(
            "direct_thread",
            "message_thread",
            "thread_detail",
            "conversation_view",
            "chat_thread",
            "message_composer",
            "composer_input",
            "message_edit_text"
        ) || signals.hasEditableLabel("message", "write a message", "send message")
        val messagesSelected = signals.hasSelectedLabel("messages", "direct", "inbox")

        return when {
            signals.hasSelectedLabel("reels", "reels tab") -> AppSurface.REELS
            signals.hasSelectedLabel("explore", "search and explore") -> AppSurface.EXPLORE
            messagesSelected && messageThreadOpen -> AppSurface.MESSAGE_THREAD
            messagesSelected -> AppSurface.MESSAGE_INBOX
            signals.hasViewId("story_viewer") || signals.hasLabel("story by") -> AppSurface.STORIES
            signals.hasViewId("clips_viewer", "reels_viewer") -> AppSurface.REELS
            signals.hasViewId("explore_grid", "explore_tab", "search_tab") -> AppSurface.EXPLORE
            messageThreadOpen -> AppSurface.MESSAGE_THREAD
            signals.hasViewId("direct_inbox", "inbox_container", "thread_list") -> {
                AppSurface.MESSAGE_INBOX
            }
            else -> AppSurface.OTHER
        }
    }

    private fun classifyFacebook(signals: AppUiSignals): AppSurface = when {
        signals.hasViewId("story_viewer", "stories_viewer") ||
            signals.hasLabel("story by") -> AppSurface.STORIES
        signals.hasSelectedLabel("reels") ||
            signals.hasViewId("reels_tab", "reels_feed", "reels_viewer") -> AppSurface.REELS
        signals.hasSelectedLabel("marketplace") ||
            signals.hasViewId("marketplace_tab", "marketplace_feed") -> AppSurface.MARKETPLACE
        else -> AppSurface.OTHER
    }

    private fun classifySnapchat(signals: AppUiSignals): AppSurface = when {
        signals.hasSelectedLabel("spotlight") ||
            signals.hasViewId("spotlight_tab", "spotlight_feed") -> AppSurface.SPOTLIGHT
        signals.hasSelectedLabel("stories", "discover") ||
            signals.hasViewId("stories_tab", "stories_feed", "discover_feed") -> AppSurface.STORIES
        else -> AppSurface.OTHER
    }

    private fun classifyX(signals: AppUiSignals): AppSurface = when {
        signals.hasSelectedLabel("explore", "search and explore") ||
            signals.hasViewId("explore_tab", "search_timeline", "explore_timeline") -> {
            AppSurface.EXPLORE
        }
        else -> AppSurface.OTHER
    }
}

internal data class ScreenRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height.toLong()

    fun intersection(other: ScreenRect): ScreenRect? {
        val intersection = ScreenRect(
            left = maxOf(left, other.left),
            top = maxOf(top, other.top),
            right = minOf(right, other.right),
            bottom = minOf(bottom, other.bottom)
        )
        return intersection.takeIf { it.area > 0L }
    }

    fun subtract(other: ScreenRect): List<ScreenRect> {
        val overlap = intersection(other) ?: return listOf(this)
        return buildList {
            addIfVisible(ScreenRect(left, top, right, overlap.top))
            addIfVisible(ScreenRect(left, overlap.bottom, right, bottom))
            addIfVisible(ScreenRect(left, overlap.top, overlap.left, overlap.bottom))
            addIfVisible(ScreenRect(overlap.right, overlap.top, right, overlap.bottom))
        }
    }

    private fun MutableList<ScreenRect>.addIfVisible(rect: ScreenRect) {
        if (rect.area > 0L) add(rect)
    }
}

internal object VisibleRegionCalculator {
    fun calculate(target: ScreenRect, occluders: List<ScreenRect>): List<ScreenRect> =
        occluders.fold(listOf(target)) { visibleRects, occluder ->
            visibleRects.flatMap { it.subtract(occluder) }
        }
}
