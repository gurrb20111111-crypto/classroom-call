package kr.okjung.classcall;

import android.content.Context;
import android.content.SharedPreferences;

/** 설정 저장소. 교실마다 '우리 반'만 정해주면 나머지는 기본값으로 돌아간다. */
public class Prefs {
    public static final String FILE = "classcall";

    public static final String K_CLASS = "cls";        // "2-8"
    public static final String K_BASE = "base";        // ntfy base 채널
    public static final String K_SEC = "sec";          // 화면에 몇 초 띄울지
    public static final String K_SOUND = "sound";      // 호출음
    public static final String K_SPEAK = "speak";      // 음성으로 읽어주기
    public static final String K_USE_NTFY = "use_ntfy";// 인터넷 중계(ntfy) 사용
    public static final String K_USE_LAN = "use_lan";  // 학교망 직접 수신 사용
    public static final String K_PORT = "port";        // 직접 수신 포트
    public static final String K_KEY = "key";          // 직접 수신 암호(비우면 검사 안 함)
    public static final String K_ROOM = "room";        // 교실 이름(표시용, 예: 2층 과학실)
    public static final String K_REPLIES = "replies";  // 답장 버튼 문구들('|'로 구분)

    public static final String DEF_REPLIES = "네, 바로 보낼게요|수업 끝나고 보낼게요|학생이 지금 없어요|확인했습니다";

    public static final String DEF_BASE = "myschool-call-CHANGEME";
    public static final int DEF_PORT = 8787;
    public static final int DEF_SEC = 60;

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String cls(Context c) { return get(c).getString(K_CLASS, ""); }
    public static String base(Context c) { return normBase(get(c).getString(K_BASE, DEF_BASE)); }

    /**
     * base 칸에 반까지 붙여 넣는 실수(예: myschool-call-CHANGEME-2-3)를 자동으로 바로잡는다.
     * 그대로 두면 반 채널이 '...-2-3-2-3'이 되고 수신확인·답장이 엉뚱한 '...-2-3-reply'로 가서
     * 콘솔에 '송신 실패'로 뜬다(실제로 두 교실에서 겪음). 끝의 '-학년-반'만 떼어낸다.
     */
    public static String normBase(String b) {
        if (b == null) return DEF_BASE;
        b = b.trim().replaceAll("\\s+", "");
        b = b.replaceAll("-\\d-\\d{1,2}$", "");
        return b.length() == 0 ? DEF_BASE : b;
    }
    public static int sec(Context c) { return get(c).getInt(K_SEC, DEF_SEC); }
    public static boolean sound(Context c) { return get(c).getBoolean(K_SOUND, true); }
    public static boolean speak(Context c) { return get(c).getBoolean(K_SPEAK, false); }
    public static boolean useNtfy(Context c) { return get(c).getBoolean(K_USE_NTFY, true); }
    public static boolean useLan(Context c) { return get(c).getBoolean(K_USE_LAN, true); }
    public static int port(Context c) { return get(c).getInt(K_PORT, DEF_PORT); }
    public static String key(Context c) { return get(c).getString(K_KEY, ""); }
    public static String room(Context c) { return get(c).getString(K_ROOM, ""); }

    /** 답장 버튼 문구 목록(빈 칸 제외). 비워두면 답장 버튼을 안 보여준다. */
    public static String[] replies(Context c) {
        String raw = get(c).getString(K_REPLIES, DEF_REPLIES);
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String s : raw.split("\\|")) {
            if (s.trim().length() > 0) out.add(s.trim());
        }
        return out.toArray(new String[0]);
    }

    /** "2학년 8반", "2-8", "208", "2 8" → "2-8" (못 알아보면 "") */
    public static String normClass(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.matches("\\d{2,3}")) {                    // 208 = 2학년 8반
            return Integer.parseInt(s.substring(0, 1)) + "-" + Integer.parseInt(s.substring(1));
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\s*(?:학년)?\\s*[-_. ]?\\s*(\\d+)\\s*(?:반)?").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1)) + "-" + Integer.parseInt(m.group(2));
        return "";
    }

    /** "2-8" → "2학년 8반" */
    public static String label(String cls) {
        if (cls == null || cls.indexOf('-') < 0) return "";
        String[] p = cls.split("-");
        return p[0] + "학년 " + p[1] + "반";
    }
}
