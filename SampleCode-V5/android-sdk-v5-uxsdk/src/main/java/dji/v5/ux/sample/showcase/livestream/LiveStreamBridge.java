/*
 * Bridge between {@link RtmpSettingsPanel} (UI) and DJI's {@code ILiveStreamManager} (SDK).
 *
 * The panel knows nothing about the SDK — it just emits {@link RtmpSettingsPanel.RtmpConfig}
 * snapshots and start/stop intents. This class translates those into
 * {@code LiveStreamSettings} / {@code StreamQuality} / {@code ScaleType} / etc., starts
 * and stops the stream, and pumps {@code LiveStreamStatusListener} callbacks back into
 * the panel's {@code render*()} methods on the UI thread.
 */

package dji.v5.ux.sample.showcase.livestream;

import android.app.Activity;
import android.text.TextUtils;

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
import dji.v5.utils.common.LogUtils;

/**
 * Owns the live-stream session for the right-drawer RTMP panel.
 *
 * <p>Lifecycle: {@link #attach()} registers status listeners; {@link #detach()} unregisters
 * them and stops any in-progress stream. Wire to a {@link RtmpSettingsPanel} via
 * {@link RtmpSettingsPanel#setHost(RtmpSettingsPanel.StreamHost)}.
 *
 * <p>Threading: SDK callbacks may arrive on background threads; this class always hops
 * back to the activity's main thread before touching the panel's UI methods.
 */
public final class LiveStreamBridge implements RtmpSettingsPanel.StreamHost {

    private static final String TAG = "LiveStreamBridge";

    private final Activity activity;
    private final RtmpSettingsPanel panel;
    private final ILiveStreamManager liveStreamManager;

    private boolean attached;
    private boolean configApplied;

    private final LiveStreamStatusListener statusListener = new LiveStreamStatusListener() {
        @Override
        public void onLiveStreamStatusUpdate(@Nullable LiveStreamStatus status) {
            if (status == null) return;
            postOnUi(() -> handleStatusUpdate(status));
        }

        @Override
        public void onError(@Nullable IDJIError error) {
            if (error == null) return;
            LogUtils.e(TAG, "LiveStream error: " + error);
            postOnUi(() -> panel.renderError("Stream error: " + safeDescription(error)));
        }
    };

    public LiveStreamBridge(@NonNull Activity activity, @NonNull RtmpSettingsPanel panel) {
        this.activity = activity;
        this.panel = panel;
        this.liveStreamManager = MediaDataCenter.getInstance().getLiveStreamManager();
    }

    /** Subscribe to status updates and seed the panel from the current SDK state. */
    @MainThread
    public void attach() {
        if (attached) return;
        attached = true;
        liveStreamManager.addLiveStreamStatusListener(statusListener);
        // Seed the panel so it reflects whatever the manager is doing right now.
        if (liveStreamManager.isStreaming()) {
            panel.renderLive();
        } else {
            panel.renderIdleStatus();
        }
    }

    /** Unsubscribe + stop any active stream. Safe to call multiple times. */
    @MainThread
    public void detach() {
        if (!attached) return;
        attached = false;
        liveStreamManager.removeLiveStreamStatusListener(statusListener);
        if (liveStreamManager.isStreaming()) {
            liveStreamManager.stopStream(null);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RtmpSettingsPanel.StreamHost
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void onApplyRtmpConfig(@NonNull RtmpSettingsPanel.RtmpConfig config) {
        applyConfig(config);
    }

    @Override
    public void onStartStream() {
        if (liveStreamManager.isStreaming()) {
            LogUtils.i(TAG, "Stream already running; ignoring start request.");
            return;
        }
        if (!configApplied) {
            panel.renderError("Apply settings before starting.");
            return;
        }
        panel.renderConnecting();
        liveStreamManager.startStream(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                LogUtils.i(TAG, "startStream onSuccess");
                // Final transition to LIVE comes from the status listener.
            }

            @Override
            public void onFailure(@NonNull IDJIError error) {
                LogUtils.e(TAG, "startStream onFailure: " + error);
                postOnUi(() -> {
                    panel.renderError("Start failed: " + safeDescription(error));
                    panel.renderIdleStatus();
                });
            }
        });
    }

    @Override
    public void onStopStream() {
        if (!liveStreamManager.isStreaming()) {
            panel.renderIdleStatus();
            return;
        }
        liveStreamManager.stopStream(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                LogUtils.i(TAG, "stopStream onSuccess");
                postOnUi(panel::renderIdleStatus);
            }

            @Override
            public void onFailure(@NonNull IDJIError error) {
                LogUtils.e(TAG, "stopStream onFailure: " + error);
                postOnUi(() -> panel.renderError("Stop failed: " + safeDescription(error)));
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Settings translation
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Push every user-tunable knob from {@code RtmpConfig} into the live-stream manager.
     * Idempotent: safe to call repeatedly without restarting the stream.
     */
    private void applyConfig(@NonNull RtmpSettingsPanel.RtmpConfig cfg) {
        if (TextUtils.isEmpty(cfg.url)) return;

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
                // RtmpConfig stores kbps; the SDK expects bits-per-second.
                liveStreamManager.setLiveVideoBitrate(Math.max(0, cfg.bitrateKbps) * 1024);
            }

            // Camera-scale only affects a preview SurfaceView, which DefaultLayoutActivity
            // doesn't expose (FPVWidget owns its own rendering). We persist the user's
            // choice for completeness but don't push it to the SDK here.
            ICameraStreamManager.ScaleType liveScale = ICameraStreamManager.ScaleType.find(cfg.liveScaleTag);
            if (liveScale != null) {
                liveStreamManager.setLiveStreamScaleType(liveScale);
            }

            configApplied = true;
            LogUtils.i(TAG, "Applied RTMP config: " + describeConfig(cfg));
        } catch (Throwable t) {
            LogUtils.e(TAG, "applyConfig failed: " + t);
            panel.renderError("Apply failed: " + t.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Status → UI translation
    // ══════════════════════════════════════════════════════════════════════════

    @MainThread
    private void handleStatusUpdate(@NonNull LiveStreamStatus status) {
        if (status.isStreaming()) {
            // Promote CONNECTING / IDLE → LIVE. Ignore stray ticks that arrive after
            // the user has stopped (panel state is then already IDLE and Stop call is
            // in flight — the manager will follow shortly).
            if (panel.isConnecting() || panel.isLive()) {
                panel.renderLive();
            }
        } else if (panel.isLive()) {
            // Only collapse to IDLE from LIVE — never undo a CONNECTING transition the
            // user just kicked off, and never bounce IDLE back to IDLE.
            panel.renderIdleStatus();
        }
        panel.renderStats(
                formatResolution(status.getResolution()),
                formatFps(status.getFps()),
                formatBitrate(status.getVbps()));
    }

    private static String formatResolution(@Nullable VideoResolution resolution) {
        if (resolution == null) return null;
        int w = resolution.getWidth();
        int h = resolution.getHeight();
        if (w <= 0 || h <= 0) return null;
        return w + " × " + h;
    }

    private static String formatFps(int fps) {
        return fps <= 0 ? null : String.format(Locale.US, "%d", fps);
    }

    /** SDK reports video bitrate as {@code vbps} (bits per second). Show kbps. */
    private static String formatBitrate(int vbps) {
        if (vbps <= 0) return null;
        return String.format(Locale.US, "%d kbps", vbps / 1024);
    }

    private static String describeConfig(RtmpSettingsPanel.RtmpConfig cfg) {
        return "url=" + cfg.url
                + ", camera=" + cfg.cameraIndex
                + ", qualityTag=" + cfg.qualityTag
                + ", bitrateMode=" + (cfg.manualBitrate ? "MANUAL" : "AUTO")
                + ", bitrateKbps=" + cfg.bitrateKbps
                + ", camScaleTag=" + cfg.cameraScaleTag
                + ", liveScaleTag=" + cfg.liveScaleTag;
    }

    private static String safeDescription(@NonNull IDJIError error) {
        String d = error.description();
        return TextUtils.isEmpty(d) ? error.toString() : d;
    }

    private void postOnUi(@NonNull Runnable r) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(r);
    }
}
