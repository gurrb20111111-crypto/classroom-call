package kr.okjung.classcall;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 설치할 때 한 번만 보는 설정 화면.
 * 교실에서 할 일: '우리 반' 적기 → 권한 2개 허용 → [저장하고 수신 시작].  그 뒤로는 손댈 일 없음.
 */
public class MainActivity extends Activity {

    private EditText etClass, etRoom, etBase, etSec, etPort, etKey, etReplies;
    private CheckBox cbSound, cbSpeak, cbNtfy, cbLan;
    private TextView tvStatus, tvLog;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() {
            refreshStatus();
            h.postDelayed(this, 1500);
        }
    };

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        load();
    }

    protected void onResume() {
        super.onResume();
        h.post(tick);
    }

    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    // ---------------- 화면 ----------------
    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(48, 48, 48, 48);
        sv.addView(v);

        v.addView(title("교실 호출 수신기"));
        v.addView(hint("이 칠판이 어느 반인지만 정해주면 됩니다. 호출이 오면 수업 화면 위에 크게 뜹니다."));

        etClass = field(v, "우리 반 (예: 2-8 또는 2학년 8반)", InputType.TYPE_CLASS_TEXT);
        etRoom = field(v, "교실 이름 (선택, 예: 본관 3층 2-8 교실)", InputType.TYPE_CLASS_TEXT);

        v.addView(section("받는 방법"));
        cbLan = check(v, "학교망에서 교사 PC가 바로 보내기 (인터넷/외부서버 없이)", true);
        cbNtfy = check(v, "인터넷 중계(ntfy)로도 받기 — 학교망이 막혀도 도착", true);
        etBase = field(v, "중계 채널 이름(base) — 모든 교실 똑같이, 반은 붙이지 마세요", InputType.TYPE_CLASS_TEXT);
        etPort = field(v, "직접 수신 포트 (기본 8787)", InputType.TYPE_CLASS_NUMBER);
        etKey = field(v, "직접 수신 암호 (선택, 비우면 검사 안 함)", InputType.TYPE_CLASS_TEXT);

        v.addView(section("표시 방법"));
        etSec = field(v, "화면에 띄워 둘 시간(초)", InputType.TYPE_CLASS_NUMBER);
        cbSound = check(v, "호출음 울리기", true);
        cbSpeak = check(v, "음성으로 읽어주기 (칠판에 한국어 음성이 있을 때만)", false);
        etReplies = field(v, "답장 버튼 문구 ('|' 로 구분, 비우면 버튼 없음)", InputType.TYPE_CLASS_TEXT);

        v.addView(section("권한 — 이 두 개를 꼭 허용해야 제대로 뜹니다"));
        v.addView(button("① 다른 앱 위에 표시 허용 (안 되면 그냥 넘어가세요)", new View.OnClickListener() {
            public void onClick(View x) { askOverlay(); }
        }));
        v.addView(button("② 배터리 절전에서 제외", new View.OnClickListener() {
            public void onClick(View x) { askBattery(); }
        }));

        v.addView(section("실행"));
        v.addView(button("저장하고 수신 시작", new View.OnClickListener() {
            public void onClick(View x) { save(); start(); }
        }));
        v.addView(button("테스트로 한 번 띄워보기", new View.OnClickListener() {
            public void onClick(View x) {
                save();
                CallOverlay.deliver(MainActivity.this,
                        "지수야~ 교무실로 오세요\n— 테스트 로부터", "테스트", "test",
                        System.currentTimeMillis() / 1000L);
            }
        }));
        v.addView(button("수신 중지", new View.OnClickListener() {
            public void onClick(View x) { stop(); }
        }));

        v.addView(section("상태"));
        tvStatus = new TextView(this);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvStatus.setTextColor(Color.parseColor("#1B5E20"));
        v.addView(tvStatus);

        v.addView(section("기록"));
        tvLog = new TextView(this);
        tvLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvLog.setTextColor(Color.parseColor("#555555"));
        v.addView(tvLog);

        return sv;
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, 0, 0, 12);
        return t;
    }

    private TextView hint(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(Color.parseColor("#777777"));
        t.setPadding(0, 0, 0, 20);
        return t;
    }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, 36, 0, 8);
        return t;
    }

    private EditText field(LinearLayout parent, String label, int type) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(Color.parseColor("#666666"));
        t.setPadding(0, 16, 0, 2);
        parent.addView(t);
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        e.setSingleLine(true);
        parent.addView(e);
        return e;
    }

    private CheckBox check(LinearLayout parent, String label, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        c.setChecked(def);
        c.setPadding(0, 10, 0, 10);
        parent.addView(c);
        return c;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 12;
        b.setLayoutParams(lp);
        return b;
    }

    // ---------------- 설정 읽기/쓰기 ----------------
    private void load() {
        etClass.setText(Prefs.cls(this));
        etRoom.setText(Prefs.room(this));
        etBase.setText(Prefs.base(this));
        etSec.setText(String.valueOf(Prefs.sec(this)));
        etPort.setText(String.valueOf(Prefs.port(this)));
        etKey.setText(Prefs.key(this));
        etReplies.setText(Prefs.get(this).getString(Prefs.K_REPLIES, Prefs.DEF_REPLIES));
        cbSound.setChecked(Prefs.sound(this));
        cbSpeak.setChecked(Prefs.speak(this));
        cbNtfy.setChecked(Prefs.useNtfy(this));
        cbLan.setChecked(Prefs.useLan(this));
    }

    private void save() {
        String cls = Prefs.normClass(etClass.getText().toString());
        if (etClass.getText().toString().trim().length() > 0 && cls.length() == 0) {
            toast("'우리 반'을 알아볼 수 없습니다. 2-8 처럼 적어주세요.");
        }
        SharedPreferences.Editor e = Prefs.get(this).edit();
        e.putString(Prefs.K_CLASS, cls);
        e.putString(Prefs.K_ROOM, etRoom.getText().toString().trim());
        String rawBase = etBase.getText().toString().trim();
        String base = Prefs.normBase(rawBase);
        if (!base.equals(rawBase)) {
            toast("중계 채널 이름엔 반을 붙이지 않습니다 → '" + base + "' 로 고쳐 저장했습니다.\n"
                    + "(반은 '우리 반' 칸에만 적으면 앱이 알아서 붙입니다)");
        }
        e.putString(Prefs.K_BASE, base);
        etBase.setText(base);
        e.putInt(Prefs.K_SEC, num(etSec.getText().toString(), Prefs.DEF_SEC));
        e.putInt(Prefs.K_PORT, num(etPort.getText().toString(), Prefs.DEF_PORT));
        e.putString(Prefs.K_KEY, etKey.getText().toString().trim());
        e.putString(Prefs.K_REPLIES, etReplies.getText().toString().trim());
        e.putBoolean(Prefs.K_SOUND, cbSound.isChecked());
        e.putBoolean(Prefs.K_SPEAK, cbSpeak.isChecked());
        e.putBoolean(Prefs.K_USE_NTFY, cbNtfy.isChecked());
        e.putBoolean(Prefs.K_USE_LAN, cbLan.isChecked());
        e.apply();
        etClass.setText(cls);
    }

    private int num(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }

    // ---------------- 시작/중지/권한 ----------------
    private void start() {
        // 권한이 없어도 시작은 한다(전체화면 알림 방식으로 뜨므로). 안내만 한 번 해 준다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            toast("'다른 앱 위에 표시' 권한 없이 시작합니다 — 전체화면 알림 방식으로 뜹니다.");
        }
        Intent i = new Intent(this, CallService.class);
        i.setAction(CallService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        toast("수신 대기를 시작했습니다.");
    }

    private void stop() {
        Intent i = new Intent(this, CallService.class);
        i.setAction(CallService.ACTION_STOP);
        startService(i);
        toast("수신 대기를 중지했습니다.");
    }

    /**
     * '다른 앱 위에 표시' 설정 화면 열기.
     * 칠판 펌웨어에 따라 이 화면이 아예 없는 경우가 있어(ActivityNotFound) 여러 경로를 차례로 시도한다.
     * 다 없어도 괜찮다 — 그때는 앱이 '전체화면 알림' 방식으로 대신 띄운다(권한 불필요).
     */
    private void askOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast("이 안드로이드 버전은 따로 허용하지 않아도 됩니다.");
            return;
        }
        Intent[] tries = new Intent[] {
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS),
        };
        for (Intent i : tries) {
            try {
                startActivity(i);
                return;
            } catch (Throwable ignored) { }
        }
        toast("이 칠판에는 '다른 앱 위에 표시' 설정 화면이 없습니다.\n"
                + "괜찮습니다 — 전체화면 알림 방식으로 대신 뜹니다. [테스트로 한 번 띄워보기]로 확인해 보세요.");
        Log.add("오버레이 설정 화면 없음 → 전체화면 알림 방식 사용");
    }

    private void askBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast("이 안드로이드 버전은 따로 설정하지 않아도 됩니다.");
            return;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast("이미 절전 제외로 설정되어 있습니다.");
                return;
            }
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Throwable t2) { toast("설정 화면을 열 수 없습니다: " + t2); }
        }
    }

    // ---------------- 상태 표시 ----------------
    private void refreshStatus() {
        String cls = Prefs.cls(this);
        StringBuilder sb = new StringBuilder();
        sb.append(CallService.running ? "● 수신 대기 중\n" : "○ 중지됨\n");
        sb.append("반: ").append(cls.length() > 0 ? Prefs.label(cls) : "미지정").append('\n');
        sb.append("이 칠판 주소: ").append(CallService.myIp(this)).append(':').append(Prefs.port(this)).append('\n');
        if (cls.length() > 0 && Prefs.useNtfy(this)) {
            sb.append("중계 채널: ").append(Prefs.base(this)).append('-').append(cls).append('\n');
        }
        sb.append(CallService.statusText());
        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        sb.append(overlay ? "표시 방식: 화면 위 덮어쓰기(오버레이)"
                          : "표시 방식: 전체화면 알림 (권한 없이도 뜸 — ① 이 안 눌리면 그대로 두세요)");
        tvStatus.setText(sb.toString());
        tvLog.setText(Log.text());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
