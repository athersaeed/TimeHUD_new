package com.boringutils.timehud.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBlockingTest {
    @Test
    fun messages_exemption_wins_after_daily_limit() {
        val decision = AppBlockDecisionEngine.decide(
            rule = rule(
                packageName = INSTAGRAM_PACKAGE,
                dailyLimitMinutes = 10,
                blockedSurfaces = setOf(AppSurface.REELS, AppSurface.STORIES)
            ),
            focusedUsageMs = 11 * 60_000L,
            surface = AppSurface.MESSAGE_THREAD
        )

        assertEquals(BlockDecision.Allow, decision)
    }

    @Test
    fun configured_surface_blocks_before_daily_limit() {
        val rule = rule(
            packageName = YOUTUBE_PACKAGE,
            dailyLimitMinutes = 60,
            blockedSurfaces = setOf(AppSurface.SHORTS)
        )

        assertEquals(
            BlockDecision.Block(BlockReason.SHORTS),
            AppBlockDecisionEngine.decide(rule, focusedUsageMs = 0L, AppSurface.SHORTS)
        )
    }

    @Test
    fun configured_x_videos_surface_blocks_before_daily_limit() {
        val rule = rule(
            packageName = X_PACKAGE,
            blockedSurfaces = setOf(AppSurface.X_VIDEOS)
        )

        assertEquals(
            BlockDecision.Block(BlockReason.X_VIDEOS),
            AppBlockDecisionEngine.decide(rule, focusedUsageMs = 0L, AppSurface.X_VIDEOS)
        )
    }

    @Test
    fun daily_limit_blocks_other_app_surfaces_at_boundary() {
        val rule = rule(packageName = "example.app", dailyLimitMinutes = 15)

        assertEquals(
            BlockDecision.Allow,
            AppBlockDecisionEngine.decide(rule, 15 * 60_000L - 1L, AppSurface.UNKNOWN)
        )
        assertEquals(
            BlockDecision.Block(BlockReason.DAILY_LIMIT),
            AppBlockDecisionEngine.decide(rule, 15 * 60_000L, AppSurface.UNKNOWN)
        )
    }

    @Test
    fun screenshot_apps_expose_the_expected_options() {
        assertEquals(
            listOf(
                AppSurface.SHORTS,
                AppSurface.VIDEO_SEARCH,
                AppSurface.PICTURE_IN_PICTURE,
                AppSurface.COMMENTS
            ),
            supportedSurfacesFor(YOUTUBE_PACKAGE)
        )
        assertEquals(
            listOf(AppSurface.STORIES, AppSurface.REELS, AppSurface.EXPLORE),
            supportedSurfacesFor(INSTAGRAM_PACKAGE)
        )
        assertEquals(
            listOf(AppSurface.STORIES, AppSurface.REELS, AppSurface.MARKETPLACE),
            supportedSurfacesFor(FACEBOOK_PACKAGE)
        )
        assertEquals(
            listOf(AppSurface.SPOTLIGHT, AppSurface.STORIES),
            supportedSurfacesFor(SNAPCHAT_PACKAGE)
        )
        assertEquals(
            listOf(AppSurface.X_VIDEOS, AppSurface.EXPLORE),
            supportedSurfacesFor(X_PACKAGE)
        )
    }

    @Test
    fun classifiers_recognize_all_configurable_surfaces() {
        val cases = listOf(
            Triple(YOUTUBE_PACKAGE, signals(selected = "shorts"), AppSurface.SHORTS),
            Triple(YOUTUBE_PACKAGE, signals(focused = "search youtube"), AppSurface.VIDEO_SEARCH),
            Triple(
                YOUTUBE_PACKAGE,
                signals(compactWindow = true),
                AppSurface.PICTURE_IN_PICTURE
            ),
            Triple(YOUTUBE_PACKAGE, signals(viewId = "comments_panel"), AppSurface.COMMENTS),
            Triple(INSTAGRAM_PACKAGE, signals(viewId = "story_viewer"), AppSurface.STORIES),
            Triple(INSTAGRAM_PACKAGE, signals(selected = "reels"), AppSurface.REELS),
            Triple(INSTAGRAM_PACKAGE, signals(viewId = "explore_grid"), AppSurface.EXPLORE),
            Triple(FACEBOOK_PACKAGE, signals(viewId = "stories_viewer"), AppSurface.STORIES),
            Triple(FACEBOOK_PACKAGE, signals(viewId = "reels_feed"), AppSurface.REELS),
            Triple(FACEBOOK_PACKAGE, signals(selected = "marketplace"), AppSurface.MARKETPLACE),
            Triple(SNAPCHAT_PACKAGE, signals(selected = "spotlight"), AppSurface.SPOTLIGHT),
            Triple(SNAPCHAT_PACKAGE, signals(viewId = "stories_feed"), AppSurface.STORIES),
            Triple(X_PACKAGE, signals(viewId = "explore_timeline"), AppSurface.EXPLORE),
            Triple(X_PACKAGE, signals(selected = "videos"), AppSurface.X_VIDEOS),
            Triple(X_PACKAGE, signals(viewId = "immersive_video_timeline"), AppSurface.X_VIDEOS)
        )

        cases.forEach { (packageName, signals, expected) ->
            assertEquals(
                "$packageName should classify ${expected.name}",
                expected,
                AppSurfaceClassifier.classify(packageName, signals)
            )
        }
    }

    @Test
    fun open_instagram_chat_has_priority_over_stale_reels_signals() {
        val surface = AppSurfaceClassifier.classify(
            INSTAGRAM_PACKAGE,
            signals(
                selected = "messages",
                viewIds = setOf("reels_viewer", "message_composer")
            )
        )

        assertEquals(AppSurface.MESSAGE_THREAD, surface)
    }

    @Test
    fun instagram_inbox_is_not_treated_as_an_open_chat() {
        val surface = AppSurfaceClassifier.classify(
            INSTAGRAM_PACKAGE,
            signals(selected = "messages", viewId = "direct_inbox")
        )

        assertEquals(AppSurface.MESSAGE_INBOX, surface)
        assertEquals(
            BlockDecision.Block(BlockReason.MESSAGE_INBOX),
            AppBlockDecisionEngine.decide(
                rule = rule(
                    packageName = INSTAGRAM_PACKAGE,
                    blockedSurfaces = setOf(AppSurface.REELS)
                ),
                focusedUsageMs = 0L,
                surface = surface
            )
        )
    }

    @Test
    fun selected_reels_beats_a_stale_messages_container() {
        val surface = AppSurfaceClassifier.classify(
            INSTAGRAM_PACKAGE,
            signals(selected = "reels", viewId = "direct_inbox")
        )

        assertEquals(AppSurface.REELS, surface)
    }

    @Test
    fun reels_viewer_beats_a_stale_messages_container() {
        val surface = AppSurfaceClassifier.classify(
            INSTAGRAM_PACKAGE,
            AppUiSignals(viewIds = setOf("reels_viewer", "thread_list"))
        )

        assertEquals(AppSurface.REELS, surface)
    }

    @Test
    fun selected_x_videos_beats_a_stale_explore_container() {
        val surface = AppSurfaceClassifier.classify(
            X_PACKAGE,
            signals(selected = "videos", viewId = "explore_timeline")
        )

        assertEquals(AppSurface.X_VIDEOS, surface)
    }

    @Test
    fun selected_x_explore_beats_a_stale_videos_container() {
        val surface = AppSurfaceClassifier.classify(
            X_PACKAGE,
            signals(selected = "explore", viewId = "immersive_video_timeline")
        )

        assertEquals(AppSurface.EXPLORE, surface)
    }

    @Test
    fun higher_popup_is_removed_from_blocked_window_region() {
        val visible = VisibleRegionCalculator.calculate(
            target = ScreenRect(0, 0, 100, 100),
            occluders = listOf(ScreenRect(25, 25, 75, 75))
        )

        assertEquals(4, visible.size)
        assertEquals(7_500L, visible.sumOf(ScreenRect::area))
        assertTrue(visible.none { it.intersection(ScreenRect(25, 25, 75, 75)) != null })
    }

    @Test
    fun edge_aligned_popup_leaves_one_contiguous_blocked_region() {
        val visible = VisibleRegionCalculator.calculate(
            target = ScreenRect(0, 0, 100, 100),
            occluders = listOf(ScreenRect(50, 0, 100, 100))
        )

        assertEquals(listOf(ScreenRect(0, 0, 50, 100)), visible)
    }

    @Test
    fun full_screen_allowed_window_hides_the_background_blocker() {
        val visible = VisibleRegionCalculator.calculate(
            target = ScreenRect(0, 0, 100, 100),
            occluders = listOf(ScreenRect(0, 0, 100, 100))
        )

        assertTrue(visible.isEmpty())
    }

    private fun rule(
        packageName: String,
        dailyLimitMinutes: Int? = null,
        blockedSurfaces: Set<AppSurface> = emptySet()
    ) = AppBlockRule(
        packageName = packageName,
        dailyLimitMinutes = dailyLimitMinutes,
        blockedSurfaces = blockedSurfaces,
        allowMessages = true
    )

    private fun signals(
        selected: String? = null,
        focused: String? = null,
        viewId: String? = null,
        viewIds: Set<String> = emptySet(),
        editable: String? = null,
        windowTitle: String = "",
        compactWindow: Boolean = false
    ) = AppUiSignals(
        labels = setOfNotNull(selected, focused),
        selectedLabels = setOfNotNull(selected),
        focusedLabels = setOfNotNull(focused),
        editableLabels = setOfNotNull(editable),
        viewIds = viewIds + setOfNotNull(viewId),
        windowTitle = windowTitle,
        isCompactWindow = compactWindow
    )
}
