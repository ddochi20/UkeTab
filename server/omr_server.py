"""
악보 사진 -> MusicXML 변환 서버 (Audiveris 사용)

설치:
  1. Java 17+ 설치
  2. Audiveris 다운로드: https://github.com/Audiveris/audiveris/releases
  3. pip install flask
  4. AUDIVERIS 환경변수에 실행 파일 경로 지정 후:
     python omr_server.py
  앱 설정에서 http://<이 PC의 IP>:8000 입력 (같은 Wi-Fi여야 함)
"""
import os, subprocess, tempfile, glob, shutil
from flask import Flask, request, Response

AUDIVERIS = os.environ.get("AUDIVERIS", "audiveris")  # 예: C:\Program Files\Audiveris\Audiveris.exe
app = Flask(__name__)

@app.post("/omr")
def omr():
    f = request.files.get("image")
    if not f:
        return "image 필드가 없습니다", 400
    work = tempfile.mkdtemp(prefix="omr_")
    try:
        img = os.path.join(work, "score.jpg")
        f.save(img)
        out = os.path.join(work, "out")
        cmd = [AUDIVERIS, "-batch", "-export", "-output", out, "--", img]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        files = glob.glob(os.path.join(out, "**", "*.mxl"), recursive=True) + \
                glob.glob(os.path.join(out, "**", "*.xml"), recursive=True)
        if not files:
            return "인식 실패:\n" + r.stdout[-1500:] + r.stderr[-1500:], 500
        path = files[0]
        if path.endswith(".mxl"):
            import zipfile
            with zipfile.ZipFile(path) as z:
                name = next(n for n in z.namelist() if n.endswith((".xml", ".musicxml")) and "META-INF" not in n)
                data = z.read(name)
        else:
            data = open(path, "rb").read()
        return Response(data, mimetype="application/vnd.recordare.musicxml+xml")
    finally:
        shutil.rmtree(work, ignore_errors=True)

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000)
