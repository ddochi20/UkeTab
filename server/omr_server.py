"""
UkeTab 서버
  POST /omr   : 악보 사진 -> MusicXML  (Audiveris 필요)
  POST /audio : mp3/wav  -> 멜로디 채보 -> MusicXML  (basic-pitch + librosa)

설치:
  pip install flask basic-pitch librosa
  (mp3 디코딩용 ffmpeg 설치 권장)
  사진 인식을 쓰려면 Java 17 + Audiveris 설치 후 AUDIVERIS 환경변수 설정
실행:
  python omr_server.py
앱 설정에서 http://<이 PC의 IP>:8000 입력 (같은 Wi-Fi)
"""
import os, subprocess, tempfile, glob, shutil, zipfile
from flask import Flask, request, Response

AUDIVERIS = os.environ.get("AUDIVERIS", "audiveris")
app = Flask(__name__)

# ---------- 악보 사진 ----------
@app.post("/omr")
def omr():
    f = request.files.get("image")
    if not f:
        return "image 필드가 없습니다", 400
    work = tempfile.mkdtemp(prefix="omr_")
    try:
        img = os.path.join(work, "score.jpg"); f.save(img)
        out = os.path.join(work, "out")
        r = subprocess.run([AUDIVERIS, "-batch", "-export", "-output", out, "--", img],
                           capture_output=True, text=True, timeout=300)
        files = glob.glob(os.path.join(out, "**", "*.mxl"), recursive=True) + \
                glob.glob(os.path.join(out, "**", "*.xml"), recursive=True)
        if not files:
            return "인식 실패:\n" + r.stdout[-1500:] + r.stderr[-1500:], 500
        path = files[0]
        if path.endswith(".mxl"):
            with zipfile.ZipFile(path) as z:
                name = next(n for n in z.namelist() if n.endswith((".xml", ".musicxml")) and "META-INF" not in n)
                data = z.read(name)
        else:
            data = open(path, "rb").read()
        return Response(data, mimetype="application/vnd.recordare.musicxml+xml")
    finally:
        shutil.rmtree(work, ignore_errors=True)

# ---------- 음원 ----------
@app.post("/audio")
def audio():
    f = request.files.get("audio")
    if not f:
        return "audio 필드가 없습니다", 400
    work = tempfile.mkdtemp(prefix="aud_")
    try:
        ext = os.path.splitext(f.filename or "a.mp3")[1] or ".mp3"
        path = os.path.join(work, "in" + ext); f.save(path)
        xml = transcribe_to_musicxml(path, f.filename or "Audio")
        return Response(xml, mimetype="application/vnd.recordare.musicxml+xml")
    except Exception as e:
        return f"채보 실패: {e}", 500
    finally:
        shutil.rmtree(work, ignore_errors=True)


def transcribe_to_musicxml(path, title):
    import numpy as np, librosa
    from basic_pitch.inference import predict
    from basic_pitch import ICASSP_2022_MODEL_PATH

    # 1) 템포 추정
    y, sr = librosa.load(path, sr=22050, mono=True)
    tempo, _ = librosa.beat.beat_track(y=y, sr=sr)
    tempo = float(np.atleast_1d(tempo)[0]) or 120.0
    if tempo < 60: tempo *= 2
    if tempo > 180: tempo /= 2
    sec_per_16th = 60.0 / tempo / 4

    # 2) 음 검출
    _, _, events = predict(path, ICASSP_2022_MODEL_PATH,
                           onset_threshold=0.6, frame_threshold=0.4, minimum_note_length=80)
    # events: (start, end, pitch, amplitude, bends)
    notes = [(s, e, int(p)) for s, e, p, a, b in events if 48 <= p <= 88 and a > 0.25]
    if not notes:
        raise RuntimeError("음을 찾지 못했습니다")

    # 3) 16분음표 그리드로 양자화, 각 칸에서 가장 높은 음 = 멜로디
    total = int(np.ceil(max(e for _, e, _ in notes) / sec_per_16th)) + 1
    grid = [None] * total
    for s, e, p in notes:
        a, b = int(round(s / sec_per_16th)), max(int(round(s / sec_per_16th)) + 1, int(round(e / sec_per_16th)))
        for i in range(a, min(b, total)):
            if grid[i] is None or p > grid[i][0] or (grid[i][1] != s and p > grid[i][0]):
                grid[i] = (p, s)

    # 4) 연속된 같은 음(같은 onset) 합치기 -> (pitch or None, 길이 in 16분)
    seq = []
    for cell in grid:
        key = cell  # (pitch, onset) 같으면 같은 음
        if seq and seq[-1][0] == key:
            seq[-1][1] += 1
        else:
            seq.append([key, 1])
    # 앞쪽 쉼표 제거
    while seq and seq[0][0] is None: seq.pop(0)

    # 5) MusicXML 생성 (divisions=4 → 16분음표=1, 4/4)
    names = ["C", "C", "D", "D", "E", "F", "F", "G", "G", "A", "A", "B"]
    alters = [0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0]
    out = ['<?xml version="1.0" encoding="UTF-8"?>', '<score-partwise version="3.1">',
           f'<work><work-title>{esc(title)}</work-title></work>',
           '<part-list><score-part id="P1"><part-name>Melody</part-name></score-part></part-list>',
           '<part id="P1">']
    measure, pos = 1, 0
    out.append('<measure number="1"><attributes><divisions>4</divisions>'
               '<time><beats>4</beats><beat-type>4</beat-type></time>'
               '<clef><sign>G</sign><line>2</line></clef></attributes>'
               f'<direction><sound tempo="{tempo:.0f}"/></direction>')
    for key, length in seq:
        while length > 0:
            room = 16 - pos
            d = min(length, room)
            if key is None:
                out.append(f'<note><rest/><duration>{d}</duration></note>')
            else:
                p = key[0]
                alt = f'<alter>{alters[p % 12]}</alter>' if alters[p % 12] else ''
                out.append(f'<note><pitch><step>{names[p % 12]}</step>{alt}<octave>{p // 12 - 1}</octave></pitch>'
                           f'<duration>{d}</duration></note>')
            pos += d; length -= d
            if pos >= 16:
                measure += 1; pos = 0
                out.append(f'</measure><measure number="{measure}">')
    if pos < 16 and pos > 0:
        out.append(f'<note><rest/><duration>{16 - pos}</duration></note>')
    out += ['</measure>', '</part>', '</score-partwise>']
    return "\n".join(out)


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000)
