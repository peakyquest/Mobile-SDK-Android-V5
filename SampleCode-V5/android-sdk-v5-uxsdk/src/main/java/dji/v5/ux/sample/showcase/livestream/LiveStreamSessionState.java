package dji.v5.ux.sample.showcase.livestream;

/**
 * High-level RTMP session state for operator UI and logging.
 */
public enum LiveStreamSessionState {
    IDLE,
    CONNECTING,
    LIVE,
    RECONNECTING,
    FAILED,
    STOPPED
}
