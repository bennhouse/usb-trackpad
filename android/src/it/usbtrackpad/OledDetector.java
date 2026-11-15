package it.usbtrackpad;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;

import java.util.Locale;

/**
 * Android non dice se lo schermo è OLED/AMOLED o LCD: lo stimiamo.
 * 1) Always-on display disponibile: quasi solo gli OLED lo hanno (su LCD consumerebbe troppo).
 * 2) Famiglie di modelli note per avere schermi OLED.
 * Se la stima sbaglia, dal Mac si può forzare con TRACKPAD_OLED=1 oppure 0.
 */
final class OledDetector {

    private OledDetector() {}

    static boolean isOled(Context ctx) {
        return hasAlwaysOnDisplay() || knownOledModel();
    }

    static boolean hasAlwaysOnDisplay() {
        try {
            Resources r = Resources.getSystem();
            int id = r.getIdentifier("config_dozeAlwaysOnDisplayAvailable", "bool", "android");
            return id != 0 && r.getBoolean(id);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean knownOledModel() {
        String brand = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        String model = Build.MODEL.toLowerCase(Locale.ROOT);

        // Google
        if (model.startsWith("pixel")) {
            return !model.contains("tablet") && !model.equals("pixel c");
        }
        if (model.equals("nexus 6") || model.equals("nexus 6p") || model.equals("galaxy nexus")) return true;

        // Samsung: Galaxy S, Note, Z, A medio-alti, M, Tab S
        if (brand.contains("samsung") && model.startsWith("sm-")) {
            String m = model.substring(3);
            if (m.startsWith("g") || m.startsWith("n") || m.startsWith("f") || m.startsWith("s")
                    || m.startsWith("e")) return true;
            if (m.matches("a[3-9]\\d.*")) return true;                 // A3x…A9x
            if (m.matches("a2[4-9]\\d.*")) return true;                // A24…A29 (AMOLED)
            if (m.matches("m[3-5]\\d.*")) return true;                 // M3x…M5x
            if (m.matches("(t8[0-9]\\d|t9\\d\\d|x[7-9]\\d\\d).*")) return true; // Tab S
            return false;
        }

        // OnePlus: tutti OLED tranne alcuni Nord N e i tablet
        if (brand.contains("oneplus")) {
            return !(model.contains("n10") || model.contains("n100") || model.contains("n200")
                    || model.contains("pad") || model.startsWith("be2013") || model.startsWith("be2011"));
        }

        // Altre famiglie quasi sempre OLED
        if (brand.contains("nothing")) return true;                                    // Nothing Phone
        if (brand.contains("motorola") && model.contains("edge")) return true;         // Motorola Edge
        if (brand.contains("sony") && model.matches("(xq-|so-5|so-4|sog).*")) return true; // Xperia 1/5/10
        if ((brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco"))
                && model.matches(".*(mi 1[0-4]|xiaomi 1[1-5]|note 1[0-4] pro|poco f[3-7]).*")) return true;
        if (brand.contains("huawei") && model.matches(".*(p[3-6]0|mate [2-6]0).*")) return true;
        if (brand.contains("asus") && model.contains("rog")) return true;
        return false;
    }
}
