package ir.smartremote.app.ir;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Locale;

/**
 * Ported directly from org.twinone.irremote.ir.SignalCorrector (divested-mobile/irremote).
 */
public class SignalCorrector {
    private static final String PREFS = "smart_remote_prefs";
    private static final String KEY_PULSE_FIX = "ir_pulse_fix";

    private final SharedPreferences prefs;

    public SignalCorrector(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_PULSE_FIX)) {
            boolean affected = isAffectedSamsung();
            prefs.edit().putBoolean(KEY_PULSE_FIX, affected).apply();
        }
    }

    private static boolean isAffectedSamsung() {
        String mfg = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase(Locale.ENGLISH) : "";
        if (!mfg.contains("samsung")) return false;
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.KITKAT) return false;
        try {
            int lastIdx = Build.VERSION.RELEASE.lastIndexOf(".");
            return Integer.parseInt(Build.VERSION.RELEASE.substring(lastIdx + 1)) < 3;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isPulseFixEnabled() {
        return prefs.getBoolean(KEY_PULSE_FIX, false);
    }

    public void setPulseFixEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PULSE_FIX, enabled).apply();
    }

    public int[] fix(int frequency, int[] patternInPulses) {
        if (isPulseFixEnabled()) {
            return patternInPulses;
        }
        int[] patternInUSecs = new int[patternInPulses.length];
        float pulsePeriod = (float) 1000000 / frequency;
        for (int i = 0; i < patternInPulses.length; i++) {
            int v = (int) (patternInPulses[i] * pulsePeriod);
            patternInUSecs[i] = Math.max(1, v);
        }
        return patternInUSecs;
    }
}
