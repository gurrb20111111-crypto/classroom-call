# -*- coding: utf-8 -*-
"""
교실 호출 수신기 (윈도우 상주형)
============================================================
교실 컴퓨터(전자칠판에 연결된 윈도우 PC)에서 백그라운드로 조용히 대기하다가,
교무실에서 '교실 호출'을 보내면 그 순간에만 화면을 전체화면으로 크게 덮어
메시지를 보여주고(소리+큰 글씨), 몇 초 뒤 자동으로 사라져 원래 수업 화면으로 돌아갑니다.

· 통로: ntfy.sh (무료 알림중계) — 표시기.html/전송기.html/통합콘솔과 '같은 토픽'이면 다 통함.
· 평소엔 화면을 전혀 차지하지 않음(창 없음). 호출 올 때만 잠깐 뜸.
· 시작프로그램에 넣어두면 컴퓨터 켤 때 자동으로 대기 시작.

[실행]  교실수신기_실행.bat  (파이썬 설치된 PC)  또는  빌드한 교실수신기.exe
[종료]  교실수신기_종료.bat   (또는 작업관리자에서 종료)

[채널]  ★ 반별 채널 ★
  · 기본 이름(base) = 모든 교실 공통.            예) myschool-call-CHANGEME
  · 반별 채널      = base-학년-반              예) myschool-call-CHANGEME-2-8  (2학년 8반)
  이 수신기는 '우리 반 채널' 하나만 듣습니다. (전체 방송은 없음 — 교무실에서 필요한 반을 각각 고름)
  → 교무실에서 2학년 8반을 고르면 그 교실에만 뜹니다.

  설치할 교실마다 exe 옆에 아래 파일 하나만 만들어 두세요(메모장으로 만들면 됨):
    · '반.txt'   ← 그 교실의 학년-반.  예)  2-8   (또는 '2학년 8반' 이라고 적어도 됨)
    · '토픽.txt' ← (선택) base 채널을 바꿀 때만.  파일이 없으면 아래 TOPIC 사용.
  ※ '반.txt' 가 없으면 아무 호출도 받지 않습니다(시작할 때 경고가 뜸).
"""
import os, sys, re, json, time, threading, urllib.request, urllib.parse, ssl
import tkinter as tk

try:
    import winsound   # 윈도우 기본 제공(소리)
except Exception:
    winsound = None

# ================= 설정 =================
TOPIC = "myschool-call-CHANGEME"       # 기본 이름(base). 실제 채널은 base-학년-반. 콘솔과 똑같아야 함
CLASS = ""                           # 이 교실의 학년-반. 예) "2-8"  (보통은 '반.txt' 로 지정)
CLEAR_SEC = 60                       # 호출이 몇 초 뒤 자동으로 사라질지
ACCENT = "#ffd24a"                   # 보내는 사람 줄 색(노랑)
# =======================================

APP_DIR = os.path.dirname(os.path.abspath(sys.argv[0]))
LOG_PATH = os.path.join(APP_DIR, "교실수신기_log.txt")


def _read_side_file(name):
    """exe/py 옆에 있는 설정 파일 한 줄 읽기(없으면 None). 메모장 UTF-8/ANSI 둘 다 대응."""
    p = os.path.join(APP_DIR, name)
    if not os.path.exists(p):
        return None
    for enc in ("utf-8-sig", "cp949"):
        try:
            t = open(p, "r", encoding=enc).read().strip()
            if t:
                return t.splitlines()[0].strip()
        except Exception:
            continue
    return None


def norm_class(s):
    """'2-8' '2학년 8반' '2 8' '2_8' '208' → '2-8'. 못 알아보면 ''."""
    s = str(s or "").strip()
    if re.match(r"^\d{2,3}$", s):                 # 208 = 2학년 8반, 21 = 2학년 1반
        return "{}-{}".format(int(s[0]), int(s[1:]))
    m = re.search(r"(\d+)\s*(?:학년)?\s*[-_. ]?\s*(\d+)\s*(?:반)?", s)
    return "{}-{}".format(int(m.group(1)), int(m.group(2))) if m else ""


def class_label(cls):
    g, c = cls.split("-")
    return "{}학년 {}반".format(g, c)


def _load_channels():
    """이 교실이 들을 채널 목록을 정한다.

    반환: (base, cls, [(topic, label)])  — 반별 채널 base-학년-반 하나만 듣는다(전체 방송 없음).
    '반.txt' 가 없으면 들을 채널이 없다 → 시작 알림에 경고를 띄운다.
    """
    base = (_read_side_file("토픽.txt") or TOPIC).strip()
    base = re.sub(r"-\d-\d{1,2}$", "", base)       # base 에 반까지 붙여 넣은 실수는 자동으로 떼어냄
    cls = norm_class(_read_side_file("반.txt") or CLASS)
    chans = []
    if cls:
        chans.append(("{}-{}".format(base, cls), class_label(cls)))
    return base, cls, chans


def log(msg):
    line = time.strftime("[%m-%d %H:%M:%S] ") + str(msg)
    try:
        with open(LOG_PATH, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass


# ---------------- 소리(호출 알림음) ----------------
def chime():
    if not winsound:
        return
    try:
        for f in (880, 1175):        # 딩-동 두 음
            winsound.Beep(f, 220)
    except Exception:
        try:
            winsound.MessageBeep(-1)
        except Exception:
            pass


class Receiver:
    def __init__(self, root):
        self.root = root
        self.base, self.cls, self.channels = _load_channels()
        self.topic = self.channels[0][0] if self.channels else "(반 미지정)"   # 대표 채널(로그용)
        self.pop = None            # 현재 떠 있는 전체화면 창
        self.hide_after = None     # 자동 숨김 예약 id
        self.ssl_verify = True     # SSL 검증 실패하면 False로 낮춤(학교망 SSL 가로채기 대응)

    # ---------- 전체화면 팝업 ----------
    def _font_pt(self, n):
        h = self.root.winfo_screenheight() or 1080
        if n <= 8:    return max(48, h // 7)
        if n <= 16:   return max(40, h // 10)
        if n <= 28:   return max(32, h // 14)
        if n <= 45:   return max(26, h // 18)
        return max(20, h // 24)

    def show(self, text, kind="", sent=0):
        """호출 메시지를 전체화면으로 표시. 첫 줄=메시지(크게), 나머지=보내는 사람(작게).
        kind = 어느 채널로 온 호출인지('2학년 8반') → 화면 왼쪽 위에 작게."""
        parts = str(text).split("\n")
        main = parts[0].strip()
        sub = " ".join(parts[1:]).strip()
        sw = self.root.winfo_screenwidth()
        sh = self.root.winfo_screenheight()

        if self.pop is None or not self.pop.winfo_exists():
            self.pop = tk.Toplevel(self.root)
            self.pop.overrideredirect(True)                 # 테두리 없음
            self.pop.configure(bg="black")
            self.pop.geometry("%dx%d+0+0" % (sw, sh))
            self.pop.attributes("-topmost", True)
            try:
                self.pop.attributes("-fullscreen", True)
            except Exception:
                pass
            wrap = int(sw * 0.92)
            self.lbl_main = tk.Label(self.pop, bg="black", fg="white",
                                     wraplength=wrap, justify="center",
                                     font=("Malgun Gothic", 40, "bold"))
            self.lbl_main.place(relx=0.5, rely=0.46, anchor="center")
            self.lbl_sub = tk.Label(self.pop, bg="black", fg=ACCENT,
                                    wraplength=wrap, justify="center",
                                    font=("Malgun Gothic", 20, "bold"))
            self.lbl_sub.place(relx=0.5, rely=0.66, anchor="center")
            self.lbl_time = tk.Label(self.pop, bg="black", fg="#8a8a8a",
                                     font=("Malgun Gothic", 14))
            self.lbl_time.place(relx=0.5, rely=0.93, anchor="center")
            self.lbl_kind = tk.Label(self.pop, bg="black", fg="#8a8a8a",
                                     font=("Malgun Gothic", 13, "bold"))
            self.lbl_kind.place(x=24, y=18, anchor="nw")
            # 아무 데나 클릭/Esc 로 즉시 닫기
            for w in (self.pop, self.lbl_main, self.lbl_sub, self.lbl_kind):
                w.bind("<Button-1>", lambda e: self.hide())
            self.pop.bind("<Escape>", lambda e: self.hide())

        self.lbl_main.config(text=main, font=("Malgun Gothic", self._font_pt(len(main)), "bold"))
        self.lbl_sub.config(text=sub, font=("Malgun Gothic", max(16, self._font_pt(len(main)) // 3), "bold"))
        if sent:                                        # 보낸 시각(1분 이상 늦었으면 받은 시각도)
            late = time.time() - sent
            t = "보낸 시각 " + time.strftime("%H:%M", time.localtime(sent))
            if 60 <= late < 86400:
                t += "   ·   받은 시각 " + time.strftime("%H:%M")
        else:
            t = "받은 시각 " + time.strftime("%H:%M")
        self.lbl_time.config(text=t)
        self.lbl_kind.config(text=kind or "")

        self.pop.deiconify()
        self.pop.lift()
        self.pop.attributes("-topmost", True)
        try:
            self.pop.focus_force()
        except Exception:
            pass

        threading.Thread(target=chime, daemon=True).start()

        if self.hide_after:
            try: self.root.after_cancel(self.hide_after)
            except Exception: pass
        self.hide_after = self.root.after(max(5, CLEAR_SEC) * 1000, self.hide)
        log("표시[{}]: {}{}".format(kind or "-", main, " / " + sub if sub else ""))

    def hide(self):
        if self.hide_after:
            try: self.root.after_cancel(self.hide_after)
            except Exception: pass
            self.hide_after = None
        if self.pop and self.pop.winfo_exists():
            self.pop.withdraw()

    # ---------- 시작 알림(3초 작은 토스트) ----------
    def startup_toast(self):
        sw = self.root.winfo_screenwidth()
        t = tk.Toplevel(self.root)
        t.overrideredirect(True)
        t.configure(bg="#1a1f26")
        t.attributes("-topmost", True)
        where = class_label(self.cls) if self.cls else "⚠ 반 미지정 — 반.txt 가 없어 아무 호출도 못 받음"
        tk.Label(t, bg="#1a1f26", fg="#e9edf2", padx=18, pady=12,
                 font=("Malgun Gothic", 12, "bold"),
                 text="교실 호출 수신 대기 시작 ✓  [%s]  채널 %d개" % (where, len(self.channels))).pack()
        t.update_idletasks()
        w = t.winfo_reqwidth(); h = t.winfo_reqheight()
        t.geometry("%dx%d+%d+%d" % (w, h, sw - w - 24, 24))
        self.root.after(3500, t.destroy)

    # ---------- 수신확인: 콘솔이 '송신 실패'를 판정할 수 있게 '받았음'을 돌려보냄 ----------
    def ack(self, cid):
        try:
            body = json.dumps({"t": "ack", "cid": cid, "cls": self.cls, "via": "pc"}).encode("utf-8")
            url = "https://ntfy.sh/" + urllib.parse.quote(self.base + "-reply", safe="")
            req = urllib.request.Request(url, data=body, method="POST",
                                         headers={"User-Agent": "okjung-call-receiver"})
            ctx = self._ctx()
            urllib.request.urlopen(req, timeout=8, context=ctx) if ctx else urllib.request.urlopen(req, timeout=8)
        except Exception as e:
            log("수신확인 보내기 실패: %s" % e)

    # ---------- ntfy 수신(백그라운드 스레드) ----------
    def _ctx(self):
        if not self.ssl_verify:
            return ssl._create_unverified_context()
        try:
            import truststore
            truststore.inject_into_ssl()
        except Exception:
            pass
        try:
            return ssl.create_default_context()
        except Exception:
            return None

    def listen_loop(self, topic=None, kind=""):
        """채널 하나를 계속 듣는다(끊기면 자동 재연결). 채널마다 스레드 하나씩 돌린다."""
        topic = topic or self.topic
        while True:
            url = "https://ntfy.sh/" + urllib.parse.quote(topic, safe="") + "/json"
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "okjung-call-receiver"})
                ctx = self._ctx()
                resp = urllib.request.urlopen(req, timeout=60, context=ctx) if ctx else urllib.request.urlopen(req, timeout=60)
                log("연결됨: {} ({})".format(topic, kind or "-"))
                for raw in resp:                 # 한 줄에 JSON 하나(스트리밍)
                    try:
                        line = raw.decode("utf-8").strip()
                    except Exception:
                        continue
                    if not line:
                        continue
                    try:
                        d = json.loads(line)
                    except Exception:
                        continue
                    if d.get("event") == "message" and d.get("message"):
                        msg = d["message"]
                        sent = d.get("time") or 0              # ntfy가 붙여준 보낸 시각(초)
                        self.root.after(0, lambda m=msg, k=kind, t=sent: self.show(m, k, t))  # UI는 메인스레드에서
                        cid = next((str(t)[4:] for t in (d.get("tags") or []) if str(t).startswith("cid-")), "")
                        if cid:
                            threading.Thread(target=self.ack, args=(cid,), daemon=True).start()
            except ssl.SSLError as e:
                if self.ssl_verify:
                    self.ssl_verify = False       # 검증 실패 → 이후엔 검증 없이 재시도
                    log("SSL 검증 실패 → 비검증으로 전환: %s" % e)
                    continue
                log("SSL 오류: %s" % e)
            except Exception as e:
                log("연결 끊김/오류(재시도): %s" % e)
            time.sleep(3)                          # 끊기면 3초 뒤 자동 재연결


def main():
    root = tk.Tk()
    root.withdraw()                                # 메인 창은 숨김(백그라운드 상주)
    try:
        root.title("교실 호출 수신기")
    except Exception:
        pass
    rec = Receiver(root)
    try:   # 종료 배치가 찾을 수 있게 PID 기록
        open(os.path.join(APP_DIR, "교실수신기.pid"), "w").write(str(os.getpid()))
    except Exception:
        pass
    log("=== 수신기 시작 (반: %s / 채널: %s) ===" % (
        rec.cls or "미지정", ", ".join(t for t, _ in rec.channels)))
    rec.startup_toast()
    for topic, kind in rec.channels:        # 우리 반 채널 듣기
        threading.Thread(target=rec.listen_loop, args=(topic, kind), daemon=True).start()
    root.mainloop()


if __name__ == "__main__":
    main()
