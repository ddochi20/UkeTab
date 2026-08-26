# UkeTab — 오선지 악보 → 우쿨렐레 타브 (Android)

## 기능
- **MusicXML 파일** (.musicxml / .xml / .mxl) 열기 → 타브 변환 (오프라인)
- **악보 사진** (카메라·갤러리) → OMR 서버로 인식 → 타브 변환
- **MP3/WAV 음원** → 서버에서 멜로디 자동 채보(basic-pitch) → 타브 변환
- 튜닝 선택: High-G / Low-G / Baritone, 카포 0~7
- 음역 밖 음은 자동 옥타브 이동, 연주 불가 음은 경고 표시
- 타브를 텍스트로 공유/저장

## 빌드
1. Android Studio(Koala 이상)에서 이 폴더 열기
2. Gradle 동기화 후 실행 (minSdk 24)

## 사진 인식 서버 (선택)
사진 인식은 폰에서 직접 하기엔 무거워서 PC에서 Audiveris를 돌립니다.
```
cd server
pip install -r requirements.txt   # flask, basic-pitch, librosa (+ ffmpeg 권장)
set AUDIVERIS=C:\Program Files\Audiveris\Audiveris.exe   # 또는 export
python omr_server.py
```
앱의 "설정"에서 `http://<PC IP>:8000` 입력. 인쇄된 깨끗한 악보만 잘 인식되고,
손글씨 악보는 거의 안 됩니다. 인식 결과는 반드시 눈으로 확인하세요.

## 구조
- `MusicXmlParser.kt` — MusicXML/MXL 파싱 (첫 번째 파트만 사용)
- `TabConverter.kt` — 음 → 줄/프렛 배정 (낮은 프렛 & 손 위치 근접 우선), ASCII 렌더링
- `OmrClient.kt` — 사진 업로드
- `MainActivity.kt` — Compose UI

## MP3 채보에 대해
반주가 많은 곡에서는 멜로디가 아닌 음이 섞여 들어옵니다. 보컬만 있는 음원이나
단선율 연주 음원이 가장 잘 됩니다. 결과는 편집이 필요하다고 생각하고 쓰세요.
