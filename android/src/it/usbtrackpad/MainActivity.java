package it.usbtrackpad;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Trackpad a tutto schermo. In alto: puntino di stato · testo · batteria.
 * Gesti: 1 dito muove · tocco = clic · tocco a 2 dita = clic destro · 3 dita = clic centrale
 * 2 dita scorrono (con inerzia) · doppio tocco tenuto e trascinato = trascina.
 */
public class MainActivity extends Activity {

    static final int PORT = 27182;

    // Palette dal sito emanuelebenassi.com (scuro) e la sua versione chiara.
    static final int[] DARK  = { 0xFF0A0A0A, 0xFFA8A49C, 0xFF807C75, 0xFF8FAE7E, 0xFFD1A25A, 0xFFC96A5F };
    static final int[] LIGHT = { 0xFFF5F1E8, 0xFF5C5852, 0xFFA8A49C, 0xFF5F8A52, 0xFFB5832F, 0xFFB4544A };
    // OLED/AMOLED: nero assoluto (pixel spenti), stessi colori del tema scuro
    static final int[] OLED  = { 0xFF000000, 0xFFA8A49C, 0xFF807C75, 0xFF8FAE7E, 0xFFD1A25A, 0xFFC96A5F };
    static final int BG = 0, TEXT = 1, BULLET = 2, OK = 3, WARN = 4, ERR = 5;

    enum State { OFFLINE, OK, NO_AX }

    final Handler ui = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    FrameLayout root;
    View dot;
    GradientDrawable dotShape;
    TextView label, bullet, pct;     // "COLLEGATO AL MAC"  •  "84%"
    LinearLayout status;
    ColorDrawable bg;

    int[] colors = DARK.clone();
    boolean dark = true;        // tema attuale del Mac
    boolean oledAuto;           // schermo OLED rilevato sul telefono
    Boolean oledForced;         // scelta imposta dal Mac (TRACKPAD_OLED), null = automatico
    ObjectAnimator blink;
    String hostName = "";
    ChargeLimiter charger;      // limitatore di carica 60/50% (solo con root)       // nome del computer collegato (inviato dal Mac o dal PC)
    State state = State.OFFLINE;
    int battery = -1;
    ValueAnimator themeAnim;

    Link link;

    // ------------------------------------------------------------------ ciclo di vita

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("trackpad", MODE_PRIVATE);
        readToken(getIntent());
        dark = prefs.getBoolean("dark", true);
        oledAuto = OledDetector.isOled(this);
        if (prefs.contains("oled")) oledForced = prefs.getBoolean("oled", false);
        colors = palette().clone();

        // plug and play: quando il Mac apre l'app, lo schermo si accende e il
        // trackpad compare anche sopra la schermata di blocco (senza sbloccare il telefono)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            // il trackpad occupa anche l'area del notch
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        getWindow().setBackgroundDrawable(new ColorDrawable(colors[BG]));

        float d = getResources().getDisplayMetrics().density;
        root = new FrameLayout(this);
        bg = new ColorDrawable(colors[BG]);
        root.setBackground(bg);
        root.setHapticFeedbackEnabled(false);
        root.setSoundEffectsEnabled(false);

        status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        final int pad = Math.round(22 * d);
        status.setPadding(pad, pad, pad, pad);

        dot = new View(this);
        dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dot.setBackground(dotShape);
        int ds = Math.round(7 * d);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ds, ds);
        dlp.rightMargin = Math.round(10 * d);
        status.addView(dot, dlp);

        // tre testi separati: al cambio tema si aggiorna solo il colore, senza rifare il layout
        label = statusText(0);
        // nomi lunghi: larghezza massima e sfumatura sul bordo destro
        label.setMaxWidth(Math.round(220 * d));
        label.setHorizontalFadingEdgeEnabled(true);
        label.setFadingEdgeLength(Math.round(32 * d));
        label.setEllipsize(null);
        label.setHorizontallyScrolling(true);
        bullet = statusText(Math.round(12 * d));
        bullet.setText("\u2022");
        pct = statusText(Math.round(12 * d));
        status.addView(label);
        status.addView(bullet);
        status.addView(pct);

        root.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));
        root.setOnTouchListener((v, e) -> onPadTouch(e));
        // il testo di stato resta lontano da notch e angoli arrotondati
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int top = 0, left = 0, right = 0;
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout c = ins.getDisplayCutout();
                if (c != null) { top = c.getSafeInsetTop(); left = c.getSafeInsetLeft(); right = c.getSafeInsetRight(); }
            }
            status.setPadding(pad + left, pad + top, pad + right, pad);
            return ins;
        });
        setContentView(root);

        charger = new ChargeLimiter(this::info);
        charger.configure(prefs.getBoolean("chargeOn", true), prefs.getInt("chargeMax", 60), prefs.getInt("chargeMin", 50));
        Intent bi = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bi != null) batteryReceiver.onReceive(this, bi);
        applyColors();
        updateStatusVisibility();
    }

    TextView statusText(int marginLeft) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setLetterSpacing(0.14f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = marginLeft;
        t.setLayoutParams(lp);
        return t;
    }

    /** La chiave arriva dal computer tramite adb: le altre app del telefono non la vedono. */
    void readToken(Intent i) {
        String t = i == null ? null : i.getStringExtra("token");
        if (t != null && t.matches("[0-9a-f]{32,128}")) prefs.edit().putString("token", t).apply();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        readToken(i);
        // ricollega subito con la chiave nuova
        if (link != null) { link.shutdown(); link = new Link(); link.start(); }
    }

    @Override protected void onStart() {
        super.onStart();
        link = new Link();
        link.start();
        charger.onBattery(battery);
    }

    @Override protected void onStop() {
        super.onStop();
        charger.release();
        stopMomentum();
        if (link != null) { link.shutdown(); link = null; }
        setState(State.OFFLINE);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(batteryReceiver);
    }

    @Override protected void onResume() { super.onResume(); immersive(); }

    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) immersive();
    }

    void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------------ stato, tema, batteria

    final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = level < 0 ? -1 : Math.round(level * 100f / scale);
            if (pct != battery) { battery = pct; renderStatus(); }
            charger.onBattery(pct);
        }
    };

    void setState(State s) {
        if (s == state) return;
        state = s;
        renderStatus();
        updateStatusVisibility();
    }

    boolean oled() { return oledForced != null ? oledForced : oledAuto; }

    /** Su OLED/AMOLED sempre scuro (nero assoluto), altrimenti il tema del Mac. */
    int[] palette() { return oled() ? OLED : dark ? DARK : LIGHT; }

    void setDark(boolean d) {
        if (d == dark) return;
        dark = d;
        prefs.edit().putBoolean("dark", d).apply();
        animateTheme();
    }

    void setOledForced(Boolean f) {
        if (f == null ? oledForced == null : f.equals(oledForced)) return;
        oledForced = f;
        if (f == null) prefs.edit().remove("oled").apply();
        else prefs.edit().putBoolean("oled", f).apply();
        animateTheme();
        updateStatusVisibility();
    }

    void animateTheme() {
        final int[] from = colors.clone(), to = palette();
        if (java.util.Arrays.equals(from, to)) return;
        if (themeAnim != null) themeAnim.cancel();
        // dissolvenza come quella di macOS al cambio di aspetto: breve e morbida (ease-in-out)
        themeAnim = ValueAnimator.ofFloat(0f, 1f);
        themeAnim.setDuration(450);
        themeAnim.setInterpolator(new PathInterpolator(0.42f, 0f, 0.58f, 1f));
        final ArgbEvaluator ev = new ArgbEvaluator();
        // a ogni fotogramma cambiano solo i colori (niente layout né testo da rifare):
        // il thread dei tocchi resta libero e il trackpad non si ferma durante la dissolvenza
        themeAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            for (int i = 0; i < colors.length; i++) colors[i] = (int) ev.evaluate(f, from[i], to[i]);
            paintColors();
        });
        themeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                getWindow().setBackgroundDrawable(new ColorDrawable(colors[BG]));
            }
        });
        themeAnim.start();
    }

    void applyColors() {
        getWindow().setBackgroundDrawable(new ColorDrawable(colors[BG]));
        renderStatus();
    }

    /** Solo colori: sfondo, puntino e testi. Economico, adatto a ogni fotogramma. */
    void paintColors() {
        bg.setColor(colors[BG]);
        // se il computer non riesce a muovere il cursore, per l'utente è come scollegato: rosso
        dotShape.setColor(colors[state == State.OK ? OK : ERR]);
        label.setTextColor(colors[TEXT]);
        pct.setTextColor(colors[TEXT]);
        bullet.setTextColor(colors[BULLET]);
    }

    void renderStatus() {
        String text;
        switch (state) {
            case OK:    text = hostName.isEmpty() ? "Collegato" : hostName; break;
            case NO_AX: text = "Mac non autorizzato";                        break;
            default:    text = "Computer non collegato";                     break;
        }
        // maiuscolo fatto a mano: setAllCaps su Android vecchi ignora i colori impostati
        label.setText(text.toUpperCase(Locale.ITALIAN));
        boolean hasBattery = battery >= 0;
        bullet.setVisibility(hasBattery ? View.VISIBLE : View.GONE);
        pct.setVisibility(hasBattery ? View.VISIBLE : View.GONE);
        if (hasBattery) pct.setText(battery + "%");
        paintColors();
    }

    /**
     * Protezione dal burn-in, solo su OLED/AMOLED: nessun testo fisso sullo schermo.
     * Mac scollegato (o permesso mancante): lo stato lampeggia.
     * Collegato: compare "Collegato al Mac" per qualche secondo, poi sparisce.
     * A ogni cambio lo stato si sposta di qualche pixel, così non consuma sempre gli stessi.
     * Su LCD resta sempre visibile.
     */
    void updateStatusVisibility() {
        if (blink != null) { blink.cancel(); blink = null; }
        status.animate().cancel();
        if (!oled()) {
            status.setAlpha(1f);
            status.setTranslationX(0); status.setTranslationY(0);
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        status.setTranslationX((float) ((Math.random() * 2 - 1) * 5 * d));
        status.setTranslationY((float) ((Math.random() * 2 - 1) * 4 * d));
        status.setAlpha(1f);
        if (state == State.OK) {
            status.animate().alpha(0f).setStartDelay(3000).setDuration(700).start();
        } else {
            blink = ObjectAnimator.ofFloat(status, View.ALPHA, 1f, 0.1f);
            blink.setDuration(900);
            blink.setRepeatMode(ValueAnimator.REVERSE);
            blink.setRepeatCount(ValueAnimator.INFINITE);
            blink.start();
        }
    }

    /** Righe dal computer: "host nome", "theme dark|light", "ax 0|1", "oled 1|0|auto". */
    void onMacLine(String line) {
        if (line.startsWith("host ")) {
            String h = line.substring(5).trim();
            if (!h.equals(hostName)) { hostName = h; renderStatus(); }
        }
        else if (line.startsWith("theme ")) setDark(line.endsWith("dark"));
        else if (line.startsWith("ax ")) setState(line.endsWith("1") ? State.OK : State.NO_AX);
        else if (line.startsWith("charge ")) {
            // "charge 60 50" (massimo, minimo) oppure "charge off"
            String[] p = line.split(" ");
            boolean on = p.length >= 3;
            int max = 60, min = 50;
            try { if (on) { max = Integer.parseInt(p[1]); min = Integer.parseInt(p[2]); } } catch (NumberFormatException e) { on = false; }
            if (on != prefs.getBoolean("chargeOn", true) || max != prefs.getInt("chargeMax", 60) || min != prefs.getInt("chargeMin", 50)) {
                prefs.edit().putBoolean("chargeOn", on).putInt("chargeMax", max).putInt("chargeMin", min).apply();
                charger.configure(on, max, min);
                charger.onBattery(battery);
            }
        }
        else if (line.startsWith("oled ")) {
            String v = line.substring(5);
            setOledForced(v.equals("1") ? Boolean.TRUE : v.equals("0") ? Boolean.FALSE : null);
        }
    }

    // ------------------------------------------------------------------ gesti

    static final long TAP_MS = 220, DRAG_WINDOW = 260;
    static final float TAP_MOVE_DP = 10, DRAG_NEAR_DP = 60;

    final SparseArray<float[]> pts = new SparseArray<>();   // id -> {x, y, startX, startY}
    boolean active, moved, dragging, dragArmed;
    boolean scrolling;          // scorrimento a 2 dita in corso
    int axis;                   // 0 libero, 1 solo orizzontale, 2 solo verticale (come sul Mac)
    float accX, accY;           // spostamento prima di decidere l'asse
    boolean zooming;            // pizzico a 2 dita
    boolean panning;            // gesto a 3, 4 o 5 dita
    int panFingers;
    float startSpread, lastSpread;
    int maxFingers;
    long gStart, lastScrollT, lastTapEnd;
    float vx, vy, lastTapX, lastTapY;

    float dp() { return getResources().getDisplayMetrics().density; }

    void addPointer(MotionEvent e, int i) {
        float x = e.getX(i), y = e.getY(i);
        pts.put(e.getPointerId(i), new float[]{ x, y, x, y });
    }

    boolean onPadTouch(MotionEvent e) {
        final float d = dp();
        final long now = e.getEventTime();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                stopMomentum();
                pts.clear();
                active = true; moved = false; dragging = false; dragArmed = false;
                scrolling = false; axis = 0; accX = accY = 0;
                zooming = false; panning = false;
                maxFingers = 1; gStart = now; vx = vy = 0; lastScrollT = now;
                addPointer(e, 0);
                if (now - lastTapEnd < DRAG_WINDOW
                        && Math.hypot(e.getX() - lastTapX, e.getY() - lastTapY) < DRAG_NEAR_DP * d) {
                    dragArmed = true;
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                addPointer(e, e.getActionIndex());
                maxFingers = Math.max(maxFingers, e.getPointerCount());
                startSpread = lastSpread = spread(e);
                // le dita non arrivano tutte insieme: un dito in più riavvia il riconoscimento
                if (e.getPointerCount() >= 3 && !panning) {
                    if (scrolling) send("s 0 0 e");
                    scrolling = false; zooming = false; axis = 0;
                }
                accX = accY = 0;
                break;

            case MotionEvent.ACTION_MOVE: {
                if (!active) break;
                float sdx = 0, sdy = 0; int n = 0;
                for (int i = 0; i < e.getPointerCount(); i++) {
                    float[] p = pts.get(e.getPointerId(i));
                    if (p == null) continue;
                    float x = e.getX(i), y = e.getY(i);
                    sdx += (x - p[0]) / d; sdy += (y - p[1]) / d;
                    p[0] = x; p[1] = y; n++;
                    if (Math.hypot(x - p[2], y - p[3]) > TAP_MOVE_DP * d) moved = true;
                }
                if (n == 0 || (sdx == 0 && sdy == 0)) break;
                if (maxFingers == 1 && e.getPointerCount() == 1) {
                    if (dragArmed && !dragging && moved) { dragging = true; send("d l n"); }
                    send(String.format(Locale.US, "m %.2f %.2f", sdx, sdy));
                } else if (e.getPointerCount() >= 3 && !scrolling && !zooming) {
                    // 3, 4 o 5 dita: il Mac decide cosa fare in base alle sue impostazioni del trackpad
                    float dx = sdx / n, dy = sdy / n;
                    if (!panning) {
                        accX += dx; accY += dy;
                        if (Math.hypot(accX, accY) < 4) break;
                        panning = true; panFingers = Math.min(5, e.getPointerCount());
                        send("p " + panFingers + " 0 0 b");
                        dx = accX; dy = accY;
                    }
                    lastSpread = spread(e);
                    send(String.format(Locale.US, "p %d %.2f %.2f c", panFingers, dx, dy));
                    // pizzico a 4-5 dita: il Mac reagisce appena si supera la soglia
                    if (panFingers >= 4 && startSpread > 0)
                        send(String.format(Locale.US, "z %d %.4f", panFingers, lastSpread / startSpread));
                } else if (e.getPointerCount() == 2 && !panning && maxFingers == 2 && (zooming || !scrolling)
                        && (zooming || isPinch(e, sdx / n, sdy / n))) {
                    // pizzico a 2 dita: zoom
                    float sp = spread(e);
                    if (!zooming) { zooming = true; lastSpread = startSpread; }
                    if (lastSpread > 0 && sp > 0) send(String.format(Locale.US, "z 2 %.4f", sp / lastSpread));
                    lastSpread = sp;
                } else if (e.getPointerCount() >= 2 && !panning && !zooming) {
                    float dx = sdx / n, dy = sdy / n;
                    String phase = "c";
                    if (!scrolling) {
                        // come il trackpad del Mac: all'inizio si blocca sull'asse dominante
                        accX += dx; accY += dy;
                        if (Math.hypot(accX, accY) < 4) break;
                        float ax = Math.abs(accX), ay = Math.abs(accY);
                        axis = ax > 1.8f * ay ? 1 : ay > 1.8f * ax ? 2 : 0;
                        dx = accX; dy = accY;
                        scrolling = true; phase = "b";
                    }
                    if (axis == 1) dy = 0;
                    if (axis == 2) dx = 0;
                    send(String.format(Locale.US, "s %.2f %.2f %s", dx, dy, phase));
                    float dt = Math.max(1, now - lastScrollT);
                    vx = 0.7f * vx + 0.3f * (dx / dt);
                    vy = 0.7f * vy + 0.3f * (dy / dt);
                    lastScrollT = now;
                }
                // dopo uno scroll a 2 dita il dito rimasto non muove il cursore
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
                pts.remove(e.getPointerId(e.getActionIndex()));
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!active) break;
                active = false;
                pts.clear();
                boolean quick = now - gStart < (maxFingers > 1 ? TAP_MS + 80 : TAP_MS);
                if (panning) {
                    send("p " + panFingers + " 0 0 e");
                } else if (zooming) {
                    // fine pizzico: niente clic né inerzia
                } else if (dragging) {
                    send("u l");
                } else if (!moved && quick && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (maxFingers == 1) {
                        // posizione del tocco (0..1): serve al Mac per il clic secondario nell'angolo
                        send(String.format(Locale.US, "c l %.3f %.3f",
                                e.getX() / Math.max(1, root.getWidth()), e.getY() / Math.max(1, root.getHeight())));
                        if (!dragArmed) { lastTapEnd = now; lastTapX = e.getX(); lastTapY = e.getY(); }
                        else lastTapEnd = 0;
                    } else if (maxFingers == 2) send("c r");
                    else send("c m");
                } else if (scrolling) {
                    send("s 0 0 e");
                    if (now - lastScrollT < 80) startMomentum(vx, vy);
                }
                break;
            }
        }
        return true;
    }

    /** Distanza media delle dita dal loro centro, in dp. */
    float spread(MotionEvent e) {
        int n = e.getPointerCount();
        float cx = 0, cy = 0;
        for (int i = 0; i < n; i++) { cx += e.getX(i); cy += e.getY(i); }
        cx /= n; cy /= n;
        float s = 0;
        for (int i = 0; i < n; i++) s += Math.hypot(e.getX(i) - cx, e.getY(i) - cy);
        return s / n / dp();
    }

    /** Le due dita si allontanano o avvicinano più di quanto si spostano insieme? */
    boolean isPinch(MotionEvent e, float dx, float dy) {
        float change = Math.abs(spread(e) - startSpread);
        accX += dx; accY += dy;
        return change > 14 && change > 1.5f * Math.hypot(accX, accY);
    }

    // inerzia dello scorrimento, come un vero trackpad
    Choreographer.FrameCallback momentum;

    void startMomentum(float startVx, float startVy) {
        if (axis == 1) startVy = 0;
        if (axis == 2) startVx = 0;
        if (Math.hypot(startVx, startVy) < 0.25) return;
        final float[] v = { startVx, startVy };
        final long[] last = { System.nanoTime() };
        final boolean[] first = { true };
        momentum = new Choreographer.FrameCallback() {
            @Override public void doFrame(long t) {
                if (momentum != this) return;
                float dt = Math.min(40, (t - last[0]) / 1e6f); last[0] = t;
                float decay = (float) Math.pow(0.95, dt / 16f);
                v[0] *= decay; v[1] *= decay;
                if (Math.hypot(v[0], v[1]) < 0.02) { momentum = null; send("s 0 0 me"); return; }
                send(String.format(Locale.US, "s %.2f %.2f %s", v[0] * dt, v[1] * dt, first[0] ? "mb" : "mc"));
                first[0] = false;
                Choreographer.getInstance().postFrameCallback(this);
            }
        };
        Choreographer.getInstance().postFrameCallback(momentum);
    }

    void stopMomentum() {
        if (momentum != null) {
            Choreographer.getInstance().removeFrameCallback(momentum);
            momentum = null;
            send("s 0 0 me");
        }
    }

    /** Riga per il registro del computer (se collegato). */
    void info(String s) {
        Link l = link;
        if (l != null) l.out.offer("i " + s);
    }

    void send(String cmd) {
        Link l = link;
        if (l != null && state != State.OFFLINE) l.out.offer(cmd);
    }

    // ------------------------------------------------------------------ connessione col Mac

    /** Collegamento TCP verso il Mac tramite `adb reverse` (localhost del telefono → Mac). */
    class Link extends Thread {
        final LinkedBlockingQueue<String> out = new LinkedBlockingQueue<>();
        volatile boolean running = true;
        volatile Socket socket;

        void shutdown() {
            running = false;
            interrupt();
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }

        @Override public void run() {
            while (running) {
                Thread writer = null;
                try (Socket s = new Socket()) {
                    socket = s;
                    s.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
                    s.setTcpNoDelay(true);
                    s.setSoTimeout(8000);   // il Mac scrive ogni 3 s: se tace, è scollegato
                    out.clear();
                    final OutputStream os = s.getOutputStream();
                    out.offer("auth " + prefs.getString("token", ""));
                    out.offer("i " + charger.describe());
                    out.offer("i " + Build.MANUFACTURER + " " + Build.MODEL + " · Android "
                            + Build.VERSION.RELEASE + " · OLED " + (oledAuto ? "rilevato" : "non rilevato"));
                    writer = new Thread(() -> writeLoop(os));
                    writer.start();
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while (running && (line = in.readLine()) != null) {
                        final String l = line.trim();
                        ui.post(() -> onMacLine(l));
                    }
                } catch (Exception ignored) {
                } finally {
                    if (writer != null) writer.interrupt();
                    socket = null;
                }
                ui.post(() -> { if (link == this) setState(MainActivity.State.OFFLINE); });
                if (!running) break;
                try { Thread.sleep(800); } catch (InterruptedException e) { break; }
            }
        }

        /** Invia i comandi, unendo i movimenti consecutivi per non accumulare ritardo. */
        void writeLoop(OutputStream os) {
            List<String> batch = new ArrayList<>();
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    batch.clear();
                    batch.add(out.take());
                    out.drainTo(batch);
                    StringBuilder sb = new StringBuilder();
                    String key = null; float ax = 0, ay = 0;
                    for (String c : batch) {
                        String[] p = c.split(" ");
                        String k = mergeKey(p);
                        if (k != null && k.equals(key)) {
                            ax += Float.parseFloat(p[at(k)]); ay += Float.parseFloat(p[at(k) + 1]);
                            continue;
                        }
                        if (key != null) { flush(sb, key, ax, ay); key = null; }
                        if (k != null) { key = k; ax = Float.parseFloat(p[at(k)]); ay = Float.parseFloat(p[at(k) + 1]); }
                        else sb.append(c).append('\n');
                    }
                    if (key != null) flush(sb, key, ax, ay);
                    os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (Exception ignored) {
                try { if (socket != null) socket.close(); } catch (Exception ignored2) {}
            }
        }

        /** Si uniscono solo i movimenti e gli scorrimenti "in corso"; inizio/fine restano separati. */
        String mergeKey(String[] p) {
            if (p[0].equals("m")) return "m";
            if (p[0].equals("s") && p.length > 3 && (p[3].equals("c") || p[3].equals("mc"))) return "s " + p[3];
            if (p[0].equals("p") && p.length > 4 && p[4].equals("c")) return "p " + p[1];
            return null;
        }

        /** Posizione dei due numeri nel comando ("p" ha prima il numero di dita). */
        int at(String key) { return key.startsWith("p") ? 2 : 1; }

        void flush(StringBuilder sb, String key, float x, float y) {
            if (key.equals("m")) sb.append(String.format(Locale.US, "m %.2f %.2f\n", x, y));
            else if (key.startsWith("p")) sb.append(String.format(Locale.US, "%s %.2f %.2f c\n", key, x, y));
            else sb.append(String.format(Locale.US, "s %.2f %.2f %s\n", x, y, key.substring(2)));
        }
    }
}
