package org.plaudbridge.app.managers

import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.models.SyncState
import kotlinx.coroutines.flow.StateFlow
import java.io.File

interface SyncManagerProtocol {
    val state: StateFlow<SyncState>
    val files: StateFlow<List<RecordingFile>>

    fun fetchFileList()
    fun startSync()
    fun startWiFiTransfer()
    fun stopSync()
    fun deleteFile(file: RecordingFile)
    fun renameFile(file: RecordingFile, newName: String)
    fun exportAudio(file: RecordingFile, callback: (Result<File>) -> Unit)
}
