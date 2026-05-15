package dji.v5.ux.sample.showcase.defaultlayout;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Map;

import dji.v5.ux.R;

/**
 * Reusable centered card-picker flyout (same window treatment as mission / map style dialogs).
 */
public final class CardPickerDialog {

    public interface Listener {
        void onCardSelected(@NonNull AlertDialog dialog);
    }

    private CardPickerDialog() {
    }

    @NonNull
    public static AlertDialog show(
            @NonNull AppCompatActivity activity,
            @LayoutRes int layoutResId,
            @NonNull Map<Integer, Listener> cardListeners) {
        View root = LayoutInflater.from(activity).inflate(layoutResId, null, false);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setCancelable(true)
                .create();
        applyCenteredTransparentWindow(dialog);
        for (Map.Entry<Integer, Listener> entry : cardListeners.entrySet()) {
            View card = root.findViewById(entry.getKey());
            if (card == null) {
                continue;
            }
            card.setClickable(true);
            card.setFocusable(true);
            Listener listener = entry.getValue();
            card.setOnClickListener(v -> listener.onCardSelected(dialog));
        }
        dialog.show();
        return dialog;
    }

    @NonNull
    public static AlertDialog.Builder centeredFlyoutBuilder(@NonNull AppCompatActivity activity) {
        return new AlertDialog.Builder(activity, R.style.UXSDKDefaultLayoutDarkAlertDialog);
    }

    /**
     * Puts the custom panel dead-center on the screen (landscape FPV layouts often default dialogs to the top).
     */
    public static void applyCenteredTransparentWindow(@NonNull AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawableResource(android.R.color.transparent);
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.gravity = Gravity.CENTER;
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(lp);
    }
}
