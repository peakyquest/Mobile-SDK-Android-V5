package dji.v5.ux.sample.showcase.waypoint;

import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import dji.v5.utils.common.LogUtils;

/**
 * Timestamped mission / mapping drawer log (same pattern as RTMP connection log).
 */
public final class MissionDrawerLog {

    private static final int MAX_CHARS = 6000;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final StringBuilder buffer = new StringBuilder();

    @Nullable
    private TextView textView;
    @Nullable
    private String logTag;

    public void bind(@Nullable TextView textView) {
        this.textView = textView;
        refreshView();
    }

    public void setLogTag(@Nullable String logTag) {
        this.logTag = logTag;
    }

    public void clear() {
        buffer.setLength(0);
        refreshView();
    }

    public void append(@NonNull String line) {
        buffer.append('[').append(timeFormat.format(new Date())).append("] ")
                .append(line)
                .append('\n');
        trimBuffer();
        if (logTag != null) {
            LogUtils.i(logTag, line);
        }
        refreshView();
    }

    private void trimBuffer() {
        while (buffer.length() > MAX_CHARS) {
            int cut = buffer.indexOf("\n", 500);
            if (cut < 0) {
                buffer.delete(0, buffer.length() / 2);
            } else {
                buffer.delete(0, cut + 1);
            }
        }
    }

    private void refreshView() {
        if (textView != null) {
            textView.setText(buffer.toString());
        }
    }
}
