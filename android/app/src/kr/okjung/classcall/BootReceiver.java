package kr.okjung.classcall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 칠판을 켜면(부팅) 자동으로 수신 대기를 시작한다. 교실에서 손댈 일이 없게 하는 핵심. */
public class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context ctx, Intent intent) {
        try {
            Intent i = new Intent(ctx, CallService.class);
            i.setAction(CallService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            Log.add("부팅 감지 → 수신 대기 시작");
        } catch (Throwable t) {
            Log.add("부팅 자동시작 실패: " + t);
        }
    }
}
