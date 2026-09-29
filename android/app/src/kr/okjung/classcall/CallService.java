package kr.okjung.classcall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * 호출을 상시 대기하는 백그라운드 서비스.
 *
 * 받는 길 두 가지를 동시에 연다(둘 다 켜두면 한쪽이 막혀도 다른 쪽으로 들어온다):
 *   1) 학교망 직접(LAN)  — 교사 PC가 이 칠판에 바로 보냄. 인터넷/외부서버 필요 없음.
 *                          TCP {포트} 에서 POST 수신, UDP {포트} 로 '어느 반 칠판인지' 응답(자동 찾기).
 *   2) 인터넷 중계(ntfy) — 학교망이 막혀 있어도 인터넷만 되면 도착. 우리 반 채널(base-학년-반).
 */
public class CallService extends Service {

    public static final String ACTION_START = "kr.okjung.classcall.START";
    public static final String ACTION_STOP = "kr.okjung.classcall.STOP";
    private static final String CH_ID = "classcall";
    private static final int NOTI_ID = 1001;

    public static final String VERSION = "1.5";
    public static volatile boolean running = false;
    private static final Map<String, String> STATUS = new HashMap<String, String>();  // 이름 → 상태

    private volatile boolean alive = false;
    private ServerSocket lanServer;
    private DatagramSocket udpSocket;

    public IBinder onBind(Intent i) { return null; }

    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopEverything();
            stopSelf();
            return START_NOT_STICKY;
        }
        // 설정을 바꾸고 다시 [수신 시작]을 누르면 새 설정으로 갈아타야 하므로, 켜져 있어도 한 번 정리하고 시작한다.
        if (alive && intent != null && ACTION_START.equals(intent.getAction())) stopEverything();
        if (!alive) startEverything();
        return START_STICKY;      // 시스템이 죽여도 다시 살아나게
    }

    public void onDestroy() {
        stopEverything();
        super.onDestroy();
    }

    // ---------------- 시작/정지 ----------------
    private void startEverything() {
        alive = true;
        running = true;
        STATUS.clear();
        startForeground(NOTI_ID, buildNotification());

        String cls = Prefs.cls(this);
        String base = Prefs.base(this);

        // 우리 반 채널 하나만 듣는다(전체 방송은 없음 — 교무실에서 필요한 반을 각각 고른다)
        if (Prefs.useNtfy(this) && base.length() > 0) {
            // 우리 반 채널 + 연결확인(핑) 채널을 한 연결로 같이 듣는다(연결 수를 늘리지 않으려고).
            // 핑은 화면에 띄우지 않고 조용히 '살아 있음(pong)'만 돌려준다.
            if (cls.length() > 0) startNtfy(base + "-" + cls, Prefs.label(cls), base + "-ping");
            else setStatus("ntfy", "⚠ '우리 반'이 비어 있어 아무 호출도 받지 않습니다");
        }
        if (Prefs.useLan(this)) {
            startLanServer();
            startDiscovery();
        }
        Log.add("수신 대기 시작 — " + (cls.length() > 0 ? Prefs.label(cls) : "반 미지정") +
                " / 내 주소 " + myIp());
    }

    private void stopEverything() {
        alive = false;
        running = false;
        try { if (lanServer != null) lanServer.close(); } catch (Throwable ignored) { }
        try { if (udpSocket != null) udpSocket.close(); } catch (Throwable ignored) { }
        lanServer = null;
        udpSocket = null;
        Log.add("수신 대기 중지");
        try { stopForeground(true); } catch (Throwable ignored) { }
    }

    // ---------------- 알림(상주 표시) ----------------
    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "교실 호출 대기",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        String cls = Prefs.cls(this);
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_ID) : new Notification.Builder(this);
        b.setContentTitle("교실 호출 대기 중")
         .setContentText((cls.length() > 0 ? Prefs.label(cls) : "반 미지정") + " · " + myIp())
         .setSmallIcon(android.R.drawable.ic_dialog_info)
         .setOngoing(true)
         .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            b.setPriority(Notification.PRIORITY_MIN);
        }
        return b.build();
    }

    // ---------------- 1) 인터넷 중계(ntfy) ----------------
    private void startNtfy(final String topic, final String kind, final String pingTopic) {
        setStatus("ntfy:" + kind, "연결 중…");
        new Thread(new Runnable() {
            public void run() {
                int wait = 3;
                String lastId = null;              // 끊겼다 다시 붙으면 이 뒤부터 받아 그 사이 호출을 놓치지 않음
                while (alive) {
                    HttpURLConnection conn = null;
                    try {
                        String u = "https://ntfy.sh/" + URLEncoder.encode(topic, "UTF-8")
                                + "," + URLEncoder.encode(pingTopic, "UTF-8") + "/json";
                        if (lastId != null) u += "?since=" + URLEncoder.encode(lastId, "UTF-8");
                        URL url = new URL(u);
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestProperty("User-Agent", "classcall-board");
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(90000);       // ntfy가 45초마다 keepalive → 90초 무응답이면 재연결
                        InputStream in = conn.getInputStream();
                        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                        setStatus("ntfy:" + kind, "연결됨");
                        Log.add("ntfy 연결됨: " + topic + " (" + kind + ")");
                        wait = 3;
                        String line;
                        while (alive && (line = br.readLine()) != null) {
                            if (line.trim().length() == 0) continue;
                            try {
                                JSONObject o = new JSONObject(line);
                                if (!"message".equals(o.optString("event"))) continue;
                                String id = o.optString("id", "");
                                if (id.length() > 0) lastId = id;
                                if (!firstSeen(id)) continue;                 // 이미 띄운 호출(재접속 중복)
                                long sent = o.optLong("time", 0);             // ntfy가 붙여주는 보낸 시각(초)
                                long now = System.currentTimeMillis() / 1000L;
                                if (pingTopic.equals(o.optString("topic"))) { // ★ 연결확인: 화면엔 아무것도 안 띄움
                                    if (sent == 0 || now - sent <= PING_STALE_SEC) handlePing(o);
                                    continue;
                                }
                                if (sent > 0 && now - sent > STALE_SEC) {    // 너무 오래된 호출은 안 띄움
                                    Log.add("오래된 호출 건너뜀(" + (now - sent) + "초 전)");
                                    continue;
                                }
                                String msg = o.optString("message", "");
                                if (msg.length() > 0) {
                                    CallOverlay.deliver(CallService.this, msg, kind, cidFromTags(o), sent);
                                }
                            } catch (Throwable ignored) { }
                        }
                    } catch (Throwable t) {
                        setStatus("ntfy:" + kind, "끊김(재시도)");
                        Log.add("ntfy 끊김(" + kind + "): " + t);
                    } finally {
                        if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
                    }
                    sleep(wait * 1000L);
                    wait = Math.min(30, wait * 2);        // 계속 실패하면 재시도 간격을 늘림
                }
                setStatus("ntfy:" + kind, "중지");
            }
        }, "ntfy-" + topic).start();
    }

    // ---------------- 2) 학교망 직접 수신(LAN) ----------------
    private void startLanServer() {
        final int port = Prefs.port(this);
        new Thread(new Runnable() {
            public void run() {
                try {
                    lanServer = new ServerSocket(port);
                    setStatus("lan", "대기 " + myIp() + ":" + port);
                    Log.add("학교망 직접 수신 대기: " + myIp() + ":" + port);
                } catch (Throwable t) {
                    setStatus("lan", "포트 사용 실패: " + t);
                    Log.add("직접 수신 포트 열기 실패: " + t);
                    return;
                }
                while (alive) {
                    Socket s = null;
                    try {
                        s = lanServer.accept();
                        s.setSoTimeout(8000);
                        handleLan(s);
                    } catch (Throwable t) {
                        if (alive) Log.add("직접 수신 오류: " + t);
                    } finally {
                        if (s != null) try { s.close(); } catch (Throwable ignored) { }
                    }
                }
            }
        }, "lan-server").start();
    }

    /** 아주 작은 HTTP 처리: POST 본문 = 호출 문구, GET /info = 이 칠판 정보(교사 PC가 확인용). */
    private void handleLan(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        StringBuilder head = new StringBuilder();
        int prev = -1, cur;
        while (head.length() < 8192 && (cur = in.read()) != -1) {     // 헤더 끝(\r\n\r\n)까지
            head.append((char) cur);
            if (prev == '\n' && cur == '\n') break;
            if (cur == '\n' && head.toString().endsWith("\r\n\r\n")) break;
            prev = cur;
        }
        String headStr = head.toString();
        String first = headStr.split("\r?\n", 2)[0];
        boolean isPost = first.toUpperCase().startsWith("POST");

        int len = 0;
        String key = "";
        String cid = null;
        long sent = 0;
        for (String line : headStr.split("\r?\n")) {
            String low = line.toLowerCase();
            if (low.startsWith("content-length:")) {
                try { len = Integer.parseInt(line.split(":", 2)[1].trim()); } catch (Throwable ignored) { }
            } else if (low.startsWith("x-key:")) {
                key = line.split(":", 2)[1].trim();
            } else if (low.startsWith("x-call-id:")) {           // 콘솔이 붙인 호출 번호
                cid = line.split(":", 2)[1].trim();
            } else if (low.startsWith("x-sent:")) {              // 콘솔이 보낸 시각(초)
                try { sent = Long.parseLong(line.split(":", 2)[1].trim()); } catch (Throwable ignored) { }
            }
        }
        if (key.length() == 0 && first.contains("?k=")) {             // ?k=암호 형태도 허용
            String q = first.substring(first.indexOf("?k=") + 3);
            key = q.split("[ &]")[0].trim();
        }

        byte[] body = new byte[Math.max(0, Math.min(len, 8192))];
        int got = 0;
        while (got < body.length) {
            int r = in.read(body, got, body.length - got);
            if (r < 0) break;
            got += r;
        }
        String text = new String(body, 0, Math.max(0, got), "UTF-8").trim();

        String want = Prefs.key(this);
        OutputStream out = s.getOutputStream();
        if (want.length() > 0 && !want.equals(key)) {
            respond(out, 401, "{\"ok\":false,\"error\":\"key\"}");
            Log.add("직접 수신 거절(암호 불일치) " + s.getInetAddress());
            return;
        }
        if (isPost && text.length() > 0) {
            CallOverlay.deliver(this, text, Prefs.label(Prefs.cls(this)) + " · 학교망 직접", cid, sent);
            respond(out, 200, info(true));
        } else {
            respond(out, 200, info(true));                            // GET /info = 살아있는지 확인용
        }
    }

    private String info(boolean ok) {
        String cls = Prefs.cls(this);
        return "{\"ok\":" + ok + ",\"app\":\"classcall\",\"ver\":\"" + VERSION + "\",\"class\":\"" + cls + "\",\"label\":\""
                + Prefs.label(cls) + "\",\"room\":\"" + Prefs.room(this).replace("\"", "")
                + "\",\"ip\":\"" + myIp() + "\",\"mac\":\"" + myMac() + "\",\"port\":" + Prefs.port(this) + "}";
    }

    private void respond(OutputStream out, int code, String json) throws Exception {
        byte[] b = json.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " ERROR") + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    /** 교사 PC가 '이 학교망에 어떤 칠판들이 있나' 찾을 때 대답해 주는 역할(UDP 브로드캐스트 응답). */
    private void startDiscovery() {
        final int port = Prefs.port(this);
        new Thread(new Runnable() {
            public void run() {
                try {
                    udpSocket = new DatagramSocket(null);
                    udpSocket.setReuseAddress(true);
                    udpSocket.bind(new java.net.InetSocketAddress(port));
                } catch (Throwable t) {
                    Log.add("자동 찾기(UDP) 열기 실패: " + t);
                    return;
                }
                byte[] buf = new byte[512];
                while (alive) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        udpSocket.receive(p);
                        String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                        if (msg.startsWith("CLASSCALL?")) {
                            byte[] reply = ("CLASSCALL!" + info(true)).getBytes("UTF-8");
                            udpSocket.send(new DatagramPacket(reply, reply.length,
                                    p.getAddress(), p.getPort()));
                        }
                    } catch (Throwable t) {
                        if (alive) sleep(500);
                    }
                }
            }
        }, "lan-discovery").start();
    }

    // ---------------- 잡다 ----------------
    /**
     * 옆 칠판 전원 켜기 중계. 교무실 PC와 칠판은 네트워크 구간이 달라 Wake-on-LAN 신호가 안 닿는다.
     * 그래서 이미 켜져 있는 칠판이 '같은 구간'에서 대신 매직 패킷을 쏜다. (자기 자신은 건너뜀)
     */
    private void handleWake(JSONObject body) {
        org.json.JSONArray macs = body.optJSONArray("macs");
        if (macs == null) return;
        String mine = myMac();
        String ip = myIp();
        int n = 0;
        for (int i = 0; i < macs.length(); i++) {
            String mac = macs.optString(i, "").toUpperCase();
            if (mac.length() == 0 || mac.equals(mine)) continue;
            if (sendMagic(mac, ip)) n++;
        }
        if (n > 0) Log.add("옆 칠판 전원 켜기 신호 중계: " + n + "대");
    }

    /** 매직 패킷(FF×6 + MAC×16)을 이 칠판이 속한 구간에 방송. */
    private static boolean sendMagic(String mac, String myIp) {
        DatagramSocket ds = null;
        try {
            String hex = mac.replaceAll("[^0-9A-Fa-f]", "");
            if (hex.length() != 12) return false;
            byte[] m = new byte[6];
            for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            byte[] pkt = new byte[6 + 16 * 6];
            for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xff;
            for (int i = 0; i < 16; i++) System.arraycopy(m, 0, pkt, 6 + i * 6, 6);
            ds = new DatagramSocket();
            ds.setBroadcast(true);
            java.util.List<String> dst = new java.util.ArrayList<String>();
            dst.add("255.255.255.255");
            if (myIp != null && myIp.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                dst.add(myIp.substring(0, myIp.lastIndexOf('.')) + ".255");
            }
            for (int r = 0; r < 3; r++) {
                for (String d : dst) {
                    InetAddress a = InetAddress.getByName(d);
                    ds.send(new DatagramPacket(pkt, pkt.length, a, 9));
                    ds.send(new DatagramPacket(pkt, pkt.length, a, 7));
                }
                sleep(200);
            }
            return true;
        } catch (Throwable t) {
            Log.add("전원 켜기 신호 실패: " + t);
            return false;
        } finally {
            if (ds != null) try { ds.close(); } catch (Throwable ignored) { }
        }
    }

    /** 이 칠판의 유선(없으면 무선) MAC 주소. 못 읽으면 "". 콘솔이 전원 켜기용으로 자동 등록한다. */
    public static String myMac() {
        String any = "";
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface ni = en.nextElement();
                if (ni.isLoopback()) continue;
                byte[] hw = ni.getHardwareAddress();
                if (hw == null || hw.length != 6) continue;
                StringBuilder sb = new StringBuilder();
                boolean zero = true;
                for (int i = 0; i < 6; i++) {
                    if (hw[i] != 0) zero = false;
                    sb.append(i == 0 ? "" : ":").append(String.format("%02X", hw[i] & 0xff));
                }
                String mac = sb.toString();
                if (zero || mac.startsWith("02:00:00")) continue;          // 가짜(가림) 주소
                String name = ni.getName() == null ? "" : ni.getName();
                if (name.startsWith("eth")) return mac;                    // 유선이 전원 켜기에 쓰임
                if (any.length() == 0) any = mac;
            }
        } catch (Throwable ignored) { }
        return any;
    }

    /** 연결확인(핑)은 이보다 오래된 것엔 답하지 않는다(재접속 때 옛 핑에 답하면 '연결됨'으로 잘못 보임). */
    private static final long PING_STALE_SEC = 60;

    /** 콘솔의 [연결 확인] 요청에 '살아 있음'을 돌려준다. 화면·소리 없음.
     *  같은 채널로 오는 'wake' 요청은 꺼진 옆 칠판을 대신 깨우는 중계다. */
    private void handlePing(JSONObject o) {
        try {
            JSONObject body = new JSONObject(o.optString("message", "{}"));
            if ("wake".equals(body.optString("t"))) {
                handleWake(body);
                return;
            }
            String pid = body.optString("pid", "");
            if (pid.length() == 0) return;
            Reply.pong(this, pid, myIp(), Prefs.port(this), myMac());
            Log.add("연결확인 요청에 응답(" + pid + ")");
        } catch (Throwable t) {
            Log.add("연결확인 응답 실패: " + t);
        }
    }

    /** 이 시간보다 오래 전에 보낸 호출은 재접속으로 뒤늦게 받아도 띄우지 않는다(엉뚱한 때 뜨는 것 방지). */
    private static final long STALE_SEC = 180;
    private static final java.util.LinkedHashSet<String> SEEN = new java.util.LinkedHashSet<String>();

    /** 처음 보는 메시지면 true. 같은 호출이 두 번 뜨지 않게 최근 100개 번호를 기억한다. */
    private static synchronized boolean firstSeen(String id) {
        if (id == null || id.length() == 0) return true;
        if (SEEN.contains(id)) return false;
        SEEN.add(id);
        if (SEEN.size() > 100) {
            java.util.Iterator<String> it = SEEN.iterator();
            it.next();
            it.remove();
        }
        return true;
    }

    /** ntfy 태그 중 'cid-xxxx' → 콘솔이 붙인 호출 번호. */
    private static String cidFromTags(JSONObject o) {
        org.json.JSONArray tags = o.optJSONArray("tags");
        if (tags == null) return null;
        for (int i = 0; i < tags.length(); i++) {
            String t = tags.optString(i, "");
            if (t.startsWith("cid-")) return t.substring(4);
        }
        return null;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private static synchronized void setStatus(String k, String v) { STATUS.put(k, v); }

    public static synchronized String statusText() {
        if (STATUS.isEmpty()) return running ? "시작하는 중…" : "중지됨";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : STATUS.entrySet()) {
            sb.append("· ").append(e.getKey()).append(" — ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** 이 칠판의 학교망 주소(교사 PC에 알려줄 값). */
    public String myIp() { return myIp(this); }

    public static String myIp(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff),
                            (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                }
            }
        } catch (Throwable ignored) { }
        try {                                        // 유선랜(전자칠판은 보통 랜선)
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                 en.hasMoreElements(); ) {
                NetworkInterface ni = en.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (Enumeration<InetAddress> ad = ni.getInetAddresses(); ad.hasMoreElements(); ) {
                    InetAddress a = ad.nextElement();
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) { }
        return "주소없음";
    }
}
