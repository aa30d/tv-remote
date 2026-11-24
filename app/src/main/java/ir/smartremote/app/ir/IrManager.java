package ir.smartremote.app.ir;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.hardware.ConsumerIrManager;
import android.hardware.ConsumerIrManager.CarrierFrequencyRange;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native IR Manager using divested-mobile/irremote's exact assets/db/ database,
 * Signal/SignalFactory/SignalCorrector pipeline, and KitKatTransmitter + TransmitOnTouchListener mechanics.
 */
public class IrManager {
    private static final String TAG = "IrManager";
    private static final String PREFS = "smart_remote_prefs";
    private static final String KEY_CATEGORY = "ir_category";
    private static final String KEY_BRAND = "ir_brand";

    private static final int LONG_PRESS_REPEAT_DELAY_MS = 250;
    private static final int REPEAT_PERIOD_MS = 200;
    private static final int INTER_DIGIT_GAP_MS = 360;
    // If another channel is dialed within this window after the previous one,
    // the previous channel entry/banner is still open, so EXIT is sent first.
    private static final int DIAL_EXIT_WINDOW_MS = 6000;

    // Button IDs matching org.twinone.irremote.components.Button
    public static final int ID_POWER = 1;
    public static final int ID_VOL_UP = 4;
    public static final int ID_VOL_DOWN = 5;
    public static final int ID_CH_UP = 6;
    public static final int ID_CH_DOWN = 7;
    public static final int ID_NAV_UP = 8;
    public static final int ID_NAV_DOWN = 9;
    public static final int ID_NAV_LEFT = 10;
    public static final int ID_NAV_RIGHT = 11;
    public static final int ID_NAV_OK = 12;
    public static final int ID_BACK = 13;
    public static final int ID_MUTE = 14;
    public static final int ID_MENU = 15;
    public static final int ID_DIGIT_0 = 16;
    public static final int ID_DIGIT_1 = 17;
    public static final int ID_DIGIT_2 = 18;
    public static final int ID_DIGIT_3 = 19;
    public static final int ID_DIGIT_4 = 20;
    public static final int ID_DIGIT_5 = 21;
    public static final int ID_DIGIT_6 = 22;
    public static final int ID_DIGIT_7 = 23;
    public static final int ID_DIGIT_8 = 24;
    public static final int ID_DIGIT_9 = 25;
    public static final int ID_GUIDE = 27;
    public static final int ID_SMART = 28;
    public static final int ID_CLEAR = 30;
    public static final int ID_EXIT = 31;
    public static final int ID_HOME = 61;
    public static final int ID_LIST = 62;
    public static final int ID_SETTING = 68;

    private static final Map<Integer, int[]> FALLBACKS = new HashMap<>();
    static {
        FALLBACKS.put(ID_HOME, new int[]{ID_HOME, ID_SMART, ID_MENU});
        FALLBACKS.put(ID_LIST, new int[]{ID_LIST, ID_GUIDE, ID_MENU});
        FALLBACKS.put(ID_SETTING, new int[]{ID_SETTING, ID_MENU, ID_SMART});
        FALLBACKS.put(ID_BACK, new int[]{ID_BACK, ID_EXIT, ID_CLEAR});
        FALLBACKS.put(ID_EXIT, new int[]{ID_EXIT, ID_BACK, ID_CLEAR});
        FALLBACKS.put(ID_CH_UP, new int[]{ID_CH_UP, 40, ID_NAV_UP});
        FALLBACKS.put(ID_CH_DOWN, new int[]{ID_CH_DOWN, 41, ID_NAV_DOWN});
    }

    public interface IrFeedbackListener {
        void onIrTransmitted();
        void onIrError(String message);
    }

    public static class BrandItem {
        public final String category;
        public final String name;
        public final int buttonCount;

        public BrandItem(String category, String name, int buttonCount) {
            this.category = category;
            this.name = name;
            this.buttonCount = buttonCount;
        }
    }

    private final Context context;
    private final AssetManager assets;
    private final SharedPreferences prefs;
    private final ConsumerIrManager consumerIr;
    private final SignalCorrector signalCorrector;
    private final HandlerThread irThread;
    private final Handler irHandler;

    // category -> brand -> buttonId -> parsed Signal
    private final Map<String, Map<String, Map<Integer, Signal>>> cache = new HashMap<>();
    private final Map<String, List<BrandItem>> brandIndex = new HashMap<>();

    private String selectedCategory = "TV";
    private String selectedBrand = "Samsung";
    private IrFeedbackListener feedbackListener;

    private volatile boolean repeatActive = false;
    private volatile boolean transmittedOnceInHold = false;
    private volatile Signal activeHoldSignal = null;
    private final AtomicInteger dialGeneration = new AtomicInteger(0);
    // elapsedRealtime() of the last dialCode() call; 0 = no dial yet.
    private volatile long lastDialAtMs = 0;

    private final Runnable repeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!repeatActive || activeHoldSignal == null) return;
            transmitSignalSync(activeHoldSignal);
            transmittedOnceInHold = true;
            if (repeatActive) {
                irHandler.postDelayed(this, REPEAT_PERIOD_MS);
            }
        }
    };

    public IrManager(Context context) {
        this.context = context.getApplicationContext();
        this.assets = this.context.getAssets();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.consumerIr = (ConsumerIrManager) this.context.getSystemService(Context.CONSUMER_IR_SERVICE);
        this.signalCorrector = new SignalCorrector(this.context);

        this.irThread = new HandlerThread("SmartRemote-IR");
        this.irThread.start();
        this.irHandler = new Handler(this.irThread.getLooper());

        indexAllBrands();

        String savedCat = prefs.getString(KEY_CATEGORY, "TV");
        String savedBrand = prefs.getString(KEY_BRAND, "Samsung");
        if (hasBrand(savedCat, savedBrand)) {
            selectedCategory = savedCat;
            selectedBrand = savedBrand;
        }
        // Preload currently selected brand signals into memory immediately
        loadBrandSignals(selectedCategory, selectedBrand);
    }

    public void setFeedbackListener(IrFeedbackListener listener) {
        this.feedbackListener = listener;
    }

    public boolean hasIrEmitter() {
        try {
            return consumerIr != null && consumerIr.hasIrEmitter();
        } catch (Exception e) {
            return false;
        }
    }

    public String getSelectedCategory() {
        return selectedCategory;
    }

    public String getSelectedBrand() {
        return selectedBrand;
    }

    public SignalCorrector getSignalCorrector() {
        return signalCorrector;
    }

    public List<BrandItem> getBrands(String category) {
        List<BrandItem> list = brandIndex.get(category);
        return list != null ? list : Collections.emptyList();
    }

    private boolean hasBrand(String category, String brand) {
        for (BrandItem item : getBrands(category)) {
            if (item.name.equals(brand)) return true;
        }
        return false;
    }

    public void setSelectedModel(String category, String brand) {
        if (!hasBrand(category, brand)) return;
        selectedCategory = category;
        selectedBrand = brand;
        lastDialAtMs = 0; // new model: next dial is a fresh session, no EXIT prefix
        prefs.edit()
                .putString(KEY_CATEGORY, category)
                .putString(KEY_BRAND, brand)
                .apply();
        loadBrandSignals(category, brand);
    }

    private void indexAllBrands() {
        for (String cat : new String[]{"TV", "Cable"}) {
            List<BrandItem> items = new ArrayList<>();
            try {
                String[] brands = assets.list("db/" + cat);
                if (brands != null) {
                    for (String brand : brands) {
                        String[] files = assets.list("db/" + cat + "/" + brand);
                        int validCount = 0;
                        if (files != null) {
                            for (String f : files) {
                                if (f.startsWith("b_") && f.endsWith(".button")) {
                                    validCount++;
                                }
                            }
                        }
                        if (validCount > 0) {
                            items.add(new BrandItem(cat, brand, validCount));
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to index category " + cat, e);
            }
            Collections.sort(items, (a, b) -> a.name.compareToIgnoreCase(b.name));
            brandIndex.put(cat, items);
        }
    }

    private synchronized Map<Integer, Signal> loadBrandSignals(String category, String brand) {
        Map<String, Map<Integer, Signal>> catMap = cache.get(category);
        if (catMap == null) {
            catMap = new HashMap<>();
            cache.put(category, catMap);
        }
        Map<Integer, Signal> existing = catMap.get(brand);
        if (existing != null) return existing;

        Map<Integer, Signal> signals = new HashMap<>();
        String dir = "db/" + category + "/" + brand;
        try {
            String[] files = assets.list(dir);
            if (files != null) {
                for (String name : files) {
                    if (!name.startsWith("b_") || !name.endsWith(".button")) continue;
                    try {
                        int id = Integer.parseInt(name.substring(2, name.length() - 7));
                        String raw = readAssetUtf8(dir + "/" + name);
                        Signal sig = SignalFactory.parse(raw);
                        signals.put(id, sig);
                    } catch (Exception ignored) {
                        // Skip malformed or zero-carrier placeholder files
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed loading brand " + dir, e);
        }
        catMap.put(brand, signals);
        return signals;
    }

    private String readAssetUtf8(String path) throws Exception {
        try (InputStream is = assets.open(path)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public Signal resolveSignal(int buttonId) {
        Map<Integer, Signal> brandMap = loadBrandSignals(selectedCategory, selectedBrand);
        int[] candidates = FALLBACKS.get(buttonId);
        if (candidates != null) {
            for (int cid : candidates) {
                Signal s = brandMap.get(cid);
                if (s != null) return s;
            }
            return null;
        }
        return brandMap.get(buttonId);
    }

    private int chooseSupportedFrequency(int requestedFreq) {
        if (consumerIr == null) return requestedFreq;
        try {
            CarrierFrequencyRange[] ranges = consumerIr.getCarrierFrequencies();
            if (ranges == null || ranges.length == 0) return requestedFreq;
            int closest = requestedFreq;
            int minDist = Integer.MAX_VALUE;
            for (CarrierFrequencyRange r : ranges) {
                int minF = r.getMinFrequency();
                int maxF = r.getMaxFrequency();
                if (requestedFreq >= minF && requestedFreq <= maxF) {
                    return requestedFreq;
                }
                int clamped = Math.max(minF, Math.min(maxF, requestedFreq));
                int dist = Math.abs(clamped - requestedFreq);
                if (dist < minDist) {
                    minDist = dist;
                    closest = clamped;
                }
            }
            return closest;
        } catch (Exception e) {
            return requestedFreq;
        }
    }

    private synchronized boolean transmitSignalSync(Signal rawSignal) {
        if (rawSignal == null) return false;
        if (!hasIrEmitter()) {
            notifyError("گوشی شما فرستنده مادون قرمز (IR) ندارد");
            return false;
        }
        try {
            Signal fixed = rawSignal.clone().fix(signalCorrector);
            int freq = chooseSupportedFrequency(fixed.getFrequency());
            int[] pattern = fixed.getPattern();
            consumerIr.transmit(freq, pattern);
            if (feedbackListener != null) {
                feedbackListener.onIrTransmitted();
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "IR transmit error", e);
            notifyError("خطا در ارسال سیگنال IR: " + e.getMessage());
            return false;
        }
    }

    private void notifyError(String msg) {
        if (feedbackListener != null) {
            feedbackListener.onIrError(msg);
        }
    }

    /**
     * Transmits a single button press once.
     */
    public boolean sendButtonOnce(int buttonId) {
        if (!hasIrEmitter()) {
            notifyError("گوشی شما فرستنده مادون قرمز (IR) ندارد");
            return false;
        }
        final Signal sig = resolveSignal(buttonId);
        if (sig == null) {
            notifyError("این کلید برای مدل " + selectedBrand + " تعریف نشده است");
            return false;
        }
        irHandler.post(() -> transmitSignalSync(sig));
        return true;
    }

    /**
     * Exact native touch listener modeled after divested-mobile/irremote's TransmitOnTouchListener:
     * - Short tap sends signal once immediately on ACTION_UP
     * - Long hold (>250ms) repeats continuously every 200ms until released
     * Views using this listener should also set an OnClickListener that calls
     * {@link #sendButtonOnce(int)}: touch taps then send via performClick(),
     * and TV D-Pad DPAD_CENTER/ENTER (which triggers performClick()) works too.
     */
    public View.OnTouchListener createButtonTouchListener(final int buttonId, final boolean allowRepeat) {
        return new View.OnTouchListener() {
            private boolean fingerDown = false;
            private Runnable longPressTrigger;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        fingerDown = true;
                        v.setPressed(true);
                        transmittedOnceInHold = false;
                        if (allowRepeat) {
                            final Signal sig = resolveSignal(buttonId);
                            if (sig != null && hasIrEmitter()) {
                                activeHoldSignal = sig;
                                longPressTrigger = () -> {
                                    if (fingerDown) {
                                        if (v.getParent() != null) {
                                            v.getParent().requestDisallowInterceptTouchEvent(true);
                                        }
                                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                                        startRepeating(sig);
                                    }
                                };
                                v.removeCallbacks(longPressTrigger);
                                v.postDelayed(longPressTrigger, LONG_PRESS_REPEAT_DELAY_MS);
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (!fingerDown) return false;
                        int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                        float x = event.getX();
                        float y = event.getY();
                        if (x < -slop || x > v.getWidth() + slop || y < -slop || y > v.getHeight() + slop) {
                            cancelHold(v, false);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (fingerDown) {
                            // Short tap only: after a long-press repeat the signal
                            // was already sent, so no extra click (and no double send).
                            if (!transmittedOnceInHold) v.performClick();
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            cancelHold(v, true);
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        cancelHold(v, false);
                        return true;
                }
                return false;
            }

            private void cancelHold(View v, boolean transmitIfNotYetSent) {
                fingerDown = false;
                v.setPressed(false);
                if (longPressTrigger != null) {
                    v.removeCallbacks(longPressTrigger);
                    longPressTrigger = null;
                }
                boolean alreadySent = transmittedOnceInHold;
                stopRepeating();
                // Clickable views (with an OnClickListener, e.g. for TV D-Pad
                // DPAD_CENTER which calls performClick) already sent above via
                // performClick(); only send here for touch-only views.
                if (transmitIfNotYetSent && !alreadySent && !v.isClickable()) {
                    sendButtonOnce(buttonId);
                }
            }
        };
    }

    private void startRepeating(Signal sig) {
        stopRepeating();
        activeHoldSignal = sig;
        repeatActive = true;
        irHandler.post(repeatRunnable);
    }

    public void stopRepeating() {
        repeatActive = false;
        irHandler.removeCallbacks(repeatRunnable);
        activeHoldSignal = null;
    }

    /**
     * Sequentially dials every digit of a channel code with a clean 360ms silent gap AFTER each burst.
     * Sequence: [EXIT] digits... [OK]
     * - OK is appended automatically so the TV tunes the channel immediately.
     * - When channels are dialed back-to-back (within {@link #DIAL_EXIT_WINDOW_MS} of the
     *   previous dial), EXIT is prepended first to close the previous channel
     *   entry/banner before the new digits are sent.
     * EXIT/OK are silently skipped if the selected brand has no such key.
     */
    public boolean dialCode(String rawCode) {
        if (!hasIrEmitter()) {
            notifyError("گوشی شما فرستنده مادون قرمز (IR) ندارد");
            return false;
        }
        String digits = normalizeDigits(rawCode);
        final List<Signal> digitSeq = new ArrayList<>();
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c >= '0' && c <= '9') {
                Signal s = resolveSignal(ID_DIGIT_0 + (c - '0'));
                if (s == null) {
                    notifyError("کلیدهای عددی برای مدل " + selectedBrand + " موجود نیست");
                    return false;
                }
                digitSeq.add(s);
            }
        }
        if (digitSeq.isEmpty()) {
            notifyError("کد عددی برای این شبکه ثبت نشده است");
            return false;
        }

        final List<Signal> seq = new ArrayList<>();
        long now = SystemClock.elapsedRealtime();
        if (lastDialAtMs != 0 && now - lastDialAtMs < DIAL_EXIT_WINDOW_MS) {
            Signal exit = resolveSignal(ID_EXIT);
            if (exit != null) seq.add(exit);
        }
        lastDialAtMs = now;
        seq.addAll(digitSeq);
        Signal ok = resolveSignal(ID_NAV_OK);
        if (ok != null) seq.add(ok);

        final int gen = dialGeneration.incrementAndGet();
        irHandler.post(() -> {
            for (int i = 0; i < seq.size(); i++) {
                if (dialGeneration.get() != gen) return;
                transmitSignalSync(seq.get(i));
                if (i < seq.size() - 1) {
                    try {
                        Thread.sleep(INTER_DIGIT_GAP_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        });
        return true;
    }

    public static String normalizeDigits(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= '\u06F0' && c <= '\u06F9') {
                sb.append((char) ('0' + (c - '\u06F0')));
            } else if (c >= '\u0660' && c <= '\u0669') {
                sb.append((char) ('0' + (c - '\u0660')));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public void release() {
        stopRepeating();
        dialGeneration.incrementAndGet();
        irHandler.removeCallbacksAndMessages(null);
        irThread.quitSafely();
    }
}
