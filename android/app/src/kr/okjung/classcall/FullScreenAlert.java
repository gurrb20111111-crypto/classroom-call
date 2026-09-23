package kr.okjung.classcall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

/**
 * '다른 앱 위에 표시' 권한이 없는 칠판을 위한 대비책.
 *
 * 두 가지를 같이 쏜다(둘 중 되는 쪽이 뜬다. 액티비티가 이미 떠 있으면 같은 창을 갱신하므로 두 번 뜨지 않는다):
 *   ① 전체화면 액티비티 직접 실행    — 안드로이드 9 이하에서는 이것만으로 잘 뜬다.
 *   ② 전체화면 알림(full-screen intent) — 안드로이드 10+ 에서 백그라운드 실행이 막힐 때 시스템이 대신 띄워준다.
 *      (전화 수신 화면이 뜨는 것과 같은 방식. 막히더라도 최소한 큰 알림 배너로는 뜬다.)
 */
public class FullScreenAlert {

    private static final String CH_ID = "classcall_call";
    private static final int NOTI_ID = 1002;

    public static void fire(Context ctx, String text, String kind, String cid, long sentAt) {
        Intent i = new Intent(ctx, CallActivity.class);
        i.putExtra(CallActivity.EX_TEXT, text);
        i.putExtra(CallActivity.EX_KIND, kind);
        i.putExtra(CallActivity.EX_CID, cid);
        i.putExtra(CallActivity.EX_SENT, sentAt);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

        boolean started = false;
        try {
            ctx.startActivity(i);
            started = true;
        } catch (Throwable t) {
            Log.add("전체화면 직접 실행 막힘: " + t);
        }

        try {
            notifyFullScreen(ctx, i, text, kind, started);
        } catch (Throwable t) {
            Log.add("전체화면 알림 실패: " + t);
        }
    }

    private static void notifyFullScreen(Context ctx, Intent target, String text, String kind,
                                         boolean alreadyStarted) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = nm.getNotificationChannel(CH_ID);
            if (ch == null) {
                ch = new NotificationChannel(CH_ID, "교실 호출", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("학생 호출이 왔을 때 화면에 크게 띄웁니다");
                ch.enableVibration(true);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                if (!Prefs.sound(ctx)) {
                    ch.setSound(null, null);
                } else {
                    ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                            new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build());
                }
                nm.createNotificationChannel(ch);
            }
        }

        int flag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(ctx, 2, target, flag);

        String[] parts = (text == null ? "" : text).split("\n");
        String main = parts.length > 0 ? parts[0].trim() : "";
        String sub = parts.length > 1 ? parts[1].trim() : (kind == null ? "" : kind);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CH_ID) : new Notification.Builder(ctx);
        b.setContentTitle(main)
         .setContentText(sub)
         .setSmallIcon(android.R.drawable.ic_dialog_info)
         .setAutoCancel(true)
         .setContentIntent(pi)
         .setFullScreenIntent(pi, true);                 // ★ 전화 수신처럼 화면을 통째로 띄우는 알림
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            b.setPriority(Notification.PRIORITY_MAX);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setCategory(Notification.CATEGORY_ALARM);
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        nm.notify(NOTI_ID, b.build());
        Log.add("권한 없이 표시: 전체화면 " + (alreadyStarted ? "실행" : "알림") + " 사용");
    }
}
