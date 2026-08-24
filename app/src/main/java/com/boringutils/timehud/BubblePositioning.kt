package com.boringutils.timehud

internal data class BubblePosition(
    val x: Int,
    val y: Int
)

internal object BubblePositioning {
    fun clamp(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        bubbleWidth: Int,
        bubbleHeight: Int
    ): BubblePosition {
        val maximumX = (screenWidth - bubbleWidth).coerceAtLeast(0)
        val maximumY = (screenHeight - bubbleHeight).coerceAtLeast(0)
        return BubblePosition(
            x = x.coerceIn(0, maximumX),
            y = y.coerceIn(0, maximumY)
        )
    }
}
