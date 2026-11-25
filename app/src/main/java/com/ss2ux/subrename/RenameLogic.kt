package com.ss2ux.subrename

import java.text.Normalizer

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
 * - 같은 키에 미디어가 2개 이상(트랙명 동일)이거나, 한 미디어에 확장자가 같은 자막이 2개 이상이면
 *   번호만으로는 짝을 못 정하므로 "파일명-자막명 기준" 매칭 후보([AmbiguousGroup])로 따로 모은다.
 *   (실제 적용 여부는 사용자가 확인창에서 정한다)
 * - 대상 이름이 이미 존재하면 충돌로 표시.
 *
 * @return 제안 목록 + 충돌/스킵 메시지 + 파일명 기준 매칭 후보
 */
data class RenameProposal(
    val oldName: String,
    val newName: String,
    val key: String,
    val mediaStem: String,
    /** 파일명 기준 매칭일 때만: 이름 일치도 0~100. 번호 키로 확정된 제안은 null */
    val score: Int? = null
)

/** 파일명 기준 매칭 결과 */
data class NameMatch(
    val proposals: List<RenameProposal>,
    val conflicts: List<String>,
    val skipped: List<String>
)

/** 트랙명(키)이 같아서 번호만으로 짝을 정할 수 없는 그룹 + 파일명 기준으로 미리 계산한 매칭 */
data class AmbiguousGroup(
    val key: String,
    val mediaCount: Int,
    val subCount: Int,
    val warning: String,    // 사용자가 변환을 건너뛸 때 보여줄 경고
    val match: NameMatch
)

data class MatchResult(
    val proposals: List<RenameProposal>,
    val conflicts: List<String>,   // 사람이 읽을 수 있는 경고
    val skipped: List<String>,     // 이미 맞는 것 등
    val ambiguousGroups: List<AmbiguousGroup> = emptyList()
)

/** 파일명 기준 매칭에서 이 일치도(0~1) 미만이면 짝으로 인정하지 않는다 */
const val MIN_NAME_SCORE = 0.5

/**
 * 이름 비교용 정규화.
 * - 유니코드 NFC (맥/윈도우 한글 자모 분리 차이 제거), 소문자, 공백 정리
 * - 자막이면 끝에 붙은 미디어 확장자 제거: "곡1.wav" (곡1.wav.vtt 의 stem) → "곡1"
 */
internal fun normalizeForCompare(stem: String, isSub: Boolean): String {
    var s = Normalizer.normalize(stem, Normalizer.Form.NFC).lowercase().trim()
    if (isSub) {
        val dot = s.lastIndexOf('.')
        if (dot > 0 && s.substring(dot + 1) in MEDIA_EXTS) s = s.substring(0, dot)
    }
    return s.replace(Regex("\\s+"), " ")
}

private fun levenshtein(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        }
        val t = prev; prev = cur; cur = t
    }
    return prev[b.length]
}

/** 0.0 ~ 1.0 (1.0 = 정규화한 이름이 완전히 같음) */
internal fun nameSimilarity(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
}

private data class Candidate(
    val media: FileInfo,
    val sub: FileInfo,
    val score: Double,
    val alreadyNamed: Boolean
)

/**
 * 같은 키 그룹 안에서 파일명(미디어 확장자 뺀 이름)이 가장 비슷한 것끼리 미디어↔자막을 짝짓는다.
 *
 * - 일치도 높은 쌍부터 greedy 로 확정한다 (이미 이름이 맞는 자막이 동점이면 우선).
 * - 자막 1개는 미디어 1개에만 붙는다.
 * - 한 미디어에는 확장자가 다른 자막(.vtt, .srt ...)을 각각 1개씩 붙일 수 있다.
 * - 일치도가 [MIN_NAME_SCORE] 미만이면 짝으로 인정하지 않는다.
 */
fun matchByName(
    key: String,
    medias: List<FileInfo>,
    subs: List<FileInfo>,
    existingNames: Set<String>
): NameMatch {
    val candidates = mutableListOf<Candidate>()
    for (m in medias) {
        val mn = normalizeForCompare(m.stem, isSub = false)
        for (s in subs) {
            val score = nameSimilarity(mn, normalizeForCompare(s.stem, isSub = true))
            if (score < MIN_NAME_SCORE) continue
            val alreadyNamed = (m.stem + "." + s.originalExt).equals(s.name, ignoreCase = true)
            candidates.add(Candidate(m, s, score, alreadyNamed))
        }
    }
    candidates.sortWith(
        compareByDescending<Candidate> { it.score }
            .thenByDescending { it.alreadyNamed }
            .thenBy { it.media.name }
            .thenBy { it.sub.name }
    )

    val proposals = mutableListOf<RenameProposal>()
    val conflicts = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val assignedSubs = mutableSetOf<String>()
    val usedSlots = mutableSetOf<String>()   // "미디어 이름 + 자막 확장자" 한 칸에 자막 1개

    for (c in candidates) {
        if (c.sub.name in assignedSubs) continue
        val slot = c.media.name + "\u0000" + c.sub.ext
        if (slot in usedSlots) continue
        assignedSubs.add(c.sub.name)
        usedSlots.add(slot)

        val desired = c.media.stem + "." + c.sub.originalExt
        if (desired.equals(c.sub.name, ignoreCase = true)) {
            skipped.add("${c.sub.name} (이미 미디어 이름과 일치)")
            continue
        }
        if (existingNames.any { it.equals(desired, ignoreCase = true) }) {
            conflicts.add("${c.sub.name} → $desired (대상 이름이 이미 존재)")
            continue
        }
        proposals.add(
            RenameProposal(
                oldName = c.sub.name,
                newName = desired,
                key = key,
                mediaStem = c.media.stem,
                score = (c.score * 100).toInt()
            )
        )
    }

    for (s in subs) {
        if (s.name !in assignedSubs) {
            conflicts.add("${s.name} → 짝이 되는 미디어를 못 찾음 (키 '$key', 이름 일치도 부족 또는 이미 다른 자막이 배정됨)")
        }
    }
    return NameMatch(proposals, conflicts, skipped)
}

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
    val groups = mutableListOf<AmbiguousGroup>()
    val existingNames = fileNames.toSet()

    val allKeys = (mediaByKey.keys + subByKey.keys).toSet()
    for (key in allKeys.sorted()) {
        val medias = mediaByKey[key].orEmpty()
        val subs = subByKey[key].orEmpty()

        if (medias.isEmpty() || subs.isEmpty()) continue

        // 번호만으로 짝이 안 정해지는 경우:
        //  - 같은 키의 미디어가 2개 이상 (트랙명 동일)
        //  - 미디어 1개에 확장자가 같은 자막이 2개 이상 (같은 이름으로 바뀌어 충돌)
        val duplicateSubExt = subs.groupBy { it.ext }.any { it.value.size > 1 }
        if (medias.size > 1 || duplicateSubExt) {
            val match = matchByName(key, medias, subs, existingNames)
            if (match.proposals.isEmpty()) {
                // 새로 바꿀 게 없으면 묻지 않는다 (이미 다 맞춰진 상태 등)
                conflicts.addAll(match.conflicts)
                skipped.addAll(match.skipped)
            } else {
                groups.add(
                    AmbiguousGroup(
                        key = key,
                        mediaCount = medias.size,
                        subCount = subs.size,
                        warning = "키 '$key': 트랙명이 같은 파일 → 파일명 기준 변환 건너뜀 " +
                            "(미디어 ${medias.size}개: ${medias.joinToString { it.name }})",
                        match = match
                    )
                )
            }
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
        skipped = skipped,
        ambiguousGroups = groups
    )
}

/**
 * 기능2: 자막 파일명 끝의 미디어 확장자 조각을 제거한다.
 * 예) 곡.mp3.vtt → 곡.vtt ,  곡.wav.srt → 곡.srt ,  song.MP3.VTT → song.VTT
 *
 * 트랙/미디어 파일과 무관하게, 폴더 안 **모든 자막** 중
 * stem 이 `.mp3` / `.wav` 등 미디어 확장자로 끝나는 것만 대상으로 한다.
 */
fun buildStripMediaExtProposals(fileNames: List<String>): MatchResult {
    val existing = fileNames.toSet()
    val occupied = existing.map { it.lowercase() }.toMutableSet()
    val proposals = ArrayList<RenameProposal>()
    val conflicts = ArrayList<String>()
    val skipped = ArrayList<String>()

    for (name in fileNames.sorted()) {
        val info = parseFileName(name) ?: continue
        if (!info.isSub) continue

        // stem 끝이 .mp3 / .wav 등인지 (대소문자 무시)
        val stem = info.stem
        val dot = stem.lastIndexOf('.')
        if (dot <= 0) {
            skipped.add("$name (미디어 확장자 조각 없음)")
            continue
        }
        val maybeMedia = stem.substring(dot + 1).lowercase()
        if (maybeMedia !in MEDIA_EXTS) {
            skipped.add("$name (미디어 확장자 조각 없음)")
            continue
        }
        val cleanStem = stem.substring(0, dot)
        if (cleanStem.isBlank()) {
            conflicts.add("$name (이름을 비울 수 없음)")
            continue
        }
        val desired = cleanStem + "." + info.originalExt
        if (desired.equals(name, ignoreCase = true)) {
            skipped.add("$name (이미 정리됨)")
            continue
        }
        // 대상 이름이 이미 있거나, 이번 배치에서 이미 쓰기로 한 이름이면 충돌
        val desiredLower = desired.lowercase()
        val existsOnDisk = existing.any { it.equals(desired, ignoreCase = true) }
        if ((existsOnDisk || desiredLower in occupied) && !desired.equals(name, ignoreCase = true)) {
            conflicts.add("$name → $desired (대상 이름이 이미 존재)")
            continue
        }
        occupied.add(desiredLower)
        occupied.remove(name.lowercase())
        proposals.add(
            RenameProposal(
                oldName = name,
                newName = desired,
                key = "strip",
                mediaStem = cleanStem
            )
        )
    }

    return MatchResult(
        proposals = proposals.sortedBy { it.oldName },
        conflicts = conflicts,
        skipped = skipped,
        ambiguousGroups = emptyList()
    )
}
