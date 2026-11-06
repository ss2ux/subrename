# 자막명 맞추기 (subrename)

미디어 파일과 자막 파일의 이름이 숫자만 같고 나머지는 다를 때, 자막 이름을 미디어 이름에 맞춰 바꾸는 오프라인 안드로이드 앱.

인터넷 권한이 없다. SAF(폴더 선택)로만 동작한다.

## 동작

1. 미디어·자막이 같이 있는 폴더를 고른다.
2. 파일명에서 **연속된 숫자 묶음**을 뽑아 키로 쓴다.  
   예: `TR7. 예시1(일본어).MP3` → 키 `7`  
   예: `S01E12 - title.mkv` → 키 `01_12`
3. 같은 키를 가진 **미디어가 정확히 1개**이고 자막이 있으면, 자막을  
   `(미디어 stem).(자막 확장자)` 로 바꾸도록 제안한다.
4. 제안 목록에서 원하는 것만 선택해 적용한다.

### 예시

```
TR7. 예시1(일본어).MP3
TR7. 예시2(중국어).VTT
```

→ `TR7. 예시2(중국어).VTT` 를 `TR7. 예시1(일본어).VTT` 로 변경

### 지원 확장자

- 자막: `.vtt` `.srt` `.lrc` `.txt`
- 미디어: mp3, m4a, flac, wav, aac, ogg, opus, wma, mp4, mkv, avi, webm, mov, m4v 등 흔한 오디오/비디오

### 충돌 처리

- 같은 키에 미디어가 2개 이상 → 제안하지 않고 경고
- 바꿀 이름이 이미 존재 → 제안하지 않고 경고
- 이미 미디어 이름과 같은 자막 → 스킵

## 스택

- Kotlin 1.9.24, Jetpack Compose, AGP 8.5.2
- minSdk 29 / targetSdk 34
- 패키지: `com.ss2ux.subrename`

## 소스 구조

| 파일 | 역할 |
|------|------|
| `RenameLogic.kt` | 숫자 키 추출, 매칭, 제안 생성 (순수 로직) |
| `AppViewModel.kt` | 폴더 URI, 스캔, 이름 변경 실행 |
| `Screens.kt` | 메인 UI |
| `MainActivity.kt` | 진입점, 폴더 선택기 |

## 빌드

GitHub Actions (`main` 푸시 또는 수동)이 `app-debug.apk` 를 만든다.  
로컬: `gradle assembleDebug`
