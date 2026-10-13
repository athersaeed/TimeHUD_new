package com.boringutils.timehud.blocking

internal data class AppWindowSnapshot(
    val windowId: Int,
    val packageName: String,
    val surface: AppSurface
)

/** Keeps navigation state through system-panel and unavailable-tree interruptions. */
internal class AppWindowMemory {
    private var previous = emptyList<AppWindowSnapshot>()

    fun update(current: List<AppWindowSnapshot>, systemPanelActive: Boolean): List<AppWindowSnapshot> {
        val resolved = current.map { snapshot ->
            val last = previous.firstOrNull {
                it.windowId == snapshot.windowId && it.packageName == snapshot.packageName
            }
            if (last != null && (systemPanelActive || snapshot.surface == AppSurface.UNKNOWN)) {
                snapshot.copy(surface = last.surface)
            } else {
                snapshot
            }
        }
        previous = if (systemPanelActive) {
            resolved + previous.filter { last -> resolved.none { it.windowId == last.windowId } }
        } else {
            resolved
        }
        return previous
    }

    fun clear() {
        previous = emptyList()
    }
}
