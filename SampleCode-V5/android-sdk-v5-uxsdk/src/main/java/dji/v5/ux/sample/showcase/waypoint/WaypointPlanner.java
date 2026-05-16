package dji.v5.ux.sample.showcase.waypoint;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.graphics.Color;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.dji.wpmzsdk.common.utils.kml.model.WaypointActionType;
import com.dji.wpmzsdk.manager.WPMZManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dji.sdk.wpmz.value.mission.WaylineActionInfo;
import dji.sdk.wpmz.value.mission.WaylineActionType;
import dji.sdk.wpmz.value.mission.WaylineExitOnRCLostAction;
import dji.sdk.wpmz.value.mission.WaylineFinishedAction;
import dji.sdk.wpmz.value.mission.WaylineLocationCoordinate2D;
import dji.sdk.wpmz.value.mission.WaylineLocationCoordinate3D;
import dji.sdk.wpmz.value.mission.WaylineMission;
import dji.sdk.wpmz.value.mission.WaylineMissionConfig;
import dji.sdk.wpmz.value.mission.WaylineWaypoint;
import dji.sdk.wpmz.value.mission.WaylineWaypointGimbalHeadingMode;
import dji.sdk.wpmz.value.mission.WaylineWaypointGimbalHeadingParam;
import dji.sdk.wpmz.value.mission.WaylineWaypointTurnMode;
import dji.sdk.wpmz.value.mission.WaylineWaypointYawMode;
import dji.sdk.wpmz.value.mission.WaylineWaypointYawParam;
import dji.sdk.wpmz.value.mission.WaylineWaypointYawPathMode;
import dji.v5.common.callback.CommonCallbacks;
import dji.v5.common.error.IDJIError;
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager;
import dji.v5.utils.common.LogUtils;
import dji.v5.ux.R;
import dji.v5.ux.map.MapWidget;
import dji.v5.ux.mapkit.core.maps.DJIMap;
import dji.v5.ux.mapkit.core.models.DJIBitmapDescriptor;
import dji.v5.ux.mapkit.core.models.DJIBitmapDescriptorFactory;
import dji.v5.ux.mapkit.core.models.DJILatLng;
import dji.v5.ux.mapkit.core.models.annotations.DJIMarker;
import dji.v5.ux.mapkit.core.models.annotations.DJIMarkerOptions;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolyline;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolylineOptions;
import dji.v5.ux.core.util.ViewUtil;

/**
 * Interactive waypoint planning on a {@link MapWidget}: add points, configure each in the side panel,
 * build KMZ, upload via {@link WaypointMissionManager}, start mission.
 */
public final class WaypointPlanner {

    private static final String TAG = "WaypointPlanner";
    private static final String PREFS_WAYPOINT_TUTORIAL = "uxsdk_waypoint_tutorial_prefs";
    private static final String KEY_DRAWER_TUTORIAL_SHOWN = "drawer_tutorial_shown";
    private static final int MAX_WAYPOINTS = 99;
    private static final double MIN_ALTITUDE_M = 5.0;
    private static final double MAX_ALTITUDE_M = 500.0;
    private static final double MIN_SPEED_MPS = 1.0;
    private static final double MAX_SPEED_MPS = 15.0;
    private static final double MIN_GLOBAL_SPEED_MPS = 1.0;
    private static final double MAX_GLOBAL_SPEED_MPS = 15.0;
    private static final double MIN_GIMBAL_PITCH_DEG = -120.0;
    private static final double MAX_GIMBAL_PITCH_DEG = 45.0;

    /** Spinner index → {@link WaylineWaypointYawMode#find(int)} index (matches sample arrays.xml order). */
    private static final int[] HEADING_SDK_FIND_INDEX = {0, 2, 3};

    private final AppCompatActivity activity;
    private final MapWidget mapWidget;

    private final WaypointMissionGlobals globals = new WaypointMissionGlobals();
    private final List<WaypointPlanItem> planItems = new ArrayList<>();
    private final List<DJIMarker> markers = new ArrayList<>();
    @Nullable
    private DJIBitmapDescriptor waypointMarkerIcon;

    private boolean planningActive;
    private DJIPolyline routePolyline;
    private String lastKmzPath;

    private View drawerShell;
    private View drawerScrim;
    private View drawerSlide;
    private TextView navLabel;
    private ImageButton btnPrev;
    private ImageButton btnNext;
    private EditText etAltitude;
    private EditText etSpeed;
    private EditText etGimbal;
    private Spinner spHeading;
    private Spinner spAction;
    private Spinner spTurn;
    private TextView missionHeader;
    private View missionBody;
    private Spinner spFinish;
    private Spinner spRcLost;
    private EditText etGlobalSpeed;
    private TextView tvTotal;
    private TextView tvDistance;
    private TextView tvEta;
    private ProgressBar uploadProgress;
    private Button btnUpload;
    private Button btnStart;

    private boolean drawerUiWired;
    private int selectedWaypointIndex = -1;
    private boolean missionSectionExpanded;
    private boolean planDirtySinceUpload;
    private boolean lastUploadSucceeded;
    private boolean uploadInProgress;
    private boolean startInProgress;
    private final MissionDrawerLog drawerLog = new MissionDrawerLog();

    private List<WaylineFinishedAction> finishChoices = new ArrayList<>();
    private List<WaylineExitOnRCLostAction> lostChoices = new ArrayList<>();
    private List<WaylineWaypointTurnMode> turnChoices = new ArrayList<>();

    private final DJIMap.OnMarkerClickListener waypointMarkerClickListener = marker -> {
        try {
            if (!planningActive) {
                return false;
            }
            int idx = markers.indexOf(marker);
            if (idx < 0 || idx >= planItems.size()) {
                return false;
            }
            WaypointPlanItem item = planItems.get(idx);
            WaylineWaypoint wp = item == null ? null : item.getWaylineWaypoint();
            if (wp == null || wp.getLocation() == null) {
                toastUi(R.string.uxsdk_waypoint_error_invalid_state);
                return true;
            }
            selectWaypoint(idx);
            showDrawer(false);
            return true;
        } catch (Exception e) {
            LogUtils.e(TAG, "marker click: " + logEx(e));
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
            return true;
        }
    };

    public WaypointPlanner(@NonNull AppCompatActivity activity, @NonNull MapWidget mapWidget) {
        this.activity = activity;
        this.mapWidget = mapWidget;
    }

    public boolean isPlanningActive() {
        return planningActive;
    }

    public boolean isDrawerVisible() {
        return drawerShell != null && drawerShell.getVisibility() == View.VISIBLE;
    }

    /**
     * Host view must be the root from {@code uxsdk_waypoint_mission_drawer_shell} (full-screen overlay).
     */
    public void bindDrawer(@Nullable View shellRoot) {
        drawerShell = shellRoot;
        if (drawerShell == null) {
            return;
        }
        drawerScrim = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_scrim);
        drawerSlide = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_slide);
        navLabel = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_nav_label);
        btnPrev = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_btn_prev);
        btnNext = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_btn_next);
        etAltitude = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_et_altitude);
        etSpeed = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_et_speed);
        etGimbal = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_et_gimbal);
        spHeading = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_sp_heading);
        spAction = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_sp_action);
        spTurn = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_sp_turn);
        missionHeader = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_mission_header);
        missionBody = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_mission_body);
        spFinish = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_sp_finish);
        spRcLost = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_sp_rc_lost);
        etGlobalSpeed = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_et_global_speed);
        tvTotal = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_tv_total);
        tvDistance = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_tv_distance);
        tvEta = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_tv_eta);
        uploadProgress = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_upload_progress);
        btnUpload = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_btn_upload);
        btnStart = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_btn_start);
        ImageButton btnClose = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_btn_close);
        Button btnApply = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_btn_apply_wp);
        Button btnDelete = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_btn_delete_wp);
        Button         btnExit = drawerShell.findViewById(R.id.uxsdk_waypoint_drawer_btn_exit);
        drawerLog.setLogTag(TAG);
        drawerLog.bind(drawerShell.findViewById(R.id.uxsdk_mission_drawer_tv_log));

        if (drawerUiWired) {
            return;
        }
        drawerUiWired = true;

        finishChoices = filterUnknown(WaylineFinishedAction.values());
        lostChoices = filterUnknown(WaylineExitOnRCLostAction.values());
        turnChoices = filterUnknown(WaylineWaypointTurnMode.values());
        if (turnChoices.size() < 2) {
            turnChoices.add(WaylineWaypointTurnMode.TO_POINT_AND_STOP_WITH_DISCONTINUITY_CURVATURE);
        }

        ArrayAdapter<String> finishAd = new ArrayAdapter<>(activity,
                R.layout.uxsdk_waypoint_drawer_spinner_item, R.id.uxsdk_waypoint_spinner_text, toNames(finishChoices));
        finishAd.setDropDownViewResource(R.layout.uxsdk_waypoint_drawer_spinner_dropdown_item);
        spFinish.setAdapter(finishAd);

        ArrayAdapter<String> lostAd = new ArrayAdapter<>(activity,
                R.layout.uxsdk_waypoint_drawer_spinner_item, R.id.uxsdk_waypoint_spinner_text, toNames(lostChoices));
        lostAd.setDropDownViewResource(R.layout.uxsdk_waypoint_drawer_spinner_dropdown_item);
        spRcLost.setAdapter(lostAd);

        String[] headingLabels = activity.getResources().getStringArray(R.array.uxsdk_waypoint_drawer_heading_labels);
        ArrayAdapter<String> headingAd = new ArrayAdapter<>(activity,
                R.layout.uxsdk_waypoint_drawer_spinner_item, R.id.uxsdk_waypoint_spinner_text, headingLabels);
        headingAd.setDropDownViewResource(R.layout.uxsdk_waypoint_drawer_spinner_dropdown_item);
        spHeading.setAdapter(headingAd);

        String[] actionLabels = activity.getResources().getStringArray(R.array.uxsdk_waypoint_drawer_action_labels);
        ArrayAdapter<String> actionAd = new ArrayAdapter<>(activity,
                R.layout.uxsdk_waypoint_drawer_spinner_item, R.id.uxsdk_waypoint_spinner_text, actionLabels);
        actionAd.setDropDownViewResource(R.layout.uxsdk_waypoint_drawer_spinner_dropdown_item);
        spAction.setAdapter(actionAd);

        ArrayAdapter<String> turnAd = new ArrayAdapter<>(activity,
                R.layout.uxsdk_waypoint_drawer_spinner_item, R.id.uxsdk_waypoint_spinner_text, toNames(turnChoices));
        turnAd.setDropDownViewResource(R.layout.uxsdk_waypoint_drawer_spinner_dropdown_item);
        spTurn.setAdapter(turnAd);

        int popupBg = R.drawable.uxsdk_waypoint_drawer_spinner_popup_bg;
        spFinish.setPopupBackgroundResource(popupBg);
        spRcLost.setPopupBackgroundResource(popupBg);
        spHeading.setPopupBackgroundResource(popupBg);
        spAction.setPopupBackgroundResource(popupBg);
        spTurn.setPopupBackgroundResource(popupBg);

        spFinish.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (finishChoices.isEmpty()) {
                    return;
                }
                globals.finishAction = finishChoices.get(clampIndex(position, 0, finishChoices.size() - 1));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        spRcLost.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (lostChoices.isEmpty()) {
                    return;
                }
                globals.lostAction = lostChoices.get(clampIndex(position, 0, lostChoices.size() - 1));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        missionHeader.setOnClickListener(v -> {
            missionSectionExpanded = !missionSectionExpanded;
            missionBody.setVisibility(missionSectionExpanded ? View.VISIBLE : View.GONE);
        });

        btnPrev.setOnClickListener(v -> stepSelection(-1));
        btnNext.setOnClickListener(v -> stepSelection(1));
        btnApply.setOnClickListener(v -> applySelectedWaypointFromDrawer());
        btnDelete.setOnClickListener(v -> confirmDeleteSelected());
        btnUpload.setOnClickListener(v -> uploadMissionFromDrawer());
        btnStart.setOnClickListener(v -> startLastUploadedMission());
        btnClose.setOnClickListener(v -> hideDrawer());
        drawerScrim.setOnClickListener(v -> hideDrawer());
        btnExit.setOnClickListener(v -> confirmExitPlanning());
    }

    public void startWaypointPlanning() {
        planningActive = true;
        attachMarkerClickListenerSafe();
        planDirtySinceUpload = true;
        lastUploadSucceeded = false;
        uploadInProgress = false;
        startInProgress = false;
        drawerLog.clear();
        drawerLog.append("Waypoint planning started — tap the map to add waypoints.");
        updateStartButtonState();
        updateUploadUiIdle();
        Toast.makeText(activity, R.string.uxsdk_waypoint_tap_map_hint, Toast.LENGTH_LONG).show();
        if (drawerShell != null) {
            populateMissionSpinnersFromGlobals();
            showDrawer(true);
            maybeShowFirstTimeTutorial();
        }
    }

    public void toggleDrawer() {
        if (!planningActive || drawerShell == null) {
            return;
        }
        if (isDrawerVisible()) {
            hideDrawer();
        } else {
            populateMissionSpinnersFromGlobals();
            showDrawer(false);
        }
    }

    /**
     * @return true if the click was consumed (do not run map / FPV swap logic).
     */
    public boolean onMapClick(@NonNull DJILatLng latLng) {
        if (!planningActive) {
            return false;
        }
        try {
            addWaypointAt(latLng);
            return true;
        } catch (Exception e) {
            LogUtils.e(TAG, "onMapClick: " + logEx(e));
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
            return true;
        }
    }

    /** @deprecated Replaced by the waypoint drawer; kept for compatibility if referenced elsewhere. */
    @Deprecated
    public void openPlanningMenu() {
        toggleDrawer();
    }

    public void clearPlanning() {
        drawerLog.append("Planning cleared.");
        detachMarkerClickListenerSafe();
        planningActive = false;
        hideDrawerImmediate();
        DJIMap map = null;
        try {
            map = mapWidget.getMap();
        } catch (Exception e) {
            LogUtils.e(TAG, "clearPlanning getMap: " + logEx(e));
        }
        if (map != null) {
            try {
                for (DJIMarker m : new ArrayList<>(markers)) {
                    if (m != null) {
                        try {
                            m.remove();
                        } catch (Exception e) {
                            LogUtils.e(TAG, "remove marker: " + logEx(e));
                        }
                    }
                }
            } catch (Exception e) {
                LogUtils.e(TAG, "clear markers: " + logEx(e));
            }
            try {
                if (routePolyline != null) {
                    routePolyline.remove();
                }
            } catch (Exception e) {
                LogUtils.e(TAG, "remove polyline: " + logEx(e));
            }
        }
        routePolyline = null;
        markers.clear();
        planItems.clear();
        lastKmzPath = null;
        selectedWaypointIndex = -1;
        planDirtySinceUpload = true;
        lastUploadSucceeded = false;
        uploadInProgress = false;
        startInProgress = false;
        updateUploadUiIdle();
        updateActionButtonsState();
        refreshDrawerSummaryOnly();
    }

    private void attachMarkerClickListenerSafe() {
        try {
            DJIMap map = mapWidget.getMap();
            if (map == null) {
                return;
            }
            map.removeOnMarkerClickListener(waypointMarkerClickListener);
            map.setOnMarkerClickListener(waypointMarkerClickListener);
        } catch (Exception e) {
            LogUtils.e(TAG, "attachMarkerClickListener: " + logEx(e));
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
        }
    }

    private void detachMarkerClickListenerSafe() {
        try {
            DJIMap map = mapWidget.getMap();
            if (map != null) {
                map.removeOnMarkerClickListener(waypointMarkerClickListener);
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "detachMarkerClickListener: " + logEx(e));
        }
    }

    private void showDrawer(boolean animateFromOffScreen) {
        if (drawerShell == null || drawerSlide == null) {
            return;
        }
        drawerShell.setVisibility(View.VISIBLE);
        Runnable anim = () -> {
            float w = drawerSlide.getWidth() > 0 ? drawerSlide.getWidth()
                    : activity.getResources().getDimension(R.dimen.uxsdk_waypoint_drawer_width);
            if (animateFromOffScreen) {
                drawerSlide.setTranslationX(w);
                drawerSlide.animate().translationX(0f).setDuration(240).start();
            } else {
                drawerSlide.setTranslationX(0f);
            }
            refreshDrawerUi();
        };
        drawerSlide.post(anim);
    }

    private void hideDrawer() {
        if (drawerShell == null || drawerSlide == null) {
            return;
        }
        float w = drawerSlide.getWidth() > 0 ? drawerSlide.getWidth()
                : activity.getResources().getDimension(R.dimen.uxsdk_waypoint_drawer_width);
        drawerSlide.animate().translationX(w).setDuration(220).withEndAction(() -> {
            drawerShell.setVisibility(View.GONE);
            drawerSlide.setTranslationX(0f);
        }).start();
    }

    private void hideDrawerImmediate() {
        if (drawerShell != null) {
            drawerShell.setVisibility(View.GONE);
        }
        if (drawerSlide != null) {
            drawerSlide.setTranslationX(0f);
        }
    }

    private void populateMissionSpinnersFromGlobals() {
        if (spFinish == null || spRcLost == null || etGlobalSpeed == null) {
            return;
        }
        int fi = finishChoices.indexOf(globals.finishAction);
        int li = lostChoices.indexOf(globals.lostAction);
        spFinish.setSelection(clampIndex(fi, 0, Math.max(0, finishChoices.size() - 1)));
        spRcLost.setSelection(clampIndex(li, 0, Math.max(0, lostChoices.size() - 1)));
        etGlobalSpeed.setText(String.valueOf(globals.globalSpeed));
    }

    private void refreshDrawerUi() {
        if (!planItems.isEmpty() && (selectedWaypointIndex < 0 || selectedWaypointIndex >= planItems.size())) {
            selectedWaypointIndex = 0;
        }
        refreshNavigatorLabel();
        populateWaypointFieldsFromSelection();
        refreshDrawerSummaryOnly();
        syncMissionSpinnersQuiet();
        updateStartButtonState();
        updateActionButtonsState();
    }

    private void syncMissionSpinnersQuiet() {
        if (spFinish == null) {
            return;
        }
        int fi = finishChoices.indexOf(globals.finishAction);
        int li = lostChoices.indexOf(globals.lostAction);
        spFinish.setSelection(clampIndex(fi, 0, Math.max(0, finishChoices.size() - 1)));
        spRcLost.setSelection(clampIndex(li, 0, Math.max(0, lostChoices.size() - 1)));
    }

    private void refreshDrawerSummaryOnly() {
        if (tvTotal == null || tvDistance == null || tvEta == null) {
            return;
        }
        int n = planItems.size();
        tvTotal.setText(activity.getString(R.string.uxsdk_waypoint_drawer_summary_total) + n);
        if (n < 2) {
            tvDistance.setText(activity.getString(R.string.uxsdk_waypoint_drawer_summary_distance)
                    + activity.getString(R.string.uxsdk_waypoint_drawer_summary_na));
            tvEta.setText(activity.getString(R.string.uxsdk_waypoint_drawer_summary_eta)
                    + activity.getString(R.string.uxsdk_waypoint_drawer_summary_na));
            return;
        }
        double meters = WaypointMissionMath.totalPathLengthMeters(planItems);
        tvDistance.setText(activity.getString(R.string.uxsdk_waypoint_drawer_summary_distance)
                + String.format(Locale.US, "%.0f", meters)
                + activity.getString(R.string.uxsdk_waypoint_drawer_summary_m));
        double gSpeed = globals.globalSpeed > 0.05 ? globals.globalSpeed : 5.0;
        double sec = meters / gSpeed;
        tvEta.setText(activity.getString(R.string.uxsdk_waypoint_drawer_summary_eta)
                + String.format(Locale.US, "%.0f", sec)
                + activity.getString(R.string.uxsdk_waypoint_drawer_summary_s));
    }

    private void refreshNavigatorLabel() {
        if (navLabel == null || btnPrev == null || btnNext == null) {
            return;
        }
        int n = planItems.size();
        if (n <= 0 || selectedWaypointIndex < 0 || selectedWaypointIndex >= n) {
            navLabel.setText(activity.getString(R.string.uxsdk_waypoint_drawer_wp_prefix)
                    + activity.getString(R.string.uxsdk_waypoint_drawer_summary_na));
            btnPrev.setEnabled(false);
            btnNext.setEnabled(false);
            btnPrev.setAlpha(0.35f);
            btnNext.setAlpha(0.35f);
            return;
        }
        int human = selectedWaypointIndex + 1;
        navLabel.setText(activity.getString(R.string.uxsdk_waypoint_drawer_wp_prefix)
                + human
                + activity.getString(R.string.uxsdk_waypoint_drawer_nav_sep)
                + n);
        boolean canPrev = selectedWaypointIndex > 0;
        boolean canNext = selectedWaypointIndex < n - 1;
        btnPrev.setEnabled(canPrev);
        btnNext.setEnabled(canNext);
        btnPrev.setAlpha(canPrev ? 1f : 0.35f);
        btnNext.setAlpha(canNext ? 1f : 0.35f);
    }

    private void stepSelection(int delta) {
        int n = planItems.size();
        if (n <= 0) {
            return;
        }
        int next = clampIndex(selectedWaypointIndex + delta, 0, n - 1);
        selectWaypoint(next);
    }

    private void selectWaypoint(int index) {
        selectedWaypointIndex = clampIndex(index, 0, Math.max(0, planItems.size() - 1));
        if (planItems.isEmpty()) {
            selectedWaypointIndex = -1;
        }
        populateWaypointFieldsFromSelection();
        refreshNavigatorLabel();
    }

    private void populateWaypointFieldsFromSelection() {
        if (etAltitude == null || etSpeed == null || etGimbal == null) {
            return;
        }
        etAltitude.setEnabled(true);
        etSpeed.setEnabled(true);
        etGimbal.setEnabled(true);
        if (spHeading != null) {
            spHeading.setEnabled(true);
        }
        if (spAction != null) {
            spAction.setEnabled(true);
        }
        if (spTurn != null) {
            spTurn.setEnabled(true);
        }

        if (planItems.isEmpty() || selectedWaypointIndex < 0 || selectedWaypointIndex >= planItems.size()) {
            etAltitude.setText(String.valueOf(30));
            etSpeed.setText(String.valueOf(globals.globalSpeed));
            etGimbal.setText(String.valueOf(-30));
            if (spHeading != null) {
                spHeading.setSelection(0);
            }
            if (spAction != null) {
                spAction.setSelection(0);
            }
            if (spTurn != null && !turnChoices.isEmpty()) {
                int ti = turnChoices.indexOf(globals.globalTurnMode);
                spTurn.setSelection(clampIndex(ti, 0, turnChoices.size() - 1));
            }
            return;
        }

        WaypointPlanItem item = planItems.get(selectedWaypointIndex);
        WaylineWaypoint w = item.getWaylineWaypoint();
        if (w == null) {
            return;
        }
        etAltitude.setText(String.valueOf(w.getHeight()));
        etSpeed.setText(String.valueOf(w.getSpeed()));
        etGimbal.setText(String.valueOf(w.getGimbalPitchAngle()));

        int headingUi = headingUiIndexFromWaypoint(w);
        if (spHeading != null) {
            spHeading.setSelection(clampIndex(headingUi, 0, HEADING_SDK_FIND_INDEX.length - 1));
        }

        int actionUi = actionUiIndexFromItem(item);
        if (spAction != null) {
            spAction.setSelection(clampIndex(actionUi, 0, 5));
        }

        int ti = turnChoices.indexOf(item.getTurnMode());
        if (spTurn != null && !turnChoices.isEmpty()) {
            spTurn.setSelection(clampIndex(ti, 0, turnChoices.size() - 1));
        }
    }

    private static int headingUiIndexFromWaypoint(@NonNull WaylineWaypoint w) {
        WaylineWaypointYawParam p = w.getYawParam();
        if (p == null || p.getYawMode() == null) {
            return 0;
        }
        WaylineWaypointYawMode mode = p.getYawMode();
        for (int i = 0; i < HEADING_SDK_FIND_INDEX.length; i++) {
            if (mode == WaylineWaypointYawMode.find(HEADING_SDK_FIND_INDEX[i])) {
                return i;
            }
        }
        return 0;
    }

    private static int actionUiIndexFromItem(@NonNull WaypointPlanItem item) {
        List<WaylineActionInfo> infos = item.getActionInfos();
        if (infos == null || infos.isEmpty()) {
            return 0;
        }
        WaylineActionInfo ai = infos.get(0);
        if (ai == null || ai.getActionType() == null) {
            return 0;
        }
        switch (ai.getActionType()) {
            case TAKE_PHOTO:
                return 1;
            case START_RECORD:
                return 2;
            case STOP_RECORD:
                return 3;
            case HOVER:
                return 5;
            default:
                return 0;
        }
    }

    private void applySelectedWaypointFromDrawer() {
        DJIMap map = mapWidget.getMap();
        if (map == null || selectedWaypointIndex < 0 || selectedWaypointIndex >= planItems.size()) {
            toastUi(R.string.uxsdk_waypoint_error_invalid_state);
            return;
        }
        try {
            globals.globalSpeed = parseAndClamp(etGlobalSpeed, globals.globalSpeed,
                    MIN_GLOBAL_SPEED_MPS, MAX_GLOBAL_SPEED_MPS, "global speed");

            WaypointPlanItem item = planItems.get(selectedWaypointIndex);
            WaylineWaypoint w = item.getWaylineWaypoint();
            if (w == null || w.getLocation() == null) {
                toastUi(R.string.uxsdk_waypoint_error_invalid_state);
                return;
            }
            WaylineLocationCoordinate2D loc = w.getLocation();
            DJILatLng latLng = new DJILatLng(loc.getLatitude(), loc.getLongitude());

            double height = parseAndClamp(etAltitude, 30, MIN_ALTITUDE_M, MAX_ALTITUDE_M, "altitude");
            double speed = parseAndClamp(etSpeed, globals.globalSpeed, MIN_SPEED_MPS, MAX_SPEED_MPS, "speed");
            double gimbal = parseAndClamp(etGimbal, -30, MIN_GIMBAL_PITCH_DEG, MAX_GIMBAL_PITCH_DEG, "gimbal");
            applyGeometryAndMotion(w, latLng, height, speed, gimbal);

            int hi = spHeading.getSelectedItemPosition();
            applyHeadingModeToWaypoint(w, hi);

            int ti = spTurn.getSelectedItemPosition();
            WaylineWaypointTurnMode tm = turnChoices.get(clampIndex(ti, 0, turnChoices.size() - 1));
            item.setTurnMode(tm);
            globals.globalTurnMode = tm;

            int ai = spAction.getSelectedItemPosition();
            item.setActionInfos(buildActionListForUiIndex(ai));

            refreshRoutePolyline(map);
            planDirtySinceUpload = true;
            lastUploadSucceeded = false;
            updateStartButtonState();
            updateActionButtonsState();
            refreshDrawerSummaryOnly();
            drawerLog.append(String.format(Locale.US,
                    "Waypoint %d updated (alt %.0f m, speed %.1f m/s).",
                    selectedWaypointIndex + 1, height, speed));
            Toast.makeText(activity, R.string.uxsdk_waypoint_updated, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            LogUtils.e(TAG, "applySelectedWaypointFromDrawer: " + logEx(e));
            drawerLog.append("Apply waypoint failed: " + logEx(e));
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
        }
    }

    private static void applyHeadingModeToWaypoint(@NonNull WaylineWaypoint w, int headingUiIndex) {
        int sdkIdx = HEADING_SDK_FIND_INDEX[clampIndex(headingUiIndex, 0, HEADING_SDK_FIND_INDEX.length - 1)];
        WaylineWaypointYawMode mode = WaylineWaypointYawMode.find(sdkIdx);
        WaylineWaypointYawParam yawParam = w.getYawParam();
        if (yawParam == null) {
            yawParam = new WaylineWaypointYawParam();
            w.setYawParam(yawParam);
        }
        yawParam.setYawMode(mode);
        yawParam.setYawPathMode(WaylineWaypointYawPathMode.FOLLOW_BAD_ARC);
        yawParam.setEnableYawAngle(mode == WaylineWaypointYawMode.SMOOTH_TRANSITION);
        if (yawParam.getPoiLocation() == null && w.getLocation() != null) {
            yawParam.setPoiLocation(new WaylineLocationCoordinate3D(
                    w.getLocation().getLatitude(), w.getLocation().getLongitude(), w.getHeight()));
        }
        w.setUseGlobalYawParam(false);
    }

    @Nullable
    private static List<WaylineActionInfo> buildActionListForUiIndex(int uiIndex) {
        WaypointActionType t = null;
        Integer val = null;
        switch (uiIndex) {
            case 0:
                return new ArrayList<>();
            case 1:
                t = WaypointActionType.START_TAKE_PHOTO;
                break;
            case 2:
                t = WaypointActionType.START_RECORD;
                break;
            case 3:
                t = WaypointActionType.STOP_RECORD;
                break;
            case 4:
                t = WaypointActionType.ROTATE_AIRCRAFT;
                val = 5;
                break;
            case 5:
                t = WaypointActionType.STAY;
                val = 5;
                break;
            default:
                return new ArrayList<>();
        }
        WaylineActionInfo info = WaypointKmzUtil.createActionInfo(t, val);
        List<WaylineActionInfo> list = new ArrayList<>();
        if (info != null) {
            list.add(info);
        }
        return list;
    }

    private void addWaypointAt(@NonNull DJILatLng latLng) {
        DJIMap map = mapWidget.getMap();
        if (map == null) {
            toastUi(R.string.uxsdk_waypoint_error_generic, "Map not ready");
            return;
        }
        if (uploadInProgress || startInProgress) {
            toastUi(R.string.uxsdk_waypoint_error_generic, "Mission action in progress");
            return;
        }
        if (planItems.size() >= MAX_WAYPOINTS) {
            toastUi(R.string.uxsdk_waypoint_error_generic, "Max waypoint limit reached (" + MAX_WAYPOINTS + ")");
            return;
        }
        try {
            WaypointPlanItem item = buildPlanItem(planItems.size(), latLng, 30, globals.globalSpeed, -30);
            item.setActionInfos(new ArrayList<>());
            planItems.add(item);

            DJIMarker marker = map.addMarker(new DJIMarkerOptions()
                    .position(latLng)
                    .title(activity.getString(R.string.uxsdk_waypoint_marker_title, planItems.size()))
                    .icon(getWaypointMarkerIcon())
                    .zIndex(4)
                    .setInfoWindowEnable(false));
            if (marker == null) {
                planItems.remove(planItems.size() - 1);
                toastUi(R.string.uxsdk_waypoint_error_generic, "Could not create map marker");
                return;
            }
            markers.add(marker);
            selectedWaypointIndex = planItems.size() - 1;
            planDirtySinceUpload = true;
            lastUploadSucceeded = false;
            refreshRoutePolyline(map);
            refreshDrawerUi();
            updateStartButtonState();
            updateActionButtonsState();
            drawerLog.append(String.format(Locale.US,
                    "Waypoint %d added (%.6f, %.6f).", planItems.size(), latLng.getLatitude(), latLng.getLongitude()));
            Toast.makeText(activity, R.string.uxsdk_waypoint_added, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            LogUtils.e(TAG, "addWaypointAt: " + logEx(e));
            drawerLog.append("Add waypoint failed: " + logEx(e));
            if (!planItems.isEmpty()) {
                try {
                    planItems.remove(planItems.size() - 1);
                } catch (Exception ignore) {
                    LogUtils.e(TAG, "rollback plan item: " + logEx(ignore));
                }
            }
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
        }
    }

    private void confirmDeleteSelected() {
        if (selectedWaypointIndex < 0 || selectedWaypointIndex >= planItems.size()) {
            return;
        }
        final int idx = selectedWaypointIndex;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.uxsdk_waypoint_delete_confirm_title)
                .setMessage(R.string.uxsdk_waypoint_delete_confirm_message)
                .setPositiveButton(R.string.uxsdk_waypoint_delete, (d, w) -> {
                    deleteWaypointAt(idx);
                    if (!planItems.isEmpty()) {
                        selectWaypoint(Math.min(idx, planItems.size() - 1));
                    } else {
                        selectedWaypointIndex = -1;
                        refreshDrawerUi();
                    }
                })
                .setNegativeButton(R.string.uxsdk_app_cancel, null)
                .show();
    }

    private void confirmExitPlanning() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.uxsdk_waypoint_discard_title)
                .setMessage(R.string.uxsdk_waypoint_discard_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> clearPlanning())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void uploadMissionFromDrawer() {
        if (uploadInProgress || startInProgress) {
            return;
        }
        if (planItems.isEmpty()) {
            Toast.makeText(activity, R.string.uxsdk_waypoint_need_one, Toast.LENGTH_SHORT).show();
            drawerLog.append("Upload skipped — no waypoints.");
            return;
        }
        globals.globalSpeed = parseAndClamp(etGlobalSpeed, globals.globalSpeed,
                MIN_GLOBAL_SPEED_MPS, MAX_GLOBAL_SPEED_MPS, "global speed");
        saveKmzToCache(true);
        if (lastKmzPath == null) {
            drawerLog.append("Upload skipped — KMZ not saved.");
            return;
        }
        drawerLog.append("Uploading KMZ (" + planItems.size() + " waypoints)…");
        uploadInProgress = true;
        lastUploadSucceeded = false;
        updateActionButtonsState();
        if (uploadProgress != null) {
            uploadProgress.setVisibility(View.VISIBLE);
            uploadProgress.setIndeterminate(false);
            uploadProgress.setProgress(0);
        }
        try {
            WaypointMissionManager.getInstance().pushKMZFileToAircraft(lastKmzPath,
                    new CommonCallbacks.CompletionCallbackWithProgress<Double>() {
                        @Override
                        public void onProgressUpdate(Double progress) {
                            activity.runOnUiThread(() -> {
                                if (uploadProgress != null && progress != null) {
                                    uploadProgress.setVisibility(View.VISIBLE);
                                    int p = (int) (progress * 100.0);
                                    uploadProgress.setProgress(clampIndex(p, 0, 100));
                                }
                            });
                        }

                        @Override
                        public void onSuccess() {
                            activity.runOnUiThread(() -> {
                                if (uploadProgress != null) {
                                    uploadProgress.setProgress(100);
                                }
                                uploadInProgress = false;
                                planDirtySinceUpload = false;
                                lastUploadSucceeded = true;
                                updateStartButtonState();
                                updateActionButtonsState();
                                drawerLog.append("Upload complete — ready to start.");
                                Toast.makeText(activity, R.string.uxsdk_waypoint_upload_ok, Toast.LENGTH_SHORT).show();
                            });
                        }

                        @Override
                        public void onFailure(@NonNull IDJIError error) {
                            activity.runOnUiThread(() -> {
                                uploadInProgress = false;
                                updateUploadUiIdle();
                                lastUploadSucceeded = false;
                                updateStartButtonState();
                                updateActionButtonsState();
                                String msg = error != null ? error.description() : "?";
                                drawerLog.append("Upload failed: " + msg);
                                Toast.makeText(activity,
                                        activity.getString(R.string.uxsdk_waypoint_upload_fail, msg),
                                        Toast.LENGTH_LONG).show();
                            });
                        }
                    });
        } catch (Exception e) {
            uploadInProgress = false;
            updateUploadUiIdle();
            updateActionButtonsState();
            LogUtils.e(TAG, "uploadMissionFromDrawer: " + logEx(e));
            drawerLog.append("Upload error: " + logEx(e));
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
        }
    }

    private void updateStartButtonState() {
        if (btnStart == null) {
            return;
        }
        btnStart.setEnabled(lastUploadSucceeded && !planDirtySinceUpload && !uploadInProgress && !startInProgress);
    }

    private void updateActionButtonsState() {
        if (btnUpload != null) {
            btnUpload.setEnabled(planningActive && !planItems.isEmpty() && !uploadInProgress && !startInProgress);
        }
        if (btnPrev != null) {
            btnPrev.setEnabled(btnPrev.isEnabled() && !uploadInProgress && !startInProgress);
        }
        if (btnNext != null) {
            btnNext.setEnabled(btnNext.isEnabled() && !uploadInProgress && !startInProgress);
        }
    }

    private void updateUploadUiIdle() {
        if (uploadProgress != null) {
            uploadProgress.setVisibility(View.INVISIBLE);
            uploadProgress.setProgress(0);
        }
    }

    private void maybeShowFirstTimeTutorial() {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_WAYPOINT_TUTORIAL, AppCompatActivity.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_DRAWER_TUTORIAL_SHOWN, false)) {
                return;
            }
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            prefs.edit().putBoolean(KEY_DRAWER_TUTORIAL_SHOWN, true).apply();
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.uxsdk_waypoint_tutorial_title)
                    .setMessage(R.string.uxsdk_waypoint_tutorial_message)
                    .setPositiveButton(R.string.uxsdk_app_ok, null)
                    .show();
        } catch (Exception e) {
            LogUtils.e(TAG, "maybeShowFirstTimeTutorial: " + logEx(e));
        }
    }

    private void deleteWaypointAt(int index) {
        try {
            DJIMap map = mapWidget.getMap();
            if (index < 0 || index >= markers.size() || index >= planItems.size()) {
                toastUi(R.string.uxsdk_waypoint_error_generic, "Invalid waypoint index");
                return;
            }
            DJIMarker m = markers.remove(index);
            planItems.remove(index);
            if (m != null) {
                try {
                    m.remove();
                } catch (Exception e) {
                    LogUtils.e(TAG, "marker.remove: " + logEx(e));
                }
            }
            reindexWaypointsAndMarkers();
            if (map != null) {
                refreshRoutePolyline(map);
            }
            planDirtySinceUpload = true;
            lastUploadSucceeded = false;
            updateStartButtonState();
            updateActionButtonsState();
            refreshDrawerSummaryOnly();
            drawerLog.append("Waypoint " + (index + 1) + " deleted.");
            toastUi(R.string.uxsdk_waypoint_deleted);
        } catch (Exception e) {
            LogUtils.e(TAG, "deleteWaypointAt: " + logEx(e));
            drawerLog.append("Delete waypoint failed: " + logEx(e));
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
        }
    }

    private void reindexWaypointsAndMarkers() {
        for (int i = 0; i < planItems.size(); i++) {
            try {
                WaypointPlanItem it = planItems.get(i);
                if (it != null && it.getWaylineWaypoint() != null) {
                    it.getWaylineWaypoint().setWaypointIndex(i);
                }
                if (i < markers.size()) {
                    DJIMarker mk = markers.get(i);
                    if (mk != null) {
                        mk.setTitle(activity.getString(R.string.uxsdk_waypoint_marker_title, i + 1));
                    }
                }
            } catch (Exception e) {
                LogUtils.e(TAG, "reindex at " + i + ": " + logEx(e));
            }
        }
    }

    private void applyGeometryAndMotion(@NonNull WaylineWaypoint waypoint, @NonNull DJILatLng latLng,
                                        double height, double speed, double gimbalPitchDeg) {
        WaylineLocationCoordinate2D location = new WaylineLocationCoordinate2D(latLng.getLatitude(), latLng.getLongitude());
        waypoint.setLocation(location);
        waypoint.setHeight(height);
        waypoint.setEllipsoidHeight(height);
        waypoint.setSpeed(speed);
        waypoint.setGimbalPitchAngle(gimbalPitchDeg);

        WaylineWaypointYawParam yawParam = waypoint.getYawParam();
        if (yawParam == null) {
            yawParam = new WaylineWaypointYawParam();
            yawParam.setEnableYawAngle(false);
            yawParam.setYawAngle(0d);
            yawParam.setYawMode(WaylineWaypointYawMode.FOLLOW_WAYLINE);
            yawParam.setYawPathMode(WaylineWaypointYawPathMode.FOLLOW_BAD_ARC);
            waypoint.setYawParam(yawParam);
        }
        yawParam.setPoiLocation(new WaylineLocationCoordinate3D(
                latLng.getLatitude(), latLng.getLongitude(), height));

        WaylineWaypointGimbalHeadingParam gh = waypoint.getGimbalHeadingParam();
        if (gh == null) {
            gh = new WaylineWaypointGimbalHeadingParam();
            gh.setHeadingMode(WaylineWaypointGimbalHeadingMode.FOLLOW_WAYLINE);
            waypoint.setGimbalHeadingParam(gh);
        }
        gh.setPitchAngle(gimbalPitchDeg);
    }

    private WaypointPlanItem buildPlanItem(int index, DJILatLng latLng, double height,
                                           double speed, double gimbalPitchDeg) {
        WaylineWaypoint waypoint = new WaylineWaypoint();
        waypoint.setWaypointIndex(index);
        waypoint.setUseGlobalTurnParam(true);
        waypoint.setUseGlobalYawParam(false);
        applyGeometryAndMotion(waypoint, latLng, height, speed, gimbalPitchDeg);

        WaylineWaypointYawParam yawParam = waypoint.getYawParam();
        if (yawParam != null) {
            yawParam.setEnableYawAngle(false);
            yawParam.setYawAngle(0d);
            yawParam.setYawMode(WaylineWaypointYawMode.FOLLOW_WAYLINE);
            yawParam.setYawPathMode(WaylineWaypointYawPathMode.FOLLOW_BAD_ARC);
        }

        WaylineWaypointGimbalHeadingParam gimbalYawParam = waypoint.getGimbalHeadingParam();
        if (gimbalYawParam != null) {
            gimbalYawParam.setHeadingMode(WaylineWaypointGimbalHeadingMode.FOLLOW_WAYLINE);
        }

        WaypointPlanItem item = new WaypointPlanItem();
        item.setWaylineWaypoint(waypoint);
        item.setTurnMode(globals.globalTurnMode);
        return item;
    }

    private void refreshRoutePolyline(@NonNull DJIMap map) {
        try {
            if (planItems.size() < 2) {
                if (routePolyline != null) {
                    try {
                        routePolyline.remove();
                    } catch (Exception e) {
                        LogUtils.e(TAG, "route remove: " + logEx(e));
                    }
                    routePolyline = null;
                }
                return;
            }
            List<DJILatLng> pts = new ArrayList<>();
            for (WaypointPlanItem it : planItems) {
                if (it == null || it.getWaylineWaypoint() == null) {
                    continue;
                }
                WaylineLocationCoordinate2D loc = it.getWaylineWaypoint().getLocation();
                if (loc == null) {
                    continue;
                }
                pts.add(new DJILatLng(loc.getLatitude(), loc.getLongitude()));
            }
            if (pts.size() < 2) {
                if (routePolyline != null) {
                    routePolyline.remove();
                    routePolyline = null;
                }
                return;
            }
            if (routePolyline != null) {
                try {
                    routePolyline.remove();
                } catch (Exception e) {
                    LogUtils.e(TAG, "old polyline remove: " + logEx(e));
                }
            }
            routePolyline = map.addPolyline(new DJIPolylineOptions()
                    .addAll(pts)
                    .color(Color.argb(220, 0, 170, 255))
                    .width(6f)
                    .zIndex(3f));
        } catch (Exception e) {
            LogUtils.e(TAG, "refreshRoutePolyline: " + logEx(e));
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
        }
    }

    private void saveKmzToCache(boolean quiet) {
        if (planItems.isEmpty()) {
            Toast.makeText(activity, R.string.uxsdk_waypoint_need_one, Toast.LENGTH_SHORT).show();
            return;
        }
        if (etGlobalSpeed != null) {
            globals.globalSpeed = parseAndClamp(etGlobalSpeed, globals.globalSpeed,
                    MIN_GLOBAL_SPEED_MPS, MAX_GLOBAL_SPEED_MPS, "global speed");
        }
        try {
            File out = new File(activity.getCacheDir(), "uxsdk_planned_waypoints.kmz");
            WaylineMission mission = WaypointKmzUtil.createWaylineMission();
            WaylineMissionConfig config = WaypointKmzUtil.createMissionConfig(globals);
            com.dji.wpmzsdk.common.data.Template template = WaypointKmzUtil.createTemplate(planItems, globals);
            WPMZManager.getInstance().generateKMZFile(out.getAbsolutePath(), mission, config, template);
            lastKmzPath = out.getAbsolutePath();
            if (!quiet) {
                drawerLog.append("KMZ saved: " + lastKmzPath);
                Toast.makeText(activity, activity.getString(R.string.uxsdk_waypoint_kmz_saved, lastKmzPath),
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "saveKmzToCache: " + logEx(e));
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
        }
    }

    private void startLastUploadedMission() {
        if (uploadInProgress || startInProgress) {
            return;
        }
        if (planDirtySinceUpload || !lastUploadSucceeded) {
            Toast.makeText(activity, R.string.uxsdk_waypoint_save_first, Toast.LENGTH_SHORT).show();
            drawerLog.append("Start skipped — upload mission first.");
            return;
        }
        try {
            if (lastKmzPath == null) {
                saveKmzToCache(true);
            }
            if (lastKmzPath == null) {
                Toast.makeText(activity, R.string.uxsdk_waypoint_save_first, Toast.LENGTH_SHORT).show();
                drawerLog.append("Start skipped — KMZ not available.");
                return;
            }
            String missionId = missionIdFromPath(lastKmzPath);
            drawerLog.append("Starting mission " + missionId + "…");
            startInProgress = true;
            updateActionButtonsState();
            WaypointMissionManager.getInstance().startMission(missionId, Collections.singletonList(0),
                    new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onSuccess() {
                            activity.runOnUiThread(() ->
                            {
                                startInProgress = false;
                                updateActionButtonsState();
                                drawerLog.append("Mission started.");
                                Toast.makeText(activity, R.string.uxsdk_waypoint_start_ok, Toast.LENGTH_SHORT).show();
                            });
                        }

                        @Override
                        public void onFailure(@NonNull IDJIError error) {
                            activity.runOnUiThread(() ->
                            {
                                startInProgress = false;
                                updateActionButtonsState();
                                String msg = error != null ? error.description() : "?";
                                drawerLog.append("Start failed: " + msg);
                                Toast.makeText(activity,
                                        activity.getString(R.string.uxsdk_waypoint_start_fail, msg),
                                        Toast.LENGTH_LONG).show();
                            });
                        }
                    });
        } catch (Exception e) {
            startInProgress = false;
            updateActionButtonsState();
            LogUtils.e(TAG, "startLastUploadedMission: " + logEx(e));
            drawerLog.append("Start error: " + logEx(e));
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
        }
    }

    @NonNull
    private static String missionIdFromPath(@NonNull String path) {
        try {
            String name = new File(path).getName();
            if (name.endsWith(".kmz")) {
                return name.substring(0, name.length() - 4);
            }
            return name;
        } catch (Exception e) {
            return "mission";
        }
    }

    private static int clampIndex(int v, int min, int max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }

    private static <T extends Enum<T>> List<T> filterUnknown(T[] values) {
        List<T> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (T v : values) {
            if (v != null && !"UNKNOWN".equals(v.name())) {
                out.add(v);
            }
        }
        return out;
    }

    private static List<String> toNames(List<? extends Enum<?>> enums) {
        List<String> n = new ArrayList<>();
        for (Enum<?> e : enums) {
            n.add(e.name());
        }
        return n;
    }

    private static String safeEt(@Nullable EditText et) {
        if (et == null || et.getText() == null) {
            return "";
        }
        return et.getText().toString();
    }

    private static double parseDouble(String s, double def) {
        try {
            if (s == null || s.trim().isEmpty()) {
                return def;
            }
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Nullable
    private DJIBitmapDescriptor getWaypointMarkerIcon() {
        if (waypointMarkerIcon != null) {
            return waypointMarkerIcon;
        }
        try {
            Drawable d = ContextCompat.getDrawable(activity, R.drawable.uxsdk_ic_waypoint_marker);
            if (d == null) {
                return null;
            }
            waypointMarkerIcon = DJIBitmapDescriptorFactory.fromBitmap(ViewUtil.getBitmapFromVectorDrawable(d));
            return waypointMarkerIcon;
        } catch (Exception e) {
            LogUtils.e(TAG, "getWaypointMarkerIcon: " + logEx(e));
            return null;
        }
    }

    private double parseAndClamp(@Nullable EditText et, double def, double min, double max, @NonNull String fieldName) {
        double raw = parseDouble(safeEt(et), def);
        double clamped = Math.max(min, Math.min(max, raw));
        if (Math.abs(raw - clamped) > 1e-6) {
            toastUi(R.string.uxsdk_waypoint_error_generic,
                    fieldName + " clamped to " + String.format(Locale.US, "%.1f", clamped));
        }
        if (et != null) {
            et.setText(String.format(Locale.US, "%.1f", clamped));
        }
        return clamped;
    }

    private void toastUi(int resId, Object... formatArgs) {
        activity.runOnUiThread(() -> {
            try {
                String text;
                if (formatArgs == null || formatArgs.length == 0) {
                    text = activity.getString(resId);
                } else {
                    text = activity.getString(resId, formatArgs);
                }
                Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                LogUtils.e(TAG, "toastUi: " + logEx(e));
            }
        });
    }

    @NonNull
    private static String logEx(@Nullable Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String m = t.getMessage();
        return m != null && !m.isEmpty() ? m : t.getClass().getSimpleName();
    }
}
