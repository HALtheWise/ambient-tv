package bot.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.nayuki.qrcodegen.QrCode;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ambient home screen: a full-screen Game of Life rotating through famous patterns, a clock,
 * status chips (Cast, AirPlay, Wi-Fi, screen-off timer) and, on Down, a shelf to swap the
 * background for another ambient scene (or shuffle them) and reach the few apps that matter. Focus is a small state machine instead of view focus,
 * so the D-pad is deterministic.
 */
public class Home extends Activity {

    private static final int AMBIENT = 0, CHIPS = 1, SCENES = 2, APPS = 3, MENU = 4, QR = 5;
    private static final int CHIP_CAST = 0, CHIP_TIMER = 1, CHIP_SETTINGS = 2, CHIPS_N = 3;
    private static final int WRAP = FrameLayout.LayoutParams.WRAP_CONTENT;
    /** Home backgrounds, in shelf order; "shuffle" rotates through all of them. */
    private static final String[] BACKGROUNDS = {"life", "fire", "fractal", "rd", "ant"};
    private static final String SHUFFLE = "shuffle";
    private static final long SHUFFLE_MS = 8 * 60 * 1000;
    private static final int ACCENT = 0xFFF0A458;
    private static final long IDLE_MS = 5 * 60 * 1000;

    private static final int INK = 0xFFF3EFE8, INK2 = 0xFFD9D4CC, MUTED = 0xFFA8A39B, FAINT = 0xFF85817A;

    private static final class Tile {
        String key, label;
        Drawable banner;
        Runnable action;
        FrameLayout card;
        TextView caption;
    }

    private static final class InputItem {
        String label;
        Intent intent;
    }

    private float dp;
    private Typeface thin, light, regular, medium;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root, stage;
    private View curtain;
    private LifeView life;
    private SceneView sceneView;
    private String choice, showing;
    private final java.util.Random random = new java.util.Random();
    private TextView clock, date, patternName, patternCredit, patternBlurb;
    private Counter patternStats;
    private final TextView[] chipText = new TextView[CHIPS_N];
    private final LinearLayout[] chips = new LinearLayout[CHIPS_N];
    private ImageView timerIcon;
    private FrameLayout shelf;
    private LinearLayout sceneRow, appRow;
    private HorizontalScrollView sceneScroll, appScroll;
    private final List<Tile> scenes = new ArrayList<>(), apps = new ArrayList<>();
    private View qr;
    private LinearLayout menu;
    private final List<TextView> menuRows = new ArrayList<>();
    private final List<InputItem> inputs = new ArrayList<>();

    private int zone = AMBIENT, chipIdx, sceneIdx, appIdx, menuIdx;
    private boolean shelfOpen;
    private long lastInput = SystemClock.uptimeMillis();
    private boolean resumed, alwaysOn;
    private String ssid;

    /**
     * Android 14 freezes cached processes, and a frozen Cast receiver drops off phones' Cast lists
     * and leaves connections hanging in CLOSE_WAIT. The stock launcher kept it bound; bound to the
     * Home process, it inherits Home's priority and is never cached.
     */
    private final ServiceConnection castKeepAlive = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };
    private boolean castBound;

    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            buildShelf();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateTimer();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        thin = font(200);
        light = font(300);
        regular = font(400);
        medium = font(500);
        alwaysOn = prefs().getBoolean("alwaysOn", false);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        curtain = new View(this);
        curtain.setBackgroundColor(Color.BLACK);
        curtain.setAlpha(0f);
        root.addView(curtain, new FrameLayout.LayoutParams(-1, -1));
        choice = prefs().getString("background", "life");

        buildClock();
        buildCaption();
        buildChips();
        buildShelf();
        String first = SHUFFLE.equals(choice) ? BACKGROUNDS[random.nextInt(BACKGROUNDS.length)] : choice;
        show(first, -1, false);

        registerReceiver(clockReceiver, timeFilter());
        IntentFilter pkg = new IntentFilter();
        pkg.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkg.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkg.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkg.addDataScheme("package");
        registerReceiver(packageReceiver, pkg);
        watchNetwork();
        Intent cast = new Intent("com.google.cast.action.BIND").setPackage("com.google.android.apps.mediashell");
        try {
            castBound = bindService(cast, castKeepAlive, BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            android.util.Log.w("Home", "cannot bind Cast receiver", e);
        }
        // The idle timer decides when the screen goes off, not the system screensaver.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(clockReceiver);
        unregisterReceiver(packageReceiver);
        if (networkCallback != null) connectivity.unregisterNetworkCallback(networkCallback);
        if (castBound) unbindService(castKeepAlive);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        lastInput = SystemClock.uptimeMillis();
        updateClock();
        updateNames();
        refreshSsid();
        handler.post(tick);
        if (life != null) life.onResume();
        if (sceneView != null) sceneView.onResume();
        if (SHUFFLE.equals(choice)) handler.postDelayed(shuffleNext, SHUFFLE_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(shuffleNext);
        if (sceneView != null) sceneView.onPause();
        if (life != null) life.onPause();
        closeMenu();
        closeQr();
        if (shelfOpen) setShelf(false, false);
        zone = AMBIENT;
        refocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to the ambient view.
        closeMenu();
        closeQr();
        if (shelfOpen) setShelf(false, true);
        zone = AMBIENT;
        refocus();
    }

    // ---- helpers ----

    private int px(float v) {
        return Math.round(v * dp);
    }

    private Typeface font(int weight) {
        try {
            return new Typeface.Builder(getAssets(), "fonts/Outfit.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (Exception e) {
            return Typeface.create("sans-serif-light", Typeface.NORMAL);
        }
    }

    private TextView text(Typeface face, float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static GradientDrawable rounded(int fill, float radius, int stroke, float strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(Math.max(1, Math.round(strokeW)), stroke);
        return g;
    }

    private FrameLayout.LayoutParams at(int gravity, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP, gravity);
        lp.setMargins(left, top, right, bottom);
        return lp;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("home", MODE_PRIVATE);
    }

    // ---- ambient layer ----

    /** A soft dark pool behind text, so the Life cells never fight it. */
    private View shade(int w, int h) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(Math.max(w, h) / 2f);
        g.setColors(new int[]{0xE6000000, 0xB3000000, 0x00000000});
        v.setBackground(g);
        v.setScaleY(h / (float) Math.max(w, h));
        return v;
    }

    private void buildClock() {
        int size = px(620);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.START);
        sl.setMargins(-px(150), 0, 0, -size / 2 + px(70));
        root.addView(shade(size, size), sl);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        clock = text(thin, 104, INK);
        clock.setLetterSpacing(-0.02f);
        date = text(light, 20, INK2);
        // The variable font's thin cut measures narrower than it draws (worse with negative
        // tracking), so never ellipsize or clip it, and reserve room for the widest time.
        clock.setEllipsize(null);
        date.setEllipsize(null);
        clock.setShadowLayer(24, 0, 0, 0xB0000000);
        date.setShadowLayer(14, 0, 0, 0xD0000000);
        box.setClipChildren(false);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(WRAP, WRAP);
        dl.topMargin = px(8);
        dl.leftMargin = px(4);
        // An explicit width: wrap_content (and even minWidth) left it as narrow as the date line.
        box.addView(clock, new LinearLayout.LayoutParams(px(400), WRAP));
        box.addView(date, dl);
        root.addView(box, at(Gravity.BOTTOM | Gravity.START, px(52), 0, 0, px(48)));
    }

    private void buildCaption() {
        int size = px(520);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.END);
        sl.setMargins(0, 0, -px(140), -size / 2 + px(40));
        root.addView(shade(size, size), sl);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.END);
        patternName = text(regular, 15, INK2);
        patternCredit = text(light, 13, MUTED);
        // What to notice: only while the chips are up (the screen is otherwise left alone).
        patternBlurb = text(light, 13, MUTED);
        patternBlurb.setSingleLine(false);
        patternBlurb.setMaxLines(2);
        patternBlurb.setMaxWidth(px(380));
        patternBlurb.setGravity(Gravity.END);
        patternBlurb.setVisibility(View.GONE);
        patternStats = new Counter(this, light, 13, FAINT, () -> counter(0), () -> counter(1));
        // Scenes like the fire are bright behind the caption: a soft shadow keeps it legible.
        for (TextView t : new TextView[]{patternName, patternCredit, patternBlurb}) t.setShadowLayer(14, 0, 0, 0xF0000000);
        for (View t : new View[]{patternName, patternCredit, patternBlurb, patternStats}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.gravity = Gravity.END;
            if (t != patternName) lp.topMargin = px(4);
            box.addView(t, lp);
        }
        root.addView(box, at(Gravity.BOTTOM | Gravity.END, 0, 0, px(36), px(34)));
    }

    // ---- backgrounds ----

    private static Scene newScene(String key) {
        switch (key) {
            case "fire": return new FireScene();
            case "fractal": return new FractalScene();
            case "rd": return new GrayScottScene();
            case "ant": return new LangtonScene();
            default: return null;
        }
    }

    /** Pick a background from the shelf; remembered until the next pick. */
    private void choose(String key) {
        choice = key;
        prefs().edit().putString("background", key).apply();
        handler.removeCallbacks(shuffleNext);
        if (SHUFFLE.equals(key)) {
            handler.postDelayed(shuffleNext, SHUFFLE_MS);
            shuffleNext.run();
        } else if (!key.equals(showing)) {
            show(key, -1, true);
        }
        markChoice();
    }

    private final Runnable shuffleNext = new Runnable() {
        @Override
        public void run() {
            int i = random.nextInt(BACKGROUNDS.length - 1);
            String next = BACKGROUNDS[i];
            if (next.equals(showing)) next = BACKGROUNDS[BACKGROUNDS.length - 1];
            show(next, -2, true);
            handler.removeCallbacks(this);
            handler.postDelayed(this, SHUFFLE_MS);
        }
    };

    /**
     * Put a background on the stage, fading through black. variant -1 starts at the first
     * variant, -2 at a random one (shuffle).
     */
    private void show(String key, int variant, boolean animate) {
        showing = key;
        Runnable swap = () -> {
            stage.removeAllViews();
            life = null;
            sceneView = null;
            Scene scene = newScene(key);
            if ("none".equals(key)) {
                // adb-only (see Config): a plain black stage, for measuring the UI by itself.
                patternStats.setVisibility(View.GONE);
            } else if (scene == null) {
                life = new LifeView(this);
                life.setListener(this::onCaption);
                stage.addView(life, new FrameLayout.LayoutParams(-1, -1));
                patternStats.setLabels("generation", "population");
                patternStats.setVisibility(View.VISIBLE);
            } else {
                int n = scene.variants().length;
                int v = variant == -2 ? random.nextInt(n) : Math.max(0, variant);
                sceneView = new SceneView(this, scene, v);
                sceneView.setListener(this::onVariant);
                stage.addView(sceneView, new FrameLayout.LayoutParams(-1, -1));
                patternStats.setVisibility(View.GONE);
            }
            curtain.animate().alpha(0f).setStartDelay(150).setDuration(700).start();
        };
        curtain.animate().cancel();
        if (animate) {
            curtain.animate().alpha(1f).setStartDelay(0).setDuration(300).withEndAction(swap).start();
        } else {
            swap.run();
        }
    }

    private void onVariant(Scene scene, Scene.Variant v) {
        patternName.setText(scene.title() + " · " + v.name);
        patternCredit.setText(v.credit);
        patternBlurb.setText(v.blurb != null ? v.blurb : "");
        String[] names = scene.counters();
        patternStats.setVisibility(names != null ? View.VISIBLE : View.GONE);
        if (names != null) patternStats.setLabels(names[0], names[1]);
        patternStats.reset();
    }

    /** The two live numbers under the caption: Life's, or the current scene's. */
    private long counter(int i) {
        if (life != null) return i == 0 ? life.generation() : life.population();
        SceneView v = sceneView;
        return v != null && v.scene().counters() != null ? v.scene().counter(i) : 0;
    }

    /** Left on the ambient view: previous pattern or variant. */
    private void previousOnStage() {
        if (life != null) life.previous();
        if (sceneView != null) sceneView.previous();
    }

    /** Right on the ambient view: next pattern or variant. */
    private void nextOnStage() {
        if (life != null) life.next();
        if (sceneView != null) sceneView.next();
    }

    private void onCaption(Catalog.Entry e, long generation, long population) {
        if (life == null) return;  // a late caption from a Life view already replaced
        if (!e.name.contentEquals(patternName.getText())) {
            patternName.setText(e.name);
            patternCredit.setText(e.credit);
            patternBlurb.setText(e.blurb);
        }
        patternStats.reset();
    }

    // ---- status chips ----

    private void buildChips() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // Cast and AirPlay share a chip: both receivers answer to the same device name. Selecting
        // it shows the network to join (QR code) along with the receiver names.
        int[][] icons = {{R.drawable.ic_cast, R.drawable.ic_airplay}, {R.drawable.ic_moon}, {R.drawable.ic_settings}};
        for (int i = 0; i < CHIPS_N; i++) {
            LinearLayout chip = new LinearLayout(this);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(px(14), 0, px(14), 0);
            for (int k = 0; k < icons[i].length; k++) {
                ImageView icon = new ImageView(this);
                icon.setImageResource(icons[i][k]);
                if (i == CHIP_TIMER) timerIcon = icon;
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(WRAP, WRAP);
                if (k > 0) il.leftMargin = px(6);
                chip.addView(icon, il);
            }
            TextView t = text(regular, 13, INK);
            t.setMaxWidth(px(220));
            if (i == CHIP_TIMER) t.setFontFeatureSettings("tnum");
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(WRAP, WRAP);
            tl.leftMargin = px(8);
            if (i != CHIP_SETTINGS) chip.addView(t, tl);
            else chip.setPadding(px(9), 0, px(9), 0);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(WRAP, px(32));
            cl.leftMargin = px(8);
            bar.addView(chip, cl);
            chips[i] = chip;
            chipText[i] = t;
        }
        root.addView(bar, at(Gravity.TOP | Gravity.END, 0, px(28), px(32), 0));
        styleChips();
        updateTimer();
    }

    private void styleChips() {
        for (int i = 0; i < CHIPS_N; i++) {
            boolean focused = zone == CHIPS && chipIdx == i;
            // Opaque enough to read over a bright scene (the fractals reach the top edge).
            chips[i].setBackground(rounded(focused ? 0xE0303034 : 0xB0141416, px(16),
                    focused ? 0xD9FFFFFF : 0x1AFFFFFF, focused ? 2 * dp : dp));
        }
    }

    private void updateNames() {
        String name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        if (name == null) name = android.os.Build.MODEL;
        chipText[CHIP_CAST].setText(name);
    }

    private void updateTimer() {
        if (alwaysOn) {
            timerIcon.setImageResource(R.drawable.ic_infinity);
            chipText[CHIP_TIMER].setText(R.string.always_on);
            return;
        }
        timerIcon.setImageResource(R.drawable.ic_moon);
        long left = Math.max(0, IDLE_MS - (SystemClock.uptimeMillis() - lastInput));
        long mins = (left + 59_999) / 60_000;
        chipText[CHIP_TIMER].setText(String.format(Locale.ROOT, "%d min", mins));
        if (resumed && left == 0) {
            lastInput = SystemClock.uptimeMillis();
            sleep();
        }
    }

    private void sleep() {
        Intent i = new Intent("bot.tvowner.SLEEP").setPackage("bot.tvowner");
        sendBroadcast(i, null);
    }

    private void toggleTimer() {
        alwaysOn = !alwaysOn;
        prefs().edit().putBoolean("alwaysOn", alwaysOn).apply();
        lastInput = SystemClock.uptimeMillis();
        updateTimer();
    }

    // ---- Wi-Fi QR ----

    private void openQr() {
        String password = prefs().getString("wifiPassword", null);
        String security = prefs().getString("wifiSecurity", "WPA");
        String network = ssid != null ? ssid : getString(R.string.wifi);

        LinearLayout card = new LinearLayout(this);
        card.setPadding(px(28), px(28), px(28), px(28));
        card.setBackground(rounded(0xF5181818, px(24), 0x1FFFFFFF, dp));
        card.setElevation(px(16));

        View code;
        if (password != null && ssid != null) {
            String payload = "WIFI:S:" + escape(ssid) + ";T:" + security + ";P:" + escape(password) + ";;";
            ImageView img = new ImageView(this);
            img.setImageDrawable(qrDrawable(QrCode.encodeText(payload, QrCode.Ecc.MEDIUM)));
            img.setBackground(rounded(INK, px(14), 0, 0));
            img.setPadding(px(12), px(12), px(12), px(12));
            code = img;
        } else {
            TextView t = text(regular, 13, 0xFF3A3835);
            t.setSingleLine(false);
            t.setGravity(Gravity.CENTER);
            t.setText(R.string.qr_no_password);
            t.setBackground(rounded(INK, px(14), 0, 0));
            t.setPadding(px(16), px(16), px(16), px(16));
            code = t;
        }
        card.addView(code, new LinearLayout.LayoutParams(px(200), px(200)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView over = text(medium, 12, MUTED);
        over.setText(R.string.join_network);
        over.setAllCaps(true);
        over.setLetterSpacing(0.12f);
        TextView name = text(light, 30, INK);
        name.setText(network);
        TextView hint = text(light, 14, MUTED);
        hint.setText(R.string.point_camera);
        info.addView(over);
        LinearLayout.LayoutParams g4 = new LinearLayout.LayoutParams(WRAP, WRAP);
        g4.topMargin = px(6);
        info.addView(name, g4);
        LinearLayout.LayoutParams g6 = new LinearLayout.LayoutParams(WRAP, WRAP);
        g6.topMargin = px(6);
        info.addView(hint, g6);
        String device = chipText[CHIP_CAST].getText().toString();
        for (String[] row : new String[][]{{getString(R.string.cast), device}, {getString(R.string.airplay), device}}) {
            LinearLayout line = new LinearLayout(this);
            TextView k = text(regular, 15, MUTED);
            k.setText(row[0]);
            TextView v = text(regular, 15, INK);
            v.setText(row[1]);
            line.addView(k, new LinearLayout.LayoutParams(px(110), WRAP));
            line.addView(v);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(WRAP, WRAP);
            ll.topMargin = px(14);
            info.addView(line, ll);
        }
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(WRAP, WRAP);
        il.leftMargin = px(28);
        card.addView(info, il);

        root.addView(card, at(Gravity.TOP | Gravity.END, 0, px(76), px(32), 0));
        card.setAlpha(0f);
        card.setTranslationY(-px(8));
        card.animate().alpha(1f).translationY(0).setDuration(180).start();
        qr = card;
        zone = QR;
        refocus();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"");
    }

    private Drawable qrDrawable(QrCode q) {
        int n = q.size;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) b.setPixel(x, y, q.getModule(x, y) ? 0xFF111111 : INK);
        }
        BitmapDrawable d = new BitmapDrawable(getResources(), b);
        d.setFilterBitmap(false);
        return d;
    }

    private void closeQr() {
        if (qr != null) {
            root.removeView(qr);
            qr = null;
            if (zone == QR) zone = CHIPS;
        }
    }

    // ---- shelf ----

    private void buildShelf() {
        if (shelf == null) {
            shelf = new FrameLayout(this);
            shelf.setBackground(rounded(0xFF141416, px(28), 0x1AFFFFFF, dp));
            shelf.setPadding(0, px(26), 0, px(60));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams hs = new LinearLayout.LayoutParams(WRAP, WRAP);
            hs.leftMargin = px(56);
            col.addView(heading(R.string.ambient), hs);
            sceneRow = new LinearLayout(this);
            sceneScroll = scroller(sceneRow);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, WRAP);
            rl.topMargin = px(4);
            col.addView(sceneScroll, rl);
            TextView apHead = heading(R.string.apps);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(WRAP, WRAP);
            hl.topMargin = px(12);
            hl.leftMargin = px(56);
            col.addView(apHead, hl);
            appRow = new LinearLayout(this);
            appScroll = scroller(appRow);
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, WRAP);
            al.topMargin = px(4);
            col.addView(appScroll, al);
            shelf.addView(col);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, WRAP, Gravity.BOTTOM);
            lp.bottomMargin = -px(28);
            root.addView(shelf, lp);
            shelf.setVisibility(View.INVISIBLE);
        }
        sceneRow.removeAllViews();
        appRow.removeAllViews();
        scenes.clear();
        apps.clear();

        PackageManager pm = getPackageManager();
        Tile lifeTile = new Tile();
        int[] labels = {R.string.life, R.string.fireplace, R.string.fractals, R.string.reaction_diffusion, R.string.ants};
        int[] banners = {R.drawable.banner_life, R.drawable.banner_fire, R.drawable.banner_fractal, R.drawable.banner_rd, R.drawable.banner_ant};
        for (int i = 0; i < BACKGROUNDS.length; i++) {
            Tile t = new Tile();
            t.key = BACKGROUNDS[i];
            t.label = getString(labels[i]);
            t.banner = getDrawable(banners[i]);
            t.action = () -> {
                choose(t.key);
                zone = AMBIENT;
                setShelf(false, true);
            };
            scenes.add(t);
        }
        Tile shuffle = new Tile();
        shuffle.key = SHUFFLE;
        shuffle.label = getString(R.string.shuffle);
        List<Drawable> parts = new ArrayList<>();
        for (Tile t : scenes) parts.add(t.banner.getConstantState().newDrawable().mutate());
        shuffle.banner = new Strips(parts);
        shuffle.action = () -> {
            choose(SHUFFLE);
            zone = AMBIENT;
            setShelf(false, true);
        };
        scenes.add(shuffle);
        List<ResolveInfo> other = new ArrayList<>();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (!ri.activityInfo.packageName.equals(getPackageName())) other.add(ri);
        }

        List<String> order = Arrays.asList(getResources().getStringArray(R.array.dock_apps));
        List<String> hidden = Arrays.asList(getResources().getStringArray(R.array.hidden_apps));
        Map<String, ResolveInfo> byPackage = new LinkedHashMap<>();
        Collections.sort(other, (a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
        for (ResolveInfo ri : other) {
            String p = ri.activityInfo.packageName;
            if (!hidden.contains(p) && !byPackage.containsKey(p)) byPackage.put(p, ri);
        }
        for (String p : order) {
            ResolveInfo ri = byPackage.remove(p);
            if (ri != null) apps.add(appTile(ri, pm));
        }
        for (ResolveInfo ri : byPackage.values()) apps.add(appTile(ri, pm));
        Tile in = new Tile();
        in.label = getString(R.string.inputs);
        in.action = this::openMenu;
        apps.add(Math.min(1, apps.size()), in);

        for (Tile t : scenes) addTile(sceneRow, t, 176, 99);
        for (Tile t : apps) addTile(appRow, t, 150, 84);
        sceneIdx = Math.min(sceneIdx, scenes.size() - 1);
        appIdx = Math.min(appIdx, apps.size() - 1);
        refocus();
    }

    /** A row that we scroll ourselves (focus is not view focus); tiles may bleed to the screen edges. */
    private HorizontalScrollView scroller(LinearLayout row) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setOverScrollMode(View.OVER_SCROLL_NEVER);
        h.setFocusable(false);
        h.setPadding(px(56), px(8), px(56), px(4));
        h.setClipToPadding(false);
        row.setClipChildren(false);
        h.addView(row);
        return h;
    }

    /** Diagonal slices of several banners, for the Shuffle tile. */
    private static final class Strips extends Drawable {
        private final List<Drawable> parts;

        Strips(List<Drawable> parts) {
            this.parts = parts;
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            int n = parts.size();
            float slant = b.height() * 0.35f;
            for (int i = 0; i < n; i++) {
                float x0 = b.left + (b.width() + slant) * i / n - slant;
                float x1 = b.left + (b.width() + slant) * (i + 1) / n - slant;
                android.graphics.Path clip = new android.graphics.Path();
                clip.moveTo(x0 + slant, b.top);
                clip.lineTo(x1 + slant + 1, b.top);
                clip.lineTo(x1 + 1, b.bottom);
                clip.lineTo(x0, b.bottom);
                clip.close();
                c.save();
                c.clipPath(clip);
                parts.get(i).setBounds(b);
                parts.get(i).draw(c);
                c.restore();
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }

    private TextView heading(int res) {
        TextView t = text(medium, 12, MUTED);
        t.setText(res);
        t.setAllCaps(true);
        t.setLetterSpacing(0.12f);
        return t;
    }

    private Tile appTile(ResolveInfo ri, PackageManager pm) {
        ActivityInfo ai = ri.activityInfo;
        Tile t = new Tile();
        t.label = String.valueOf(ri.loadLabel(pm));
        t.banner = ai.loadBanner(pm);
        ComponentName cn = new ComponentName(ai.packageName, ai.name);
        t.action = () -> launch(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setComponent(cn));
        return t;
    }

    private void addTile(LinearLayout row, Tile t, int w, int h) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        FrameLayout card = new FrameLayout(this);
        card.setBackground(rounded(0xFF1E1E21, px(12), 0x14FFFFFF, dp));
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(12));
            }
        });
        if (t.banner != null) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(t.banner);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView l = text(light, 18, INK);
            l.setText(t.label);
            l.setGravity(Gravity.CENTER);
            card.addView(l, new FrameLayout.LayoutParams(-1, -1));
        }
        col.addView(card, new LinearLayout.LayoutParams(px(w), px(h)));
        t.caption = text(regular, 13, MUTED);
        t.caption.setText(t.label);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(px(w), WRAP);
        cl.topMargin = px(10);
        col.addView(t.caption, cl);
        t.card = card;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.rightMargin = px(20);
        row.addView(col, lp);
    }

    private void setShelf(boolean open, boolean animate) {
        shelfOpen = open;
        shelf.animate().cancel();
        if (open) {
            shelf.setVisibility(View.VISIBLE);
            if (shelf.getHeight() > 0 && animate) {
                shelf.setTranslationY(shelf.getHeight());
                shelf.animate().translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator(1.6f)).start();
            } else {
                shelf.setTranslationY(0);
            }
        } else if (animate) {
            shelf.animate().translationY(shelf.getHeight()).setDuration(200)
                    .withEndAction(() -> shelf.setVisibility(View.INVISIBLE)).start();
        } else {
            shelf.setVisibility(View.INVISIBLE);
        }
    }

    // ---- focus ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        lastInput = SystemClock.uptimeMillis();
        int k = e.getKeyCode();
        boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BACK;
        if (!nav) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            onKey(k);
            updateTimer();
        }
        return true;
    }

    private void onKey(int k) {
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = k == KeyEvent.KEYCODE_BACK;
        boolean up = k == KeyEvent.KEYCODE_DPAD_UP, down = k == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean left = k == KeyEvent.KEYCODE_DPAD_LEFT, right = k == KeyEvent.KEYCODE_DPAD_RIGHT;
        switch (zone) {
            case QR:
                closeQr();
                break;
            case MENU:
                if (ok) {
                    if (menuIdx < inputs.size()) launch(inputs.get(menuIdx).intent);
                    closeMenu();
                } else if (up) menuIdx = Math.max(0, menuIdx - 1);
                else if (down) menuIdx = Math.min(menuRows.size() - 1, menuIdx + 1);
                else closeMenu();
                styleMenu();
                break;
            case AMBIENT:
                if (up) zone = CHIPS;
                else if (down || ok) {
                    zone = SCENES;
                    for (int i = 0; i < scenes.size(); i++) if (scenes.get(i).key.equals(choice)) sceneIdx = i;
                    setShelf(true, true);
                } else if (right) nextOnStage();
                else if (left) previousOnStage();
                break;
            case CHIPS:
                if (left) chipIdx = Math.max(0, chipIdx - 1);
                else if (right) chipIdx = Math.min(CHIPS_N - 1, chipIdx + 1);
                else if (down || back) zone = AMBIENT;
                else if (ok && chipIdx == CHIP_CAST) openQr();
                else if (ok && chipIdx == CHIP_TIMER) toggleTimer();
                else if (ok && chipIdx == CHIP_SETTINGS) launch(new Intent(Settings.ACTION_SETTINGS));
                break;
            case SCENES:
                if (left) sceneIdx = Math.max(0, sceneIdx - 1);
                else if (right) sceneIdx = Math.min(scenes.size() - 1, sceneIdx + 1);
                else if (down && !apps.isEmpty()) {
                    zone = APPS;
                    appIdx = Math.min(appIdx, apps.size() - 1);
                } else if (up || back) {
                    zone = AMBIENT;
                    setShelf(false, true);
                } else if (ok) scenes.get(sceneIdx).action.run();
                break;
            case APPS:
                if (left) appIdx = Math.max(0, appIdx - 1);
                else if (right) appIdx = Math.min(apps.size() - 1, appIdx + 1);
                else if (up) zone = SCENES;
                else if (back) {
                    zone = AMBIENT;
                    setShelf(false, true);
                } else if (ok) apps.get(appIdx).action.run();
                break;
        }
        refocus();
    }

    private void markChoice() {
        focusRow(scenes, zone == SCENES ? sceneIdx : -1, sceneScroll);
    }

    private void refocus() {
        if (chips[0] != null) styleChips();
        if (patternBlurb != null) {
            boolean show = (zone == CHIPS || zone == QR) && patternBlurb.length() > 0;
            patternBlurb.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        focusRow(scenes, zone == SCENES ? sceneIdx : -1, sceneScroll);
        focusRow(apps, zone == APPS ? appIdx : -1, appScroll);
    }

    private void focusRow(List<Tile> row, int idx, HorizontalScrollView scroll) {
        if (idx >= 0 && idx < row.size() && row.get(idx).card != null && scroll != null) {
            View col = (View) row.get(idx).card.getParent();
            int visible = scroll.getWidth() - scroll.getPaddingLeft() - scroll.getPaddingRight();
            int x = scroll.getScrollX();
            // Keep the focused tile in view, with a peek of the next one when there is one.
            int peek = idx < row.size() - 1 ? px(60) : 0;
            if (col.getRight() + peek > x + visible) x = col.getRight() + peek - visible;
            if (col.getLeft() - (idx > 0 ? px(60) : 0) < x) x = Math.max(0, col.getLeft() - (idx > 0 ? px(60) : 0));
            if (visible > 0) scroll.smoothScrollTo(x, 0);
        }
        for (int i = 0; i < row.size(); i++) {
            Tile t = row.get(i);
            if (t.card == null) continue;
            boolean f = i == idx;
            t.card.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(160).start();
            t.card.setForeground(f ? rounded(0x00000000, px(12), 0xE6FFFFFF, 2 * dp) : null);
            boolean chosen = t.key != null && t.key.equals(choice);
            t.caption.setTextColor(f ? INK : chosen ? ACCENT : MUTED);
        }
    }

    private void launch(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            android.util.Log.w("Home", "cannot launch " + i, e);
        }
    }

    // ---- inputs menu ----

    private void loadInputs() {
        inputs.clear();
        TvInputManager tim = getSystemService(TvInputManager.class);
        boolean tuner = false;
        if (tim != null) {
            // HDMI-CEC devices show up as child inputs of their port; use their names as labels.
            Map<String, String> cecNames = new LinkedHashMap<>();
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getParentId() != null && !info.isHidden(this)) {
                    cecNames.put(info.getParentId(), String.valueOf(info.loadLabel(this)));
                }
            }
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getParentId() != null || info.isHidden(this)) continue;
                if (info.getType() == TvInputInfo.TYPE_TUNER) {
                    tuner = true;
                    continue;
                }
                if (!info.isPassthroughInput()) continue;
                InputItem item = new InputItem();
                CharSequence custom = info.loadCustomLabel(this);
                item.label = String.valueOf(custom != null ? custom : info.loadLabel(this));
                String cec = cecNames.get(info.getId());
                if (cec != null && !cec.equals(item.label)) item.label += " \u00b7 " + cec;
                item.intent = new Intent(Intent.ACTION_VIEW,
                        TvContract.buildChannelUriForPassthroughInput(info.getId()));
                inputs.add(item);
            }
        }
        Collections.sort(inputs, (x, y) -> x.label.compareToIgnoreCase(y.label));
        Intent live = getPackageManager().getLeanbackLaunchIntentForPackage("com.tcl.tv");
        if (tuner && live != null) {
            InputItem item = new InputItem();
            item.label = getString(R.string.live_tv);
            item.intent = live;
            inputs.add(item);
        }
    }

    private void openMenu() {
        loadInputs();
        if (inputs.isEmpty()) return;
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(px(8), px(8), px(8), px(8));
        menu.setBackground(rounded(0xF51C1C1E, px(18), 0x33FFFFFF, dp));
        menu.setElevation(px(24));
        menuRows.clear();
        for (InputItem item : inputs) {
            TextView row = text(regular, 15, INK);
            row.setText(item.label);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(16), 0, px(24), 0);
            menu.addView(row, new LinearLayout.LayoutParams(-1, px(40)));
            menuRows.add(row);
        }
        View anchor = apps.size() > 1 && apps.get(1).card != null ? apps.get(1).card : appRow;
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int w = px(260);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, WRAP, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = loc[0];
        lp.bottomMargin = root.getHeight() - loc[1] + px(12);
        root.addView(menu, lp);
        menu.setAlpha(0f);
        menu.animate().alpha(1f).setDuration(160).start();
        zone = MENU;
        menuIdx = 0;
        styleMenu();
    }

    private void styleMenu() {
        for (int i = 0; i < menuRows.size(); i++) {
            menuRows.get(i).setBackground(i == menuIdx ? rounded(0x33FFFFFF, px(10), 0, 0) : null);
        }
    }

    private void closeMenu() {
        if (menu != null) {
            root.removeView(menu);
            menu = null;
        }
        if (zone == MENU) zone = APPS;
    }

    // ---- clock and network ----

    private static IntentFilter timeFilter() {
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        return f;
    }

    private void updateClock() {
        Date now = new Date();
        Locale l = Locale.getDefault();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now));
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(l, "EEEEdMMMM"), now));
    }

    private static String cleanSsid(String s) {
        return s == null || s.contains("<unknown") ? null : s.replace("\"", "");
    }

    @SuppressWarnings("deprecation")
    private String currentSsid() {
        android.net.wifi.WifiManager wifi = getSystemService(android.net.wifi.WifiManager.class);
        WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;
        return info != null ? cleanSsid(info.getSSID()) : null;
    }

    private void refreshSsid() {
        if (ssid != null) return;
        String found = currentSsid();
        if (found != null) {
            ssid = found;
        }
    }

    private void watchNetwork() {
        connectivity = getSystemService(ConnectivityManager.class);
        networkCallback = new ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            @Override
            public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                String name;
                String found = null;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    name = getString(R.string.ethernet);
                } else if (caps.getTransportInfo() instanceof WifiInfo) {
                    found = cleanSsid(((WifiInfo) caps.getTransportInfo()).getSSID());
                    // Right after a wake the callback can arrive before the SSID is known.
                    if (found == null) found = currentSsid();
                    name = found != null ? found : getString(R.string.wifi);
                } else {
                    name = getString(R.string.wifi);
                }
                final String f = found;
                runOnUiThread(() -> {
                    ssid = f;
                });
            }

            @Override
            public void onLost(Network n) {
                runOnUiThread(() -> {
                    ssid = null;
                });
            }
        };
        connectivity.registerDefaultNetworkCallback(networkCallback);
    }
}
