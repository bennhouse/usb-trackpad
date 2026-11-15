package it.usbtrackpad;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Limitatore di carica, solo su telefoni con root.
 * Sopra il massimo (60%) ferma la ricarica; la riprende solo quando la batteria
 * scende al minimo (50%). Senza root non fa nulla: Android non permette alle app
 * di controllare la ricarica.
 *
 * Usa il comando del kernel che interrompe la ricarica: il nome cambia da telefono
 * a telefono, quindi prova i più diffusi.
 */
final class ChargeLimiter {

    interface Log { void line(String s); }

    /** Nodo del kernel, valore per "ricarica attiva", valore per "ricarica ferma". */
    private static final String[][] NODES = {
        { "/sys/class/power_supply/battery/battery_charging_enabled", "1", "0" }, // Qualcomm (Nexus, molti)
        { "/sys/class/power_supply/battery/charging_enabled",         "1", "0" },
        { "/sys/class/power_supply/battery/input_suspend",            "0", "1" }, // Qualcomm recenti, Pixel
        { "/sys/class/qcom-battery/input_suspend",                    "0", "1" },
        { "/sys/class/power_supply/battery/charge_disable",           "0", "1" },
        { "/sys/class/power_supply/battery/store_mode",               "0", "1" }, // Samsung
        { "/sys/class/power_supply/battery/batt_slate_mode",          "0", "1" }, // Samsung
        { "/sys/class/power_supply/battery/op_disable_charge",        "0", "1" }, // OnePlus
        { "/sys/class/power_supply/charger/charger_enabled",          "1", "0" },
        { "/sys/class/power_supply/battery/mmi_charging_enable",      "1", "0" }, // Motorola
    };

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Log log;

    private volatile boolean probed, available;
    private String[] node;            // nodo scelto
    private boolean paused;           // ricarica fermata da noi
    private int max = 60, min = 50;
    private boolean enabled = true;

    ChargeLimiter(Log log) { this.log = log; }

    /** Soglie dal computer: "charge 60 50" oppure "charge off". */
    void configure(boolean on, int maxPct, int minPct) {
        exec.execute(() -> {
            enabled = on && maxPct > minPct && maxPct <= 100 && minPct >= 0;
            if (enabled) { max = maxPct; min = minPct; }
            if (!enabled && paused) resume("limitatore disattivato");
        });
    }

    /** Da chiamare a ogni aggiornamento della batteria. */
    void onBattery(int pct) {
        if (pct < 0) return;
        exec.execute(() -> {
            if (!probe() || !enabled) return;
            if (!paused && pct >= max) {
                if (write(node[2])) { paused = true; log.line("🔋 Carica in pausa al " + pct + "% (riprende al " + min + "%)"); }
            } else if (paused && pct <= min) {
                resume("batteria al " + pct + "%");
            }
        });
    }

    /** Ripristina sempre la ricarica quando l'app non è in primo piano: mai un telefono che non carica più. */
    void release() {
        exec.execute(() -> { if (paused) resume("app chiusa"); });
    }

    /** Stato da mostrare nel registro del computer. */
    String describe() {
        if (!probed) return "limitatore di carica: in verifica";
        if (!available) return "limitatore di carica: non disponibile (serve il root)";
        return "limitatore di carica: attivo " + max + "/" + min + "% (" + node[0] + ")";
    }

    boolean isPaused() { return paused; }

    // ------------------------------------------------------------------

    private void resume(String why) {
        if (write(node[1])) { paused = false; log.line("🔌 Carica ripresa (" + why + ")"); }
    }

    private boolean probe() {
        if (probed) return available;
        probed = true;
        if (!hasSu()) return available = false;
        // trova il primo nodo presente sul telefono
        StringBuilder sh = new StringBuilder();
        for (String[] n : NODES) sh.append("[ -e ").append(n[0]).append(" ] && echo ").append(n[0]).append(" && exit 0; ");
        String found = su(sh.toString()).trim();
        for (String[] n : NODES) if (n[0].equals(found)) node = n;
        available = node != null;
        log.line(describe());
        return available;
    }

    private boolean write(String value) {
        if (node == null) return false;
        su("echo " + value + " > " + node[0]);
        return su("cat " + node[0]).trim().equals(value);
    }

    /** Cerca il binario su senza eseguirlo: sui telefoni senza root nessuna richiesta compare. */
    private static boolean hasSu() {
        String path = System.getenv("PATH");
        String dirs = (path == null ? "" : path) + ":/system/bin:/system/xbin:/sbin:/su/bin:/debug_ramdisk";
        for (String d : dirs.split(":")) if (!d.isEmpty() && new File(d, "su").exists()) return true;
        return false;
    }

    private static String su(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            StringBuilder out = new StringBuilder();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0) out.append(new String(buf, 0, n));
            p.waitFor();
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
