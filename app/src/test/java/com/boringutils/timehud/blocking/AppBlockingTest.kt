package com.boringutils.timehud.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBlockingTest {
    @Test fun facebook_home_requires_verified_toolbar_and_keeps_section_priority() {
        val home = AppUiSignals(facebookHomeFeedOpen = true, selectedLabels = setOf("home"))
        assertEquals(AppSurface.HOME_FEED, AppSurfaceClassifier.classify(FACEBOOK_PACKAGE, home))
        assertEquals(AppSurface.OTHER, AppSurfaceClassifier.classify(FACEBOOK_PACKAGE,
            AppUiSignals(selectedLabels = setOf("home"), labels = setOf("facebook logo", "search facebook"))))
        assertEquals(AppSurface.HOME_FEED, AppSurfaceClassifier.classify(FACEBOOK_PACKAGE,
            home.copy(viewIds = setOf("reels_feed", "marketplace_feed"))))
        assertEquals(AppSurface.STORIES, AppSurfaceClassifier.classify(FACEBOOK_PACKAGE,
            home.copy(facebookStoryViewerOpen = true)))
        assertEquals(AppSurface.REELS, AppSurfaceClassifier.classify(FACEBOOK_PACKAGE,
            home.copy(selectedLabels = setOf("reels"))))
        assertEquals(AppSurface.MARKETPLACE, AppSurfaceClassifier.classify(FACEBOOK_PACKAGE,
            home.copy(selectedLabels = setOf("marketplace"))))
    }

    @Test fun facebook_home_is_a_shared_opt_in_section() {
        for (pkg in listOf(FACEBOOK_PACKAGE, FACEBOOK_LITE_PACKAGE)) {
            assertTrue(AppSurface.HOME_FEED in supportedSurfacesFor(pkg))
            assertFalse(AppSurface.HOME_FEED in AppBlockRule(pkg).blockedSurfaces)
        }
        val rule = AppBlockRule(FACEBOOK_PACKAGE, blockedSurfaces = setOf(AppSurface.HOME_FEED), browserFacebook = true)
        assertEquals(BlockDecision.Block(BlockReason.HOME_FEED),
            AppBlockDecisionEngine.decide(rule, 0L, AppSurface.HOME_FEED))
        assertEquals(BlockDecision.Block(BlockReason.HOME_FEED),
            BrowserFacebookClassifier.decision(AppSurface.HOME_FEED, rule.blockedSurfaces))
        listOf(AppSurface.REELS, AppSurface.STORIES, AppSurface.MARKETPLACE, AppSurface.OTHER).forEach {
            assertEquals(BlockDecision.Allow, AppBlockDecisionEngine.decide(rule, 0L, it))
            assertEquals(BlockDecision.Allow, BrowserFacebookClassifier.decision(it, rule.blockedSurfaces))
        }
    }

    @Test
    fun instagram_home_requires_selected_navigation_and_visible_feed_content() {
        listOf(setOf("main_feed_action_bar"), setOf("reels_tray_container"),
            setOf("sticky_header_list", "row_feed_button_like")).forEach { ids ->
            assertEquals(AppSurface.HOME_FEED, AppSurfaceClassifier.classify(INSTAGRAM_PACKAGE,
                AppUiSignals(selectedLabels = setOf("home"), viewIds = ids)))
            assertEquals(AppSurface.OTHER, AppSurfaceClassifier.classify(INSTAGRAM_PACKAGE,
                AppUiSignals(labels = setOf("home"), viewIds = ids + "feed_tab")))
        }
        assertEquals(AppSurface.OTHER, AppSurfaceClassifier.classify(INSTAGRAM_PACKAGE,
            AppUiSignals(selectedLabels = setOf("home"), viewIds = setOf("profile_header"))))
    }

    @Test
    fun instagram_active_sections_beat_retained_home_feed_markers() {
        val retained = setOf("main_feed_action_bar", "reels_tray_container", "feed_tab")
        mapOf("messages" to AppSurface.MESSAGE_INBOX, "reels" to AppSurface.REELS,
            "explore" to AppSurface.EXPLORE).forEach { (selected, expected) ->
            assertEquals(expected, AppSurfaceClassifier.classify(INSTAGRAM_PACKAGE,
                AppUiSignals(selectedLabels = setOf(selected), viewIds = retained)))
        }
        assertEquals(AppSurface.STORIES, AppSurfaceClassifier.classify(INSTAGRAM_PACKAGE,
            AppUiSignals(selectedLabels = setOf("home"),
                viewIds = retained + "reel_viewer_root")))
        assertEquals(AppSurface.HOME_FEED, AppSurfaceClassifier.classify(INSTAGRAM_PACKAGE,
            AppUiSignals(selectedLabels = setOf("home"),
                viewIds = retained + setOf("direct_inbox", "clips_viewer", "explore_grid"))))
    }

    @Test
    fun home_feed_rule_blocks_only_home_and_preserves_message_exemption() {
        val homeRule = rule(INSTAGRAM_PACKAGE, blockedSurfaces = setOf(AppSurface.HOME_FEED))
        assertTrue(homeRule.isConfigured)
        assertEquals(BlockDecision.Block(BlockReason.HOME_FEED),
            AppBlockDecisionEngine.decide(homeRule, 0L, AppSurface.HOME_FEED))
        listOf(AppSurface.MESSAGE_INBOX, AppSurface.MESSAGE_THREAD, AppSurface.REELS,
            AppSurface.STORIES, AppSurface.EXPLORE, AppSurface.OTHER).forEach { surface ->
            assertEquals(BlockDecision.Allow, AppBlockDecisionEngine.decide(homeRule, 0L, surface))
        }
        assertEquals(BlockDecision.Allow, AppBlockDecisionEngine.decide(
            homeRule.copy(blockedSurfaces = emptySet()), 0L, AppSurface.HOME_FEED))
        assertEquals(BlockDecision.Allow, AppBlockDecisionEngine.decide(
            homeRule.copy(dailyLimitMinutes = 1), 120_000L, AppSurface.MESSAGE_INBOX))
    }

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
            listOf(AppSurface.HOME_FEED, AppSurface.STORIES, AppSurface.REELS, AppSurface.EXPLORE),
            supportedSurfacesFor(INSTAGRAM_PACKAGE)
        )
        assertEquals(
            listOf(AppSurface.HOME_FEED, AppSurface.STORIES, AppSurface.REELS, AppSurface.MARKETPLACE),
            supportedSurfacesFor(FACEBOOK_PACKAGE)
        )
        assertEquals(
            listOf(AppSurface.SPOTLIGHT, AppSurface.DISCOVER, AppSurface.STORIES),
            supportedSurfacesFor(SNAPCHAT_PACKAGE)
        )
        assertEquals(
            listOf(AppSurface.EXPLORE, AppSurface.VIDEO_FEED),
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
            Triple(SNAPCHAT_PACKAGE, signals(viewId = "discover_viewer"), AppSurface.DISCOVER),
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
    fun youtube_open_comments_header_blocks_but_preview_text_does_not() {
        val rule = AppBlockRule(YOUTUBE_PACKAGE, blockedSurfaces = setOf(AppSurface.COMMENTS))
        val openPanel = AppSurfaceClassifier.classify(YOUTUBE_PACKAGE,
            AppUiSignals(youtubeCommentsPanelOpen = true))
        assertEquals(AppSurface.COMMENTS, openPanel)
        assertEquals(BlockDecision.Block(BlockReason.COMMENTS),
            AppBlockDecisionEngine.decide(rule, 0L, openPanel))
        val preview = AppSurfaceClassifier.classify(YOUTUBE_PACKAGE,
            AppUiSignals(labels = setOf("comments", "replies"),
                viewIds = setOf("com.google.android.youtube:id/modern_title")))
        assertEquals(AppSurface.OTHER, preview)
        assertEquals(BlockDecision.Allow, AppBlockDecisionEngine.decide(rule, 0L, preview))
    }

    @Test
    fun snapchat_section_rules_block_independently_and_leave_chat_and_camera_available() {
        val sections = listOf(
            Triple("spotlight", AppSurface.SPOTLIGHT, BlockReason.SPOTLIGHT),
            Triple("discover", AppSurface.DISCOVER, BlockReason.DISCOVER),
            Triple("stories", AppSurface.STORIES, BlockReason.STORIES)
        )
        sections.forEach { (_, blockedSurface, reason) ->
            val rule = rule(SNAPCHAT_PACKAGE, blockedSurfaces = setOf(blockedSurface))
            sections.forEach { (label, surface, _) ->
                val detected = AppSurfaceClassifier.classify(SNAPCHAT_PACKAGE,
                    if (surface == AppSurface.DISCOVER) signals(viewId = "discover_viewer")
                    else signals(selected = label)
                )
                assertEquals(surface, detected)
                assertEquals(
                    if (surface == blockedSurface) BlockDecision.Block(reason) else BlockDecision.Allow,
                    AppBlockDecisionEngine.decide(rule, 0L, detected)
                )
            }
            listOf("chat", "camera").forEach { label ->
                val detected = AppSurfaceClassifier.classify(SNAPCHAT_PACKAGE, signals(selected = label))
                assertEquals(BlockDecision.Allow, AppBlockDecisionEngine.decide(rule, 0L, detected))
            }
        }
    }

    @Test
    fun snapchat_discover_navigation_does_not_block_the_browsing_page() {
        assertEquals(
            AppSurface.OTHER,
            AppSurfaceClassifier.classify(
                SNAPCHAT_PACKAGE, signals(selected = "discover", viewId = "discover_feed")
            )
        )
        assertEquals(
            AppSurface.SPOTLIGHT,
            AppSurfaceClassifier.classify(
                SNAPCHAT_PACKAGE, signals(selected = "spotlight", viewId = "discover_feed")
            )
        )
    }

    @Test
    fun snapchat_unselected_navigation_and_plain_labels_do_not_block_chat() {
        assertEquals(
            AppSurface.OTHER,
            AppSurfaceClassifier.classify(
                SNAPCHAT_PACKAGE,
                AppUiSignals(
                    labels = setOf("spotlight", "discover", "stories"),
                    viewIds = setOf("spotlight_tab", "discover_tab", "stories_tab")
                )
            )
        )
    }

    @Test
    fun snapchat_discover_and_spotlight_content_containers_are_recognized() {
        listOf("discover_viewer", "discover_player").forEach { id ->
            assertEquals(
                AppSurface.DISCOVER,
                AppSurfaceClassifier.classify(SNAPCHAT_PACKAGE, signals(viewId = id))
            )
        }
        assertEquals(
            AppSurface.SPOTLIGHT,
            AppSurfaceClassifier.classify(SNAPCHAT_PACKAGE, signals(viewId = "spotlight_feed"))
        )
    }

    @Test
    fun snapchat_discover_grid_remains_available_until_a_post_opens() {
        listOf("discover_feed", "discover_page", "df_large_story").forEach { id ->
            val surface = AppSurfaceClassifier.classify(SNAPCHAT_PACKAGE, signals(viewId = id))
            assertEquals(AppSurface.OTHER, surface)
            assertEquals(BlockDecision.Allow, AppBlockDecisionEngine.decide(
                rule(SNAPCHAT_PACKAGE, blockedSurfaces = setOf(AppSurface.DISCOVER)), 0L, surface
            ))
        }
        val viewer = AppUiSignals(viewIds = setOf("opera_viewer", "context_chrome_header/chrome_subscribe_button", "discover_feed"))
        val opened = AppSurfaceClassifier.classify(SNAPCHAT_PACKAGE, viewer)
        assertEquals(AppSurface.DISCOVER, opened)
        assertEquals(BlockDecision.Block(BlockReason.DISCOVER), AppBlockDecisionEngine.decide(
            rule(SNAPCHAT_PACKAGE, blockedSurfaces = setOf(AppSurface.DISCOVER)), 0L, opened
        ))
        assertEquals(AppSurface.OTHER, AppSurfaceClassifier.classify(SNAPCHAT_PACKAGE, signals(viewId = "opera_viewer")))
    }

    @Test
    fun snapchat_friends_navigation_and_reply_controls_override_shared_publisher_controls() {
        val publicViewer = AppUiSignals(viewIds = setOf("opera_viewer", "context_chrome_header/chrome_subscribe_button"))
        val variants = listOf(
            publicViewer.copy(snapchatFriendStoryNavigation = true),
            publicViewer.copy(editableLabels = setOf("send a chat")),
            publicViewer.copy(viewIds = publicViewer.viewIds + "story_reply")
        )
        variants.forEach { signals ->
            val surface = AppSurfaceClassifier.classify(SNAPCHAT_PACKAGE, signals)
            assertEquals(AppSurface.STORIES, surface)
            assertEquals(BlockDecision.Allow, AppBlockDecisionEngine.decide(
                rule(SNAPCHAT_PACKAGE, blockedSurfaces = setOf(AppSurface.DISCOVER)), 0L, surface
            ))
        }
    }

    @Test
    fun snapchat_friends_marker_is_scoped_to_navigation_subtitle() {
        assertTrue(isSnapchatFriendStoryNavigation("chrome_subtitle", "Example from Quick Add"))
        assertTrue(isSnapchatFriendStoryNavigation("$SNAPCHAT_PACKAGE:id/chrome_subtitle", "Example FROM QUICK ADD"))
        assertFalse(isSnapchatFriendStoryNavigation("post_caption", "Example from Quick Add"))
        assertFalse(isSnapchatFriendStoryNavigation("chrome_subtitle", "Example"))
        assertFalse(isSnapchatFriendStoryNavigation("chrome_subtitle", null))
    }

    @Test
    fun old_snapchat_stories_rules_retain_discover_coverage_without_changing_spotlight() {
        assertEquals(
            setOf(AppSurface.STORIES, AppSurface.DISCOVER),
            migrateSnapchatDiscoverSurfaces(setOf(AppSurface.STORIES))
        )
        assertEquals(
            setOf(AppSurface.SPOTLIGHT),
            migrateSnapchatDiscoverSurfaces(setOf(AppSurface.SPOTLIGHT))
        )
        assertEquals(emptySet<AppSurface>(), migrateSnapchatDiscoverSurfaces(emptySet()))
    }

    @Test
    fun x_video_feed_blocks_only_when_its_rule_is_enabled() {
        val signals = signals(viewId = "videotab")
        val surface = AppSurfaceClassifier.classify(X_PACKAGE, signals)
        assertEquals(AppSurface.VIDEO_FEED, surface)
        assertEquals(
            BlockDecision.Block(BlockReason.VIDEO_FEED),
            AppBlockDecisionEngine.decide(
                rule(X_PACKAGE, blockedSurfaces = setOf(AppSurface.VIDEO_FEED)), 0L, surface
            )
        )
        assertEquals(
            BlockDecision.Allow,
            AppBlockDecisionEngine.decide(
                rule(X_PACKAGE, blockedSurfaces = setOf(AppSurface.EXPLORE)), 0L, surface
            )
        )
    }

    @Test
    fun x_video_feed_has_priority_over_underlying_explore_signals() {
        assertEquals(
            AppSurface.VIDEO_FEED,
            AppSurfaceClassifier.classify(
                X_PACKAGE,
                signals(selected = "explore", viewIds = setOf("videotab", "explore_timeline"))
            )
        )
        assertEquals(
            AppSurface.VIDEO_FEED,
            AppSurfaceClassifier.classify(X_PACKAGE, signals(viewId = "$X_PACKAGE:id/videotab"))
        )
    }

    @Test
    fun x_timeline_and_photo_controls_do_not_qualify_as_video_feed() {
        listOf("mainlanding", "scaffold_home_tabbed", "video_tab_button", "videotab_button", "photo_viewer")
            .forEach { viewId ->
                val surface = AppSurfaceClassifier.classify(
                    X_PACKAGE,
                    AppUiSignals(
                        labels = setOf("video", "back", "more options", "like", "share", "follow"),
                        viewIds = setOf(viewId)
                    )
                )
                assertEquals(AppSurface.OTHER, surface)
                assertEquals(
                    BlockDecision.Allow,
                    AppBlockDecisionEngine.decide(
                        rule(X_PACKAGE, blockedSurfaces = setOf(AppSurface.VIDEO_FEED)), 0L, surface
                    )
                )
            }
        assertEquals(AppSurface.OTHER, AppSurfaceClassifier.classify(X_PACKAGE, AppUiSignals()))
        assertEquals(
            AppSurface.UNKNOWN,
            AppSurfaceClassifier.classify("other.app", signals(viewId = "videotab"))
        )
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
    fun facebook_story_controls_take_priority_and_obey_the_independent_story_rule() {
        for (packageName in listOf(FACEBOOK_PACKAGE, FACEBOOK_LITE_PACKAGE)) {
            val surface = AppSurfaceClassifier.classify(packageName,
                AppUiSignals(facebookStoryViewerOpen = true, selectedLabels = setOf("reels")))
            assertEquals(AppSurface.STORIES, surface)
            assertEquals(BlockDecision.Block(BlockReason.STORIES), AppBlockDecisionEngine.decide(
                AppBlockRule(packageName, blockedSurfaces = setOf(AppSurface.STORIES)), 0L, surface))
            assertEquals(BlockDecision.Allow, AppBlockDecisionEngine.decide(
                AppBlockRule(packageName, blockedSurfaces = setOf(AppSurface.REELS)), 0L, surface))
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
    fun instagram_inbox_remains_available_with_section_blocking() {
        val surface = AppSurfaceClassifier.classify(
            INSTAGRAM_PACKAGE,
            signals(selected = "messages", viewId = "direct_inbox")
        )

        assertEquals(AppSurface.MESSAGE_INBOX, surface)
        assertEquals(
            BlockDecision.Allow,
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
    fun instagram_inbox_exemption_applies_after_daily_limit_and_can_be_disabled() {
        val rule = rule(INSTAGRAM_PACKAGE, dailyLimitMinutes = 10)
        assertEquals(BlockDecision.Allow,
            AppBlockDecisionEngine.decide(rule, 11 * 60_000L, AppSurface.MESSAGE_INBOX))
        assertEquals(BlockDecision.Block(BlockReason.DAILY_LIMIT),
            AppBlockDecisionEngine.decide(rule.copy(allowMessages = false),
                11 * 60_000L, AppSurface.MESSAGE_INBOX))
    }

    @Test
    fun instagram_story_reply_composer_does_not_exempt_the_story() {
        listOf("story_viewer", "stories_viewer", "com.instagram.android:id/reel_viewer_root")
            .forEach { viewerId ->
                val surface = AppSurfaceClassifier.classify(INSTAGRAM_PACKAGE, AppUiSignals(
                    viewIds = setOf(viewerId, "message_composer_container", "direct_inbox"),
                    selectedLabels = setOf("messages"),
                    editableLabels = setOf("send message")
                ))
                assertEquals(AppSurface.STORIES, surface)
                assertEquals(BlockDecision.Block(BlockReason.STORIES),
                    AppBlockDecisionEngine.decide(rule(INSTAGRAM_PACKAGE,
                        blockedSurfaces = setOf(AppSurface.STORIES)), 0L, surface))
            }
    }

    @Test
    fun instagram_story_tray_does_not_block_home_or_inbox() {
        assertEquals(AppSurface.OTHER, AppSurfaceClassifier.classify(INSTAGRAM_PACKAGE,
            AppUiSignals(viewIds = setOf("reels_tray_container", "reel_empty_badge", "search_tab", "explore_tab"))))
        assertEquals(AppSurface.MESSAGE_INBOX, AppSurfaceClassifier.classify(INSTAGRAM_PACKAGE,
            AppUiSignals(viewIds = setOf("direct_inbox_action_bar",
                "inbox_refreshable_thread_list_recyclerview", "search_tab", "explore_tab"))))
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
