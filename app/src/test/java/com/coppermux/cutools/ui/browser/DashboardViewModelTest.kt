package com.coppermux.cutools.ui.browser

import com.coppermux.cutools.core.browser.DashboardEventClassifier
import com.coppermux.cutools.core.browser.DashboardEventStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardViewModelTest {

    @Test
    fun initialState_defaultsToTrue() {
        val vm = DashboardViewModel()
        assertTrue(vm.isRecording.value)
    }

    @Test
    fun toggleRecording_standalone_flipsState() {
        val vm = DashboardViewModel()
        assertTrue(vm.isRecording.value)

        vm.toggleRecording()
        assertFalse(vm.isRecording.value)

        vm.toggleRecording()
        assertTrue(vm.isRecording.value)
    }

    @Test
    fun setRecording_standalone_updatesState() {
        val vm = DashboardViewModel()
        vm.setRecording(false)
        assertFalse(vm.isRecording.value)

        vm.setRecording(true)
        assertTrue(vm.isRecording.value)
    }

    @Test
    fun connectedToStream_toggleFreezesIngestion() {
        val stream = DashboardEventStream()
        val vm = DashboardViewModel(stream)

        assertTrue(vm.isRecording.value)
        assertTrue(stream.isRecording)

        val ev1 = DashboardEventClassifier.buildDocumentFinishedEvent("https://test.com", 1_000L, 50L)
        stream.append(ev1)
        assertEquals(1, stream.state.eventCount)

        // Pausing via ViewModel
        vm.toggleRecording()
        assertFalse(vm.isRecording.value)
        assertFalse(stream.isRecording)

        // Events arriving while paused are dropped (feed frozen)
        val ev2 = DashboardEventClassifier.buildDocumentFinishedEvent("https://test.com/other", 2_000L, 50L)
        stream.append(ev2)
        assertEquals(1, stream.state.eventCount)

        // Resuming via ViewModel
        vm.toggleRecording()
        assertTrue(vm.isRecording.value)
        assertTrue(stream.isRecording)

        val ev3 = DashboardEventClassifier.buildDocumentFinishedEvent("https://test.com/resumed", 3_000L, 50L)
        stream.append(ev3)
        assertEquals(2, stream.state.eventCount)
    }
}
