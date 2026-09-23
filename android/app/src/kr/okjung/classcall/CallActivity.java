package kr.okjung.classcall;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

/**
 * 호출을 전체화면으로 보여주는 화면.
 *
 * '다른 앱 위에 표시' 권한이 없는 칠판(설정 화면조차 없는 펌웨어)에서는 오버레이를 못 쓰므로,
 * 이 액티비티를 대신 띄운다. 화면 모양은 오버레이와 같은 CallView(큰 글씨 + 답장 버튼 + 보낸 시각).
 */
public class CallActivity extends Activity implements CallView.Host {

    public static final String EX_TEXT = "text";
    public static final String EX_KIND = "kind";
    public static final String EX_CID = "cid";
    public static final String EX_SENT = "sent";

    private CallView view;
    private static String lastKey;
    private static long lastAt;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable closeTask;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 잠금/절전 상태여도 화면을 켜고 위로 올라오게
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        view = new CallView(this, this);
        setContentView(view.root);
        render(getIntent());
    }

    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        render(intent);
    }

    public void close() { finish(); }

    private void render(Intent intent) {
        if (intent == null) return;
        String text = intent.getStringExtra(EX_TEXT);
        String kind = intent.getStringExtra(EX_KIND);
        String cid = intent.getStringExtra(EX_CID);
        long sent = intent.getLongExtra(EX_SENT, 0);

        // 직접 실행 + 전체화면 알림이 둘 다 성공하면 같은 호출이 두 번 들어온다 → 두 번째는 무시(소리 두 번 방지)
        String key = cid + "|" + text;
        long nowMs = System.currentTimeMillis();
        if (key.equals(lastKey) && nowMs - lastAt < 5000) return;
        lastKey = key;
        lastAt = nowMs;

        view.bind(text, kind, cid, sent);

        CallOverlay.wakeScreen(this);
        String main = (text == null ? "" : text).split("\n")[0].trim();
        if (Prefs.sound(this)) CallOverlay.beep();
        if (Prefs.speak(this)) CallOverlay.speak(this, main);

        if (closeTask != null) h.removeCallbacks(closeTask);
        closeTask = new Runnable() { public void run() { finish(); } };
        h.postDelayed(closeTask, Math.max(5, Prefs.sec(this)) * 1000L);
        Log.add("전체화면 표시[" + (kind == null ? "-" : kind) + "] " + main);
    }

    protected void onDestroy() {
        if (closeTask != null) h.removeCallbacks(closeTask);
        super.onDestroy();
    }
}
