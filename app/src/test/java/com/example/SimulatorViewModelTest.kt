package com.example

import com.example.ui.OrientationMode
import com.example.ui.SPBU_SIMULATOR_URL
import com.example.ui.SimulatorLoadState
import com.example.ui.SimulatorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorViewModelTest {

    @Test
    fun `initial state points to SPBU Simulator URL and starts loading`() {
        val vm = SimulatorViewModel()
        val state = vm.uiState.value
        assertEquals(SPBU_SIMULATOR_URL, state.targetUrl)
        assertEquals(SPBU_SIMULATOR_URL, state.currentUrl)
        assertEquals(SimulatorLoadState.Loading(0), state.loadState)
        assertFalse(state.hasCompletedInitialLoad)
        assertTrue(state.keepScreenOn)
        assertFalse(state.isFullscreen)
    }

    @Test
    fun `progress 100 transitions load state to Ready`() {
        val vm = SimulatorViewModel()
        vm.onProgressChanged(55)
        assertEquals(SimulatorLoadState.Loading(55), vm.uiState.value.loadState)

        vm.onProgressChanged(100)
        assertEquals(SimulatorLoadState.Ready, vm.uiState.value.loadState)
        assertTrue(vm.uiState.value.hasCompletedInitialLoad)
    }

    @Test
    fun `error state and retry flow transition cleanly`() {
        val vm = SimulatorViewModel()
        vm.onPageError(errorCode = -2, description = "ERR_INTERNET_DISCONNECTED", isOffline = true)

        val err = vm.uiState.value.loadState as SimulatorLoadState.Error
        assertTrue(err.isOffline)
        assertEquals(-2, err.errorCode)

        vm.prepareRetry()
        assertEquals(SimulatorLoadState.Loading(5), vm.uiState.value.loadState)
    }

    @Test
    fun `orientation cycles through AUTO, LANDSCAPE, and PORTRAIT`() {
        val vm = SimulatorViewModel()
        assertEquals(OrientationMode.AUTO, vm.uiState.value.orientationMode)

        vm.cycleOrientation()
        assertEquals(OrientationMode.LANDSCAPE, vm.uiState.value.orientationMode)

        vm.cycleOrientation()
        assertEquals(OrientationMode.PORTRAIT, vm.uiState.value.orientationMode)

        vm.cycleOrientation()
        assertEquals(OrientationMode.AUTO, vm.uiState.value.orientationMode)
    }

    @Test
    fun `toggling fullscreen collapses controls panel`() {
        val vm = SimulatorViewModel()
        vm.setControlsExpanded(true)
        assertTrue(vm.uiState.value.controlsExpanded)

        vm.toggleFullscreen()
        assertTrue(vm.uiState.value.isFullscreen)
        assertFalse(vm.uiState.value.controlsExpanded)
    }
}
