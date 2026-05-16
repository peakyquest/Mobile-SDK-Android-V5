/*
 * Production-oriented bridge between {@link RtmpSettingsPanel} and DJI MSDK v5 {@link ILiveStreamManager}.
 *
 * <p>Key APIs (see project {@code Docs/Android_API/.../ILiveStreamManager.html}):
 * {@link ILiveStreamManager#setLiveStreamSettings}, {@link ILiveStreamManager#setCameraIndex},
 * {@link ILiveStreamManager#setLiveStreamQuality}, {@link ILiveStreamManager#setLiveVideoBitrateMode},
 * {@link ILiveStreamManager#setLiveVideoBitrate}, {@link ILiveStreamManager#setLiveStreamScaleType},
 * {@link ILiveStreamManager#startStream}, {@link ILiveStreamManager#stopStream},
 * {@link ILiveStreamManager#addLiveStreamStatusListener}.
 *
 * <p>{@link dji.v5.manager.datacenter.livestream.LiveStreamStatus} exposes FPS, vbps, resolution, packet loss,
 * packet cache length, and RTT (documented as streaming latency) — see
 * {@code ILiveStreamManager_LiveStreamStatus.html}.
 *
 * <p><b>Scaling caveat:</b> UI "Camera (local) scale" maps to {@link ICameraStreamManager#putCameraStreamSurface}
 * scale for <em>on-device preview surfaces</em>, not to {@code ILiveStreamManager}. Only
 * {@link ILiveStreamManager#setLiveStreamScaleType} affects the encoded live-stream branch (MSDK 5.10+); changing
 * quality/scale may require stopping and restarting the stream on some firmware builds.
 *
 * <p>Decoder readiness: {@link ICameraStreamManager#setKeepAliveDecoding} keeps the pipeline warm so
 * {@code startStream} is less likely to fail with LIVE_STREAM_IS_NOT_READY when no surface briefly
 * references the stream (MSDK 5.8+).
 */

package dji.v5.ux.sample.showcase.livestream;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import java.lang.reflect.Method;

import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.v5.common.callback.CommonCallbacks;
import dji.v5.common.error.IDJIError;
import dji.v5.manager.datacenter.MediaDataCenter;
import dji.v5.manager.datacenter.livestream.LiveStreamSettings;
import dji.v5.manager.datacenter.livestream.LiveStreamStatus;
import dji.v5.manager.datacenter.livestream.LiveStreamStatusListener;
import dji.v5.manager.datacenter.livestream.LiveStreamType;
import dji.v5.manager.datacenter.livestream.LiveVideoBitrateMode;
import dji.v5.manager.datacenter.livestream.StreamQuality;
import dji.v5.manager.datacenter.livestream.VideoResolution;
import dji.v5.manager.datacenter.livestream.settings.RtmpSettings;
import dji.v5.manager.interfaces.ICameraStreamManager;
import dji.v5.manager.interfaces.ILiveStreamManager;
import dji.v5.ux.R;
import dji.v5.utils.common.LogUtils;

/**
 * Owns RTMP lifecycle, status listener, and UI callbacks. Filter logcat by tag {@value #LOG_TAG}.
 */
public final class LiveStreamBridge implements RtmpSettingsPanel.StreamHost {

    /** Use: {@code adb logcat -s UxRtmp} */
    public static final String LOG_TAG = "UxRtmp";

    private static final int MIN_VBPS_FOR_LIVE = 32 * 1024;
    private static final long CONNECTING_TIMEOUT_MS = 45_000L;
    private static final long STATUS_VERBOSE_INTERVAL_MS = 2_000L;

    private final Activity activity;
    private final RtmpSettingsPanel panel;
    private final ILiveStreamManager liveStreamManager;
    private final ICameraStreamManager cameraStreamManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);

    private boolean attached;
    private boolean configApplied;
    private boolean userRequestedStop;
    private boolean hadEncoderPublish;

    private int reconnectCount;
    private long lastVerboseStatusLogMs;

    /** Last applied operator settings (for diagnostics when SDK omits vbps, etc.). */
    @Nullable
    private String lastAppliedUrl;
    private int lastCameraScaleTag = -1;
    private int lastLiveScaleTag = -1;
    private int lastQualityTag = -1;
    private boolean lastManualBitrate;
    private int lastBitrateKbps;

    @Nullable
    private Runnable connectingTimeoutRunnable;

    private final LiveStreamStatusListener statusListener = new LiveStreamStatusListener() {
        @Override
        public void onLiveStreamStatusUpdate(@Nullable LiveStreamStatus status) {
            if (status == null) {
                return;
            }
            postOnUi(() -> handleStatusUpdate(status));
        }

        @Override
        public void onError(@Nullable IDJIError error) {
            if (error == null) {
                return;
            }
            logDjiError("LiveStream onError", error);
            postOnUi(() -> {
                cancelConnectingTimeout();
                hadEncoderPublish = false;
                String msg = safeDescription(error);
                panel.appendDiagnosticLine("Stream failure (SDK onError): " + msg);
                emitState(LiveStreamSessionState.FAILED, msg, false, true, classifyError(msg, error));
            });
        }
    };

    public LiveStreamBridge(@NonNull Activity activity, @NonNull RtmpSettingsPanel panel) {
        this.activity = activity;
        this.panel = panel;
        this.liveStreamManager = MediaDataCenter.getInstance().getLiveStreamManager();
        this.cameraStreamManager = MediaDataCenter.getInstance().getCameraStreamManager();
    }

    @MainThread
    public void attach() {
        if (attached) {
            return;
        }
        attached = true;
        hadEncoderPublish = false;
        userRequestedStop = false;
        reconnectCount = 0;
        lastVerboseStatusLogMs = 0L;
        LogUtils.i(LOG_TAG, "attach: register LiveStreamStatusListener, setKeepAliveDecoding(true)");
        enableKeepAliveDecoding(true);
        liveStreamManager.addLiveStreamStatusListener(statusListener);
        if (liveStreamManager.isStreaming()) {
            scheduleConnectingTimeout();
            emitState(LiveStreamSessionState.CONNECTING, null, false, false, RtmpSettingsPanel.StreamErrorClass.GENERIC);
        } else {
            panel.applyStreamState(LiveStreamSessionState.IDLE, null, false);
        }
    }

    @MainThread
    public void detach() {
        cancelConnectingTimeout();
        if (!attached) {
            return;
        }
        attached = false;
        LogUtils.i(LOG_TAG, "detach: remove listener, stop stream if running, setKeepAliveDecoding(false)");
        liveStreamManager.removeLiveStreamStatusListener(statusListener);
        enableKeepAliveDecoding(false);
        if (liveStreamManager.isStreaming()) {
            liveStreamManager.stopStream(null);
        }
        hadEncoderPublish = false;
        userRequestedStop = false;
        panel.appendDiagnosticLine("Encoder / stream session torn down (detach)");
        panel.applyStreamState(LiveStreamSessionState.IDLE, null, false);
    }

    @Override
    public void onApplyRtmpConfig(@NonNull RtmpSettingsPanel.RtmpConfig config) {
        applyConfig(config);
    }

    @Override
    public void onStartStream() {
        LogUtils.i(LOG_TAG, "onStartStream requested");
        if (liveStreamManager.isStreaming()) {
            LogUtils.w(LOG_TAG, "onStartStream ignored: already streaming");
            return;
        }
        if (!configApplied) {
            emitState(LiveStreamSessionState.FAILED,
                    activity.getString(R.string.uxsdk_rtmp_msg_apply_before_start),
                    false, true, RtmpSettingsPanel.StreamErrorClass.GENERIC);
            return;
        }
        userRequestedStop = false;
        hadEncoderPublish = false;
        scheduleConnectingTimeout();
        panel.appendDiagnosticLine("Encoder init: startStream() requested (RTMP handshake begins after SDK ready)");
        emitState(LiveStreamSessionState.CONNECTING, null, false, false, RtmpSettingsPanel.StreamErrorClass.GENERIC);
        ComponentIndexType cam = liveStreamManager.getCameraIndex();
        panel.appendDiagnosticLine("startStream() cameraIndex=" + cam);
        liveStreamManager.startStream(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                LogUtils.i(LOG_TAG, "startStream onSuccess (await LiveStreamStatus for LIVE / publisher metrics)");
            }

            @Override
            public void onFailure(@NonNull IDJIError error) {
                logDjiError("startStream onFailure", error);
                postOnUi(() -> {
                    cancelConnectingTimeout();
                    hadEncoderPublish = false;
                    String msg = safeDescription(error);
                    panel.appendDiagnosticLine("startStream failed: " + msg);
                    emitState(LiveStreamSessionState.FAILED, msg, false, true, classifyError(msg, error));
                });
            }
        });
    }

    @Override
    public void onStopStream() {
        LogUtils.i(LOG_TAG, "onStopStream requested");
        userRequestedStop = true;
        if (!liveStreamManager.isStreaming()) {
            cancelConnectingTimeout();
            userRequestedStop = false;
            emitState(LiveStreamSessionState.IDLE, null, false, false, RtmpSettingsPanel.StreamErrorClass.GENERIC);
            return;
        }
        liveStreamManager.stopStream(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                LogUtils.i(LOG_TAG, "RTMP Stream Stopped Successfully (operator stop, SDK stopStream onSuccess)");
                postOnUi(() -> {
                    cancelConnectingTimeout();
                    hadEncoderPublish = false;
                    userRequestedStop = false;
                    panel.appendDiagnosticLine("Stream stopped @ " + isoFmt.format(new Date()));
                    LiveStreamSessionState ps = panel.getStreamSessionState();
                    if (ps == LiveStreamSessionState.CONNECTING) {
                        emitState(LiveStreamSessionState.IDLE, null, false, false, RtmpSettingsPanel.StreamErrorClass.GENERIC);
                    } else if (ps == LiveStreamSessionState.LIVE
                            || ps == LiveStreamSessionState.RECONNECTING) {
                        emitState(LiveStreamSessionState.STOPPED, null, false, true, RtmpSettingsPanel.StreamErrorClass.GENERIC);
                    }
                });
            }

            @Override
            public void onFailure(@NonNull IDJIError error) {
                logDjiError("stopStream onFailure", error);
                postOnUi(() -> {
                    userRequestedStop = false;
                    String msg = safeDescription(error);
                    emitState(LiveStreamSessionState.FAILED, msg, false, true, RtmpSettingsPanel.StreamErrorClass.GENERIC);
                });
            }
        });
    }

    private void applyConfig(@NonNull RtmpSettingsPanel.RtmpConfig cfg) {
        if (TextUtils.isEmpty(cfg.url)) {
            return;
        }
        try {
            lastAppliedUrl = cfg.url.trim();
            lastCameraScaleTag = cfg.cameraScaleTag;
            lastLiveScaleTag = cfg.liveScaleTag;
            lastQualityTag = cfg.qualityTag;
            lastManualBitrate = cfg.manualBitrate;
            lastBitrateKbps = cfg.bitrateKbps;

            LiveStreamSettings settings = new LiveStreamSettings.Builder()
                    .setLiveStreamType(LiveStreamType.RTMP)
                    .setRtmpSettings(new RtmpSettings.Builder()
                            .setUrl(cfg.url)
                            .build())
                    .build();
            liveStreamManager.setLiveStreamSettings(settings);
            liveStreamManager.setCameraIndex(cfg.cameraIndex == null
                    ? ComponentIndexType.LEFT_OR_MAIN
                    : cfg.cameraIndex);

            StreamQuality quality = StreamQuality.find(cfg.qualityTag);
            if (quality != null) {
                liveStreamManager.setLiveStreamQuality(quality);
            }

            liveStreamManager.setLiveVideoBitrateMode(cfg.manualBitrate
                    ? LiveVideoBitrateMode.MANUAL
                    : LiveVideoBitrateMode.AUTO);
            if (cfg.manualBitrate) {
                liveStreamManager.setLiveVideoBitrate(Math.max(0, cfg.bitrateKbps) * 1024);
            }

            ICameraStreamManager.ScaleType liveScale = ICameraStreamManager.ScaleType.find(cfg.liveScaleTag);
            if (liveScale != null) {
                liveStreamManager.setLiveStreamScaleType(liveScale);
            }

            configApplied = true;
            panel.setAppliedRtmpUrl(cfg.url.trim());

            LogUtils.i(LOG_TAG, "applyConfig requested: urlLen=" + cfg.url.length()
                    + " cameraIndex=" + cfg.cameraIndex
                    + " qualityTag=" + cfg.qualityTag
                    + " cameraScaleTag(UI)=" + cfg.cameraScaleTag
                    + " liveScaleTag=" + cfg.liveScaleTag
                    + " manualBr=" + cfg.manualBitrate
                    + " bitrateKbps=" + cfg.bitrateKbps);
            panel.appendDiagnosticLine("Config pushed: qualityTag=" + cfg.qualityTag
                    + " liveScaleTag=" + cfg.liveScaleTag
                    + " camScaleTag(UI only)=" + cfg.cameraScaleTag);
            panel.appendDiagnosticLine(
                    "Note: camScaleTag is for ICameraStreamManager.putCameraStreamSurface preview, "
                            + "not ILiveStreamManager. Live encode uses setLiveStreamScaleType (MSDK 5.10+). "
                            + "Restart stream after quality/scale changes if output looks unchanged.");

            logManagerReadbackAfterApply();
        } catch (Throwable t) {
            LogUtils.e(LOG_TAG, "applyConfig exception: " + t);
            panel.appendDiagnosticLine("applyConfig exception: " + t);
            emitState(LiveStreamSessionState.FAILED,
                    t.getMessage() == null ? t.toString() : t.getMessage(),
                    false, true, RtmpSettingsPanel.StreamErrorClass.GENERIC);
        }
    }

    private void logManagerReadbackAfterApply() {
        try {
            LogUtils.i(LOG_TAG, "readback getCameraIndex=" + liveStreamManager.getCameraIndex());
        } catch (Throwable t) {
            LogUtils.w(LOG_TAG, "readback camera: " + t);
        }
        try {
            LogUtils.i(LOG_TAG, "readback getLiveStreamQuality=" + liveStreamManager.getLiveStreamQuality());
        } catch (Throwable t) {
            LogUtils.w(LOG_TAG, "readback quality: " + t);
        }
        try {
            LogUtils.i(LOG_TAG, "readback getLiveStreamScaleType=" + liveStreamManager.getLiveStreamScaleType());
        } catch (Throwable t) {
            LogUtils.w(LOG_TAG, "readback liveStreamScaleType (needs MSDK 5.10+): " + t);
        }
        try {
            LogUtils.i(LOG_TAG, "readback bitrateMode=" + liveStreamManager.getLiveVideoBitrateMode()
                    + " bitrate(bps)=" + liveStreamManager.getLiveVideoBitrate());
        } catch (Throwable t) {
            LogUtils.w(LOG_TAG, "readback bitrate: " + t);
        }
    }

    @MainThread
    private void handleStatusUpdate(@NonNull LiveStreamStatus status) {
        boolean managerOn = liveStreamManager.isStreaming();
        boolean publishing = isActivelyPublishing(status);
        LiveStreamSessionState prev = panel.getStreamSessionState();

        if (!managerOn) {
            cancelConnectingTimeout();
            panel.setStreamHealth(activity.getString(R.string.uxsdk_rtmp_health_offline),
                    R.color.uxsdk_white_70_percent);
            panel.renderLiveDiagnosticsMetrics(null);
            if (userRequestedStop) {
                userRequestedStop = false;
                hadEncoderPublish = false;
                if (prev == LiveStreamSessionState.CONNECTING) {
                    emitState(LiveStreamSessionState.IDLE, null, false, false, RtmpSettingsPanel.StreamErrorClass.GENERIC);
                } else if (prev != LiveStreamSessionState.IDLE
                        && prev != LiveStreamSessionState.STOPPED
                        && prev != LiveStreamSessionState.FAILED) {
                    boolean showStopToast = prev == LiveStreamSessionState.LIVE
                            || prev == LiveStreamSessionState.RECONNECTING;
                    emitState(LiveStreamSessionState.STOPPED, null, false, showStopToast, RtmpSettingsPanel.StreamErrorClass.GENERIC);
                }
            } else {
                if (prev == LiveStreamSessionState.LIVE
                        || prev == LiveStreamSessionState.RECONNECTING) {
                    hadEncoderPublish = false;
                    LogUtils.w(LOG_TAG, "stream ended without local stop (link/server)");
                    panel.appendDiagnosticLine("Stream dropped (manager reports not streaming)");
                    emitState(LiveStreamSessionState.IDLE, null, true, true, RtmpSettingsPanel.StreamErrorClass.NETWORK);
                } else if (prev == LiveStreamSessionState.CONNECTING) {
                    hadEncoderPublish = false;
                    emitState(LiveStreamSessionState.IDLE, null, false, false, RtmpSettingsPanel.StreamErrorClass.GENERIC);
                }
            }
        } else {
            if (publishing) {
                cancelConnectingTimeout();
                if (!hadEncoderPublish) {
                    logStreamStartedSuccessBlock(status);
                }
                hadEncoderPublish = true;
                HealthUi health = healthFromMetrics(status);
                panel.setStreamHealth(health.label, health.colorRes);
                emitState(LiveStreamSessionState.LIVE, null, false, true, RtmpSettingsPanel.StreamErrorClass.GENERIC);
            } else {
                panel.setStreamHealth(activity.getString(R.string.uxsdk_rtmp_health_connecting),
                        R.color.uxsdk_yellow_500);
                if (hadEncoderPublish) {
                    LogUtils.w(LOG_TAG, "encoder stalled — UI reconnecting");
                    panel.appendDiagnosticLine("Publisher stalled (fps/vbps low); marking RECONNECTING");
                    emitState(LiveStreamSessionState.RECONNECTING, null, false, false, RtmpSettingsPanel.StreamErrorClass.GENERIC);
                } else {
                    emitState(LiveStreamSessionState.CONNECTING, null, false, false, RtmpSettingsPanel.StreamErrorClass.GENERIC);
                }
            }
        }

        ComponentIndexType camIdx = liveStreamManager.getCameraIndex();
        String encRes = formatResolution(status.getResolution());
        String camFeed = describeAircraftStreamFrame(cameraStreamManager, camIdx);
        String resLine = encRes != null && camFeed != null
                ? activity.getString(R.string.uxsdk_rtmp_stats_encoder_vs_feed, encRes, camFeed)
                : (encRes != null ? encRes : camFeed);

        int vbps = status.getVbps();
        String bitrateLine = buildBitrateDisplay(status, vbps);

        panel.renderStats(resLine, formatFps(status.getFps()), bitrateLine);

        int rtt = status.getRtt();
        int loss = status.getPacketLoss();
        int cache = status.getPacketCacheLen();
        String rttStr = (rtt <= 0) ? "—" : rtt + " ms";
        String metrics = activity.getString(R.string.uxsdk_rtmp_metrics_detail_template,
                rttStr,
                loss,
                cache,
                camFeed != null ? camFeed : "—",
                reconnectCount,
                vbps);
        panel.renderLiveDiagnosticsMetrics(metrics);

        maybeLogVerboseStatusSnapshot(status, publishing, managerOn);
    }

    private void logStreamStartedSuccessBlock(@NonNull LiveStreamStatus status) {
        String ts = isoFmt.format(new Date());
        String url = lastAppliedUrl == null ? "(unknown)" : lastAppliedUrl;
        ComponentIndexType cam = liveStreamManager.getCameraIndex();
        VideoResolution vr = status.getResolution();
        String res = formatResolution(vr);
        LogUtils.i(LOG_TAG, "RTMP Stream Started Successfully @ " + ts
                + " url=" + url
                + " camera=" + cam
                + " encoderOut=" + res
                + " fps=" + status.getFps()
                + " vbps(raw)=" + status.getVbps()
                + " qualityTag=" + lastQualityTag
                + " liveScaleTag=" + lastLiveScaleTag
                + " bitrateMode=" + (lastManualBitrate ? "MANUAL" : "AUTO")
                + " targetKbps=" + lastBitrateKbps);
        panel.appendDiagnosticLine("RTMP Stream Started Successfully @ " + ts);
        panel.appendDiagnosticLine("Server URL: " + url);
        panel.appendDiagnosticLine("Selected camera: " + cam + " | encoder resolution: " + (res != null ? res : "—"));
    }

    private void maybeLogVerboseStatusSnapshot(
            @NonNull LiveStreamStatus status,
            boolean publishing,
            boolean managerOn) {
        long now = SystemClock.uptimeMillis();
        if (now - lastVerboseStatusLogMs < STATUS_VERBOSE_INTERVAL_MS) {
            return;
        }
        lastVerboseStatusLogMs = now;
        LogUtils.i(LOG_TAG, "LiveStreamStatus snapshot: streaming=" + status.isStreaming()
                + " managerOn=" + managerOn
                + " publishing=" + publishing
                + " fps=" + status.getFps()
                + " vbps(raw)=" + status.getVbps()
                + " res=" + formatResolution(status.getResolution())
                + " loss=" + status.getPacketLoss()
                + " cache=" + status.getPacketCacheLen()
                + " rtt(ms)=" + status.getRtt());
    }

    @NonNull
    private String buildBitrateDisplay(@NonNull LiveStreamStatus status, int vbps) {
        if (vbps > 0) {
            return formatBitrateKbpsFromSdk(vbps);
        }
        if (lastManualBitrate && lastBitrateKbps > 0) {
            return activity.getString(R.string.uxsdk_rtmp_bitrate_sdk_zero_manual, lastBitrateKbps);
        }
        int est = heuristicBitrateKbps(status.getResolution(), status.getFps());
        if (est > 0) {
            return activity.getString(R.string.uxsdk_rtmp_bitrate_sdk_zero_auto, est);
        }
        return "0 kbps (SDK vbps=0; no est.)";
    }

    /**
     * DJI docs: {@code getVbps} returns live video bit rate; reference implementation divides by 1024 for kbps.
     * If your build returns kbps already as a small int, log raw {@code vbps} in diagnostics and adjust here.
     */
    @NonNull
    private static String formatBitrateKbpsFromSdk(int vbps) {
        return String.format(Locale.US, "%d kbps (SDK vbps/1024)", vbps / 1024);
    }

    /** Rough Mbps upper bound when SDK does not populate {@code vbps} (AUTO mode). Not a ground-truth meter. */
    private static int heuristicBitrateKbps(@Nullable VideoResolution res, int fps) {
        if (res == null || fps <= 0) {
            return 0;
        }
        int w = res.getWidth();
        int h = res.getHeight();
        if (w <= 0 || h <= 0) {
            return 0;
        }
        long pixels = (long) w * (long) h;
        int est = (int) Math.min(12000, Math.max(400, pixels * fps / 180_000L));
        return est;
    }

    private static final class HealthUi {
        final String label;
        final int colorRes;

        HealthUi(String label, int colorRes) {
            this.label = label;
            this.colorRes = colorRes;
        }
    }

    @NonNull
    private HealthUi healthFromMetrics(@NonNull LiveStreamStatus status) {
        int fps = status.getFps();
        int vbps = status.getVbps();
        int loss = Math.max(0, status.getPacketLoss());
        int rtt = status.getRtt();

        boolean badRtt = rtt > 400;
        boolean midRtt = rtt > 200 && rtt <= 400;
        boolean heavyLoss = loss > 30;
        boolean someLoss = loss > 8;

        if (fps >= 26 && !heavyLoss && !badRtt) {
            return new HealthUi(activity.getString(R.string.uxsdk_rtmp_health_excellent), R.color.uxsdk_green_500);
        }
        if (fps >= 22 && !heavyLoss && !badRtt) {
            return new HealthUi(activity.getString(R.string.uxsdk_rtmp_health_good), R.color.uxsdk_green);
        }
        if (fps >= 16 && !heavyLoss) {
            return new HealthUi(activity.getString(R.string.uxsdk_rtmp_health_fair), R.color.uxsdk_white);
        }
        if (fps > 0 || vbps > 0) {
            if (heavyLoss || badRtt) {
                return new HealthUi(activity.getString(R.string.uxsdk_rtmp_health_poor), R.color.uxsdk_red_500);
            }
            if (someLoss || midRtt || (fps < 16 && fps > 0)) {
                return new HealthUi(activity.getString(R.string.uxsdk_rtmp_health_marginal), R.color.uxsdk_orange_material_800);
            }
            return new HealthUi(activity.getString(R.string.uxsdk_rtmp_health_marginal), R.color.uxsdk_orange_material_800);
        }
        if (vbps >= MIN_VBPS_FOR_LIVE) {
            return new HealthUi(activity.getString(R.string.uxsdk_rtmp_health_good), R.color.uxsdk_green);
        }
        return new HealthUi(activity.getString(R.string.uxsdk_rtmp_health_connecting), R.color.uxsdk_yellow_500);
    }

    /**
     * Some firmware builds report {@code fps} without {@code vbps}; treat healthy FPS as publishing to avoid
     * false "reconnecting" and perpetual "marginal" health when vbps stays at 0.
     */
    private static boolean isActivelyPublishing(@NonNull LiveStreamStatus status) {
        if (!status.isStreaming()) {
            return false;
        }
        int fps = status.getFps();
        int vbps = status.getVbps();
        return fps >= 12 || vbps >= MIN_VBPS_FOR_LIVE;
    }

    @MainThread
    private void emitState(
            @NonNull LiveStreamSessionState next,
            @Nullable String errorMessage,
            boolean disconnectedIdle,
            boolean fireToast,
            @NonNull RtmpSettingsPanel.StreamErrorClass errClass) {
        String displayError = errorMessage;
        if (next == LiveStreamSessionState.FAILED && !TextUtils.isEmpty(errorMessage)) {
            displayError = enrichNotReadyUserMessage(errorMessage);
        }
        LiveStreamSessionState prev = panel.applyStreamState(next, displayError, disconnectedIdle);
        if (prev == next) {
            return;
        }
        if (prev == LiveStreamSessionState.LIVE && next == LiveStreamSessionState.RECONNECTING) {
            reconnectCount++;
            LogUtils.w(LOG_TAG, "Reconnect attempt count=" + reconnectCount);
            panel.appendDiagnosticLine("Reconnect attempt #" + reconnectCount);
        }
        LogUtils.i(LOG_TAG, "state " + prev + " -> " + next + (fireToast ? " (toast)" : ""));
        panel.appendDiagnosticLine("State " + prev + " -> " + next);
        if (!fireToast) {
            return;
        }
        if (next == LiveStreamSessionState.LIVE) {
            if (prev != LiveStreamSessionState.RECONNECTING) {
                panel.toastStreamMessage(activity.getString(R.string.uxsdk_rtmp_toast_streaming_started), Toast.LENGTH_SHORT);
            }
        } else if (next == LiveStreamSessionState.FAILED) {
            String detail = TextUtils.isEmpty(displayError) ? "" : displayError;
            int len = Toast.LENGTH_LONG;
            switch (errClass) {
                case CAMERA:
                    panel.toastStreamMessage(activity.getString(R.string.uxsdk_rtmp_toast_camera_unavailable, detail), len);
                    break;
                case NETWORK:
                    panel.toastStreamMessage(activity.getString(R.string.uxsdk_rtmp_toast_server_unreachable, detail), len);
                    break;
                default:
                    panel.toastStreamMessage(activity.getString(R.string.uxsdk_rtmp_toast_streaming_error, detail), len);
                    break;
            }
        } else if (next == LiveStreamSessionState.STOPPED) {
            panel.toastStreamMessage(activity.getString(R.string.uxsdk_rtmp_toast_stopped), Toast.LENGTH_SHORT);
        } else if (next == LiveStreamSessionState.IDLE && disconnectedIdle) {
            panel.toastStreamMessage(activity.getString(R.string.uxsdk_rtmp_toast_stopped), Toast.LENGTH_SHORT);
        }
    }

    private RtmpSettingsPanel.StreamErrorClass classifyError(@NonNull String msg, @NonNull IDJIError error) {
        String blob = (nz(error.errorCode()) + " " + nz(error.hint()) + " " + msg).toLowerCase(Locale.US);
        if (blob.contains("not_ready") || blob.contains("camera")) {
            return RtmpSettingsPanel.StreamErrorClass.CAMERA;
        }
        if (blob.contains("timeout") || blob.contains("network") || blob.contains("unreachable")
                || blob.contains("connection")) {
            return RtmpSettingsPanel.StreamErrorClass.NETWORK;
        }
        return RtmpSettingsPanel.StreamErrorClass.GENERIC;
    }

    private void scheduleConnectingTimeout() {
        cancelConnectingTimeout();
        connectingTimeoutRunnable = () -> {
            connectingTimeoutRunnable = null;
            if (!panel.isConnecting()) {
                return;
            }
            LogUtils.e(LOG_TAG, "connecting timeout " + CONNECTING_TIMEOUT_MS + "ms");
            panel.appendDiagnosticLine("Timeout waiting for LIVE");
            if (liveStreamManager.isStreaming()) {
                liveStreamManager.stopStream(null);
            }
            hadEncoderPublish = false;
            emitState(LiveStreamSessionState.FAILED,
                    activity.getString(R.string.uxsdk_rtmp_err_connection_timeout),
                    false, true, RtmpSettingsPanel.StreamErrorClass.NETWORK);
        };
        mainHandler.postDelayed(connectingTimeoutRunnable, CONNECTING_TIMEOUT_MS);
    }

    private void cancelConnectingTimeout() {
        if (connectingTimeoutRunnable != null) {
            mainHandler.removeCallbacks(connectingTimeoutRunnable);
            connectingTimeoutRunnable = null;
        }
    }

    private static String formatResolution(@Nullable VideoResolution resolution) {
        if (resolution == null) {
            return null;
        }
        int w = resolution.getWidth();
        int h = resolution.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        return w + " × " + h;
    }

    private static String formatFps(int fps) {
        return fps <= 0 ? null : String.format(Locale.US, "%d", fps);
    }

    @Nullable
    private static String describeAircraftStreamFrame(
            @NonNull ICameraStreamManager mgr,
            @NonNull ComponentIndexType cam) {
        try {
            java.lang.reflect.Method m = ICameraStreamManager.class.getMethod(
                    "getAircraftStreamFrameInfo", ComponentIndexType.class);
            Object fi = m.invoke(mgr, cam);
            if (fi == null) {
                return null;
            }
            Integer w = invokeIntGetter(fi, "getWidth");
            Integer h = invokeIntGetter(fi, "getHeight");
            Integer fr = invokeIntGetter(fi, "getFrameRate");
            if (w != null && h != null && w > 0 && h > 0) {
                String fpsPart = fr != null && fr > 0 ? " @" + fr + "fps" : "";
                return w + "×" + h + fpsPart;
            }
        } catch (Throwable t) {
            LogUtils.w(LOG_TAG, "describeAircraftStreamFrameInfo: " + t);
        }
        return null;
    }

    @Nullable
    private static Integer invokeIntGetter(@NonNull Object target, @NonNull String name) {
        try {
            Method m = target.getClass().getMethod(name);
            Object o = m.invoke(target);
            if (o instanceof Integer) {
                return (Integer) o;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void logDjiError(@NonNull String prefix, @NonNull IDJIError error) {
        LogUtils.e(LOG_TAG, prefix
                + " code=" + nz(error.errorCode())
                + " desc=" + nz(error.description())
                + " hint=" + nz(error.hint()));
    }

    private static String nz(@Nullable String s) {
        return s == null ? "" : s;
    }

    @NonNull
    private static String safeDescription(@NonNull IDJIError error) {
        String desc = nz(error.description()).trim();
        String code = nz(error.errorCode()).trim();
        String hint = nz(error.hint()).trim();

        if (!isWeakUserMessage(desc)) {
            if (code.isEmpty() || desc.contains(code)) {
                return desc;
            }
            return desc + " (" + code + ")";
        }

        StringBuilder sb = new StringBuilder();
        if (!code.isEmpty()) {
            sb.append(code);
        }
        if (!hint.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" — ");
            }
            sb.append(hint);
        }
        if (sb.length() > 0) {
            return sb.toString();
        }
        if (!desc.isEmpty()) {
            return desc;
        }
        String fallback = error.toString();
        return TextUtils.isEmpty(fallback) ? "Error (no details)" : fallback;
    }

    private static boolean isWeakUserMessage(@NonNull String desc) {
        if (TextUtils.isEmpty(desc)) {
            return true;
        }
        String t = desc.trim().toLowerCase(Locale.US);
        return "unknown".equals(t) || "unkonw".equals(t) || "n/a".equals(t) || "null".equals(t) || "?".equals(t);
    }

    @NonNull
    private String enrichNotReadyUserMessage(@NonNull String msg) {
        String compact = msg.trim().toLowerCase(Locale.US);
        if (compact.contains("not ready") || compact.contains("live_stream_is_not_ready")) {
            return msg.trim() + "\n\n" + activity.getString(R.string.uxsdk_rtmp_hint_live_stream_not_ready);
        }
        return msg;
    }

    private void enableKeepAliveDecoding(boolean enable) {
        try {
            cameraStreamManager.setKeepAliveDecoding(enable);
            LogUtils.i(LOG_TAG, "ICameraStreamManager.setKeepAliveDecoding(" + enable + ")");
        } catch (Throwable t) {
            LogUtils.w(LOG_TAG, "setKeepAliveDecoding skipped: " + t);
        }
    }

    private void postOnUi(@NonNull Runnable r) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        activity.runOnUiThread(r);
    }
}
