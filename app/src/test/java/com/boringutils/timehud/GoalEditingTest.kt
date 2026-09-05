package com.boringutils.timehud

import org.junit.Assert.assertEquals
import org.junit.Test

class GoalEditingTest {
    @Test fun saving_unchanged_editor_keeps_overlay_deletion() {
        assertEquals("B", reconcileRemovedGoals("A\nB", "A\nB", "B"))
    }

    @Test fun unsaved_additions_survive_overlay_deletion() {
        assertEquals("B\nC", reconcileRemovedGoals("A\nB", "A\nB\nC", "B"))
    }

    @Test fun editing_a_line_preserves_the_new_text() {
        assertEquals("A revised\nB", reconcileRemovedGoals("A\nB", "A revised\nB", "B"))
    }

    @Test fun deleting_one_duplicate_does_not_delete_both() {
        assertEquals("A\nB", reconcileRemovedGoals("A\nA", "A\nA\nB", "A"))
    }

    @Test fun no_external_change_preserves_draft_exactly() {
        assertEquals(" A \nB\n", reconcileRemovedGoals("A", " A \nB\n", "A"))
    }

    @Test fun non_latin_goals_are_reconciled_without_identity_collisions() {
        assertEquals("学习\nNew", reconcileRemovedGoals("学习\n阅读", "学习\n阅读\nNew", "学习"))
    }

    @Test fun independently_edited_goal_groups_can_keep_their_drafts() {
        assertEquals("B", reconcileRemovedGoals("A\nB", "A\nB", "B"))
        assertEquals("New long term", reconcileRemovedGoals("Old", "New long term", "Old"))
    }
}
