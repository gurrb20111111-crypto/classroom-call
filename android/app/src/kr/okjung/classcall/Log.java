package kr.okjung.classcall;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 화면에서 바로 확인할 수 있는 간단한 기록(최근 60줄). 문제 생겼을 때 이것만 보면 된다. */
public class Log {
    private static final List<String> LINES = new ArrayList<String>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA);

    public static synchronized void add(String msg) {
        LINES.add(0, "[" + FMT.format(new Date()) + "] " + msg);
        while (LINES.size() > 60) LINES.remove(LINES.size() - 1);
        android.util.Log.i("classcall", msg);
    }

    public static synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String s : LINES) sb.append(s).append('\n');
        return sb.toString();
    }
}
