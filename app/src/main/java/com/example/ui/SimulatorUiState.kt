package com.example.ui

import android.content.pm.ActivityInfo
import android.webkit.JsPromptResult
import android.webkit.JsResult

const val SPBU_SIMULATOR_URL = "https://app.spbusimulator.online/"

sealed interface SimulatorLoadState {
    data class Loading(val progress: Int) : SimulatorLoadState
    data object Ready : SimulatorLoadState
    data class Error(
        val errorCode: Int,
        val description: String,
        val isOffline: Boolean,
    ) : SimulatorLoadState
}

enum class OrientationMode(val requestedOrientation: Int) {
    AUTO(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
    LANDSCAPE(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
    PORTRAIT(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
}

sealed interface JsDialogState {
    data class Alert(
        val message: String,
        val result: JsResult,
    ) : JsDialogState

    data class Confirm(
        val message: String,
        val result: JsResult,
    ) : JsDialogState

    data class Prompt(
        val message: String,
        val defaultValue: String,
        val result: JsPromptResult,
    ) : JsDialogState
}

data class SimulatorUiState(
    val targetUrl: String = SPBU_SIMULATOR_URL,
    val currentUrl: String = SPBU_SIMULATOR_URL,
    val pageTitle: String = "",
    val loadState: SimulatorLoadState = SimulatorLoadState.Loading(0),
    val hasCompletedInitialLoad: Boolean = false,
    val canGoBack: Boolean = false,
    val isFullscreen: Boolean = false,
    val keepScreenOn: Boolean = true,
    val orientationMode: OrientationMode = OrientationMode.AUTO,
    val controlsExpanded: Boolean = false,
    val jsDialog: JsDialogState? = null,
)
