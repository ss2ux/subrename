package com.ss2ux.subrename

/**
 * 순수 로직: 파일 이름에서 숫자 키를 뽑고, 미디어·자막을 매칭해 이름 변경 제안을 만든다.
 * UI / Android API 와 무관.
 */

/** 미디어 확장자 (소문자 비교) */
val MEDIA_EXTS = setOf(
    "mp3", "m4a", "flac", "wav", "aac", "ogg", "opus", "wma",
    "mp4", "mkv", "avi", "webm", "mov", "m4v", "wmv", "flv", "ts", "m2ts"
)

/** 자막 확장자 (소문자 비교). 요청: VTT, SRT, LRC, TXT */
val SUB_EXTS = setOf("vtt", "srt", "lrc", "txt")

/**
 * 파일명(확장자 제외)에서 숫자 키를 추출한다.
 * - 연속된 숫자 묶음을 모두 찾아 밑줄로 이어 붙인다. (예: "TR7. 예시" → "7", "S01E12" → "01_12")
 * - 숫자가 하나도 없으면 null.
 * 이 키가 같으면 같은 트랙/에피소드로 본다.
 */
fun extractNumberKey(nameWithoutExt: String): String? {
    val matches = Regex("\\d+").findAll(nameWithoutExt).map { it.value }.toList()
    return if (matches.isEmpty()) null else matches.joinToString("_")
}

/** 확장자 없는 이름 + 확장자 */
data class FileInfo(
    val name: String,           // 전체 파일명 (예: "TR7. 예시1(일본어).MP3")
    val stem: String,           // 확장자 제외 (예: "TR7. 예시1(일본어)")
    val ext: String,            // 소문자 확장자 (매칭용, 예: "mp3")
    val originalExt: String,    // 원래 대소문자 유지 확장자 (예: "MP3" 또는 "vtt")
    val key: String?            // 숫자 키
) {
    val isMedia: Boolean get() = ext in MEDIA_EXTS
    val isSub: Boolean get() = ext in SUB_EXTS
}

fun parseFileName(fullName: String): FileInfo? {
    val lastDot = fullName.lastIndexOf('.')
    if (lastDot <= 0 || lastDot == fullName.length - 1) return null
    val stem = fullName.substring(0, lastDot)
    val originalExt = fullName.substring(lastDot + 1)
    val ext = originalExt.lowercase()
    if (stem.isBlank()) return null
    return FileInfo(fullName, stem, ext, originalExt, extractNumberKey(stem))
}

/**
 * 한 폴더 안의 파일 이름 목록을 받아 이름 변경 제안을 만든다.
 *
 * 규칙:
 * - 같은 숫자 키를 가진 미디어가 정확히 1개이고, 자막이 1개 이상이면
 *   각 자막을 (미디어 stem + "." + 자막 원래 확장자) 로 바꾸도록 제안.
 * - 이미 이름이 같은 경우(이미 맞음)는 제안하지 않는다.
 * - 키가 null인 파일은 무시.
 * - 같은 키에 미디어가 2개 이상이면 충돌로 표시 (제안 안 함).
 * - 대상 이름이 이미 존재하면 충돌로 표시.
 *
 * @return 제안 목록 + 충돌/스킵 메시지
 */
data class RenameProposal(
    val oldName: String,
    val newName: String,
    val key: String,
    val mediaStem: String
)

data class MatchResult(
    val proposals: List<RenameProposal>,
    val conflicts: List<String>,   // 사람이 읽을 수 있는 경고
    val skipped: List<String>      // 이미 맞는 것 등
)

fun buildProposals(fileNames: List<String>): MatchResult {
    val infos = fileNames.mapNotNull { parseFileName(it) }
    val mediaByKey = mutableMapOf<String, MutableList<FileInfo>>()
    val subByKey = mutableMapOf<String, MutableList<FileInfo>>()

    for (info in infos) {
        val k = info.key ?: continue
        when {
            info.isMedia -> mediaByKey.getOrPut(k) { mutableListOf() }.add(info)
            info.isSub -> subByKey.getOrPut(k) { mutableListOf() }.add(info)
        }
    }

    val proposals = mutableListOf<RenameProposal>()
    val conflicts = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val existingNames = fileNames.toSet()

    val allKeys = (mediaByKey.keys + subByKey.keys).toSet()
    for (key in allKeys.sorted()) {
        val medias = mediaByKey[key].orEmpty()
        val subs = subByKey[key].orEmpty()

        if (medias.isEmpty() || subs.isEmpty()) continue

        if (medias.size > 1) {
            conflicts.add("키 '$key': 미디어가 ${medias.size}개 → ${medias.joinToString { it.name }}")
            continue
        }

        val media = medias[0]
        for (sub in subs) {
            // 자막 확장자 원래 대소문자를 유지한다
            val desired = media.stem + "." + sub.originalExt
            if (desired.equals(sub.name, ignoreCase = true)) {
                skipped.add("${sub.name} (이미 미디어 이름과 일치)")
                continue
            }
            if (existingNames.any { it.equals(desired, ignoreCase = true) } &&
                !desired.equals(sub.name, ignoreCase = true)
            ) {
                conflicts.add("${sub.name} → $desired (대상 이름이 이미 존재)")
                continue
            }
            proposals.add(
                RenameProposal(
                    oldName = sub.name,
                    newName = desired,
                    key = key,
                    mediaStem = media.stem
                )
            )
        }
    }

    return MatchResult(
        proposals = proposals.sortedBy { it.oldName },
        conflicts = conflicts,
        skipped = skipped
    )
}
