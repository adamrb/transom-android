package org.plaudbridge.app.managers

import org.plaudbridge.app.models.RecordingState
import kotlinx.coroutines.flow.StateFlow

interface RecordingManagerProtocol {
    val state: StateFlow<RecordingState>
    val waveformLevel: StateFlow<Float>

    fun startRecord()
    fun stopRecord()
    fun pauseRecord()
    fun resumeRecord()
}
