package com.boringutils.timehud.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppWindowMemoryTest {
    private val reels = AppWindowSnapshot(1, INSTAGRAM_PACKAGE, AppSurface.REELS)

    @Test
    fun notification_panel_does_not_replace_reels_with_other_surface() {
        val memory = AppWindowMemory()
        memory.update(listOf(reels), false)
        assertEquals(listOf(reels), memory.update(listOf(reels.copy(surface = AppSurface.OTHER)), true))
        assertEquals(listOf(reels), memory.update(listOf(reels.copy(surface = AppSurface.UNKNOWN)), false))
        assertEquals(listOf(reels), memory.update(listOf(reels), false))
    }

    @Test
    fun app_window_missing_behind_notifications_is_restored() {
        val memory = AppWindowMemory()
        memory.update(listOf(reels), false)
        assertEquals(listOf(reels), memory.update(emptyList(), true))
        assertEquals(listOf(reels), memory.update(emptyList(), true))
        assertEquals(listOf(reels), memory.update(listOf(reels.copy(surface = AppSurface.UNKNOWN)), false))
    }

    @Test
    fun home_or_another_app_clears_absent_window_without_system_panel() {
        val memory = AppWindowMemory()
        memory.update(listOf(reels), false)
        memory.update(emptyList(), true)
        assertTrue(memory.update(emptyList(), false).isEmpty())
        val other = AppWindowSnapshot(2, "other.app", AppSurface.UNKNOWN)
        assertEquals(listOf(other), memory.update(listOf(other), false))
        // Returning to Reels uses a fresh observation, rather than covering the other app.
        assertEquals(listOf(reels), memory.update(listOf(reels), false))
    }

    @Test
    fun readable_navigation_out_of_reels_resumes_normal_rules() {
        val memory = AppWindowMemory()
        memory.update(listOf(reels), false)
        memory.update(emptyList(), true)
        val messages = reels.copy(surface = AppSurface.MESSAGE_THREAD)
        assertEquals(listOf(messages), memory.update(listOf(messages), false))
        val feed = reels.copy(surface = AppSurface.OTHER)
        assertEquals(listOf(feed), memory.update(listOf(feed), false))
    }

    @Test
    fun reused_window_id_does_not_transfer_reels_state_to_another_package() {
        val memory = AppWindowMemory()
        memory.update(listOf(reels), false)
        val other = reels.copy(packageName = "other.app", surface = AppSurface.UNKNOWN)
        assertEquals(listOf(other), memory.update(listOf(other), true))
    }

    @Test
    fun service_cleanup_discards_remembered_sections() {
        val memory = AppWindowMemory()
        memory.update(listOf(reels), false)
        memory.clear()
        assertTrue(memory.update(emptyList(), true).isEmpty())
    }

    @Test
    fun remembered_surface_still_obeys_rule_changes_and_grace() {
        val memory = AppWindowMemory()
        memory.update(listOf(reels), false)
        val surface = memory.update(emptyList(), true).single().surface
        val rule = AppBlockRule(INSTAGRAM_PACKAGE, blockedSurfaces = setOf(AppSurface.REELS))
        val blocked = AppBlockDecisionEngine.decide(rule, 0L, surface)
        assertEquals(BlockDecision.Block(BlockReason.REELS), blocked)
        assertEquals(BlockDecision.Allow, AppBlockDecisionEngine.decide(rule.copy(blockedSurfaces = emptySet()), 0L, surface))
        assertEquals(BlockDecision.Allow, AppLimitGracePolicy.applyTo(blocked, AppLimitGraceStatus.Active(AppLimitGraceKind.NORMAL, 1000L)))
    }
}
