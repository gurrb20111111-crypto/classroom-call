package kr.okjung.classcall;

import android.content.Context;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 교무실 콘솔로 돌려보내는 신호. 채널 = <base>-reply  (콘솔이 이 채널을 듣고 있다)
 *   ack   : 호출이 이 칠판 화면에 떴다(수신확인) — 자동으로 보냄
 *   reply : 교실에서 누른 답장 버튼 문구
 * 콘솔은 cid(호출 번호)+반으로 어느 호출에 대한 것인지 맞춘다.
 * ※ 인터넷(ntfy)으로 돌려보내므로 교사 PC 방화벽에 막히지 않는다.
 */
public class Reply {

    public static void ack(Context ctx, String cid) {
        send(ctx, "ack", cid, null);
    }

    public static void reply(Context ctx, String cid, String text) {
        send(ctx, "reply", cid, text);
    }

    private static void send(final Context ctx, final String type, final String cid, final String text) {
        if (cid == null || cid.length() == 0) return;          // 번호 없는 호출(옛 콘솔/전송기)은 돌려줄 곳이 없음
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection c = null;
                try {
                    String base = Prefs.base(app);
                    if (base.length() == 0) return;
                    JSONObject o = new JSONObject();
                    o.put("t", type);
                    o.put("cid", cid);
                    o.put("cls", Prefs.cls(app));
                    o.put("via", "app");
                    if (text != null) o.put("reply", text);
                    byte[] body = o.toString().getBytes("UTF-8");

                    URL url = new URL("https://ntfy.sh/" + URLEncoder.encode(base + "-reply", "UTF-8"));
                    c = (HttpURLConnection) url.openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    OutputStream os = c.getOutputStream();
                    os.write(body);
                    os.close();
                    int code = c.getResponseCode();
                    Log.add(("ack".equals(type) ? "수신확인" : "답장 '" + text + "'") + " 보냄 → " + code);
                } catch (Throwable t) {
                    Log.add(("ack".equals(type) ? "수신확인" : "답장") + " 보내기 실패: " + t);
                } finally {
                    if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
                }
            }
        }, "reply").start();
    }
}
