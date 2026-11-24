package ir.smartremote.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ir.smartremote.app.ir.IrManager;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends AppCompatActivity implements ChannelAdapter.Listener {
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "smart_remote_native_prefs";
    private static final String KEY_CHANNEL_CODES = "channel_codes_json";
    private static final String KEY_DEFAULT_QUALITY = "default_stream_quality";
    private static final String KEY_TV_FOCUS_MODE = "tv_focus_mode"; // auto | on | off
    private static final String KEY_FRAME_SOURCE = "frame_source"; // telewebion | sepehr

    public static final String QUALITY_ADAPTIVE = "adaptive";
    public static final String QUALITY_HIGHEST = "highest";
    public static final String QUALITY_LOWEST = "lowest";

    private static final String STREAM_UA = "Mozilla/5.0 (Android 12; Mobile; rv:161.0) Gecko/161.0 Firefox/161.0";

    private SharedPreferences prefs;
    private IrManager irManager;
    private SepehrApiClient sepehrApi;
    private ImageCacheManager imageCache;
    private FrameExtractor frameExtractor;
    private final ExecutorService bgPool = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Channel state (keyed by Sepehr UID)
    private final List<ChannelItem> channels = new ArrayList<>();
    private final Map<String, String> savedCodes = new HashMap<>();
    private ChannelAdapter channelAdapter;
    private boolean isRefreshing = false;
    private boolean isCapturingFrames = false;
    private String frameProgressText = null;
    private String frameSource = "telewebion";

    // Header UI (buttons moved from the old RecyclerView header item)
    private TextView btnRefresh;
    private TextView btnCaptureFrames;
    private TextView btnEditCodes;
    private TextView btnSelectTvModel;
    private TextView btnDefaultQuality;
    private View btnHeaderSettings;
    private View headerOptionsContainer;
    private CheckBox chkTvFocus;
    private TextView btnFrameSourceTelewebion;
    private TextView btnFrameSourceSepehr;

    // Player UI & ExoPlayer state
    private FrameLayout stickyPlayerSlot;
    private FrameLayout playerAspectBox;
    private FrameLayout fullscreenContainer;
    private PlayerView exoPlayerView;
    private View playerLoadingOverlay;
    private View playerErrorOverlay;
    private TextView txtPlayerError;
    private TextView txtPlayerTitle;
    private ImageButton btnPlayerCenterPlay;
    private ImageButton btnPlayerPlayPause;
    private ImageButton btnPlayerMute;
    private ImageButton btnPlayerFullscreen;
    private TextView btnPlayerQuality;
    private View playerControlsBar;

    private ExoPlayer exoPlayer;
    private DefaultTrackSelector trackSelector;
    private boolean isPlayerOpen = false;
    private boolean isFullscreen = false;
    private boolean isMuted = false;
    private boolean controlsVisible = true;
    private int currentChannelIndex = -1;
    private String currentStreamSource = "telewebion";
    private String currentStreamUrl = "";
    private String defaultQualityMode = QUALITY_ADAPTIVE;
    private boolean appliedDefaultQualityForCurrentStream = false;
    private String manualQualityLabel = null; // null = using default / adaptive

    // Remote Overlay UI
    private View remotePanelWrapper;
    private ViewPager2 remoteViewPager;
    private View dotPage1;
    private View dotPage2;
    private View irLedDot;
    private boolean isRemotePanelOpen = false;

    // Toast UI
    private View toastChip;
    private ImageView toastIcon;
    private TextView toastText;
    private Runnable hideToastRunnable;
    private Runnable hideControlsRunnable;

    // File import/export launchers (SAF)
    private String pendingExportJson = null;
    private EditCodesCallback activeEditCodesCallback = null;

    private interface EditCodesCallback {
        void onCodesImported(Map<String, String> imported);
    }

    private final ActivityResultLauncher<Intent> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && pendingExportJson != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                            if (os != null) {
                                os.write(pendingExportJson.getBytes(StandardCharsets.UTF_8));
                                showToast("فایل کد کانال‌ها با موفقیت ذخیره شد", true);
                            }
                        } catch (Exception e) {
                            showToast("خطا در ذخیره فایل", false);
                        }
                    }
                }
                pendingExportJson = null;
            });

    private final ActivityResultLauncher<Intent> importLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        try (InputStream is = getContentResolver().openInputStream(uri)) {
                            if (is != null) {
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                byte[] buf = new byte[4096];
                                int n;
                                while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                                JSONObject obj = new JSONObject(new String(baos.toByteArray(), StandardCharsets.UTF_8));
                                Map<String, String> imported = new HashMap<>();
                                Iterator<String> keys = obj.keys();
                                while (keys.hasNext()) {
                                    String k = keys.next();
                                    imported.put(k, obj.optString(k, ""));
                                }
                                if (activeEditCodesCallback != null) {
                                    activeEditCodesCallback.onCodesImported(imported);
                                }
                                showToast("کدها از فایل وارد شدند (برای ثبت ذخیره کنید)", true);
                            }
                        } catch (Exception e) {
                            showToast("فرمت فایل انتخابی معتبر نیست", false);
                        }
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        defaultQualityMode = prefs.getString(KEY_DEFAULT_QUALITY, QUALITY_ADAPTIVE);
        frameSource = prefs.getString(KEY_FRAME_SOURCE, "telewebion");
        loadSavedCodes();

        irManager = new IrManager(this);
        sepehrApi = new SepehrApiClient(this);
        imageCache = new ImageCacheManager(this);
        frameExtractor = new FrameExtractor(this, imageCache);

        bindViews();
        setupHeaderButtons();
        setupIrCallbacks();
        setupRemoteOverlay();
        setupPlayer();
        setupChannelsGrid();
        applyTvFocusMode();

        // Load cached/seed channels immediately, then refresh live EPG in background
        String cachedJson = sepehrApi.getCachedOrSeedChannelsJson();
        List<ChannelItem> initial = SepehrApiClient.parseChannels(cachedJson, savedCodes, false);
        channels.clear();
        channels.addAll(initial);
        channelAdapter.setChannels(channels);
        syncHeaderState();

        refreshChannelsFromApi(false, true);

        // TV (D-Pad): start with focus inside the channels grid so arrow keys
        // navigate immediately without any touch. Header buttons are above the
        // grid in the AppBar and reachable with the arrows too.
        if (resolveTvFocusEnabled()) {
            getWindow().getDecorView().post(() -> {
                View rv = findViewById(R.id.channelsRecycler);
                if (rv != null) rv.requestFocus();
            });
        }
    }

    private void bindViews() {
        btnRefresh = findViewById(R.id.btnRefresh);
        btnCaptureFrames = findViewById(R.id.btnCaptureFrames);
        btnEditCodes = findViewById(R.id.btnEditCodes);
        btnSelectTvModel = findViewById(R.id.btnSelectTvModel);
        btnDefaultQuality = findViewById(R.id.btnDefaultQuality);
        btnHeaderSettings = findViewById(R.id.btnHeaderSettings);
        headerOptionsContainer = findViewById(R.id.headerOptionsContainer);
        chkTvFocus = findViewById(R.id.chkTvFocus);
        btnFrameSourceTelewebion = findViewById(R.id.btnFrameSourceTelewebion);
        btnFrameSourceSepehr = findViewById(R.id.btnFrameSourceSepehr);

        stickyPlayerSlot = findViewById(R.id.stickyPlayerSlot);
        playerAspectBox = findViewById(R.id.playerAspectBox);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        exoPlayerView = findViewById(R.id.exoPlayerView);
        playerLoadingOverlay = findViewById(R.id.playerLoadingOverlay);
        playerErrorOverlay = findViewById(R.id.playerErrorOverlay);
        txtPlayerError = findViewById(R.id.txtPlayerError);
        txtPlayerTitle = findViewById(R.id.txtPlayerTitle);
        btnPlayerCenterPlay = findViewById(R.id.btnPlayerCenterPlay);
        btnPlayerPlayPause = findViewById(R.id.btnPlayerPlayPause);
        btnPlayerMute = findViewById(R.id.btnPlayerMute);
        btnPlayerFullscreen = findViewById(R.id.btnPlayerFullscreen);
        btnPlayerQuality = findViewById(R.id.btnPlayerQuality);
        playerControlsBar = findViewById(R.id.playerControlsBar);

        remotePanelWrapper = findViewById(R.id.remotePanelWrapper);
        remoteViewPager = findViewById(R.id.remoteViewPager);
        dotPage1 = findViewById(R.id.dotPage1);
        dotPage2 = findViewById(R.id.dotPage2);
        irLedDot = findViewById(R.id.irLedDot);

        toastChip = findViewById(R.id.toastChip);
        toastIcon = findViewById(R.id.toastIcon);
        toastText = findViewById(R.id.toastText);

        // Make portrait player box true 16:9 height based on screen width
        playerAspectBox.post(() -> {
            if (!isFullscreen) {
                int w = getResources().getDisplayMetrics().widthPixels;
                int h = Math.round(w * 9f / 16f);
                ViewGroup.LayoutParams lp = playerAspectBox.getLayoutParams();
                if (lp.height != h) {
                    lp.height = h;
                    playerAspectBox.setLayoutParams(lp);
                }
            }
        });
    }

    private void setupHeaderButtons() {
        btnRefresh.setOnClickListener(v -> onRefreshClicked());
        btnCaptureFrames.setOnClickListener(v -> onCaptureFramesClicked());
        btnEditCodes.setOnClickListener(v -> onEditCodesClicked());
        btnSelectTvModel.setOnClickListener(v -> onSelectTvModelClicked());
        btnDefaultQuality.setOnClickListener(v -> onDefaultQualityClicked());
        btnHeaderSettings.setOnClickListener(v -> toggleHeaderOptions());
        chkTvFocus.setOnCheckedChangeListener((buttonView, isChecked) ->
                setTvFocusMode(isChecked ? "on" : "off"));
        btnFrameSourceTelewebion.setOnClickListener(v -> setFrameSource("telewebion"));
        btnFrameSourceSepehr.setOnClickListener(v -> setFrameSource("sepehr"));
    }

    private void toggleHeaderOptions() {
        boolean show = headerOptionsContainer.getVisibility() != View.VISIBLE;
        headerOptionsContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && resolveTvFocusEnabled()) {
            btnEditCodes.requestFocus();
        }
    }

    private void configureDialogFocus(View view) {
        if (!resolveTvFocusEnabled()) clearFocusRings(view);
    }

    static void clearFocusRings(View view) {
        if (view.getId() == R.id.dialArea) {
            // Keep its selectable foreground ripple; only remove the TV ring background.
            view.setBackgroundResource(android.R.color.transparent);
        } else if (view.isFocusable()) {
            view.setForeground(null);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                clearFocusRings(group.getChildAt(i));
            }
        }
    }

    // ==================== TV FOCUS MODE (D-PAD) ====================
    // Focus ring + D-Pad navigation are only for TV. Auto-detected via
    // leanback feature / TV UI mode; the header checkbox overrides it.
    // When off, focus is blocked on the whole activity root (touch and
    // dialogs/keyboard are unaffected), so no focus ring ever shows.

    private boolean isTvDevice() {
        try {
            if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true;
        } catch (Exception ignored) {
        }
        try {
            int modeType = getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
            if (modeType == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean resolveTvFocusEnabled() {
        if (!isTvDevice()) return false;
        String mode = prefs.getString(KEY_TV_FOCUS_MODE, "auto");
        if ("on".equals(mode)) return true;
        if ("off".equals(mode)) return false;
        return true;
    }

    private void applyTvFocusMode() {
        boolean enabled = resolveTvFocusEnabled();
        View root = findViewById(R.id.rootContainer);
        if (root instanceof ViewGroup) {
            ((ViewGroup) root).setDescendantFocusability(enabled
                    ? ViewGroup.FOCUS_AFTER_DESCENDANTS
                    : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            // On phones, remove TV-specific focus decorations as well as blocking
            // D-Pad focus; preserve normal touch feedback where possible.
            if (!isTvDevice()) clearFocusRings(root);
        }
        if (!enabled && getCurrentFocus() != null) {
            getCurrentFocus().clearFocus();
        }
        syncTvFocusToggle();
    }

    private void setTvFocusMode(String mode) {
        prefs.edit().putString(KEY_TV_FOCUS_MODE, mode).apply();
        applyTvFocusMode();
    }

    private void syncTvFocusToggle() {
        if (chkTvFocus == null) return;
        // Detection is available on Android TV; keep the override off phones.
        chkTvFocus.setVisibility(isTvDevice() ? View.VISIBLE : View.GONE);
        boolean enabled = resolveTvFocusEnabled();
        if (chkTvFocus.isChecked() != enabled) {
            chkTvFocus.setChecked(enabled);
        }
    }

    // ==================== FRAME CAPTURE SOURCE ====================

    private void setFrameSource(String source) {
        frameSource = source;
        prefs.edit().putString(KEY_FRAME_SOURCE, source).apply();
        syncFrameSourceButtons();
        showToast("منبع عکس اولین فریم: " + ("sepehr".equals(source) ? "سپهر" : "تلوبیون"), true);
    }

    private void syncFrameSourceButtons() {
        if (btnFrameSourceTelewebion == null || btnFrameSourceSepehr == null) return;
        boolean tele = !"sepehr".equals(frameSource);
        btnFrameSourceTelewebion.setBackgroundResource(tele ? R.drawable.bg_sheet_btn_cyan : R.drawable.bg_sheet_btn_white);
        btnFrameSourceTelewebion.setTextColor(tele ? 0xFFFFFFFF : 0xFF1F2937);
        btnFrameSourceSepehr.setBackgroundResource(!tele ? R.drawable.bg_sheet_btn_cyan : R.drawable.bg_sheet_btn_white);
        btnFrameSourceSepehr.setTextColor(!tele ? 0xFFFFFFFF : 0xFF1F2937);
    }

    private void setupIrCallbacks() {
        irManager.setFeedbackListener(new IrManager.IrFeedbackListener() {
            @Override
            public void onIrTransmitted() {
                mainHandler.post(() -> flashIrLed());
            }

            @Override
            public void onIrError(String message) {
                mainHandler.post(() -> showToast(message, false));
            }
        });
    }

    private void flashIrLed() {
        irLedDot.animate().cancel();
        irLedDot.setAlpha(1f);
        irLedDot.animate().alpha(0f).setDuration(240).start();
    }

    private void setupRemoteOverlay() {
        // Bottom always-visible bar buttons
        ImageButton btnMenu = findViewById(R.id.btnRemoteMenu);
        ImageButton btnVolDown = findViewById(R.id.btnRemoteVolDown);
        ImageButton btnVolUp = findViewById(R.id.btnRemoteVolUp);
        ImageButton btnPower = findViewById(R.id.btnRemotePower);

        btnMenu.setOnClickListener(v -> toggleRemotePanel());
        btnVolDown.setOnTouchListener(irManager.createButtonTouchListener(IrManager.ID_VOL_DOWN, true));
        btnVolUp.setOnTouchListener(irManager.createButtonTouchListener(IrManager.ID_VOL_UP, true));
        btnPower.setOnTouchListener(irManager.createButtonTouchListener(IrManager.ID_POWER, false));
        // TV (D-Pad): DPAD_CENTER/ENTER triggers performClick(), not touch.
        btnVolDown.setOnClickListener(v -> irManager.sendButtonOnce(IrManager.ID_VOL_DOWN));
        btnVolUp.setOnClickListener(v -> irManager.sendButtonOnce(IrManager.ID_VOL_UP));
        btnPower.setOnClickListener(v -> irManager.sendButtonOnce(IrManager.ID_POWER));

        // 2-page ViewPager2 for numpad + D-pad
        remoteViewPager.setAdapter(new RemotePagerAdapter(irManager));
        remoteViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                dotPage1.setAlpha(position == 0 ? 1f : 0.35f);
                dotPage2.setAlpha(position == 1 ? 1f : 0.35f);
            }
        });
    }

    private void toggleRemotePanel() {
        isRemotePanelOpen = !isRemotePanelOpen;
        if (isRemotePanelOpen) {
            remotePanelWrapper.setVisibility(View.VISIBLE);
            remotePanelWrapper.setAlpha(0f);
            remotePanelWrapper.setTranslationY(24f);
            remotePanelWrapper.animate().alpha(1f).translationY(0f).setDuration(180).start();
        } else {
            remotePanelWrapper.animate().alpha(0f).translationY(20f).setDuration(140)
                    .withEndAction(() -> remotePanelWrapper.setVisibility(View.GONE)).start();
        }
    }

    private void setupChannelsGrid() {
        RecyclerView rv = findViewById(R.id.channelsRecycler);
        channelAdapter = new ChannelAdapter(imageCache, this, isTvDevice());
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.setAdapter(channelAdapter);
        rv.setItemAnimator(null);
    }

    // ==================== NATIVE EXOPLAYER (HLS + QUALITY) ====================

    private void setupPlayer() {
        trackSelector = new DefaultTrackSelector(this);
        exoPlayer = new ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .build();
        exoPlayerView.setPlayer(exoPlayer);
        // The video surface itself must not steal D-Pad focus from the controls.
        exoPlayerView.setFocusable(false);

        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    playerLoadingOverlay.setVisibility(View.VISIBLE);
                    playerErrorOverlay.setVisibility(View.GONE);
                } else if (state == Player.STATE_READY) {
                    playerLoadingOverlay.setVisibility(View.GONE);
                    playerErrorOverlay.setVisibility(View.GONE);
                    updatePlayPauseIcons();
                } else if (state == Player.STATE_ENDED) {
                    updatePlayPauseIcons();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseIcons();
                if (isPlaying) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    scheduleHideControls();
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    showPlayerControls(false);
                }
            }

            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                if (!appliedDefaultQualityForCurrentStream) {
                    List<VideoQualityOption> opts = extractVideoQualities(tracks);
                    if (!opts.isEmpty()) {
                        appliedDefaultQualityForCurrentStream = true;
                        applyDefaultQualityPreference(opts);
                    }
                }
                updateQualityButtonLabel();
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "ExoPlayer error: " + error.getErrorCodeName(), error);
                playerLoadingOverlay.setVisibility(View.GONE);
                playerErrorOverlay.setVisibility(View.VISIBLE);
                txtPlayerError.setText("خطا در پخش استریم (" + error.getErrorCodeName() + ")");
                updatePlayPauseIcons();
            }
        });

        findViewById(R.id.btnPlayerClose).setOnClickListener(v -> closePlayer());
        findViewById(R.id.btnPlayerRetry).setOnClickListener(v -> {
            if (!currentStreamUrl.isEmpty()) startPlayback(currentStreamUrl);
        });
        btnPlayerCenterPlay.setOnClickListener(v -> togglePlayPause());
        btnPlayerPlayPause.setOnClickListener(v -> togglePlayPause());
        findViewById(R.id.btnPlayerPrev).setOnClickListener(v -> stepChannel(-1));
        findViewById(R.id.btnPlayerNext).setOnClickListener(v -> stepChannel(1));
        btnPlayerMute.setOnClickListener(v -> toggleMute());
        btnPlayerFullscreen.setOnClickListener(v -> toggleFullscreen());
        btnPlayerQuality.setOnClickListener(v -> openLiveQualitySheet());

        findViewById(R.id.playerTapOverlay).setOnClickListener(v -> {
            if (controlsVisible) {
                hidePlayerControls();
            } else {
                showPlayerControls(true);
            }
        });
    }

    private void playChannelStream(ChannelItem channel, String source) {
        int idx = channels.indexOf(channel);
        if (idx >= 0) currentChannelIndex = idx;
        currentStreamSource = source;

        String url = "telewebion".equals(source) ? channel.getTelewebionStreamUrl() : channel.sepehrStreamUrl;
        if (url == null || url.trim().isEmpty()) {
            showToast("آدرس استریم برای این شبکه یافت نشد", false);
            return;
        }

        String sourceName = "telewebion".equals(source) ? "تلوبیون" : "سپهر";
        txtPlayerTitle.setText(sourceName + " - " + channel.name);
        stickyPlayerSlot.setVisibility(View.VISIBLE);
        isPlayerOpen = true;
        showPlayerControls(true);
        startPlayback(url);
        // TV (D-Pad): move focus onto the player controls right away.
        btnPlayerPlayPause.post(() -> btnPlayerPlayPause.requestFocus());
    }

    private void startPlayback(String url) {
        currentStreamUrl = url;
        appliedDefaultQualityForCurrentStream = false;
        manualQualityLabel = null;

        // Clear any previous manual track override before loading new stream
        trackSelector.setParameters(
                trackSelector.buildUponParameters()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        );
        updateQualityButtonLabel();

        playerErrorOverlay.setVisibility(View.GONE);
        playerLoadingOverlay.setVisibility(View.VISIBLE);

        Map<String, String> headers = new HashMap<>();
        if (url.contains("telewebion")) {
            headers.put("Referer", "https://telewebion.com/");
        } else {
            headers.put("Referer", "https://sepehrtv.ir/");
            headers.put("Origin", "https://sepehrtv.ir");
        }

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(STREAM_UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12000)
                .setReadTimeoutMs(15000)
                .setDefaultRequestProperties(headers);

        HlsMediaSource mediaSource = new HlsMediaSource.Factory(httpFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(url));

        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);
    }

    private void closePlayer() {
        if (isFullscreen) exitFullscreen();
        exoPlayer.stop();
        exoPlayer.clearMediaItems();
        stickyPlayerSlot.setVisibility(View.GONE);
        isPlayerOpen = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void togglePlayPause() {
        if (exoPlayer.isPlaying()) {
            exoPlayer.pause();
        } else {
            if (exoPlayer.getPlaybackState() == Player.STATE_IDLE && !currentStreamUrl.isEmpty()) {
                startPlayback(currentStreamUrl);
            } else {
                exoPlayer.play();
            }
        }
        updatePlayPauseIcons();
    }

    private void stepChannel(int delta) {
        if (channels.isEmpty() || currentChannelIndex < 0) return;
        int next = (currentChannelIndex + delta + channels.size()) % channels.size();
        playChannelStream(channels.get(next), currentStreamSource);
    }

    private void toggleMute() {
        isMuted = !isMuted;
        exoPlayer.setVolume(isMuted ? 0f : 1f);
        btnPlayerMute.setImageResource(isMuted ? R.drawable.ic_volume_mute : R.drawable.ic_volume_high);
    }

    private void toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen();
        } else {
            enterFullscreen();
        }
    }

    private void enterFullscreen() {
        if (isFullscreen) return;
        isFullscreen = true;

        ViewGroup parent = (ViewGroup) playerAspectBox.getParent();
        if (parent != null) parent.removeView(playerAspectBox);
        fullscreenContainer.addView(playerAspectBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullscreenContainer.setVisibility(View.VISIBLE);
        btnPlayerFullscreen.setImageResource(R.drawable.ic_compress);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        WindowInsetsControllerCompat ctrl = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        ctrl.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        ctrl.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void exitFullscreen() {
        if (!isFullscreen) return;
        isFullscreen = false;

        ViewGroup parent = (ViewGroup) playerAspectBox.getParent();
        if (parent != null) parent.removeView(playerAspectBox);
        int w = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        int h = Math.round(w * 9f / 16f);
        stickyPlayerSlot.addView(playerAspectBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        fullscreenContainer.setVisibility(View.GONE);
        btnPlayerFullscreen.setImageResource(R.drawable.ic_expand);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        WindowInsetsControllerCompat ctrl = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        ctrl.show(WindowInsetsCompat.Type.systemBars());
    }

    private void updatePlayPauseIcons() {
        boolean playing = exoPlayer.isPlaying();
        btnPlayerPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        btnPlayerCenterPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        btnPlayerCenterPlay.setVisibility((!playing || controlsVisible) ? View.VISIBLE : View.GONE);
    }

    private void showPlayerControls(boolean autoHide) {
        controlsVisible = true;
        playerControlsBar.setVisibility(View.VISIBLE);
        btnPlayerCenterPlay.setVisibility(View.VISIBLE);
        if (autoHide && exoPlayer.isPlaying()) {
            scheduleHideControls();
        } else if (hideControlsRunnable != null) {
            mainHandler.removeCallbacks(hideControlsRunnable);
        }
    }

    private void hidePlayerControls() {
        if (!exoPlayer.isPlaying()) return;
        controlsVisible = false;
        playerControlsBar.setVisibility(View.GONE);
        btnPlayerCenterPlay.setVisibility(View.GONE);
    }

    private void scheduleHideControls() {
        if (hideControlsRunnable != null) mainHandler.removeCallbacks(hideControlsRunnable);
        hideControlsRunnable = this::hidePlayerControls;
        mainHandler.postDelayed(hideControlsRunnable, 3200);
    }

    // ==================== STREAM QUALITY MANAGEMENT ====================

    private static class VideoQualityOption {
        final TrackGroup group;
        final int trackIndex;
        final int height;
        final int width;
        final int bitrate;
        final boolean selected;

        VideoQualityOption(TrackGroup group, int trackIndex, int width, int height, int bitrate, boolean selected) {
            this.group = group;
            this.trackIndex = trackIndex;
            this.width = width;
            this.height = height;
            this.bitrate = bitrate;
            this.selected = selected;
        }

        String shortLabel() {
            if (height > 0) return height + "p";
            if (bitrate > 0) return (bitrate / 1000) + "k";
            return "استاندارد";
        }

        String fullLabel() {
            StringBuilder sb = new StringBuilder();
            if (height > 0) {
                sb.append(height).append("p");
                if (width > 0) sb.append(" (").append(width).append("×").append(height).append(")");
            } else {
                sb.append("کیفیت استریم");
            }
            if (bitrate > 0) {
                sb.append(" • ").append(bitrate / 1000).append(" kbps");
            }
            return sb.toString();
        }
    }

    private List<VideoQualityOption> extractVideoQualities(Tracks tracks) {
        List<VideoQualityOption> options = new ArrayList<>();
        if (tracks == null) return options;
        for (Tracks.Group tg : tracks.getGroups()) {
            if (tg.getType() != C.TRACK_TYPE_VIDEO) continue;
            TrackGroup mediaGroup = tg.getMediaTrackGroup();
            for (int i = 0; i < tg.length; i++) {
                if (!tg.isTrackSupported(i)) continue;
                Format f = tg.getTrackFormat(i);
                options.add(new VideoQualityOption(
                        mediaGroup, i, f.width, f.height, f.bitrate, tg.isTrackSelected(i)));
            }
        }
        Collections.sort(options, new Comparator<VideoQualityOption>() {
            @Override
            public int compare(VideoQualityOption a, VideoQualityOption b) {
                int cmp = Integer.compare(b.height, a.height);
                return cmp != 0 ? cmp : Integer.compare(b.bitrate, a.bitrate);
            }
        });
        return options;
    }

    private void applyDefaultQualityPreference(List<VideoQualityOption> sortedDesc) {
        if (sortedDesc.isEmpty()) return;
        if (QUALITY_HIGHEST.equals(defaultQualityMode)) {
            VideoQualityOption best = sortedDesc.get(0);
            selectFixedQuality(best, null);
        } else if (QUALITY_LOWEST.equals(defaultQualityMode)) {
            VideoQualityOption low = sortedDesc.get(sortedDesc.size() - 1);
            selectFixedQuality(low, null);
        } else {
            // Adaptive (ABR)
            trackSelector.setParameters(
                    trackSelector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_VIDEO));
        }
        updateQualityButtonLabel();
    }

    private void selectFixedQuality(VideoQualityOption opt, String explicitLabel) {
        TrackSelectionOverride override = new TrackSelectionOverride(opt.group, Collections.singletonList(opt.trackIndex));
        trackSelector.setParameters(
                trackSelector.buildUponParameters()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .addOverride(override));
        manualQualityLabel = explicitLabel;
        updateQualityButtonLabel();
    }

    private void updateQualityButtonLabel() {
        if (manualQualityLabel != null) {
            btnPlayerQuality.setText(manualQualityLabel);
            return;
        }
        // Check current active resolution
        String activeRes = "";
        if (exoPlayer != null) {
            Format vf = exoPlayer.getVideoFormat();
            if (vf != null && vf.height > 0) {
                activeRes = " (" + vf.height + "p)";
            }
        }
        if (QUALITY_HIGHEST.equals(defaultQualityMode)) {
            btnPlayerQuality.setText("بالاترین" + activeRes);
        } else if (QUALITY_LOWEST.equals(defaultQualityMode)) {
            btnPlayerQuality.setText("پایین‌ترین" + activeRes);
        } else {
            btnPlayerQuality.setText("تطبیقی" + activeRes);
        }
    }

    private String getDefaultQualityDisplayName() {
        if (QUALITY_HIGHEST.equals(defaultQualityMode)) return "بالاترین کیفیت";
        if (QUALITY_LOWEST.equals(defaultQualityMode)) return "پایین‌ترین کیفیت";
        return "تطبیقی (بر اساس سرعت اینترنت)";
    }

    private void openLiveQualitySheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.Theme_SmartRemote_BottomSheetDialog);
        View view = LayoutInflater.from(this).inflate(R.layout.sheet_quality, null);
        configureDialogFocus(view);
        dialog.setContentView(view);

        TextView title = view.findViewById(R.id.txtQualitySheetTitle);
        title.setText("کیفیت پخش زنده");
        view.findViewById(R.id.btnCloseQualitySheet).setOnClickListener(v -> dialog.dismiss());

        LinearLayout container = view.findViewById(R.id.qualityOptionsContainer);
        List<VideoQualityOption> qualities = extractVideoQualities(exoPlayer.getCurrentTracks());

        boolean isAdaptiveNow = (manualQualityLabel == null && QUALITY_ADAPTIVE.equals(defaultQualityMode));
        addQualityOptionRow(container,
                "تطبیقی (بر اساس سرعت اینترنت)",
                "تغییر خودکار کیفیت متناسب با پهنای باند",
                isAdaptiveNow,
                () -> {
                    manualQualityLabel = null;
                    trackSelector.setParameters(
                            trackSelector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_VIDEO));
                    updateQualityButtonLabel();
                    showToast("کیفیت پخش روی حالت تطبیقی قرار گرفت", true);
                    dialog.dismiss();
                });

        if (qualities.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("این استریم فقط یک کیفیت دارد یا هنوز لیست کیفیت‌ها بارگذاری نشده است.");
            empty.setTextColor(0xFF6B7280);
            empty.setTextSize(12f);
            empty.setPadding(8, 8, 8, 12);
            container.addView(empty);
        } else {
            for (VideoQualityOption q : qualities) {
                boolean checked = (manualQualityLabel != null && manualQualityLabel.equals(q.shortLabel()))
                        || (manualQualityLabel == null && !QUALITY_ADAPTIVE.equals(defaultQualityMode) && q.selected);
                addQualityOptionRow(container,
                        q.fullLabel(),
                        null,
                        checked,
                        () -> {
                            selectFixedQuality(q, q.shortLabel());
                            showToast("کیفیت پخش روی " + q.shortLabel() + " تنظیم شد", true);
                            dialog.dismiss();
                        });
            }
        }

        // Shortcut row to configure Default Quality preference
        addQualityOptionRow(container,
                "تنظیم کیفیت پیش‌فرض: " + getDefaultQualityDisplayName(),
                "تغییر حالت پیش‌فرض برای شروع تمام شبکه‌ها",
                false,
                () -> {
                    dialog.dismiss();
                    openDefaultQualitySheet();
                });

        dialog.show();
        // TV (D-Pad): focus the first quality option.
        view.post(() -> {
            if (container.getChildCount() > 0) container.getChildAt(0).requestFocus();
        });
    }

    private void openDefaultQualitySheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.Theme_SmartRemote_BottomSheetDialog);
        View view = LayoutInflater.from(this).inflate(R.layout.sheet_quality, null);
        configureDialogFocus(view);
        dialog.setContentView(view);

        TextView title = view.findViewById(R.id.txtQualitySheetTitle);
        title.setText("کیفیت پیش‌فرض پخش آنلاین");
        view.findViewById(R.id.btnCloseQualitySheet).setOnClickListener(v -> dialog.dismiss());

        LinearLayout container = view.findViewById(R.id.qualityOptionsContainer);

        addQualityOptionRow(container,
                "بالاترین کیفیت",
                "همیشه بالاترین رزولوشن موجود انتخاب می‌شود",
                QUALITY_HIGHEST.equals(defaultQualityMode),
                () -> setDefaultQualityMode(QUALITY_HIGHEST, dialog));

        addQualityOptionRow(container,
                "پایین‌ترین کیفیت",
                "مصرف اینترنت کمتر و بارگذاری سریع‌تر",
                QUALITY_LOWEST.equals(defaultQualityMode),
                () -> setDefaultQualityMode(QUALITY_LOWEST, dialog));

        addQualityOptionRow(container,
                "تطبیقی (بر اساس سرعت اینترنت)",
                "انتخاب هوشمند و خودکار کیفیت بر اساس سرعت اتصال",
                QUALITY_ADAPTIVE.equals(defaultQualityMode),
                () -> setDefaultQualityMode(QUALITY_ADAPTIVE, dialog));

        dialog.show();
        // TV (D-Pad): focus the first quality option.
        view.post(() -> {
            if (container.getChildCount() > 0) container.getChildAt(0).requestFocus();
        });
    }

    private void setDefaultQualityMode(String mode, BottomSheetDialog dialog) {
        defaultQualityMode = mode;
        prefs.edit().putString(KEY_DEFAULT_QUALITY, mode).apply();
        manualQualityLabel = null;
        if (isPlayerOpen && exoPlayer != null) {
            List<VideoQualityOption> opts = extractVideoQualities(exoPlayer.getCurrentTracks());
            applyDefaultQualityPreference(opts);
        }
        syncHeaderState();
        showToast("کیفیت پیش‌فرض: " + getDefaultQualityDisplayName(), true);
        dialog.dismiss();
    }

    private void addQualityOptionRow(LinearLayout parent, String label, String sub, boolean selected, Runnable onClick) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_quality_option, parent, false);
        configureDialogFocus(row);
        TextView txtLabel = row.findViewById(R.id.txtQualityLabel);
        TextView txtSub = row.findViewById(R.id.txtQualitySub);
        ImageView check = row.findViewById(R.id.imgQualityCheck);

        txtLabel.setText(label);
        if (sub != null && !sub.isEmpty()) {
            txtSub.setText(sub);
            txtSub.setVisibility(View.VISIBLE);
        }
        if (selected) {
            row.setBackgroundResource(R.drawable.bg_model_selected);
            check.setVisibility(View.VISIBLE);
        }
        row.setOnClickListener(v -> onClick.run());
        parent.addView(row);
    }

    // ==================== HEADER BUTTON ACTIONS ====================

    private void onRefreshClicked() {
        refreshChannelsFromApi(false, false);
    }

    private void onCaptureFramesClicked() {
        if (isCapturingFrames) {
            showToast("دریافت عکس‌ها در حال انجام است...", true);
            return;
        }
        if (channels.isEmpty()) return;

        isCapturingFrames = true;
        frameProgressText = "در حال دریافت فریم (0/" + channels.size() + ")...";
        // Not-yet-updated images are dimmed until their frame arrives.
        List<String> pending = new ArrayList<>();
        for (ChannelItem ch : channels) pending.add(ch.uid);
        channelAdapter.setPendingUids(pending);
        syncHeaderState();
        String sourceName = "sepehr".equals(frameSource) ? "سپهر" : "تلوبیون";
        showToast("در حال دریافت اولین فریم از " + sourceName + " با پایین‌ترین کیفیت...", true);

        frameExtractor.extractAll(new ArrayList<>(channels), frameSource, new FrameExtractor.Listener() {
            @Override
            public void onProgress(String uid, int completed, int total, boolean success) {
                mainHandler.post(() -> {
                    frameProgressText = "در حال دریافت فریم (" + completed + "/" + total + ")...";
                    syncHeaderState();
                    if (success) {
                        channelAdapter.markUpdated(uid);
                    }
                });
            }

            @Override
            public void onFinished(int succeeded, int total) {
                mainHandler.post(() -> {
                    isCapturingFrames = false;
                    frameProgressText = null;
                    channelAdapter.clearPending();
                    syncHeaderState();
                    if (succeeded > 0) {
                        showToast("عکس " + succeeded + " از " + total + " شبکه در حافظه ذخیره شد", true);
                    } else {
                        showToast("امکان دریافت فریم استریم وجود نداشت", false);
                    }
                });
            }
        });
    }

    private void onEditCodesClicked() {
        openEditCodesSheet();
    }

    private void onSelectTvModelClicked() {
        openTvModelSheet();
    }

    private void onDefaultQualityClicked() {
        openDefaultQualitySheet();
    }

    @Override
    public void onDialChannel(ChannelItem channel) {
        if (channel.code == null || channel.code.trim().isEmpty()) {
            showToast("برای " + channel.name + " کد کانالی ثبت نشده است", false);
            return;
        }
        if (irManager.dialCode(channel.code)) {
            showToast("ارسال کد " + channel.code + " (" + channel.name + ")", true);
        }
    }

    @Override
    public void onPlayStream(ChannelItem channel, String source) {
        playChannelStream(channel, source);
    }

    // ==================== SEPEHR API REFRESH ====================

    private void refreshChannelsFromApi(boolean overwriteCodesFromApi, boolean silent) {
        if (isRefreshing) return;
        isRefreshing = true;
        syncHeaderState();
        if (!silent) showToast("در حال بروزرسانی از API سپهر...", true);

        bgPool.execute(() -> {
            try {
                String json = sepehrApi.fetchChannelsJson();
                List<ChannelItem> updated = SepehrApiClient.parseChannels(json, savedCodes, overwriteCodesFromApi);
                if (updated.isEmpty()) throw new RuntimeException("لیست شبکه‌ها خالی بود");

                if (overwriteCodesFromApi) {
                    for (ChannelItem ch : updated) {
                        if (ch.code != null && !ch.code.isEmpty()) {
                            savedCodes.put(ch.uid, ch.code);
                        }
                    }
                    persistSavedCodes();
                }

                mainHandler.post(() -> {
                    isRefreshing = false;
                    channels.clear();
                    channels.addAll(updated);
                    channelAdapter.setChannels(channels);
                    syncHeaderState();
                    if (!silent) {
                        showToast(updated.size() + " شبکه از سپهر بروزرسانی شد", true);
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Sepehr API refresh failed", e);
                mainHandler.post(() -> {
                    isRefreshing = false;
                    syncHeaderState();
                    if (!silent) {
                        showToast("خطا در اتصال به API سپهر (نمایش لیست کش‌شده)", false);
                    }
                });
            }
        });
    }

    // ==================== BOTTOM SHEET: EDIT CHANNEL CODES ====================

    private void openEditCodesSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.Theme_SmartRemote_BottomSheetDialog);
        View view = LayoutInflater.from(this).inflate(R.layout.sheet_edit_codes, null);
        configureDialogFocus(view);
        dialog.setContentView(view);

        view.findViewById(R.id.btnCloseEditSheet).setOnClickListener(v -> dialog.dismiss());

        final Map<String, String> draftCodes = new HashMap<>();
        for (ChannelItem ch : channels) {
            draftCodes.put(ch.uid, ch.code != null ? ch.code : "");
        }

        RecyclerView rv = view.findViewById(R.id.rvEditCodes);
        rv.setLayoutManager(new LinearLayoutManager(this));
        EditCodesAdapter adapter = new EditCodesAdapter(channels, draftCodes, resolveTvFocusEnabled());
        rv.setAdapter(adapter);

        activeEditCodesCallback = imported -> {
            for (ChannelItem ch : channels) {
                if (imported.containsKey(ch.uid)) {
                    draftCodes.put(ch.uid, imported.get(ch.uid));
                }
            }
            adapter.notifyDataSetChanged();
        };
        dialog.setOnDismissListener(d -> activeEditCodesCallback = null);

        // 1: Fetch from API
        view.findViewById(R.id.btnFetchCodesApi).setOnClickListener(v -> {
            showToast("در حال دریافت شماره کانال‌ها از API سپهر...", true);
            bgPool.execute(() -> {
                try {
                    String json = sepehrApi.fetchChannelsJson();
                    List<ChannelItem> fresh = SepehrApiClient.parseChannels(json, null, true);
                    mainHandler.post(() -> {
                        for (ChannelItem ch : fresh) {
                            draftCodes.put(ch.uid, ch.defaultNumber);
                        }
                        adapter.notifyDataSetChanged();
                        showToast("شماره کانال‌ها از API سپهر دریافت شد", true);
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> showToast("خطا در دریافت از API سپهر", false));
                }
            });
        });

        // 2: Export to file (SAF)
        view.findViewById(R.id.btnExportCodes).setOnClickListener(v -> {
            try {
                JSONObject out = new JSONObject();
                for (ChannelItem ch : channels) {
                    out.put(ch.uid, draftCodes.get(ch.uid));
                }
                pendingExportJson = out.toString(2);
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, "smart_remote_codes.json");
                exportLauncher.launch(intent);
            } catch (Exception e) {
                showToast("خطا در آماده سازی فایل خروجی", false);
            }
        });

        // 3: Import from file (SAF)
        view.findViewById(R.id.btnImportCodes).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            importLauncher.launch(intent);
        });

        // 4: Save changes
        view.findViewById(R.id.btnSaveCodes).setOnClickListener(v -> {
            for (ChannelItem ch : channels) {
                String c = draftCodes.get(ch.uid);
                ch.code = c != null ? c.trim() : "";
                savedCodes.put(ch.uid, ch.code);
            }
            persistSavedCodes();
            channelAdapter.notifyDataSetChanged();
            showToast("کد کانال‌ها با موفقیت ذخیره شد", true);
            dialog.dismiss();
        });

        dialog.show();
        // TV (D-Pad): focus the codes list so arrows can reach the inputs.
        rv.post(() -> rv.requestFocus());
    }

    private static class EditCodesAdapter extends RecyclerView.Adapter<EditCodesAdapter.RowVH> {
        private final List<ChannelItem> items;
        private final Map<String, String> draft;
        private final boolean tvFocusEnabled;

        EditCodesAdapter(List<ChannelItem> items, Map<String, String> draft, boolean tvFocusEnabled) {
            this.items = items;
            this.draft = draft;
            this.tvFocusEnabled = tvFocusEnabled;
        }

        @NonNull
        @Override
        public RowVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_edit_code_row, parent, false);
            if (!tvFocusEnabled) clearFocusRings(row);
            return new RowVH(row);
        }

        @Override
        public void onBindViewHolder(@NonNull RowVH h, int pos) {
            ChannelItem ch = items.get(pos);
            h.txtName.setText(ch.name);
            h.txtUid.setText(ch.uid);

            if (h.watcher != null) h.edtCode.removeTextChangedListener(h.watcher);
            String val = draft.get(ch.uid);
            h.edtCode.setText(val != null ? val : "");
            h.watcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    draft.put(ch.uid, s.toString().trim());
                }
            };
            h.edtCode.addTextChangedListener(h.watcher);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class RowVH extends RecyclerView.ViewHolder {
            final TextView txtName;
            final TextView txtUid;
            final EditText edtCode;
            TextWatcher watcher;

            RowVH(@NonNull View v) {
                super(v);
                txtName = v.findViewById(R.id.txtEditChannelName);
                txtUid = v.findViewById(R.id.txtEditChannelUid);
                edtCode = v.findViewById(R.id.edtChannelCode);
            }
        }
    }

    // ==================== BOTTOM SHEET: TV MODEL SELECTOR (IRREMOTE DB) ====================

    private void openTvModelSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.Theme_SmartRemote_BottomSheetDialog);
        View view = LayoutInflater.from(this).inflate(R.layout.sheet_tv_model, null);
        configureDialogFocus(view);
        dialog.setContentView(view);

        view.findViewById(R.id.btnCloseTvSheet).setOnClickListener(v -> dialog.dismiss());

        TextView txtStatus = view.findViewById(R.id.txtIrStatus);
        if (irManager.hasIrEmitter()) {
            txtStatus.setText("فرستنده مادون قرمز گوشی فعال است");
            txtStatus.setTextColor(0xFF059669);
        } else {
            txtStatus.setText("این دستگاه فاقد فرستنده مادون قرمز (IR) است");
            txtStatus.setTextColor(0xFFD97706);
        }

        TextView tabTv = view.findViewById(R.id.tabTv);
        TextView tabCable = view.findViewById(R.id.tabCable);
        EditText edtSearch = view.findViewById(R.id.edtBrandSearch);
        RecyclerView rvModels = view.findViewById(R.id.rvTvModels);
        CheckBox chkPulse = view.findViewById(R.id.chkPulseFix);

        chkPulse.setChecked(irManager.getSignalCorrector().isPulseFixEnabled());
        chkPulse.setOnCheckedChangeListener((buttonView, isChecked) ->
                irManager.getSignalCorrector().setPulseFixEnabled(isChecked));

        final String[] activeCategory = {irManager.getSelectedCategory()};
        final List<IrManager.BrandItem> filtered = new ArrayList<>();

        rvModels.setLayoutManager(new GridLayoutManager(this, 2));
        TvModelAdapter adapter = new TvModelAdapter(filtered, irManager, (brand) -> {
            irManager.setSelectedModel(activeCategory[0], brand.name);
            syncHeaderState();
            showToast("مدل " + brand.name + " انتخاب شد", true);
        }, resolveTvFocusEnabled());
        rvModels.setAdapter(adapter);

        Runnable applyFilter = () -> {
            String q = edtSearch.getText().toString().trim().toLowerCase(Locale.US);
            List<IrManager.BrandItem> all = irManager.getBrands(activeCategory[0]);
            filtered.clear();
            for (IrManager.BrandItem b : all) {
                if (q.isEmpty() || b.name.toLowerCase(Locale.US).contains(q)) {
                    filtered.add(b);
                }
            }
            adapter.setActiveCategory(activeCategory[0]);
            adapter.notifyDataSetChanged();

            boolean isTv = "TV".equals(activeCategory[0]);
            tabTv.setBackgroundResource(isTv ? R.drawable.bg_sheet_btn_cyan : R.drawable.bg_sheet_btn_white);
            tabTv.setTextColor(isTv ? 0xFFFFFFFF : 0xFF1F2937);
            tabCable.setBackgroundResource(!isTv ? R.drawable.bg_sheet_btn_cyan : R.drawable.bg_sheet_btn_white);
            tabCable.setTextColor(!isTv ? 0xFFFFFFFF : 0xFF1F2937);
        };

        tabTv.setOnClickListener(v -> {
            activeCategory[0] = "TV";
            applyFilter.run();
        });
        tabCable.setOnClickListener(v -> {
            activeCategory[0] = "Cable";
            applyFilter.run();
        });
        edtSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilter.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        view.findViewById(R.id.btnTestPower).setOnClickListener(v -> {
            if (irManager.sendButtonOnce(IrManager.ID_POWER)) {
                showToast("ارسال فرمان تست پاور برای " + irManager.getSelectedBrand(), true);
            }
        });
        view.findViewById(R.id.btnConfirmTvModel).setOnClickListener(v -> dialog.dismiss());

        applyFilter.run();
        dialog.show();
        // TV (D-Pad): focus the brand list so arrows can reach the models.
        rvModels.post(() -> rvModels.requestFocus());
    }

    private static class TvModelAdapter extends RecyclerView.Adapter<TvModelAdapter.ModelVH> {
        interface OnSelect {
            void onSelected(IrManager.BrandItem brand);
        }

        private final List<IrManager.BrandItem> items;
        private final IrManager irManager;
        private final OnSelect onSelect;
        private final boolean tvFocusEnabled;
        private String activeCategory = "TV";

        TvModelAdapter(List<IrManager.BrandItem> items, IrManager irManager, OnSelect onSelect,
                       boolean tvFocusEnabled) {
            this.items = items;
            this.irManager = irManager;
            this.onSelect = onSelect;
            this.tvFocusEnabled = tvFocusEnabled;
        }

        void setActiveCategory(String cat) {
            this.activeCategory = cat;
        }

        @NonNull
        @Override
        public ModelVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_tv_model, parent, false);
            if (!tvFocusEnabled) clearFocusRings(row);
            return new ModelVH(row);
        }

        @Override
        public void onBindViewHolder(@NonNull ModelVH h, int pos) {
            IrManager.BrandItem b = items.get(pos);
            h.txtBrand.setText(b.name);
            h.txtCount.setText(b.buttonCount + " کلید");

            boolean sel = activeCategory.equals(irManager.getSelectedCategory())
                    && b.name.equals(irManager.getSelectedBrand());
            h.root.setBackgroundResource(sel ? R.drawable.bg_model_selected : R.drawable.bg_model_default);
            h.imgCheck.setVisibility(sel ? View.VISIBLE : View.GONE);

            h.root.setOnClickListener(v -> {
                onSelect.onSelected(b);
                notifyDataSetChanged();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ModelVH extends RecyclerView.ViewHolder {
            final View root;
            final TextView txtBrand;
            final TextView txtCount;
            final ImageView imgCheck;

            ModelVH(@NonNull View v) {
                super(v);
                root = v.findViewById(R.id.modelItemRoot);
                txtBrand = v.findViewById(R.id.txtBrandName);
                txtCount = v.findViewById(R.id.txtBrandCount);
                imgCheck = v.findViewById(R.id.imgModelSelected);
            }
        }
    }

    // ==================== PERSISTENCE & HELPERS ====================

    private void syncHeaderState() {
        if (btnRefresh == null) return;
        btnRefresh.setText(isRefreshing ? "در حال بروزرسانی..." : "به روز رسانی");
        btnRefresh.setEnabled(!isRefreshing);
        btnRefresh.setAlpha(isRefreshing ? 0.7f : 1f);

        if (frameProgressText != null) {
            btnCaptureFrames.setText(frameProgressText);
        } else {
            btnCaptureFrames.setText("دریافت فریم اول");
        }

        btnSelectTvModel.setText("مدل تلویزیون: " + irManager.getSelectedBrand());
        btnDefaultQuality.setText("کیفیت پیش‌فرض پخش: " + getDefaultQualityDisplayName());
        syncFrameSourceButtons();
        syncTvFocusToggle();
    }

    private void loadSavedCodes() {
        savedCodes.clear();
        String raw = prefs.getString(KEY_CHANNEL_CODES, "{}");
        try {
            JSONObject obj = new JSONObject(raw);
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String k = it.next();
                savedCodes.put(k, obj.optString(k, ""));
            }
        } catch (Exception ignored) {
        }
    }

    private void persistSavedCodes() {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> e : savedCodes.entrySet()) {
                obj.put(e.getKey(), e.getValue());
            }
            prefs.edit().putString(KEY_CHANNEL_CODES, obj.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void showToast(String message, boolean success) {
        toastText.setText(message);
        toastIcon.setColorFilter(success ? 0xFF34D399 : 0xFFF87171);
        toastChip.setVisibility(View.VISIBLE);
        toastChip.setAlpha(1f);

        if (hideToastRunnable != null) mainHandler.removeCallbacks(hideToastRunnable);
        hideToastRunnable = () -> toastChip.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> toastChip.setVisibility(View.GONE)).start();
        mainHandler.postDelayed(hideToastRunnable, 2800);
    }

    @Override
    public void onBackPressed() {
        if (isFullscreen) {
            exitFullscreen();
            return;
        }
        if (isRemotePanelOpen) {
            toggleRemotePanel();
            return;
        }
        if (isPlayerOpen) {
            closePlayer();
            return;
        }
        super.onBackPressed();
    }

    // ==================== TV REMOTE (D-PAD / MEDIA KEYS) ====================
    // Arrow keys + DPAD_CENTER/ENTER are handled by Android's focus system:
    // every interactive view is focusable and shows a focus ring, so the
    // remote just moves focus and clicks the focused view. Only the keys
    // the focus system does not know (media/channel/menu) are mapped here.

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_GUIDE) {
            toggleRemotePanel();
            return true;
        }
        if (isPlayerOpen) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    togglePlayPause();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    stepChannel(1);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    stepChannel(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    // Focus is on the video area (not a control): reveal the
                    // controls and put focus on Play/Pause instead of nothing.
                    if (!isInPlayerArea(getCurrentFocus())) {
                        showPlayerControls(true);
                        btnPlayerPlayPause.requestFocus();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // Controls hidden and focus not on them: any arrow brings
                    // the controls back instead of moving focus behind the player.
                    if (!controlsVisible && !isInPlayerArea(getCurrentFocus())) {
                        showPlayerControls(true);
                        btnPlayerPlayPause.requestFocus();
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean isInPlayerArea(View v) {
        while (v != null) {
            if (v == playerAspectBox) return true;
            if (v.getParent() instanceof View) {
                v = (View) v.getParent();
            } else {
                return false;
            }
        }
        return false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (exoPlayer != null && exoPlayer.isPlaying()) {
            exoPlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        irManager.release();
        frameExtractor.shutdown();
        imageCache.shutdown();
        bgPool.shutdownNow();
    }
}
