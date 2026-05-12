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
 * <p>Decoder readiness: {@link ICameraStreamManager#setKeepAliveDecoding} keeps the pipeline warm so
 * {@code startStream} is less likely to fail with LIVE_STREAM_IS_NOT_READY when no surface briefly
 * references the stream (MSDK 5.8+).
 */

package dji.v5.ux.sample.showcase.livestream;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

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

    private final Activity activity;
    private final RtmpSettingsPanel panel;
    private final ILiveStreamManager liveStreamManager;
    private final ICameraStreamManager cameraStreamManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean attached;
    private boolean configApplied;
    private boolean userRequestedStop;
    private boolean hadEncoderPublish;

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
                panel.appendDiagnosticLine("SDK error: " + msg);
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
        emitState(LiveStreamSessionState.CONNECTING, null, false, false, RtmpSettingsPanel.StreamErrorClass.GENERIC);
        ComponentIndexType cam = liveStreamManager.getCameraIndex();
        panel.appendDiagnosticLine("startStream() cameraIndex=" + cam);
        liveStreamManager.startStream(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                LogUtils.i(LOG_TAG, "startStream onSuccess (await status for LIVE)");
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
                LogUtils.i(LOG_TAG, "stopStream onSuccess");
                postOnUi(() -> {
                    cancelConnectingTimeout();
                    hadEncoderPublish = false;
                    userRequestedStop = false;
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
            LogUtils.i(LOG_TAG, "applyConfig ok urlLen=" + cfg.url.length()
                    + " camera=" + cfg.cameraIndex
                    + " qualityTag=" + cfg.qualityTag
                    + " manualBr=" + cfg.manualBitrate
                    + " kbps=" + cfg.bitrateKbps);
            panel.appendDiagnosticLine("Config applied to ILiveStreamManager");
        } catch (Throwable t) {
            LogUtils.e(LOG_TAG, "applyConfig exception: " + t);
            panel.appendDiagnosticLine("applyConfig exception: " + t);
            emitState(LiveStreamSessionState.FAILED,
                    t.getMessage() == null ? t.toString() : t.getMessage(),
                    false, true, RtmpSettingsPanel.StreamErrorClass.GENERIC);
        }
    }

    @MainThread
    private void handleStatusUpdate(@NonNull LiveStreamStatus status) {
        boolean managerOn = liveStreamManager.isStreaming();
        boolean publishing = isActivelyPublishing(status);
        LiveStreamSessionState prev = panel.getStreamSessionState();

        if (!managerOn) {
            cancelConnectingTimeout();
            panel.setStreamHealth(activity.getString(R.string.uxsdk_rtmp_health_offline));
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
                    LogUtils.i(LOG_TAG, "publisher active fps=" + status.getFps() + " vbps=" + status.getVbps());
                }
                hadEncoderPublish = true;
                panel.setStreamHealth(healthLabelFromMetrics(status));
                emitState(LiveStreamSessionState.LIVE, null, false, true, RtmpSettingsPanel.StreamErrorClass.GENERIC);
            } else {
                panel.setStreamHealth(activity.getString(R.string.uxsdk_rtmp_health_connecting));
                if (hadEncoderPublish) {
                    LogUtils.w(LOG_TAG, "encoder stalled — UI reconnecting");
                    emitState(LiveStreamSessionState.RECONNECTING, null, false, false, RtmpSettingsPanel.StreamErrorClass.GENERIC);
                } else {
                    emitState(LiveStreamSessionState.CONNECTING, null, false, false, RtmpSettingsPanel.StreamErrorClass.GENERIC);
                }
            }
        }

        panel.renderStats(
                formatResolution(status.getResolution()),
                formatFps(status.getFps()),
                formatBitrate(status.getVbps()));
    }

    private String healthLabelFromMetrics(@NonNull LiveStreamStatus status) {
        int fps = status.getFps();
        int vbps = status.getVbps();
        if (fps >= 15 && vbps >= MIN_VBPS_FOR_LIVE) {
            return activity.getString(R.string.uxsdk_rtmp_health_good);
        }
        if (fps > 0 || vbps > 0) {
            return activity.getString(R.string.uxsdk_rtmp_health_marginal);
        }
        return activity.getString(R.string.uxsdk_rtmp_health_connecting);
    }

    private static boolean isActivelyPublishing(@NonNull LiveStreamStatus status) {
        if (!status.isStreaming()) {
            return false;
        }
        return status.getFps() > 0 || status.getVbps() >= MIN_VBPS_FOR_LIVE;
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

    private static String formatBitrate(int vbps) {
        if (vbps <= 0) {
            return null;
        }
        return String.format(Locale.US, "%d kbps", vbps / 1024);
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
