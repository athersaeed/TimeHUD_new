package com.boringutils.timehud.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserInstagramTest {
    @Test fun routes_distinguish_feed_inbox_thread_stories_reels_and_explore() {
        val cases = mapOf(
            "https://www.instagram.com/" to AppSurface.VIDEO_FEED,
            "instagram.com/direct/inbox/" to AppSurface.MESSAGE_INBOX,
            "instagram.com/direct/t/123/" to AppSurface.MESSAGE_THREAD,
            "instagram.com/stories/example/123/" to AppSurface.STORIES,
            "instagram.com/reels/" to AppSurface.REELS,
            "instagram.com/reel/example/" to AppSurface.REELS,
            "instagram.com/explore/" to AppSurface.EXPLORE,
            "instagram.com/accounts/login/" to AppSurface.OTHER,
            "instagram.com/example/" to AppSurface.OTHER
        )
        cases.forEach { (url, surface) ->
            assertEquals(url, surface, BrowserInstagramClassifier.classify(BrowserInstagramClassifier.address(url)))
        }
    }

    @Test fun firefox_accessible_address_includes_an_instruction() {
        assertEquals(AppSurface.MESSAGE_INBOX, BrowserInstagramClassifier.classify(
            BrowserInstagramClassifier.address(" instagram.com/direct/inbox. Search or enter address")))
    }

    @Test fun unrelated_hosts_searches_and_lookalike_domains_remain_available() {
        listOf("https://example.com/instagram.com", "instagram.com.evil.example/stories/a/1/",
            "https://instagram.com@evil.example/", "https://google.com/search?q=instagram.com",
            "not a url", "javascript:instagram.com").forEach {
            assertFalse(it, BrowserInstagramClassifier.address(it).instagramAddress)
            assertEquals(BlockDecision.Allow, BrowserInstagramClassifier.decision(
                BrowserInstagramClassifier.classify(BrowserInstagramClassifier.address(it))))
        }
    }

    @Test fun unrelated_address_beats_instagram_like_page_controls() {
        val signals = BrowserInstagramClassifier.address("example.com").copy(
            instagramBrand = true, controls = BrowserInstagramClassifier.controlLabels, hasComposer = true)
        assertEquals(AppSurface.OTHER, BrowserInstagramClassifier.classify(signals))
    }

    @Test fun editing_address_does_not_block_a_typed_but_unopened_url() {
        assertEquals(AppSurface.OTHER, BrowserInstagramClassifier.classify(
            BrowserInstagramClassifier.address("instagram.com/stories/a/1/", editing = true)))
    }

    @Test fun story_viewer_beats_underlying_dm_route_and_its_reply_composer() {
        val signals = BrowserInstagramClassifier.address("instagram.com/direct/t/123/").copy(
            controls = setOf("close", "like", "direct"), hasComposer = true)
        assertEquals(AppSurface.STORIES, BrowserInstagramClassifier.classify(signals))
    }

    @Test fun dm_and_inbox_routes_stay_allowed_despite_shared_navigation_controls() {
        listOf("instagram.com/direct/t/123/", "instagram.com/direct/inbox/").forEach {
            val signals = BrowserInstagramClassifier.address(it).copy(
                controls = setOf("home", "reels", "messages", "like", "share"), hasComposer = true)
            assertEquals(BlockDecision.Allow, BrowserInstagramClassifier.decision(
                BrowserInstagramClassifier.classify(signals)))
        }
    }

    @Test fun navigation_labels_alone_do_not_identify_feed_or_reels() {
        assertEquals(AppSurface.OTHER, BrowserInstagramClassifier.classify(BrowserInstagramSignals(
            instagramBrand = true, controls = setOf("home", "reels", "messages"))))
        assertEquals(AppSurface.OTHER, BrowserInstagramClassifier.classify(BrowserInstagramSignals(
            controls = BrowserInstagramClassifier.controlLabels, hasComposer = true)))
    }

    @Test fun branded_feed_can_be_recognized_when_address_is_unavailable() {
        assertEquals(AppSurface.VIDEO_FEED, BrowserInstagramClassifier.classify(BrowserInstagramSignals(
            instagramBrand = true, controls = setOf("home", "reels", "messages", "like", "comment", "share", "save"))))
    }

    @Test fun test_setting_is_opt_in_and_can_stand_alone() {
        assertFalse(AppBlockRule(INSTAGRAM_PACKAGE).isConfigured)
        assertFalse(AppBlockRule(INSTAGRAM_PACKAGE).browserInstagramTest)
        assertTrue(AppBlockRule(INSTAGRAM_PACKAGE, browserInstagramTest = true).isConfigured)
    }

    @Test fun recognized_distraction_sections_are_blocked_and_unknown_sections_fail_open() {
        listOf(AppSurface.VIDEO_FEED, AppSurface.REELS, AppSurface.STORIES, AppSurface.EXPLORE).forEach {
            assertTrue(BrowserInstagramClassifier.decision(it) is BlockDecision.Block)
        }
        assertEquals(BlockDecision.Allow, BrowserInstagramClassifier.decision(AppSurface.UNKNOWN))
        assertEquals(BlockDecision.Allow, BrowserInstagramClassifier.decision(AppSurface.OTHER))
    }
}
