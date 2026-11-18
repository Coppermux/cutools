package com.coppermux.cutools.ui.browser

import androidx.lifecycle.ViewModel
import com.coppermux.cutools.core.browser.DashboardEventStream
import com.coppermux.cutools.core.browser.DashboardEventStreamListener
import com.coppermux.cutools.core.browser.DashboardStreamState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 18 / Redesign — Dashboard ViewModel for feed controls.
 *
 * Exposes a reactive [isRecording] StateFlow (defaulting to true) and a
 * [toggleRecording] function to pause/resume interceptor feed capture in
 * the underlying [DashboardEventStream].
 */
class DashboardViewModel(
    private val dashboardEventStream: DashboardEventStream? = null,
) : ViewModel() {
    private val _isRecording = MutableStateFlow(dashboardEventStream?.state?.isRecording ?: true)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    init {
        dashboardEventStream?.addListener(object : DashboardEventStreamListener {
            override fun onDashboardStateChanged(state: DashboardStreamState) {
                _isRecording.value = state.isRecording
            }
        })
    }

    fun toggleRecording() {
        if (dashboardEventStream != null) {
            dashboardEventStream.toggleRecording()
        } else {
            _isRecording.value = !_isRecording.value
        }
    }

    fun setRecording(recording: Boolean) {
        if (dashboardEventStream != null) {
            dashboardEventStream.setRecording(recording)
        } else {
            _isRecording.value = recording
        }
    }
}
