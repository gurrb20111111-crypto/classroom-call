package kr.okjung.classcall;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.WindowManager;

import java.util.Locale;

/**
 * 호출을 화면에 띄우는 곳.
 *   ① '다른 앱 위에 표시' 권한이 있으면 → 오버레이(수업 화면 위에 바로 덮음)
 *   ② 권한이 없거나 그 설정 화면조차 없는 칠판 → 전체화면 액티비티 + 전체화면 알림
 * 어느 쪽이든 화면 모양은 CallView 하나로 같다. 화면에 띄우는 순간 콘솔로 '받았음(ack)'을 돌려보낸다.
 */
public class CallOverlay {

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static CallView view;
    private static Runnable hideTask;
    private static TextToSpeech tts;

    /** 옛 방식 호출(번호·보낸시각 없음) — 테스트 등에서 사용. */
    public static void deliver(Context ctx, String text, String kind) {
        deliver(ctx, text, kind, null, 0);
    }

    /**
     * @param cid    콘솔이 붙여 보낸 호출 번호(없으면 null → 답장 버튼·수신확인 없음)
     * @param sentAt 보낸 시각(유닉스 초, 모르면 0)
     */
    public static void deliver(Context ctx, String text, String kind, String cid, long sentAt) {
        Context app = ctx.getApplicationContext();
        boolean canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || android.provider.Settings.canDrawOverlays(app);
        if (canOverlay) {
            show(app, text, kind, cid, sentAt);
        } else {
            FullScreenAlert.fire(app, text, kind, cid, sentAt);
        }
        if (cid != null && !"test".equals(cid)) Reply.ack(app, cid);   // ★ 수신확인 → 콘솔 '송신 실패' 판정용
    }

    private static void show(final Context ctx, final String text, final String kind,
                             final String cid, final long sentAt) {
        UI.post(new Runnable() {
            public void run() {
                try {
                    showInner(ctx, text, kind, cid, sentAt);
                } catch (Throwable t) {
                    Log.add("표시 실패(오버레이) → 전체화면으로 전환: " + t);
                    FullScreenAlert.fire(ctx, text, kind, cid, sentAt);
                }
            }
        });
    }

    @SuppressLint("InlinedApi")
    private static void showInner(final Context ctx, String text, String kind, String cid, long sentAt) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (view == null) {
            view = new CallView(ctx, new CallView.Host() {
                public void close() { hide(ctx); }
            });
        }
        view.bind(text, kind, cid, sentAt);

        if (view.root.getParent() == null) {
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            wm.addView(view.root, lp);
        }

        wakeScreen(ctx);
        String main = (text == null ? "" : text).split("\n")[0].trim();
        if (Prefs.sound(ctx)) beep();
        if (Prefs.speak(ctx)) speak(ctx, main);

        if (hideTask != null) UI.removeCallbacks(hideTask);
        hideTask = new Runnable() { public void run() { hide(ctx); } };
        UI.postDelayed(hideTask, Math.max(5, Prefs.sec(ctx)) * 1000L);
        Log.add("표시[" + (kind == null ? "-" : kind) + "] " + main);
    }

    public static void hide(final Context ctx) {
        UI.post(new Runnable() {
            public void run() {
                if (view != null && view.root.getParent() != null) {
                    try {
                        WindowManager wm = (WindowManager) ctx.getApplicationContext()
                                .getSystemService(Context.WINDOW_SERVICE);
                        wm.removeView(view.root);
                    } catch (Throwable ignored) { }
                }
            }
        });
    }

    /** 글자 수에 따라 크게/작게 — 짧은 호출일수록 화면 가득 차게. */
    public static int fontFor(int n) {
        if (n <= 8) return 110;
        if (n <= 16) return 84;
        if (n <= 28) return 60;
        if (n <= 45) return 44;
        return 32;
    }

    static void wakeScreen(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE, "classcall:show");
            wl.acquire(15000);
        } catch (Throwable ignored) { }
    }

    public static void beep() {
        new Thread(new Runnable() {
            public void run() {
                ToneGenerator tg = null;
                try {
                    tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 600);
                    Thread.sleep(800);
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 600);
                    Thread.sleep(800);
                } catch (Throwable ignored) {
                } finally {
                    if (tg != null) try { tg.release(); } catch (Throwable ignored) { }
                }
            }
        }).start();
    }

    /** 음성으로 읽어주기(옵션). 칠판에 한국어 TTS가 없으면 조용히 넘어간다. */
    public static void speak(Context ctx, final String text) {
        try {
            if (tts == null) {
                tts = new TextToSpeech(ctx.getApplicationContext(), new TextToSpeech.OnInitListener() {
                    public void onInit(int status) {
                        if (status == TextToSpeech.SUCCESS) {
                            try { tts.setLanguage(Locale.KOREAN); } catch (Throwable ignored) { }
                            sayNow(text);
                        }
                    }
                });
            } else {
                sayNow(text);
            }
        } catch (Throwable ignored) { }
    }

    @SuppressWarnings("deprecation")
    private static void sayNow(String text) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "call");
            } else {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
        } catch (Throwable ignored) { }
    }
}
