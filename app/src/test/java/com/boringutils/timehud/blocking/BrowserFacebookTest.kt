package com.boringutils.timehud.blocking

import org.junit.Assert.*
import org.junit.Test

class BrowserFacebookTest {
    private fun surface(url: String, title: String = "facebook") = BrowserFacebookClassifier.classify(
        BrowserFacebookClassifier.address(url).copy(pageTitle = title))

    @Test fun routes_distinguish_sections_from_other_facebook_pages() {
        mapOf(
            "https://m.facebook.com/" to AppSurface.HOME_FEED,
            "www.facebook.com/reel/123/?referral_source=fb_shorts_tab" to AppSurface.REELS,
            "m.facebook.com/reels/" to AppSurface.REELS,
            "m.facebook.com/marketplace/" to AppSurface.MARKETPLACE,
            "m.facebook.com/marketplace/item/123/" to AppSurface.MARKETPLACE,
            "m.facebook.com/stories/123/456/" to AppSurface.STORIES,
            "m.facebook.com/stories/" to AppSurface.OTHER,
            "m.facebook.com/story.php?story_fbid=123" to AppSurface.OTHER,
            "m.facebook.com/photo.php?fbid=123" to AppSurface.OTHER,
            "m.facebook.com/messages/t/123/" to AppSurface.OTHER,
            "m.facebook.com/search/" to AppSurface.OTHER,
            "m.facebook.com/profile.php?id=123" to AppSurface.OTHER,
            "m.facebook.com/reel/" to AppSurface.OTHER,
            "m.facebook.com/marketplaces/" to AppSurface.OTHER
        ).forEach { (url, expected) -> assertEquals(url, expected, surface(url)) }
    }

    @Test fun firefox_description_is_parsed_without_retaining_the_address() {
        val signals = BrowserFacebookClassifier.address(" m.facebook.com/marketplace. Search or enter address")
        assertEquals(AppSurface.MARKETPLACE, BrowserFacebookClassifier.classify(signals))
        assertFalse(signals.toString().contains("facebook.com"))
    }

    @Test fun unrelated_hosts_and_address_editing_override_facebook_controls() {
        listOf("https://facebook.com.evil.example/reel/123", "https://facebook.com@evil.example/",
            "https://example.com/facebook.com", "https://google.com/search?q=facebook.com",
            "javascript:facebook.com", "not a URL", "file://facebook.com/reel/123").forEach { url ->
            val signals = BrowserFacebookClassifier.address(url).copy(facebookBrand = true,
                selectedNavigation = setOf(AppSurface.REELS), controls = BrowserFacebookClassifier.controlLabels)
            assertEquals(url, AppSurface.OTHER, BrowserFacebookClassifier.classify(signals))
        }
        assertEquals(AppSurface.OTHER, BrowserFacebookClassifier.classify(
            BrowserFacebookClassifier.address("m.facebook.com/reel/123", editing = true)
                .copy(facebookBrand = true, selectedNavigation = setOf(AppSurface.REELS))))
    }

    @Test fun navigation_requires_the_fixed_template_and_normalizes_counts() {
        assertEquals(AppSurface.HOME_FEED, BrowserFacebookClassifier.navigationSurface("feed, 1,800 new, 1 of 6"))
        assertEquals(AppSurface.REELS, BrowserFacebookClassifier.navigationSurface("reels, 4 of 6"))
        assertEquals(AppSurface.MARKETPLACE, BrowserFacebookClassifier.navigationSurface("marketplace, 2 new, 6 of 6"))
        listOf("Reels", "Reels by PRIVATE_IDENTITY", "reels, tab 4 of 6", "reels, 4 of 60").forEach {
            assertNull(BrowserFacebookClassifier.navigationSurface(it))
        }
    }

    @Test fun previews_and_conflicting_navigation_do_not_identify_an_active_reel() {
        val feed = BrowserFacebookClassifier.address("m.facebook.com").copy(facebookBrand = true,
            selectedNavigation = setOf(AppSurface.HOME_FEED), controls = setOf("create story", "facebook logo"))
        assertEquals(AppSurface.HOME_FEED, BrowserFacebookClassifier.classify(feed))
        assertEquals(AppSurface.OTHER, BrowserFacebookClassifier.classify(BrowserFacebookSignals(
            controls = BrowserFacebookClassifier.controlLabels, selectedNavigation = setOf(AppSurface.REELS))))
        assertEquals(AppSurface.OTHER, BrowserFacebookClassifier.classify(feed.copy(
            selectedNavigation = setOf(AppSurface.HOME_FEED, AppSurface.REELS))))
    }

    @Test fun comments_at_the_base_domain_do_not_become_feed_or_inherit_a_reel() {
        val feed = BrowserFacebookClassifier.address("m.facebook.com").copy(pageTitle = "facebook")
        assertEquals(AppSurface.COMMENTS, BrowserFacebookClassifier.classify(feed.copy(pageTitle = "comments")))
        assertEquals(AppSurface.COMMENTS, BrowserFacebookClassifier.classify(feed.copy(controls = setOf("comments"))))
        assertEquals(BlockDecision.Allow, BrowserFacebookClassifier.decision(AppSurface.COMMENTS, FACEBOOK_BROWSER_SURFACES.toSet()))
        assertEquals(AppSurface.OTHER, BrowserFacebookClassifier.classify(BrowserFacebookSignals()))
    }

    @Test fun explicit_other_routes_beat_retained_navigation_and_story_controls() {
        val profile = BrowserFacebookClassifier.address("m.facebook.com/profile.php?id=123").copy(
            facebookBrand = true, selectedNavigation = setOf(AppSurface.HOME_FEED), controls = setOf("close story", "next story"))
        assertEquals(AppSurface.OTHER, BrowserFacebookClassifier.classify(profile))
    }

    @Test fun story_viewer_beats_underlying_feed() {
        assertEquals(AppSurface.STORIES, BrowserFacebookClassifier.classify(
            BrowserFacebookClassifier.address("m.facebook.com").copy(
                controls = setOf("close story", "next story"), selectedNavigation = setOf(AppSurface.HOME_FEED))))
    }

    @Test fun every_browser_switch_only_blocks_its_own_section() {
        FACEBOOK_BROWSER_SURFACES.forEach { blocked ->
            FACEBOOK_BROWSER_SURFACES.forEach { actual ->
                assertEquals("$blocked / $actual", actual == blocked,
                    BrowserFacebookClassifier.decision(actual, setOf(blocked)) is BlockDecision.Block)
            }
            assertEquals(BlockDecision.Allow, BrowserFacebookClassifier.decision(blocked, emptySet()))
        }
    }

    @Test fun browser_toggle_is_opt_in_and_does_not_change_native_decisions() {
        val rule = AppBlockRule(FACEBOOK_PACKAGE, browserFacebook = true)
        assertTrue(rule.isConfigured)
        assertTrue(rule.blockedSurfaces.isEmpty())
        assertFalse(AppBlockRule(FACEBOOK_PACKAGE).isConfigured)
        assertFalse(AppBlockRule("com.android.chrome", browserFacebook = true).isConfigured)
        assertEquals(BlockDecision.Allow, AppBlockDecisionEngine.decide(rule, 0L, AppSurface.REELS))
    }
}
