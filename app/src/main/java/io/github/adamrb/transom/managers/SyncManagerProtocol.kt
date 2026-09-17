package io.github.adamrb.transom.managers

import io.github.adamrb.transom.models.RecordingFile
import io.github.adamrb.transom.models.SyncState
import kotlinx.coroutines.flow.StateFlow
import java.io.File

interface SyncManagerProtocol {
    val state: StateFlow<SyncState>
    val files: StateFlow<List<RecordingFile>>

    fun fetchFileList()
    fun startSync()
    fun startWiFiTransfer()
    fun stopSync()
    /** Delete the phone's index entry and audio, and keep the session from re-syncing. */
    fun deleteFile(file: RecordingFile)
    /** Drop the audio only; the entry stays (flagged) so the session is not downloaded again. */
    fun removeFromPhone(file: RecordingFile)
    fun renameFile(file: RecordingFile, newName: String)
    fun exportAudio(file: RecordingFile, callback: (Result<File>) -> Unit)
}
