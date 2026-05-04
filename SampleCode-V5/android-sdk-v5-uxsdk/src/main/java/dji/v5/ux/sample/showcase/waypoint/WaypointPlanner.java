package dji.v5.ux.sample.showcase.waypoint;

import android.app.AlertDialog;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.dji.wpmzsdk.manager.WPMZManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dji.sdk.wpmz.value.mission.WaylineExitOnRCLostAction;
import dji.sdk.wpmz.value.mission.WaylineFinishedAction;
import dji.sdk.wpmz.value.mission.WaylineLocationCoordinate2D;
import dji.sdk.wpmz.value.mission.WaylineLocationCoordinate3D;
import dji.sdk.wpmz.value.mission.WaylineMission;
import dji.sdk.wpmz.value.mission.WaylineMissionConfig;
import dji.sdk.wpmz.value.mission.WaylineWaypoint;
import dji.sdk.wpmz.value.mission.WaylineWaypointGimbalHeadingMode;
import dji.sdk.wpmz.value.mission.WaylineWaypointGimbalHeadingParam;
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
import dji.v5.ux.mapkit.core.models.DJILatLng;
import dji.v5.ux.mapkit.core.models.annotations.DJIMarker;
import dji.v5.ux.mapkit.core.models.annotations.DJIMarkerOptions;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolyline;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolylineOptions;

/**
 * Interactive waypoint planning on a {@link MapWidget}: add points, configure each, build KMZ, upload, start.
 */
public final class WaypointPlanner {

    private static final String TAG = "WaypointPlanner";

    private final AppCompatActivity activity;
    private final MapWidget mapWidget;

    private final WaypointMissionGlobals globals = new WaypointMissionGlobals();
    private final List<WaypointPlanItem> planItems = new ArrayList<>();
    private final List<DJIMarker> markers = new ArrayList<>();

    private boolean planningActive;
    private boolean configDialogOpen;
    private DJIPolyline routePolyline;
    private String lastKmzPath;

    private final DJIMap.OnMarkerClickListener waypointMarkerClickListener = marker -> {
        try {
            if (!planningActive || configDialogOpen) {
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
            WaylineLocationCoordinate2D loc = wp.getLocation();
            DJILatLng pos = new DJILatLng(loc.getLatitude(), loc.getLongitude());
            showWaypointConfigDialog(Integer.valueOf(idx), pos);
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

    public void startWaypointPlanning() {
        planningActive = true;
        attachMarkerClickListenerSafe();
        Toast.makeText(activity, R.string.uxsdk_waypoint_tap_map_hint, Toast.LENGTH_LONG).show();
    }

    /**
     * @return true if the click was consumed (do not run map / FPV swap logic).
     */
    public boolean onMapClick(@NonNull DJILatLng latLng) {
        if (!planningActive || configDialogOpen) {
            return false;
        }
        try {
            showWaypointConfigDialog(null, latLng);
            return true;
        } catch (Exception e) {
            LogUtils.e(TAG, "onMapClick: " + logEx(e));
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
            return true;
        }
    }

    public void openPlanningMenu() {
        if (!planningActive) {
            return;
        }
        try {
            String[] items = activity.getResources().getStringArray(R.array.uxsdk_waypoint_plan_menu);
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.uxsdk_waypoint_plan_menu_title)
                    .setItems(items, (d, which) -> {
                        try {
                            if (which == 0) {
                                showMissionGlobalsDialog();
                            } else if (which == 1) {
                                saveKmzToCache(false);
                            } else if (which == 2) {
                                uploadLastKmz();
                            } else if (which == 3) {
                                startLastUploadedMission();
                            } else if (which == 4) {
                                new AlertDialog.Builder(activity)
                                        .setTitle(R.string.uxsdk_waypoint_discard_title)
                                        .setMessage(R.string.uxsdk_waypoint_discard_message)
                                        .setPositiveButton(android.R.string.ok, (di, w) -> clearPlanning())
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .show();
                            }
                        } catch (Exception e) {
                            LogUtils.e(TAG, "plan menu item: " + logEx(e));
                            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Exception e) {
            LogUtils.e(TAG, "openPlanningMenu: " + logEx(e));
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
        }
    }

    public void clearPlanning() {
        detachMarkerClickListenerSafe();
        planningActive = false;
        configDialogOpen = false;
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

    private void showMissionGlobalsDialog() {
        try {
            View root = LayoutInflater.from(activity).inflate(R.layout.uxsdk_dialog_mission_globals, null, false);
            Spinner finishSpinner = root.findViewById(R.id.uxsdk_spinner_finish_action);
            Spinner lostSpinner = root.findViewById(R.id.uxsdk_spinner_rc_lost);
            EditText etGlobalSpeed = root.findViewById(R.id.uxsdk_et_mission_global_speed);

            List<WaylineFinishedAction> finishChoices = filterUnknown(WaylineFinishedAction.values());
            List<WaylineExitOnRCLostAction> lostChoices = filterUnknown(WaylineExitOnRCLostAction.values());
            if (finishChoices.isEmpty() || lostChoices.isEmpty()) {
                toastUi(R.string.uxsdk_waypoint_error_generic, "Mission enums unavailable");
                return;
            }

            finishSpinner.setAdapter(new android.widget.ArrayAdapter<>(
                    activity, android.R.layout.simple_spinner_dropdown_item, toNames(finishChoices)));
            lostSpinner.setAdapter(new android.widget.ArrayAdapter<>(
                    activity, android.R.layout.simple_spinner_dropdown_item, toNames(lostChoices)));

            int fi = finishChoices.indexOf(globals.finishAction);
            int li = lostChoices.indexOf(globals.lostAction);
            finishSpinner.setSelection(clampIndex(fi, 0, finishChoices.size() - 1));
            lostSpinner.setSelection(clampIndex(li, 0, lostChoices.size() - 1));
            etGlobalSpeed.setText(String.valueOf(globals.globalSpeed));

            new AlertDialog.Builder(activity)
                    .setTitle(R.string.uxsdk_mission_globals_title)
                    .setView(root)
                    .setPositiveButton(R.string.uxsdk_app_ok, (dialog, w) -> {
                        try {
                            int fp = clampIndex(finishSpinner.getSelectedItemPosition(), 0, finishChoices.size() - 1);
                            int lp = clampIndex(lostSpinner.getSelectedItemPosition(), 0, lostChoices.size() - 1);
                            globals.finishAction = finishChoices.get(fp);
                            globals.lostAction = lostChoices.get(lp);
                            globals.globalSpeed = parseDouble(etGlobalSpeed.getText() != null
                                    ? etGlobalSpeed.getText().toString() : "", globals.globalSpeed);
                        } catch (Exception e) {
                            LogUtils.e(TAG, "save mission globals: " + logEx(e));
                            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
                        }
                    })
                    .setNegativeButton(R.string.uxsdk_app_cancel, null)
                    .show();
        } catch (Exception e) {
            LogUtils.e(TAG, "showMissionGlobalsDialog: " + logEx(e));
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
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

    /**
     * @param editIndex null to add a new waypoint at {@code latLng}; non-null to edit existing.
     */
    private void showWaypointConfigDialog(@Nullable Integer editIndex, @NonNull DJILatLng latLng) {
        DJIMap map = mapWidget.getMap();
        if (map == null) {
            toastUi(R.string.uxsdk_waypoint_error_generic, "Map not ready");
            return;
        }
        try {
            View root = LayoutInflater.from(activity).inflate(R.layout.uxsdk_dialog_waypoint_config, null, false);
            TextView heading = root.findViewById(R.id.uxsdk_waypoint_dialog_heading);
            TextView summary = root.findViewById(R.id.uxsdk_waypoint_coord_summary);
            EditText etHeight = root.findViewById(R.id.uxsdk_et_waypoint_height);
            EditText etSpeed = root.findViewById(R.id.uxsdk_et_waypoint_speed);
            EditText etGimbal = root.findViewById(R.id.uxsdk_et_waypoint_gimbal_pitch);
            CheckBox cbGlobalSpeed = root.findViewById(R.id.uxsdk_cb_use_global_speed);
            EditText etGlobalSpeed = root.findViewById(R.id.uxsdk_et_global_speed);

            final boolean isEdit = editIndex != null;
            if (isEdit && (editIndex < 0 || editIndex >= planItems.size())) {
                toastUi(R.string.uxsdk_waypoint_error_invalid_state);
                return;
            }
            if (heading != null) {
                heading.setText(isEdit ? activity.getString(R.string.uxsdk_waypoint_dialog_heading_edit)
                        : activity.getString(R.string.uxsdk_waypoint_dialog_heading_add));
            }
            String latStr = String.format(Locale.US, "%.6f", latLng.getLatitude());
            String lonStr = String.format(Locale.US, "%.6f", latLng.getLongitude());
            summary.setText(activity.getString(R.string.uxsdk_waypoint_coord_lat_prefix)
                    + latStr
                    + activity.getString(R.string.uxsdk_waypoint_coord_lon_prefix)
                    + lonStr);

            if (isEdit) {
                try {
                    WaylineWaypoint w = planItems.get(editIndex).getWaylineWaypoint();
                    if (w == null) {
                        toastUi(R.string.uxsdk_waypoint_error_invalid_state);
                        return;
                    }
                    double h = w.getHeight();
                    double sp = w.getSpeed();
                    double gb = w.getGimbalPitchAngle();
                    etHeight.setText(String.valueOf(h));
                    etSpeed.setText(String.valueOf(sp));
                    etGimbal.setText(String.valueOf(gb));
                    boolean useGlobal = Math.abs(sp - globals.globalSpeed) < 0.05;
                    cbGlobalSpeed.setChecked(useGlobal);
                    etGlobalSpeed.setText(String.valueOf(globals.globalSpeed));
                } catch (Exception e) {
                    LogUtils.e(TAG, "prefill waypoint: " + logEx(e));
                    toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
                    return;
                }
            } else {
                etHeight.setText("30");
                etSpeed.setText(String.valueOf(globals.globalSpeed));
                etGimbal.setText("-30");
                cbGlobalSpeed.setChecked(true);
                etGlobalSpeed.setText(String.valueOf(globals.globalSpeed));
            }

            configDialogOpen = true;
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setView(root)
                    .setPositiveButton(isEdit ? R.string.uxsdk_waypoint_save : R.string.uxsdk_waypoint_add_confirm,
                            (dialog, w) -> {
                                try {
                                    applyWaypointFromForm(map, editIndex, latLng, etHeight, etSpeed, etGimbal,
                                            cbGlobalSpeed, etGlobalSpeed, isEdit);
                                } catch (Exception e) {
                                    LogUtils.e(TAG, "save waypoint: " + logEx(e));
                                    toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
                                } finally {
                                    configDialogOpen = false;
                                }
                            })
                    .setNegativeButton(R.string.uxsdk_app_cancel, (dialog, w) -> {
                        configDialogOpen = false;
                    })
                    .setOnDismissListener(dialog -> configDialogOpen = false);

            if (isEdit) {
                final int idx = editIndex.intValue();
                builder.setNeutralButton(R.string.uxsdk_waypoint_delete, (dialog, w) -> {
                    try {
                        new AlertDialog.Builder(activity)
                                .setTitle(R.string.uxsdk_waypoint_delete_confirm_title)
                                .setMessage(R.string.uxsdk_waypoint_delete_confirm_message)
                                .setPositiveButton(R.string.uxsdk_waypoint_delete, (d2, w2) -> {
                                    try {
                                        deleteWaypointAt(idx);
                                        configDialogOpen = false;
                                    } catch (Exception e) {
                                        LogUtils.e(TAG, "delete waypoint: " + logEx(e));
                                        toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
                                        configDialogOpen = false;
                                    }
                                })
                                .setNegativeButton(R.string.uxsdk_app_cancel, (d2, w2) -> configDialogOpen = false)
                                .show();
                    } catch (Exception e) {
                        LogUtils.e(TAG, "delete confirm: " + logEx(e));
                        toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
                        configDialogOpen = false;
                    }
                });
            }

            builder.show();
        } catch (Exception e) {
            LogUtils.e(TAG, "showWaypointConfigDialog: " + logEx(e));
            configDialogOpen = false;
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
        }
    }

    private void applyWaypointFromForm(@NonNull DJIMap map, @Nullable Integer editIndex, @NonNull DJILatLng latLng,
                                       EditText etHeight, EditText etSpeed, EditText etGimbal,
                                       CheckBox cbGlobalSpeed, EditText etGlobalSpeed, boolean isEdit) {
        if (etHeight == null || etSpeed == null || etGimbal == null || cbGlobalSpeed == null || etGlobalSpeed == null) {
            toastUi(R.string.uxsdk_waypoint_error_generic, "Form fields missing");
            return;
        }
        double height = parseDouble(safeEt(etHeight), 30);
        double gimbalPitch = parseDouble(safeEt(etGimbal), -30);
        boolean useGlobal = cbGlobalSpeed.isChecked();
        double gSpeed = parseDouble(safeEt(etGlobalSpeed), globals.globalSpeed);
        double wSpeed = parseDouble(safeEt(etSpeed), globals.globalSpeed);
        if (useGlobal) {
            globals.globalSpeed = gSpeed;
        }
        double appliedSpeed = useGlobal ? globals.globalSpeed : wSpeed;

        if (isEdit && editIndex != null) {
            if (editIndex < 0 || editIndex >= planItems.size()) {
                toastUi(R.string.uxsdk_waypoint_error_generic, "Invalid waypoint index");
                return;
            }
            WaypointPlanItem existing = planItems.get(editIndex);
            if (existing == null) {
                toastUi(R.string.uxsdk_waypoint_error_invalid_state);
                return;
            }
            WaylineWaypoint waypoint = existing.getWaylineWaypoint();
            if (waypoint == null) {
                toastUi(R.string.uxsdk_waypoint_error_invalid_state);
                return;
            }
            applyGeometryAndMotion(waypoint, latLng, height, appliedSpeed, gimbalPitch);
            Toast.makeText(activity, R.string.uxsdk_waypoint_updated, Toast.LENGTH_SHORT).show();
        } else {
            try {
                WaypointPlanItem item = buildPlanItem(planItems.size(), latLng, height, appliedSpeed, gimbalPitch);
                item.setActionInfos(new ArrayList<>());
                planItems.add(item);

                DJIMarker marker = map.addMarker(new DJIMarkerOptions()
                        .position(latLng)
                        .title(activity.getString(R.string.uxsdk_waypoint_marker_title, planItems.size()))
                        .zIndex(4)
                        .setInfoWindowEnable(false));
                if (marker == null) {
                    planItems.remove(planItems.size() - 1);
                    toastUi(R.string.uxsdk_waypoint_error_generic, "Could not create map marker");
                    return;
                }
                markers.add(marker);
                Toast.makeText(activity, R.string.uxsdk_waypoint_added, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                LogUtils.e(TAG, "add waypoint: " + logEx(e));
                if (!planItems.isEmpty()) {
                    try {
                        planItems.remove(planItems.size() - 1);
                    } catch (Exception ignore) {
                        LogUtils.e(TAG, "rollback plan item: " + logEx(ignore));
                    }
                }
                toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
                return;
            }
        }
        refreshRoutePolyline(map);
    }

    private static String safeEt(@Nullable EditText et) {
        if (et == null || et.getText() == null) {
            return "";
        }
        return et.getText().toString();
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
            toastUi(R.string.uxsdk_waypoint_deleted);
        } catch (Exception e) {
            LogUtils.e(TAG, "deleteWaypointAt: " + logEx(e));
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
        try {
            File out = new File(activity.getCacheDir(), "uxsdk_planned_waypoints.kmz");
            WaylineMission mission = WaypointKmzUtil.createWaylineMission();
            WaylineMissionConfig config = WaypointKmzUtil.createMissionConfig(globals);
            com.dji.wpmzsdk.common.data.Template template = WaypointKmzUtil.createTemplate(planItems);
            WPMZManager.getInstance().generateKMZFile(out.getAbsolutePath(), mission, config, template);
            lastKmzPath = out.getAbsolutePath();
            if (!quiet) {
                Toast.makeText(activity, activity.getString(R.string.uxsdk_waypoint_kmz_saved, lastKmzPath),
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "saveKmzToCache: " + logEx(e));
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
        }
    }

    private void uploadLastKmz() {
        if (planItems.isEmpty()) {
            Toast.makeText(activity, R.string.uxsdk_waypoint_need_one, Toast.LENGTH_SHORT).show();
            return;
        }
        saveKmzToCache(true);
        if (lastKmzPath == null) {
            return;
        }
        try {
            WaypointMissionManager.getInstance().pushKMZFileToAircraft(lastKmzPath,
                    new CommonCallbacks.CompletionCallbackWithProgress<Double>() {
                        @Override
                        public void onProgressUpdate(Double progress) {
                        }

                        @Override
                        public void onSuccess() {
                            activity.runOnUiThread(() ->
                                    Toast.makeText(activity, R.string.uxsdk_waypoint_upload_ok, Toast.LENGTH_SHORT).show());
                        }

                        @Override
                        public void onFailure(@NonNull IDJIError error) {
                            activity.runOnUiThread(() ->
                                    Toast.makeText(activity,
                                            activity.getString(R.string.uxsdk_waypoint_upload_fail,
                                                    error != null ? error.description() : "?"),
                                            Toast.LENGTH_LONG).show());
                        }
                    });
        } catch (Exception e) {
            LogUtils.e(TAG, "uploadLastKmz: " + logEx(e));
            toastUi(R.string.uxsdk_waypoint_error_generic, logEx(e));
        }
    }

    private void startLastUploadedMission() {
        try {
            if (lastKmzPath == null) {
                saveKmzToCache(true);
            }
            if (lastKmzPath == null) {
                Toast.makeText(activity, R.string.uxsdk_waypoint_save_first, Toast.LENGTH_SHORT).show();
                return;
            }
            String missionId = missionIdFromPath(lastKmzPath);
            WaypointMissionManager.getInstance().startMission(missionId, Collections.singletonList(0),
                    new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onSuccess() {
                            activity.runOnUiThread(() ->
                                    Toast.makeText(activity, R.string.uxsdk_waypoint_start_ok, Toast.LENGTH_SHORT).show());
                        }

                        @Override
                        public void onFailure(@NonNull IDJIError error) {
                            activity.runOnUiThread(() ->
                                    Toast.makeText(activity,
                                            activity.getString(R.string.uxsdk_waypoint_start_fail,
                                                    error != null ? error.description() : "?"),
                                            Toast.LENGTH_LONG).show());
                        }
                    });
        } catch (Exception e) {
            LogUtils.e(TAG, "startLastUploadedMission: " + logEx(e));
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
