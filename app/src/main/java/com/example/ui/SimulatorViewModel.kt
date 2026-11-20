package com.example.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SimulatorViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SimulatorUiState())
    val uiState: StateFlow<SimulatorUiState> = _uiState.asStateFlow()

    fun onPageStarted(url: String?, canGoBack: Boolean) {
        _uiState.update { state ->
            state.copy(
                currentUrl = url ?: state.currentUrl,
                canGoBack = canGoBack,
                loadState = if (state.loadState is SimulatorLoadState.Error) {
                    SimulatorLoadState.Loading(10)
                } else {
                    state.loadState
                },
            )
        }
    }

    fun onProgressChanged(progress: Int) {
        _uiState.update { state ->
            if (state.loadState is SimulatorLoadState.Error) {
                state
            } else if (progress >= 100) {
                state.copy(
                    loadState = SimulatorLoadState.Ready,
                    hasCompletedInitialLoad = true,
                )
            } else {
                state.copy(
                    loadState = SimulatorLoadState.Loading(progress.coerceIn(0, 99)),
                )
            }
        }
    }

    fun onPageFinished(url: String?, canGoBack: Boolean) {
        _uiState.update { state ->
            val nextLoadState = if (state.loadState is SimulatorLoadState.Error) {
                state.loadState
            } else {
                SimulatorLoadState.Ready
            }
            state.copy(
                currentUrl = url ?: state.currentUrl,
                canGoBack = canGoBack,
                loadState = nextLoadState,
                hasCompletedInitialLoad = state.hasCompletedInitialLoad || nextLoadState is SimulatorLoadState.Ready,
            )
        }
    }

    fun onPageTitleChanged(title: String?) {
        val cleanTitle = title?.trim().orEmpty()
        if (cleanTitle.isNotEmpty()) {
            _uiState.update { it.copy(pageTitle = cleanTitle) }
        }
    }

    fun onNavigationStateChanged(canGoBack: Boolean) {
        _uiState.update { it.copy(canGoBack = canGoBack) }
    }

    fun onPageError(errorCode: Int, description: String, isOffline: Boolean) {
        _uiState.update { state ->
            state.copy(
                loadState = SimulatorLoadState.Error(
                    errorCode = errorCode,
                    description = description.ifBlank { "Network error" },
                    isOffline = isOffline,
                ),
            )
        }
    }

    fun prepareRetry() {
        _uiState.update { state ->
            state.copy(
                loadState = SimulatorLoadState.Loading(5),
            )
        }
    }

    fun toggleFullscreen() {
        _uiState.update { state ->
            val nextFullscreen = !state.isFullscreen
            state.copy(
                isFullscreen = nextFullscreen,
                controlsExpanded = if (nextFullscreen) false else state.controlsExpanded,
            )
        }
    }

    fun toggleKeepScreenOn() {
        _uiState.update { it.copy(keepScreenOn = !it.keepScreenOn) }
    }

    fun cycleOrientation() {
        _uiState.update { state ->
            val next = when (state.orientationMode) {
                OrientationMode.AUTO -> OrientationMode.LANDSCAPE
                OrientationMode.LANDSCAPE -> OrientationMode.PORTRAIT
                OrientationMode.PORTRAIT -> OrientationMode.AUTO
            }
            state.copy(orientationMode = next)
        }
    }

    fun setControlsExpanded(expanded: Boolean) {
        _uiState.update { it.copy(controlsExpanded = expanded) }
    }

    fun showJsDialog(dialog: JsDialogState) {
        _uiState.update { it.copy(jsDialog = dialog) }
    }

    fun clearJsDialog() {
        _uiState.update { it.copy(jsDialog = null) }
    }
}
