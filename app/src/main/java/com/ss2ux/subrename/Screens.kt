package com.ss2ux.subrename

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: AppViewModel = viewModel()) {
    val folderLauncher = rememberLauncherForActivityResult(OpenDocumentTreeRw()) { uri ->
        if (uri != null) vm.takeFolder(uri)
    }

    // 트랙명이 같은 파일이 있을 때: 파일명-자막명 기준으로 변환할지 확인
    if (vm.pendingGroups.isNotEmpty()) {
        val groups = vm.pendingGroups
        val total = groups.sumOf { it.match.proposals.size }
        AlertDialog(
            onDismissRequest = { vm.declineNameMatch() },
            title = { Text("트랙명이 같은 파일이 있어요") },
            text = {
                Text(
                    "트랙명(번호)이 같은 파일이 ${groups.size}그룹 있어 번호만으로는 짝을 정할 수 없습니다.\n\n" +
                        "파일명-자막명 기준으로 변환할까요?\n" +
                        "미디어 확장자(.wav 등)를 뺀 이름이 가장 비슷한 것끼리 맞추고, 자막 ${total}개를 바꿀 예정입니다.\n\n" +
                        "예) 곡1.wav + 곡1.wav.vtt → 곡1.vtt"
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.acceptNameMatch() }) { Text("변환") }
            },
            dismissButton = {
                TextButton(onClick = { vm.declineNameMatch() }) { Text("건너뛰기") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("자막명 맞추기") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    if (vm.treeUri != null) {
                        IconButton(onClick = { vm.scanFolder() }, enabled = !vm.busy) {
                            Icon(Icons.Default.Refresh, contentDescription = "다시 스캔")
                        }
                        IconButton(onClick = { vm.clearFolder() }) {
                            Icon(Icons.Default.Close, contentDescription = "폴더 닫기")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // 폴더 선택 / 현재 폴더
            if (vm.treeUri == null) {
                Button(
                    onClick = { folderLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("폴더 선택")
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "미디어 파일과 자막 파일이 같이 있는 폴더를 고르세요.\n" +
                        "같은 숫자가 들어 있는 이름을 찾아 자막 이름을 미디어 이름에 맞춥니다.\n\n" +
                        "예)\n" +
                        "  TR7. 예시1(일본어).MP3\n" +
                        "  TR7. 예시2(중국어).VTT\n" +
                        "→ TR7. 예시1(일본어).VTT\n\n" +
                        "지원 자막: VTT, SRT, LRC, TXT\n" +
                        "완전 오프라인. 인터넷 권한 없음.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "폴더: ${vm.folderName ?: "?"}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { folderLauncher.launch(null) },
                        modifier = Modifier.weight(1f),
                        enabled = !vm.busy
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("다른 폴더")
                    }
                    Button(
                        onClick = { vm.applySelected() },
                        modifier = Modifier.weight(1f),
                        enabled = !vm.busy && vm.proposals.any { it.selected }
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("선택한 항목 적용")
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 전체 선택 / 해제
                if (vm.proposals.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.selectAll(true) }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "전체 선택")
                        }
                        Text("전체 선택", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(12.dp))
                        IconButton(onClick = { vm.selectAll(false) }) {
                            Icon(Icons.Default.Close, contentDescription = "전체 해제")
                        }
                        Text("전체 해제", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // 메시지
            vm.message?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (vm.busy) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            // 충돌 경고
            if (vm.conflicts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "충돌 / 경고",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                vm.conflicts.forEach { c ->
                    Text(
                        "• $c",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }
            }

            // 제안 목록
            if (vm.proposals.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "이름 변경 제안 (${vm.proposals.count { it.selected }}/${vm.proposals.size})",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(vm.proposals, key = { it.oldName }) { p ->
                        ProposalCard(p, onToggle = { vm.toggleSelected(p.oldName) })
                    }
                }
            } else if (vm.treeUri != null && !vm.busy && vm.message != null) {
                // 이미 위에서 메시지 표시
            }
        }
    }
}

@Composable
private fun ProposalCard(p: UiProposal, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (p.selected)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = p.selected, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    p.oldName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "→ ${p.newName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "키: ${p.key}" + (p.score?.let { "  ·  파일명 일치도 $it%" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
