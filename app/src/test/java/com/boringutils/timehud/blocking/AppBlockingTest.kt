package com.boringutils.timehud.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun active_grace_bypasses_daily_limits_and_section_blocks_but_not_brick_mode() {
        val activeGrace = AppLimitGraceStatus.Active(
            kind = AppLimitGraceKind.NORMAL,
            endMs = 20_000L
        )

        assertEquals(
            BlockDecision.Allow,
            AppLimitGracePolicy.applyTo(
                BlockDecision.Block(BlockReason.DAILY_LIMIT),
                activeGrace
            )
        )
        assertEquals(
            BlockDecision.Allow,
            AppLimitGracePolicy.applyTo(
                BlockDecision.Block(BlockReason.REELS),
                activeGrace
            )
        )
        assertEquals(
            BlockDecision.Allow,
            AppLimitGracePolicy.applyTo(
                BlockDecision.Block(BlockReason.MESSAGE_INBOX),
                activeGrace
            )
        )
        assertEquals(
            BlockDecision.Block(BlockReason.BRICK_MODE),
            AppLimitGracePolicy.applyTo(
                BlockDecision.Block(BlockReason.BRICK_MODE),
                activeGrace
            )
        )
    }

    @Test
    fun normal_grace_is_followed_by_shared_cooldown_and_one_emergency_grace() {
        val nowMs = 1_000_000L
        val normalState = AppLimitGracePolicy.startNormal(
            usagePeriodStartMs = 0L,
            durationMinutes = 10,
            nowMs = nowMs
        )
        assertEquals(
            AppLimitGraceStatus.Active(AppLimitGraceKind.NORMAL, nowMs + 10 * 60_000L),
            AppLimitGracePolicy.status(normalState, nowMs)
        )

        val afterNormalMs = nowMs + 10 * 60_000L
        assertEquals(
            AppLimitGraceStatus.Cooldown(
                endMs = afterNormalMs + 30 * 60_000L,
                emergencyAvailable = true
            ),
            AppLimitGracePolicy.status(normalState, afterNormalMs)
        )

        val emergencyState = AppLimitGracePolicy.startEmergency(normalState, afterNormalMs)
        assertEquals(
            AppLimitGraceStatus.Active(
                AppLimitGraceKind.EMERGENCY,
                afterNormalMs + 3 * 60_000L
            ),
            AppLimitGracePolicy.status(emergencyState, afterNormalMs)
        )
        assertEquals(
            AppLimitGraceStatus.Cooldown(
                endMs = afterNormalMs + 30 * 60_000L,
                emergencyAvailable = false
            ),
            AppLimitGracePolicy.status(emergencyState, afterNormalMs + 3 * 60_000L)
        )
    }

    @Test
    fun ending_normal_grace_early_starts_cooldown_from_that_time() {
        val nowMs = 2_000_000L
        val normalState = AppLimitGracePolicy.startNormal(
            usagePeriodStartMs = 0L,
            durationMinutes = 30,
            nowMs = nowMs
        )
        val endedAtMs = nowMs + 5 * 60_000L
        val endedState = AppLimitGracePolicy.endActiveGrace(normalState, endedAtMs)

        assertEquals(
            AppLimitGraceStatus.Cooldown(
                endMs = endedAtMs + 30 * 60_000L,
                emergencyAvailable = true
            ),
            AppLimitGracePolicy.status(endedState, endedAtMs)
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
        assertEquals(listOf(AppSurface.EXPLORE), supportedSurfacesFor(X_PACKAGE))
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
            Triple(X_PACKAGE, signals(viewId = "explore_timeline"), AppSurface.EXPLORE)
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
    fun facebook_reel_viewer_is_recognized_without_navigation_tab() {
        val signals = AppUiSignals(
            labels = setOf("add a comment...", "original audio", "follow", "like", "share")
        )

        assertEquals(AppSurface.REELS, AppSurfaceClassifier.classify(FACEBOOK_PACKAGE, signals))
        assertEquals(AppSurface.REELS, AppSurfaceClassifier.classify(FACEBOOK_LITE_PACKAGE, signals))
    }

    @Test
    fun facebook_post_comment_prompt_alone_does_not_block_as_reel() {
        val signals = AppUiSignals(labels = setOf("add a comment...", "like", "share"))

        assertEquals(AppSurface.OTHER, AppSurfaceClassifier.classify(FACEBOOK_PACKAGE, signals))
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
    fun volume_panel_keeps_blocking_controls_in_usable_upper_region() {
        val target = ScreenRect(0, 0, 626, 1_280)
        val visible = VisibleRegionCalculator.calculate(
            target,
            listOf(ScreenRect(497, 285, 626, 683))
        )

        assertEquals(
            ScreenRect(0, 0, 497, 1_280),
            BlockingControlsPlacement.order(target, visible, 280, 220).first()
        )
    }

    @Test
    fun short_regions_fall_back_to_largest_space() {
        val target = ScreenRect(0, 0, 600, 1_200)
        val visible = listOf(
            ScreenRect(0, 0, 600, 250),
            ScreenRect(0, 250, 600, 540)
        )

        assertEquals(
            ScreenRect(0, 250, 600, 540),
            BlockingControlsPlacement.order(target, visible, 280, 220).first()
        )
    }

    @Test
    fun full_screen_allowed_window_hides_the_background_blocker() {
        val visible = VisibleRegionCalculator.calculate(
            target = ScreenRect(0, 0, 100, 100),
            occluders = listOf(ScreenRect(0, 0, 100, 100))
        )

        assertTrue(visible.isEmpty())
    }

    @Test
    fun blocking_overlays_do_not_leave_a_hole_for_a_compact_floating_bubble() {
        val target = ScreenRect(0, 0, 1_000, 2_000)
        val bubble = ScreenRect(820, 120, 940, 240)

        assertFalse(
            BlockingOccluderPolicy.shouldPreserve(
                targetBounds = target,
                occluderBounds = bubble,
            )
        )
    }

    @Test
    fun blocking_overlays_still_preserve_full_width_system_ui() {
        val target = ScreenRect(0, 0, 1_000, 2_000)
        val statusBar = ScreenRect(0, 0, 1_000, 80)

        assertTrue(
            BlockingOccluderPolicy.shouldPreserve(
                targetBounds = target,
                occluderBounds = statusBar,
            )
        )
    }

    @Test
    fun volume_control_does_not_shrink_blocker() {
        val target = ScreenRect(0, 0, 626, 1_280)
        val volumeWindow = ScreenRect(497, 285, 626, 683)

        assertTrue(BlockingOccluderPolicy.isVolumeControlId("com.android.systemui:id/volume_dialog"))
        assertTrue(BlockingOccluderPolicy.isVolumeControlId("com.samsung.systemui.volume:id/slider"))
        assertFalse(BlockingOccluderPolicy.isVolumeControlId("com.android.systemui:id/status_bar"))
        assertFalse(
            BlockingOccluderPolicy.shouldPreserve(
                targetBounds = target,
                occluderBounds = volumeWindow,
                isVolumePanel = true,
            )
        )
        assertEquals(
            listOf(target),
            VisibleRegionCalculator.calculate(
                target,
                listOf(volumeWindow).filter {
                    BlockingOccluderPolicy.shouldPreserve(target, it, isVolumePanel = true)
                }
            )
        )
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
