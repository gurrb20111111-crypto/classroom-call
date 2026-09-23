package kr.okjung.classcall;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 호출 화면 한 장(오버레이와 전체화면 액티비티가 똑같이 이걸 쓴다).
 *
 *   ┌───────────────────────────────────────┐
 *   │ 3학년 6반                              │  ← 어느 채널로 왔는지
 *   │        지수야~ 교무실로 오세요           │  ← 호출(크게)
 *   │            — 권혁규T 로부터              │  ← 보낸 사람
 *   │  [네, 바로 보낼게요] [수업 끝나고…] …     │  ← 답장 버튼(누르면 콘솔로 감)
 *   │           보낸 시각 11:42               │  ← 선생님이 보낸 시각
 *   └───────────────────────────────────────┘
 * 버튼 말고 빈 곳을 누르면 그냥 닫힌다.
 */
public class CallView {

    public interface Host { void close(); }

    private final Context ctx;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    public final FrameLayout root;
    private final TextView tvMain, tvSub, tvKind, tvTime, tvDone;
    private final LinearLayout btnRow;
    private final List<Button> buttons = new ArrayList<Button>();
    private String cid;

    public CallView(Context ctx, Host h) {
        this.ctx = ctx;
        this.host = h;

        root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        tvMain = new TextView(ctx);
        tvMain.setTextColor(Color.WHITE);
        tvMain.setGravity(Gravity.CENTER);
        tvMain.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(tvMain);

        tvSub = new TextView(ctx);
        tvSub.setTextColor(Color.parseColor("#FFD24A"));
        tvSub.setGravity(Gravity.CENTER);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        tvSub.setPadding(0, 40, 0, 0);
        box.addView(tvSub);

        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        boxLp.gravity = Gravity.CENTER;
        boxLp.leftMargin = 60; boxLp.rightMargin = 60; boxLp.bottomMargin = 120;
        root.addView(box, boxLp);

        tvKind = small(ctx, 20);
        FrameLayout.LayoutParams kindLp = wrap(Gravity.TOP | Gravity.START);
        kindLp.leftMargin = 40; kindLp.topMargin = 30;
        root.addView(tvKind, kindLp);

        // 아래쪽: 답장 버튼 줄 → 답장 완료 문구 → 보낸 시각
        LinearLayout bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);

        btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        bottom.addView(btnRow);

        tvDone = new TextView(ctx);
        tvDone.setTextColor(Color.parseColor("#7CD992"));
        tvDone.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        tvDone.setGravity(Gravity.CENTER);
        tvDone.setVisibility(View.GONE);
        bottom.addView(tvDone);

        tvTime = small(ctx, 22);
        tvTime.setGravity(Gravity.CENTER);
        tvTime.setPadding(0, 24, 0, 0);
        bottom.addView(tvTime);

        FrameLayout.LayoutParams botLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        botLp.gravity = Gravity.BOTTOM;
        botLp.bottomMargin = 36;
        root.addView(bottom, botLp);

        root.setOnClickListener(new View.OnClickListener() {   // 빈 곳 누르면 닫기
            public void onClick(View v) { host.close(); }
        });
        root.setFocusable(true);
    }

    /** 새 호출 내용으로 화면을 채운다. sentAt = 보낸 시각(초, 모르면 0). */
    public void bind(String text, String kind, String cid, long sentAt) {
        this.cid = cid;
        String[] parts = (text == null ? "" : text).split("\n");
        String main = parts.length > 0 ? parts[0].trim() : "";
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < parts.length; i++) sb.append(i > 1 ? " " : "").append(parts[i].trim());
        String sub = sb.toString().trim();

        tvMain.setText(main);
        tvMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, CallOverlay.fontFor(main.length()));
        tvSub.setText(sub);
        tvSub.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        tvKind.setText(kind == null ? "" : kind);
        tvTime.setText(timeLine(sentAt));
        tvDone.setVisibility(View.GONE);
        buildButtons();
    }

    /** "보낸 시각 11:42"  — 늦게 도착했으면(1분 이상) 받은 시각도 같이. */
    private static String timeLine(long sentAt) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.KOREA);
        long now = System.currentTimeMillis() / 1000L;
        if (sentAt <= 0) return "받은 시각 " + f.format(new Date());
        String s = "보낸 시각 " + f.format(new Date(sentAt * 1000L));
        long late = now - sentAt;
        if (late >= 60 && late < 86400) s += "   ·   받은 시각 " + f.format(new Date());
        return s;
    }

    private void buildButtons() {
        btnRow.removeAllViews();
        buttons.clear();
        String[] opts = Prefs.replies(ctx);
        // 호출 번호가 없으면(옛 전송기에서 온 호출) 돌려보낼 곳이 없으니 버튼을 숨긴다
        if (cid == null || cid.length() == 0 || opts.length == 0) {
            btnRow.setVisibility(View.GONE);
            return;
        }
        btnRow.setVisibility(View.VISIBLE);
        for (final String opt : opts) {
            Button b = new Button(ctx);
            b.setText(opt);
            b.setAllCaps(false);
            b.setTextColor(Color.WHITE);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            b.setPadding(44, 22, 44, 22);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#2B3440"));
            bg.setStroke(3, Color.parseColor("#4A90E2"));
            bg.setCornerRadius(28);
            b.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = 14; lp.rightMargin = 14;
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { onReply(opt); }
            });
            btnRow.addView(b);
            buttons.add(b);
        }
    }

    private void onReply(String opt) {
        for (Button b : buttons) b.setEnabled(false);          // 두 번 눌림 방지
        Reply.reply(ctx, cid, opt);
        btnRow.setVisibility(View.GONE);
        tvDone.setText("‘" + opt + "’ 답장을 보냈습니다 ✓");
        tvDone.setVisibility(View.VISIBLE);
        ui.postDelayed(new Runnable() { public void run() { host.close(); } }, 1800);
    }

    private static TextView small(Context ctx, int sp) {
        TextView t = new TextView(ctx);
        t.setTextColor(Color.parseColor("#8A8A8A"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private static FrameLayout.LayoutParams wrap(int gravity) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravity;
        return lp;
    }
}
