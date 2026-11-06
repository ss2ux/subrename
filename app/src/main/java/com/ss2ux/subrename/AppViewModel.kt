package com.ss2ux.subrename

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiProposal(
    val oldName: String,
    val newName: String,
    val key: String,
    val selected: Boolean = true
)

class AppViewModel(private val app: Application) : AndroidViewModel(app) {

    var treeUri by mutableStateOf<Uri?>(null)
        private set
    var folderName by mutableStateOf<String?>(null)
        private set

    var proposals by mutableStateOf<List<UiProposal>>(emptyList())
        private set
    var conflicts by mutableStateOf<List<String>>(emptyList())
        private set
    var skipped by mutableStateOf<List<String>>(emptyList())
        private set

    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    /** 마지막 스캔 시 본 파일 이름들 (다시 스캔용) */
    private var lastFileNames: List<String> = emptyList()

    fun clearMessage() { message = null }

    fun takeFolder(uri: Uri) {
        // 영구 권한
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            app.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // 일부 기기/상황에서 persist 실패해도 당장은 동작할 수 있음
        }
        treeUri = uri
        val doc = DocumentFile.fromTreeUri(app, uri)
        folderName = doc?.name ?: uri.lastPathSegment
        scanFolder()
    }

    fun clearFolder() {
        treeUri = null
        folderName = null
        proposals = emptyList()
        conflicts = emptyList()
        skipped = emptyList()
        lastFileNames = emptyList()
        message = null
    }

    fun toggleSelected(oldName: String) {
        proposals = proposals.map {
            if (it.oldName == oldName) it.copy(selected = !it.selected) else it
        }
    }

    fun selectAll(selected: Boolean) {
        proposals = proposals.map { it.copy(selected = selected) }
    }

    fun scanFolder() {
        val uri = treeUri ?: return
        busy = true
        message = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val tree = DocumentFile.fromTreeUri(app, uri)
                    ?: return@withContext null
                val names = tree.listFiles()
                    .filter { it.isFile && it.name != null }
                    .mapNotNull { it.name }
                lastFileNames = names
                buildProposals(names) to names.size
            }
            busy = false
            if (result == null) {
                message = "폴더를 열 수 없습니다."
                return@launch
            }
            val (match, total) = result
            proposals = match.proposals.map {
                UiProposal(it.oldName, it.newName, it.key, selected = true)
            }
            conflicts = match.conflicts
            skipped = match.skipped
            message = when {
                match.proposals.isEmpty() && match.conflicts.isEmpty() ->
                    "매칭할 항목이 없습니다. (미디어·자막 ${total}개 스캔)"
                else ->
                    "스캔 완료: 제안 ${match.proposals.size}개 / 충돌 ${match.conflicts.size}개 / 이미 일치 ${match.skipped.size}개 (전체 ${total}개)"
            }
        }
    }

    fun applySelected() {
        val uri = treeUri ?: return
        val toApply = proposals.filter { it.selected }
        if (toApply.isEmpty()) {
            message = "선택된 항목이 없습니다."
            return
        }
        busy = true
        message = null
        viewModelScope.launch {
            val (ok, fail) = withContext(Dispatchers.IO) {
                val tree = DocumentFile.fromTreeUri(app, uri)
                    ?: return@withContext 0 to toApply.size
                var success = 0
                var failure = 0
                for (p in toApply) {
                    val file = tree.findFile(p.oldName)
                    if (file == null || !file.exists()) {
                        failure++
                        continue
                    }
                    // 대상이 이미 있으면 실패로 처리 (로직에서 걸렀지만 race 가능)
                    if (tree.findFile(p.newName) != null && !p.newName.equals(p.oldName, ignoreCase = true)) {
                        failure++
                        continue
                    }
                    val renamed = try {
                        file.renameTo(p.newName)
                    } catch (_: Exception) {
                        false
                    }
                    if (renamed) success++ else failure++
                }
                success to failure
            }
            busy = false
            message = "이름 변경 완료: 성공 $ok / 실패 $fail"
            // 다시 스캔해서 목록 갱신
            scanFolder()
        }
    }
}
