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
 * 트랙 번호가 아닌 숫자(포맷/품질 표기)를 제거하기 위한 패턴.
 * 예: MP3, MP4, M4A, 320kbps, 44.1kHz, 24bit, 1080p, x264, 5.1ch
 */
private val NOISE_REGEX = Regex(
    "(?i)(?<![A-Za-z0-9])(?:mp3|mp4|m4a|m4b|m4v|m2ts|ac3|eac3|dts|h\\.?26[45]|x26[45]|" +
        "\\d{3,4}p|\\d{2,3}\\s?kbps|\\d+(?:\\.\\d+)?\\s?khz|\\d+\\s?bit|\\d\\.\\d\\s?ch)(?![A-Za-z0-9])"
)

/** 1순위: TRACK / TR / T / 트랙 / トラック 같은 접두어 뒤의 번호. 예: TR7, Track01, t3, 트랙 5 */
private val PREFIXED_REGEX = Regex(
    "(?i)(?<![A-Za-z])(?:track|tr|t|트랙|トラック)[\\s._\\-]{0,2}(\\d{1,3})([A-Za-z])?(?![A-Za-z0-9])"
)

/** 2순위: 이름 맨 앞의 번호. 예: 01_제목, 1-제목, 01A 제목, [01] 제목 */
private val LEADING_REGEX = Regex(
    "^[\\s._\\-\\[(\\u3010\\u300C]*(\\d{1,3})([A-Za-z])?(?![A-Za-z0-9])"
)

/** 3순위: 이름 중간에 구분자로 둘러싸인 번호. 예: 앨범명 - 05 - 제목 */
private val STANDALONE_REGEX = Regex(
    "(?<![A-Za-z0-9])(\\d{1,3})([A-Za-z])?(?![A-Za-z0-9])"
)

private fun buildKey(digits: String, suffix: String?): String {
    val num = digits.trimStart('0').ifEmpty { "0" }
    return num + (suffix?.lowercase() ?: "")
}

/**
 * 파일명(확장자 제외)에서 트랙 키를 추출한다.
 *
 * 순서:
 * 0. MP3, 320kbps 같은 포맷/품질 표기는 먼저 지운다. (MP3 의 3 이 트랙으로 읽히는 것 방지)
 * 1. TR / Track / T / 트랙 + 숫자
 * 2. 이름 맨 앞의 숫자 (01_, 1-, 01A, 1a ...)
 * 3. 이름 중간에 구분자로 분리된 숫자
 * 4. 위가 모두 실패하면 기존 방식: 모든 숫자 묶음을 '_' 로 연결 (S01E12 → 1_12)
 *
 * 앞자리 0 은 제거하고(01 == 1), 뒤에 붙은 한 글자 알파벳(A/a)은 소문자로 키에 포함한다. (01A → "1a")
 * 숫자가 하나도 없으면 null.
 */
fun extractNumberKey(nameWithoutExt: String): String? {
    val cleaned = NOISE_REGEX.replace(nameWithoutExt, " ")

    for (regex in listOf(PREFIXED_REGEX, LEADING_REGEX, STANDALONE_REGEX)) {
        val m = regex.find(cleaned) ?: continue
        return buildKey(m.groupValues[1], m.groupValues[2].ifEmpty { null })
    }

    val all = Regex("\\d+").findAll(cleaned).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()
    return if (all.isEmpty()) null else all.joinToString("_")
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
