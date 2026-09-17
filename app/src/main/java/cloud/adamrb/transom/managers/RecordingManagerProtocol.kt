package cloud.adamrb.transom.managers

import cloud.adamrb.transom.models.RecordingState
import kotlinx.coroutines.flow.StateFlow

interface RecordingManagerProtocol {
    val state: StateFlow<RecordingState>
    val waveformLevel: StateFlow<Float>

    fun startRecord()
    fun stopRecord()
    fun pauseRecord()
    fun resumeRecord()
}
