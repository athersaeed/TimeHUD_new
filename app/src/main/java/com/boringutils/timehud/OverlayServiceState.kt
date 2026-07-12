package com.boringutils.timehud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ServicePrimaryAction {
    START,
    STOP
}

data class OverlayServiceUiState(
    val isRunning: Boolean = false
) {
    val primaryAction: ServicePrimaryAction
        get() = if (isRunning) ServicePrimaryAction.STOP else ServicePrimaryAction.START
}

object OverlayServiceStateStore {
    private val _uiState = MutableStateFlow(OverlayServiceUiState())
    val uiState: StateFlow<OverlayServiceUiState> = _uiState.asStateFlow()

    fun markRunning() {
        _uiState.value = OverlayServiceUiState(isRunning = true)
    }

    fun markStopped() {
        _uiState.value = OverlayServiceUiState()
    }
}
