/*
 * RTMP live-stream settings panel — operator UX + DJI MSDK v5 {@link RtmpSettingsPanel.StreamHost} bridge.
 *
 * <p>Camera inventory uses {@code ICameraStreamManager.AvailableCameraUpdatedListener} data forwarded from
 * {@link dji.v5.ux.sample.showcase.defaultlayout.DefaultLayoutActivity}. Stream control is implemented in
 * {@link LiveStreamBridge} (tag {@link LiveStreamBridge#LOG_TAG}).
 */

package dji.v5.ux.sample.showcase.livestream;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.v5.ux.R;
import dji.v5.utils.common.ContextUtil;
import dji.v5.utils.common.DjiSharedPreferencesManager;
import dji.v5.utils.common.LogUtils;

public final class RtmpSettingsPanel {

    private static final String LOG_TAG = LiveStreamBridge.LOG_TAG;

    private static final String PREFS = "uxsdk_rtmp_prefs";
    private static final String KEY_URL = "rtmp_url";
    private static final String KEY_QUALITY = "rtmp_quality";
    private static final String KEY_BITRATE_MODE_MANUAL = "rtmp_bitrate_manual";
    private static final String KEY_BITRATE_KBPS = "rtmp_bitrate_kbps";
    private static final String KEY_CAMERA_SCALE = "rtmp_cam_scale_type";
    private static final String KEY_LIVE_SCALE = "rtmp_live_scale_type";

    private static final ComponentIndexType DEFAULT_CAMERA = ComponentIndexType.LEFT_OR_MAIN;
    private static final int DEFAULT_QUALITY_TAG = 2;
    private static final int DEFAULT_CAMERA_SCALE_TAG = 2;
    private static final int DEFAULT_LIVE_SCALE_TAG = 1;
    private static final int MIN_BITRATE_KBPS = 256;
    private static final int MAX_BITRATE_KBPS = 16384;
    private static final int DEFAULT_BITRATE_KBPS = 2048;
    private static final String SAMPLE_APP_RTMP_PREFS_KEY = "livestream-rtmp";

    public enum StreamErrorClass {
        GENERIC,
        CAMERA,
        NETWORK
    }

    public interface StreamHost {
        void onApplyRtmpConfig(@NonNull RtmpConfig config);

        void onStartStream();

        void onStopStream();
    }

    public static final class RtmpConfig {
        public final String url;
        public final ComponentIndexType cameraIndex;
        public final int qualityTag;
        public final boolean manualBitrate;
        public final int bitrateKbps;
        public final int cameraScaleTag;
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
    private final SimpleDateFormat diagTime = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final StringBuilder diagnostics = new StringBuilder();

    @Nullable
    private StreamHost host;

    private View introExpandable;
    private TextView introToggle;
    private TextView tvCameraSummary;
    private Spinner spinnerCamera;
    private ArrayAdapter<String> cameraAdapter;
    private final ArrayList<ComponentIndexType> cameraOrder = new ArrayList<>();
    private EditText etUrl;
    private TextView tvActiveUrl;
    private Button btnCopyUrl;
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
    private TextView tvHealth;
    private TextView tvDiagnostics;
    private TextView tvError;
    private Button btnSave;
    private Button btnStart;
    private Button btnStop;

    private LiveStreamSessionState sessionState = LiveStreamSessionState.IDLE;
    private boolean disconnectedIdleLabel;
    @Nullable
    private String lastFailedMessage;
    private long streamStartUptimeMs;

    @Nullable
    private ComponentIndexType activeFpvCamera;

    private final Runnable uptimeTicker = new Runnable() {
        @Override
        public void run() {
            if (sessionState != LiveStreamSessionState.LIVE || tvUptime == null) {
                return;
            }
            long elapsedMs = SystemClock.uptimeMillis() - streamStartUptimeMs;
            tvUptime.setText(activity.getString(
                    R.string.uxsdk_rtmp_uptime_with_separator, formatUptime(elapsedMs)));
            tvUptime.postDelayed(this, 1000L);
        }
    };

    public RtmpSettingsPanel(@NonNull AppCompatActivity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void bindRtmpPanelRoot(@NonNull View panelRoot, @NonNull StreamHost streamHost) {
        introExpandable = panelRoot.findViewById(R.id.uxsdk_rtmp_setup_expandable);
        introToggle = panelRoot.findViewById(R.id.uxsdk_rtmp_setup_toggle);
        tvCameraSummary = panelRoot.findViewById(R.id.uxsdk_rtmp_tv_camera_summary);
        spinnerCamera = panelRoot.findViewById(R.id.uxsdk_rtmp_spinner_camera);
        etUrl = panelRoot.findViewById(R.id.uxsdk_rtmp_et_url);
        tvActiveUrl = panelRoot.findViewById(R.id.uxsdk_rtmp_tv_active_url);
        btnCopyUrl = panelRoot.findViewById(R.id.uxsdk_rtmp_btn_copy_url);
        rgQuality = panelRoot.findViewById(R.id.uxsdk_rtmp_rg_quality);
        rgBitrateMode = panelRoot.findViewById(R.id.uxsdk_rtmp_rg_bitrate_mode);
        manualBitrateRow = panelRoot.findViewById(R.id.uxsdk_rtmp_manual_bitrate_row);
        sbBitrate = panelRoot.findViewById(R.id.uxsdk_rtmp_sb_bitrate);
        tvBitrate = panelRoot.findViewById(R.id.uxsdk_rtmp_tv_bitrate);
        rgCameraScale = panelRoot.findViewById(R.id.uxsdk_rtmp_rg_cam_scale);
        rgLiveScale = panelRoot.findViewById(R.id.uxsdk_rtmp_rg_live_scale);
        tvStatus = panelRoot.findViewById(R.id.uxsdk_rtmp_status_text);
        statusDot = panelRoot.findViewById(R.id.uxsdk_rtmp_status_dot);
        tvUptime = panelRoot.findViewById(R.id.uxsdk_rtmp_status_uptime);
        tvStatsResolution = panelRoot.findViewById(R.id.uxsdk_rtmp_tv_stats_resolution);
        tvStatsFps = panelRoot.findViewById(R.id.uxsdk_rtmp_tv_stats_fps);
        tvStatsBitrate = panelRoot.findViewById(R.id.uxsdk_rtmp_tv_stats_bitrate);
        tvHealth = panelRoot.findViewById(R.id.uxsdk_rtmp_tv_health);
        tvDiagnostics = panelRoot.findViewById(R.id.uxsdk_rtmp_tv_diagnostics);
        tvError = panelRoot.findViewById(R.id.uxsdk_rtmp_tv_error);
        btnSave = panelRoot.findViewById(R.id.uxsdk_rtmp_btn_save);
        btnStart = panelRoot.findViewById(R.id.uxsdk_rtmp_btn_start);
        btnStop = panelRoot.findViewById(R.id.uxsdk_rtmp_btn_stop);

        cameraAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        spinnerCamera.setAdapter(cameraAdapter);
        spinnerCamera.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateCameraSummaryUi();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        wireIntro();
        wireRtmpForm();
        restoreFromPrefs();
        tvBitrate.setText(activity.getString(
                R.string.uxsdk_rtmp_bitrate_kbps, progressToKbps(sbBitrate.getProgress())));
        applyStreamState(LiveStreamSessionState.IDLE, null, false);
        renderEmptyStats();
        setStreamHealth(activity.getString(R.string.uxsdk_rtmp_health_offline));
        appendDiagnosticLine("Panel bound");
        setHost(streamHost);
    }

    public void setHost(@Nullable StreamHost streamHost) {
        this.host = streamHost;
    }

    /**
     * Forward {@link dji.v5.manager.interfaces.ICameraStreamManager.AvailableCameraUpdatedListener} results.
     */
    public void onDeviceCamerasUpdated(@NonNull List<ComponentIndexType> available,
                                       @Nullable ComponentIndexType primaryFpv) {
        activeFpvCamera = primaryFpv;
        cameraOrder.clear();
        cameraOrder.addAll(available);
        ArrayList<String> labels = new ArrayList<>();
        for (ComponentIndexType c : cameraOrder) {
            labels.add(formatCameraLabel(c));
        }
        cameraAdapter.clear();
        cameraAdapter.addAll(labels);
        cameraAdapter.notifyDataSetChanged();

        if (cameraOrder.isEmpty()) {
            appendDiagnosticLine("Camera list empty from SDK");
        } else {
            int select = 0;
            if (primaryFpv != null && primaryFpv != ComponentIndexType.UNKNOWN) {
                int idx = cameraOrder.indexOf(primaryFpv);
                if (idx >= 0) {
                    select = idx;
                }
            }
            spinnerCamera.setSelection(select, false);
            appendDiagnosticLine("Cameras updated count=" + cameraOrder.size() + " primaryFpv=" + primaryFpv);
        }
        updateCameraSummaryUi();
        updateButtonsForStreamState(sessionState);
    }

    public void updateCameraFromHost(@Nullable ComponentIndexType cameraIndex) {
        if (cameraIndex == null || cameraIndex == ComponentIndexType.UNKNOWN) {
            return;
        }
        activeFpvCamera = cameraIndex;
        updateCameraSummaryUi();
    }

    public void onDestroy() {
        if (tvUptime != null) {
            tvUptime.removeCallbacks(uptimeTicker);
        }
        sessionState = LiveStreamSessionState.IDLE;
        disconnectedIdleLabel = false;
    }

    @NonNull
    public LiveStreamSessionState getStreamSessionState() {
        return sessionState;
    }

    public boolean isConnecting() {
        return sessionState == LiveStreamSessionState.CONNECTING;
    }

    public boolean isLive() {
        return sessionState == LiveStreamSessionState.LIVE;
    }

    public boolean isReconnecting() {
        return sessionState == LiveStreamSessionState.RECONNECTING;
    }

    @NonNull
    public LiveStreamSessionState applyStreamState(
            @NonNull LiveStreamSessionState newState,
            @Nullable String errorMessage,
            boolean showDisconnectedIdleLabel) {
        LiveStreamSessionState prev = sessionState;
        if (prev == newState && newState != LiveStreamSessionState.FAILED) {
            if (newState != LiveStreamSessionState.IDLE
                    || disconnectedIdleLabel == showDisconnectedIdleLabel) {
                return prev;
            }
        }
        if (prev == newState && newState == LiveStreamSessionState.FAILED) {
            if (TextUtils.equals(lastFailedMessage, errorMessage)) {
                return prev;
            }
        }

        sessionState = newState;
        disconnectedIdleLabel = (newState == LiveStreamSessionState.IDLE) && showDisconnectedIdleLabel;
        lastFailedMessage = newState == LiveStreamSessionState.FAILED ? errorMessage : null;

        switch (newState) {
            case IDLE:
                if (tvStatus != null) {
                    tvStatus.setText(disconnectedIdleLabel
                            ? R.string.uxsdk_rtmp_status_disconnected
                            : R.string.uxsdk_rtmp_status_idle);
                }
                tintStatusDot(R.color.uxsdk_white_50_percent);
                stopUptimeTickerAndClear();
                if (tvError != null) {
                    tvError.setVisibility(View.GONE);
                }
                break;
            case CONNECTING:
                if (tvStatus != null) {
                    tvStatus.setText(R.string.uxsdk_rtmp_status_connecting);
                }
                tintStatusDot(R.color.uxsdk_yellow_500);
                stopUptimeTickerAndClear();
                if (tvError != null) {
                    tvError.setVisibility(View.GONE);
                }
                break;
            case LIVE:
                if (prev != LiveStreamSessionState.RECONNECTING) {
                    streamStartUptimeMs = SystemClock.uptimeMillis();
                }
                if (tvStatus != null) {
                    tvStatus.setText(R.string.uxsdk_rtmp_status_live);
                }
                tintStatusDot(R.color.uxsdk_green);
                if (tvUptime != null) {
                    tvUptime.removeCallbacks(uptimeTicker);
                    tvUptime.setText(activity.getString(
                            R.string.uxsdk_rtmp_uptime_with_separator, formatUptime(0L)));
                    tvUptime.post(uptimeTicker);
                }
                if (tvError != null) {
                    tvError.setVisibility(View.GONE);
                }
                break;
            case RECONNECTING:
                if (tvStatus != null) {
                    tvStatus.setText(R.string.uxsdk_rtmp_status_reconnecting);
                }
                tintStatusDot(R.color.uxsdk_orange_material_800);
                if (tvUptime != null) {
                    tvUptime.removeCallbacks(uptimeTicker);
                }
                if (tvError != null) {
                    tvError.setVisibility(View.GONE);
                }
                break;
            case FAILED:
                if (tvStatus != null) {
                    tvStatus.setText(R.string.uxsdk_rtmp_status_failed);
                }
                tintStatusDot(R.color.uxsdk_red);
                stopUptimeTickerAndClear();
                if (tvError != null) {
                    tvError.setText(errorMessage == null ? "" : errorMessage);
                    tvError.setVisibility(View.VISIBLE);
                }
                break;
            case STOPPED:
                if (tvStatus != null) {
                    tvStatus.setText(R.string.uxsdk_rtmp_status_stopped);
                }
                tintStatusDot(R.color.uxsdk_white_50_percent);
                stopUptimeTickerAndClear();
                if (tvError != null) {
                    tvError.setVisibility(View.GONE);
                }
                break;
            default:
                break;
        }
        updateButtonsForStreamState(newState);
        return prev;
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

    public void setStreamHealth(@NonNull String label) {
        if (tvHealth != null) {
            tvHealth.setText(label);
        }
    }

    public void setAppliedRtmpUrl(@NonNull String url) {
        if (tvActiveUrl != null) {
            tvActiveUrl.setText(url);
        }
    }

    public void appendDiagnosticLine(@NonNull String line) {
        String stamp = diagTime.format(new Date());
        String row = "[" + stamp + "] " + line + "\n";
        diagnostics.append(row);
        while (diagnostics.length() > 6000) {
            int cut = diagnostics.indexOf("\n", 500);
            if (cut < 0) {
                diagnostics.delete(0, diagnostics.length() / 2);
            } else {
                diagnostics.delete(0, cut + 1);
            }
        }
        LogUtils.i(LOG_TAG, line);
        if (tvDiagnostics != null) {
            tvDiagnostics.setText(diagnostics.toString());
        }
    }

    public void toastStreamMessage(@NonNull String message, int duration) {
        Toast.makeText(activity, message, duration).show();
    }

    private void wireIntro() {
        if (introToggle == null || introExpandable == null) {
            return;
        }
        introToggle.setOnClickListener(v -> {
            boolean show = introExpandable.getVisibility() != View.VISIBLE;
            introExpandable.setVisibility(show ? View.VISIBLE : View.GONE);
            introToggle.setText(show
                    ? activity.getString(R.string.uxsdk_rtmp_setup_toggle_collapse)
                    : activity.getString(R.string.uxsdk_rtmp_setup_toggle_expand));
        });
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

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        btnCopyUrl.setOnClickListener(v -> copyUrlToClipboard());
        btnSave.setOnClickListener(v -> onSaveClicked());
        btnStart.setOnClickListener(v -> onStartClicked());
        btnStop.setOnClickListener(v -> onStopClicked());
    }

    private void copyUrlToClipboard() {
        String url = etUrl.getText() == null ? "" : etUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            toastStreamMessage(activity.getString(R.string.uxsdk_rtmp_msg_url_empty), Toast.LENGTH_SHORT);
            return;
        }
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("rtmp", url));
            toastStreamMessage(activity.getString(R.string.uxsdk_rtmp_toast_clipboard_copied), Toast.LENGTH_SHORT);
        }
    }

    private void onSaveClicked() {
        RtmpConfig cfg = readForm();
        if (!validateUrlOrToast(cfg.url)) {
            return;
        }
        persistToPrefs(cfg);
        toastStreamMessage(activity.getString(R.string.uxsdk_rtmp_msg_saved), Toast.LENGTH_SHORT);
        if (host != null) {
            host.onApplyRtmpConfig(cfg);
        }
    }

    private void onStartClicked() {
        if (cameraOrder.isEmpty()) {
            toastStreamMessage(activity.getString(R.string.uxsdk_rtmp_toast_camera_unavailable,
                    activity.getString(R.string.uxsdk_rtmp_camera_none)), Toast.LENGTH_LONG);
            return;
        }
        RtmpConfig cfg = readForm();
        if (!validateUrlOrToast(cfg.url)) {
            return;
        }
        new AlertDialog.Builder(activity, R.style.UXSDKDefaultLayoutDarkAlertDialog)
                .setTitle(R.string.uxsdk_rtmp_confirm_start_title)
                .setMessage(R.string.uxsdk_rtmp_confirm_start_message)
                .setNegativeButton(R.string.uxsdk_app_cancel, (d, w) -> d.dismiss())
                .setPositiveButton(R.string.uxsdk_rtmp_btn_start, (d, w) -> {
                    d.dismiss();
                    persistToPrefs(cfg);
                    if (host == null) {
                        renderError(activity.getString(R.string.uxsdk_rtmp_msg_host_not_ready));
                        return;
                    }
                    host.onApplyRtmpConfig(cfg);
                    host.onStartStream();
                })
                .show();
    }

    private void onStopClicked() {
        new AlertDialog.Builder(activity, R.style.UXSDKDefaultLayoutDarkAlertDialog)
                .setTitle(R.string.uxsdk_rtmp_confirm_stop_title)
                .setMessage(R.string.uxsdk_rtmp_confirm_stop_message)
                .setNegativeButton(R.string.uxsdk_app_cancel, (d, w) -> d.dismiss())
                .setPositiveButton(R.string.uxsdk_rtmp_btn_stop, (d, w) -> {
                    d.dismiss();
                    if (host == null) {
                        applyStreamState(LiveStreamSessionState.IDLE, null, false);
                        return;
                    }
                    host.onStopStream();
                })
                .show();
    }

    private void restoreFromPrefs() {
        etUrl.setText(prefs.getString(KEY_URL, ""));
        if (TextUtils.isEmpty(etUrl.getText())) {
            String sampleUrl = readRtmpUrlFromSampleAppSharedPrefs();
            if (!TextUtils.isEmpty(sampleUrl)) {
                etUrl.setText(sampleUrl);
            }
        }

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

        String applied = prefs.getString(KEY_URL, "").trim();
        if (!applied.isEmpty()) {
            setAppliedRtmpUrl(applied);
        }
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
        writeRtmpUrlToSampleAppSharedPrefs(cfg.url);
    }

    private static void writeRtmpUrlToSampleAppSharedPrefs(@NonNull String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        try {
            DjiSharedPreferencesManager.putString(
                    ContextUtil.getContext(), SAMPLE_APP_RTMP_PREFS_KEY, url.trim());
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    private static String readRtmpUrlFromSampleAppSharedPrefs() {
        try {
            String u = DjiSharedPreferencesManager.getString(
                    ContextUtil.getContext(), SAMPLE_APP_RTMP_PREFS_KEY, "");
            return TextUtils.isEmpty(u) ? null : u.trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @NonNull
    private RtmpConfig readForm() {
        String url = etUrl.getText() == null ? "" : etUrl.getText().toString().trim();
        int quality = readCheckedTag(rgQuality, DEFAULT_QUALITY_TAG);
        boolean manual = rgBitrateMode.getCheckedRadioButtonId() == R.id.uxsdk_rtmp_rb_br_manual;
        int bitrate = progressToKbps(sbBitrate.getProgress());
        int cameraScale = readCheckedTag(rgCameraScale, DEFAULT_CAMERA_SCALE_TAG);
        int liveScale = readCheckedTag(rgLiveScale, DEFAULT_LIVE_SCALE_TAG);
        ComponentIndexType cam = getSelectedCamera();
        if (cam == null) {
            cam = DEFAULT_CAMERA;
        }
        return new RtmpConfig(url, cam, quality, manual, bitrate, cameraScale, liveScale);
    }

    @Nullable
    private ComponentIndexType getSelectedCamera() {
        if (cameraOrder.isEmpty()) {
            return null;
        }
        int pos = spinnerCamera.getSelectedItemPosition();
        if (pos < 0 || pos >= cameraOrder.size()) {
            return cameraOrder.get(0);
        }
        return cameraOrder.get(pos);
    }

    private void updateCameraSummaryUi() {
        if (tvCameraSummary == null) {
            return;
        }
        String avail;
        if (cameraOrder.isEmpty()) {
            avail = activity.getString(R.string.uxsdk_rtmp_camera_none);
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cameraOrder.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(formatCameraLabel(cameraOrder.get(i)));
            }
            avail = sb.toString();
        }
        String fpv = activeFpvCamera == null || activeFpvCamera == ComponentIndexType.UNKNOWN
                ? "—" : formatCameraLabel(activeFpvCamera);
        ComponentIndexType sel = getSelectedCamera();
        String live = sel == null ? "—" : formatCameraLabel(sel);
        tvCameraSummary.setText(activity.getString(R.string.uxsdk_rtmp_camera_summary, avail, fpv, live));
    }

    @NonNull
    private static String formatCameraLabel(@NonNull ComponentIndexType c) {
        return c.name();
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

    private void renderError(@Nullable String message) {
        applyStreamState(LiveStreamSessionState.FAILED, message, false);
    }

    private void renderEmptyStats() {
        renderStats(null, null, null);
    }

    private void stopUptimeTickerAndClear() {
        if (tvUptime != null) {
            tvUptime.removeCallbacks(uptimeTicker);
            tvUptime.setText("");
        }
    }

    private void updateButtonsForStreamState(@NonNull LiveStreamSessionState state) {
        boolean inFlight = state == LiveStreamSessionState.CONNECTING
                || state == LiveStreamSessionState.LIVE
                || state == LiveStreamSessionState.RECONNECTING;
        if (btnStart != null) {
            btnStart.setEnabled(!inFlight && !cameraOrder.isEmpty());
        }
        if (btnStop != null) {
            btnStop.setEnabled(inFlight);
        }
    }

    private void tintStatusDot(int colorRes) {
        if (statusDot == null) {
            return;
        }
        statusDot.setBackgroundTintList(ContextCompat.getColorStateList(activity, colorRes));
    }

    private static int readCheckedTag(RadioGroup group, int fallback) {
        int checkedId = group.getCheckedRadioButtonId();
        if (checkedId == View.NO_ID) {
            return fallback;
        }
        View checked = group.findViewById(checkedId);
        if (!(checked instanceof RadioButton)) {
            return fallback;
        }
        Object tag = checked.getTag();
        if (tag instanceof String) {
            try {
                return Integer.parseInt((String) tag);
            } catch (NumberFormatException ignored) { /* fall through */ }
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
        if (span <= 0) {
            return 0;
        }
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
