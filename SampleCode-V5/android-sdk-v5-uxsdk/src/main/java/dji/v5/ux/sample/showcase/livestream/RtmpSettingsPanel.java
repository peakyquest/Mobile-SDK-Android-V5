/*
 * RTMP live-stream settings panel.
 *
 * Lives inside the right drawer of {@link dji.v5.ux.sample.showcase.defaultlayout.DefaultLayoutActivity}.
 * This class owns the UI state only — it persists user input via SharedPreferences and
 * exposes hooks that the activity (or a future ILiveStreamManager binding) can call into.
 *
 * The actual ILiveStreamManager wiring is intentionally left for a second pass; the
 * relevant integration points are marked with `// TODO(rtmp-wiring):` so they're easy to
 * find when we move from UI-only to live streaming.
 */

package dji.v5.ux.sample.showcase.livestream;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.Locale;

import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.v5.ux.R;

/**
 * UI controller for the RTMP live-stream panel. Handles tab switching with the DJI
 * {@code SettingPanelWidget}, persists configuration, and presents live status.
 *
 * <p>Stream lifecycle calls (start / stop / settings push) are stubbed. Hook them up
 * from {@link #onStartClicked()} / {@link #onStopClicked()} when wiring
 * {@code ILiveStreamManager}.
 */
public final class RtmpSettingsPanel {

    private static final String PREFS = "uxsdk_rtmp_prefs";
    private static final String KEY_URL = "rtmp_url";
    private static final String KEY_QUALITY = "rtmp_quality";
    private static final String KEY_BITRATE_MODE_MANUAL = "rtmp_bitrate_manual";
    private static final String KEY_BITRATE_KBPS = "rtmp_bitrate_kbps";
    private static final String KEY_CAMERA_SCALE = "rtmp_cam_scale_type";
    private static final String KEY_LIVE_SCALE = "rtmp_live_scale_type";

    // ── Default tags (match DJI SDK enum ordinals) ────────────────────────────
    /** Until we expose a camera-source picker, RTMP always streams the main camera. */
    private static final ComponentIndexType DEFAULT_CAMERA = ComponentIndexType.LEFT_OR_MAIN;
    /** {@code StreamQuality.HD} */
    private static final int DEFAULT_QUALITY_TAG = 2;
    /** {@code ICameraStreamManager.ScaleType.CENTER_INSIDE} — matches local preview convention. */
    private static final int DEFAULT_CAMERA_SCALE_TAG = 2;
    /** {@code ICameraStreamManager.ScaleType.CENTER_CROP} — matches outbound-stream convention. */
    private static final int DEFAULT_LIVE_SCALE_TAG = 1;

    private static final int MIN_BITRATE_KBPS = 256;
    private static final int MAX_BITRATE_KBPS = 16384;
    private static final int DEFAULT_BITRATE_KBPS = 2048;

    /** Bridges {@link RtmpSettingsPanel} → {@code ILiveStreamManager} wiring (added later). */
    public interface StreamHost {
        /** Persist or push the chosen RTMP URL + stream config to the live-stream manager. */
        void onApplyRtmpConfig(@NonNull RtmpConfig config);

        /** Request stream start with the latest applied config. */
        void onStartStream();

        /** Request stream stop. */
        void onStopStream();
    }

    /** Snapshot of every user-tunable RTMP setting. Immutable per emit. */
    public static final class RtmpConfig {
        public final String url;
        public final ComponentIndexType cameraIndex;
        /** {@code StreamQuality} ordinal (SD=1, HD=2, FULL_HD=3, ORIGINAL=100). */
        public final int qualityTag;
        public final boolean manualBitrate;
        public final int bitrateKbps;
        /** {@code ICameraStreamManager.ScaleType} ordinal for the local preview surface. */
        public final int cameraScaleTag;
        /** {@code ICameraStreamManager.ScaleType} ordinal for the outbound live stream. */
        public final int liveScaleTag;

        public RtmpConfig(String url, ComponentIndexType cameraIndex, int qualityTag,
                          boolean manualBitrate, int bitrateKbps,
                          int cameraScaleTag, int liveScaleTag) {
            this.url = url;
            this.cameraIndex = cameraIndex;
            this.qualityTag = qualityTag;
            this.manualBitrate = manualBitrate;
            this.bitrateKbps = bitrateKbps;
            this.cameraScaleTag = cameraScaleTag;
            this.liveScaleTag = liveScaleTag;
        }
    }

    private final AppCompatActivity activity;
    private final SharedPreferences prefs;
    @Nullable
    private StreamHost host;

    // ── Tab strip ─────────────────────────────────────────────────────────────
    private TextView tabAircraft;
    private TextView tabRtmp;
    private View aircraftPanel;
    private View rtmpPanel;

    // ── RTMP form ─────────────────────────────────────────────────────────────
    private EditText etUrl;
    private RadioGroup rgQuality;
    private RadioGroup rgBitrateMode;
    private View manualBitrateRow;
    private SeekBar sbBitrate;
    private TextView tvBitrate;
    private RadioGroup rgCameraScale;
    private RadioGroup rgLiveScale;
    private TextView tvStatus;
    private View statusDot;
    private TextView tvUptime;
    private TextView tvStatsResolution;
    private TextView tvStatsFps;
    private TextView tvStatsBitrate;
    private TextView tvError;
    private Button btnSave;
    private Button btnStart;
    private Button btnStop;

    // ── Runtime state ─────────────────────────────────────────────────────────
    /** UI state machine. Transitions are gated so background SDK pushes can't flicker us. */
    private enum UiState { IDLE, CONNECTING, LIVE }

    private UiState uiState = UiState.IDLE;
    private long streamStartUptimeMs;
    private final Runnable uptimeTicker = new Runnable() {
        @Override
        public void run() {
            if (uiState != UiState.LIVE || tvUptime == null) return;
            long elapsedMs = SystemClock.uptimeMillis() - streamStartUptimeMs;
            tvUptime.setText(formatUptime(elapsedMs));
            tvUptime.postDelayed(this, 1000L);
        }
    };

    public RtmpSettingsPanel(@NonNull AppCompatActivity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Attach an optional bridge to the live-stream manager. Safe to call later. */
    public void setHost(@Nullable StreamHost host) {
        this.host = host;
    }

    /**
     * Bind to the right-drawer root from {@code uxsdk_drawer_right_settings.xml}.
     * Must be called after {@code Activity.setContentView}.
     */
    public void bind(@NonNull View rightDrawerRoot) {
        tabAircraft = rightDrawerRoot.findViewById(R.id.uxsdk_right_drawer_tab_aircraft);
        tabRtmp = rightDrawerRoot.findViewById(R.id.uxsdk_right_drawer_tab_rtmp);
        aircraftPanel = rightDrawerRoot.findViewById(R.id.manual_right_nav_setting);
        rtmpPanel = rightDrawerRoot.findViewById(R.id.uxsdk_right_drawer_rtmp_panel);

        etUrl = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_et_url);
        rgQuality = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_rg_quality);
        rgBitrateMode = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_rg_bitrate_mode);
        manualBitrateRow = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_manual_bitrate_row);
        sbBitrate = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_sb_bitrate);
        tvBitrate = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_tv_bitrate);
        rgCameraScale = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_rg_cam_scale);
        rgLiveScale = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_rg_live_scale);
        tvStatus = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_status_text);
        statusDot = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_status_dot);
        tvUptime = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_status_uptime);
        tvStatsResolution = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_tv_stats_resolution);
        tvStatsFps = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_tv_stats_fps);
        tvStatsBitrate = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_tv_stats_bitrate);
        tvError = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_tv_error);
        btnSave = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_btn_save);
        btnStart = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_btn_start);
        btnStop = rightDrawerRoot.findViewById(R.id.uxsdk_rtmp_btn_stop);

        wireTabs();
        wireRtmpForm();
        restoreFromPrefs();
        renderIdleStatus();
        renderEmptyStats();
    }

    /**
     * Reserved for a future multi-camera picker. RTMP currently always streams
     * {@link #DEFAULT_CAMERA}, so this is a no-op.
     */
    public void updateCameraFromHost(@Nullable ComponentIndexType cameraIndex) {
        // intentional no-op
    }

    /** Release UI timers; call from {@code Activity.onDestroy()}. */
    public void onDestroy() {
        if (tvUptime != null) {
            tvUptime.removeCallbacks(uptimeTicker);
        }
        uiState = UiState.IDLE;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Wiring
    // ══════════════════════════════════════════════════════════════════════════

    private void wireTabs() {
        tabAircraft.setOnClickListener(v -> showAircraftTab());
        tabRtmp.setOnClickListener(v -> showRtmpTab());
        showAircraftTab();
    }

    private void showAircraftTab() {
        tabAircraft.setSelected(true);
        tabRtmp.setSelected(false);
        if (aircraftPanel != null) aircraftPanel.setVisibility(View.VISIBLE);
        if (rtmpPanel != null) rtmpPanel.setVisibility(View.GONE);
    }

    private void showRtmpTab() {
        tabAircraft.setSelected(false);
        tabRtmp.setSelected(true);
        if (aircraftPanel != null) aircraftPanel.setVisibility(View.GONE);
        if (rtmpPanel != null) rtmpPanel.setVisibility(View.VISIBLE);
    }

    private void wireRtmpForm() {
        rgBitrateMode.setOnCheckedChangeListener((group, checkedId) -> {
            boolean manual = checkedId == R.id.uxsdk_rtmp_rb_br_manual;
            manualBitrateRow.setVisibility(manual ? View.VISIBLE : View.GONE);
        });

        sbBitrate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvBitrate.setText(activity.getString(
                        R.string.uxsdk_rtmp_bitrate_kbps, progressToKbps(progress)));
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { /* no-op */ }

            @Override public void onStopTrackingTouch(SeekBar seekBar) { /* no-op */ }
        });

        btnSave.setOnClickListener(v -> onSaveClicked());
        btnStart.setOnClickListener(v -> onStartClicked());
        btnStop.setOnClickListener(v -> onStopClicked());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Persistence
    // ══════════════════════════════════════════════════════════════════════════

    private void restoreFromPrefs() {
        etUrl.setText(prefs.getString(KEY_URL, ""));

        int qualityTag = prefs.getInt(KEY_QUALITY, DEFAULT_QUALITY_TAG);
        checkByTag(rgQuality, qualityTag, R.id.uxsdk_rtmp_rb_q_hd);

        boolean manual = prefs.getBoolean(KEY_BITRATE_MODE_MANUAL, false);
        rgBitrateMode.check(manual ? R.id.uxsdk_rtmp_rb_br_manual : R.id.uxsdk_rtmp_rb_br_auto);
        manualBitrateRow.setVisibility(manual ? View.VISIBLE : View.GONE);

        int kbps = prefs.getInt(KEY_BITRATE_KBPS, DEFAULT_BITRATE_KBPS);
        sbBitrate.setProgress(kbpsToProgress(kbps));
        tvBitrate.setText(activity.getString(R.string.uxsdk_rtmp_bitrate_kbps, kbps));

        int cameraScaleTag = prefs.getInt(KEY_CAMERA_SCALE, DEFAULT_CAMERA_SCALE_TAG);
        checkByTag(rgCameraScale, cameraScaleTag, R.id.uxsdk_rtmp_rb_cam_scale_inside);

        int liveScaleTag = prefs.getInt(KEY_LIVE_SCALE, DEFAULT_LIVE_SCALE_TAG);
        checkByTag(rgLiveScale, liveScaleTag, R.id.uxsdk_rtmp_rb_live_scale_crop);
    }

    private void persistToPrefs(@NonNull RtmpConfig cfg) {
        prefs.edit()
                .putString(KEY_URL, cfg.url)
                .putInt(KEY_QUALITY, cfg.qualityTag)
                .putBoolean(KEY_BITRATE_MODE_MANUAL, cfg.manualBitrate)
                .putInt(KEY_BITRATE_KBPS, cfg.bitrateKbps)
                .putInt(KEY_CAMERA_SCALE, cfg.cameraScaleTag)
                .putInt(KEY_LIVE_SCALE, cfg.liveScaleTag)
                .apply();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Click handlers
    // ══════════════════════════════════════════════════════════════════════════

    private void onSaveClicked() {
        RtmpConfig cfg = readForm();
        if (!validateUrlOrToast(cfg.url)) return;
        persistToPrefs(cfg);
        toast(R.string.uxsdk_rtmp_msg_saved);
        if (host != null) host.onApplyRtmpConfig(cfg);
    }

    private void onStartClicked() {
        RtmpConfig cfg = readForm();
        if (!validateUrlOrToast(cfg.url)) return;
        persistToPrefs(cfg);
        if (host == null) {
            renderError("Live-stream service not ready yet.");
            return;
        }
        host.onApplyRtmpConfig(cfg);
        host.onStartStream();
        // Status (CONNECTING / LIVE / IDLE) is driven by LiveStreamBridge from here on.
    }

    private void onStopClicked() {
        if (host == null) {
            renderIdleStatus();
            return;
        }
        host.onStopStream();
        // IDLE transition + stats reset come from LiveStreamBridge once the SDK confirms.
    }

    // ══════════════════════════════════════════════════════════════════════════
    // State rendering — public so the activity can drive it from real callbacks.
    // ══════════════════════════════════════════════════════════════════════════

    public void renderIdleStatus() {
        if (uiState == UiState.IDLE) return;
        uiState = UiState.IDLE;
        if (tvStatus != null) tvStatus.setText(R.string.uxsdk_rtmp_status_idle);
        tintStatusDot(R.color.uxsdk_white_50_percent);
        if (tvUptime != null) {
            tvUptime.removeCallbacks(uptimeTicker);
            tvUptime.setText("");
        }
        if (tvError != null) tvError.setVisibility(View.GONE);
        toggleStartStopButtons(/* streaming= */ false);
    }

    public void renderConnecting() {
        if (uiState == UiState.CONNECTING) return;
        uiState = UiState.CONNECTING;
        if (tvStatus != null) tvStatus.setText(R.string.uxsdk_rtmp_status_connecting);
        tintStatusDot(R.color.uxsdk_white);
        if (tvUptime != null) {
            tvUptime.removeCallbacks(uptimeTicker);
            tvUptime.setText("");
        }
        if (tvError != null) tvError.setVisibility(View.GONE);
        toggleStartStopButtons(/* streaming= */ true);
    }

    public void renderLive() {
        // Idempotent: the SDK emits a status update every ~1 s while streaming.
        // We must only seed the start timestamp + re-arm the ticker on the
        // CONNECTING→LIVE (or IDLE→LIVE) edge, otherwise the uptime counter
        // would reset to 00:00:00 on every emission.
        if (uiState == UiState.LIVE) return;
        uiState = UiState.LIVE;
        streamStartUptimeMs = SystemClock.uptimeMillis();
        if (tvStatus != null) tvStatus.setText(R.string.uxsdk_rtmp_status_live);
        tintStatusDot(R.color.uxsdk_red);
        if (tvUptime != null) {
            tvUptime.removeCallbacks(uptimeTicker);
            tvUptime.post(uptimeTicker);
        }
        if (tvError != null) tvError.setVisibility(View.GONE);
        toggleStartStopButtons(/* streaming= */ true);
    }

    /** True iff the panel is currently in the {@code LIVE} state. */
    public boolean isLive() {
        return uiState == UiState.LIVE;
    }

    /** True iff the panel is currently in the {@code CONNECTING} state. */
    public boolean isConnecting() {
        return uiState == UiState.CONNECTING;
    }

    public void renderError(@Nullable String message) {
        if (tvStatus != null) tvStatus.setText(R.string.uxsdk_rtmp_status_error);
        tintStatusDot(R.color.uxsdk_red);
        if (tvError != null) {
            tvError.setText(message == null ? "" : message);
            tvError.setVisibility(View.VISIBLE);
        }
    }

    public void renderStats(@Nullable String resolution, @Nullable String fps, @Nullable String bitrate) {
        if (tvStatsResolution != null) {
            tvStatsResolution.setText(activity.getString(
                    R.string.uxsdk_rtmp_stats_resolution, orPlaceholder(resolution)));
        }
        if (tvStatsFps != null) {
            tvStatsFps.setText(activity.getString(
                    R.string.uxsdk_rtmp_stats_fps, orPlaceholder(fps)));
        }
        if (tvStatsBitrate != null) {
            tvStatsBitrate.setText(activity.getString(
                    R.string.uxsdk_rtmp_stats_bitrate, orPlaceholder(bitrate)));
        }
    }

    private void renderEmptyStats() {
        renderStats(null, null, null);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private RtmpConfig readForm() {
        String url = etUrl.getText() == null ? "" : etUrl.getText().toString().trim();
        int quality = readCheckedTag(rgQuality, DEFAULT_QUALITY_TAG);
        boolean manual = rgBitrateMode.getCheckedRadioButtonId() == R.id.uxsdk_rtmp_rb_br_manual;
        int bitrate = progressToKbps(sbBitrate.getProgress());
        int cameraScale = readCheckedTag(rgCameraScale, DEFAULT_CAMERA_SCALE_TAG);
        int liveScale = readCheckedTag(rgLiveScale, DEFAULT_LIVE_SCALE_TAG);
        return new RtmpConfig(url, DEFAULT_CAMERA, quality, manual, bitrate, cameraScale, liveScale);
    }

    private boolean validateUrlOrToast(String url) {
        if (TextUtils.isEmpty(url)) {
            toast(R.string.uxsdk_rtmp_msg_url_empty);
            return false;
        }
        if (!url.startsWith("rtmp://") && !url.startsWith("rtmps://")) {
            toast(R.string.uxsdk_rtmp_msg_url_invalid);
            return false;
        }
        return true;
    }

    private void toggleStartStopButtons(boolean streaming) {
        if (btnStart != null) btnStart.setEnabled(!streaming);
        if (btnStop != null) btnStop.setEnabled(streaming);
    }

    private void tintStatusDot(int colorRes) {
        if (statusDot == null) return;
        statusDot.setBackgroundTintList(
                ContextCompat.getColorStateList(activity, colorRes));
    }

    private static int readCheckedTag(RadioGroup group, int fallback) {
        int checkedId = group.getCheckedRadioButtonId();
        if (checkedId == View.NO_ID) return fallback;
        View checked = group.findViewById(checkedId);
        if (!(checked instanceof RadioButton)) return fallback;
        Object tag = checked.getTag();
        if (tag instanceof String) {
            try { return Integer.parseInt((String) tag); } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return fallback;
    }

    private static void checkByTag(RadioGroup group, int wantedTag, int fallbackChildId) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String && Integer.toString(wantedTag).equals(tag)) {
                group.check(child.getId());
                return;
            }
        }
        group.check(fallbackChildId);
    }

    private static int progressToKbps(int progress) {
        int span = MAX_BITRATE_KBPS - MIN_BITRATE_KBPS;
        return MIN_BITRATE_KBPS + Math.round(progress / 100f * span);
    }

    private static int kbpsToProgress(int kbps) {
        int span = MAX_BITRATE_KBPS - MIN_BITRATE_KBPS;
        if (span <= 0) return 0;
        int clamped = Math.max(MIN_BITRATE_KBPS, Math.min(MAX_BITRATE_KBPS, kbps));
        return Math.round((clamped - MIN_BITRATE_KBPS) / (float) span * 100f);
    }

    private static String formatUptime(long elapsedMs) {
        long totalSec = elapsedMs / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    private String orPlaceholder(@Nullable String value) {
        return TextUtils.isEmpty(value) ? activity.getString(R.string.uxsdk_rtmp_stats_placeholder) : value;
    }

    private void toast(int messageRes) {
        Toast.makeText(activity, messageRes, Toast.LENGTH_SHORT).show();
    }
}
