package com.boringutils.timehud.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeHudColorsTest {
    @Test
    fun softGraphitePalette_containsOnlyNeutralColors() {
        val colors = listOf(
            TimeHudColors.background,
            TimeHudColors.surface,
            TimeHudColors.surfaceElevated,
            TimeHudColors.surfaceSelected,
            TimeHudColors.disabledSurface,
            TimeHudColors.border,
            TimeHudColors.borderSubtle,
            TimeHudColors.textPrimary,
            TimeHudColors.textSecondary,
            TimeHudColors.textDisabled,
            TimeHudColors.textEmphasis,
            TimeHudColors.action,
            TimeHudColors.onAction,
            TimeHudColors.statusPositive,
            TimeHudColors.statusWarning,
            TimeHudColors.statusDestructive
        )

        colors.forEach(::assertNeutral)
    }

    private fun assertNeutral(color: Color) {
        assertEquals(color.red, color.green, 0f)
        assertEquals(color.green, color.blue, 0f)
    }
}
