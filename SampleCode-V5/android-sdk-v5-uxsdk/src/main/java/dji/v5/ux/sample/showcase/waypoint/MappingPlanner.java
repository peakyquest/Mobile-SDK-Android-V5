package dji.v5.ux.sample.showcase.waypoint;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.dji.wpmzsdk.manager.WPMZManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dji.sdk.wpmz.value.mission.WaylineLocationCoordinate2D;
import dji.sdk.wpmz.value.mission.WaylineLocationCoordinate3D;
import dji.sdk.wpmz.value.mission.WaylineExitOnRCLostAction;
import dji.sdk.wpmz.value.mission.WaylineFinishedAction;
import dji.sdk.wpmz.value.mission.WaylineWaypoint;
import dji.sdk.wpmz.value.mission.WaylineWaypointGimbalHeadingMode;
import dji.sdk.wpmz.value.mission.WaylineWaypointGimbalHeadingParam;
import dji.sdk.wpmz.value.mission.WaylineWaypointYawMode;
import dji.sdk.wpmz.value.mission.WaylineWaypointYawParam;
import dji.sdk.wpmz.value.mission.WaylineWaypointYawPathMode;
import dji.v5.common.callback.CommonCallbacks;
import dji.v5.common.error.IDJIError;
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager;
import dji.v5.ux.R;
import dji.v5.ux.core.util.ViewUtil;
import dji.v5.ux.map.MapWidget;
import dji.v5.ux.mapkit.core.maps.DJIMap;
import dji.v5.ux.mapkit.core.models.DJIBitmapDescriptor;
import dji.v5.ux.mapkit.core.models.DJIBitmapDescriptorFactory;
import dji.v5.ux.mapkit.core.models.DJICameraPosition;
import dji.v5.ux.mapkit.core.models.DJILatLng;
import dji.v5.ux.mapkit.core.models.annotations.DJIMarker;
import dji.v5.ux.mapkit.core.models.annotations.DJIMarkerOptions;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolygon;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolygonOptions;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolyline;
import dji.v5.ux.mapkit.core.models.annotations.DJIPolylineOptions;

/**
 * Mapping planner: tap to add polygon vertices and generate predefined shapes.
 */
public final class MappingPlanner {
    private static final String TAG = "MappingPlanner";
    private static final String PREFS_MAPPING_TUTORIAL = "uxsdk_mapping_tutorial_prefs";
    private static final String KEY_MAPPING_TUTORIAL_SHOWN = "mapping_tutorial_shown";
    private final AppCompatActivity activity;
    private final MapWidget mapWidget;

    private final List<EditablePolygon> polygons = new ArrayList<>();
    private long nextPolygonId = 1L;
    private boolean planningActive;
    private long activePolygonId = -1L;

    private View drawerShell;
    private View drawerSlide;
    private TextView tvCount;
    private TextView tvArea;
    private TextView tvPerimeter;
    private TextView tvGeneratedCount;
    private TextView tvGeneratedDistance;
    private TextView tvGeneratedEta;
    private EditText etAltitude;
    private EditText etSpeed;
    private EditText etGimbalPitch;
    private EditText etSpacing;
    private Spinner spFinish;
    private Spinner spRcLost;
    private ProgressBar uploadProgress;
    private Button btnUpload;
    private Button btnStart;
    private DJIMap.OnMarkerDragListener markerDragListener;
    private DJIMap.OnMarkerClickListener markerClickListener;
    private long selectedPolygonId = -1L;
    private int selectedVertexIndex = -1;
    private DJIBitmapDescriptor waypointMarkerIcon;
    private DJIBitmapDescriptor generatedWaypointMarkerIcon;
    private final WaypointMissionGlobals missionGlobals = new WaypointMissionGlobals();
    private final List<WaylineFinishedAction> finishChoices = new ArrayList<>();
    private final List<WaylineExitOnRCLostAction> lostChoices = new ArrayList<>();
    private boolean suppressMissionSpinnerCallbacks;
    private boolean uploadInProgress;
    private boolean startInProgress;
    private final MissionDrawerLog drawerLog = new MissionDrawerLog();
    private boolean planDirtySinceUpload = true;
    private boolean lastUploadSucceeded;
    @Nullable
    private String lastKmzPath;

    private static final class EditablePolygon {
        private final long id;
        private final List<DJILatLng> points = new ArrayList<>();
        private final List<DJIMarker> markers = new ArrayList<>();
        @Nullable
        private DJIPolygon polygon;
        private boolean isCircle;
        private double circleRadiusMeters;
        @Nullable
        private DJILatLng circleCenter;
        @Nullable
        private DJIMarker circleCenterMarker;
        private final List<DJILatLng> generatedWaypoints = new ArrayList<>();
        private final List<DJIMarker> generatedMarkers = new ArrayList<>();
        @Nullable
        private DJIPolyline generatedRouteLine;

        private EditablePolygon(long id) {
            this.id = id;
        }
    }

    private static final int CIRCLE_SEGMENTS = 24;
    private static final int MAX_GENERATED_WAYPOINTS = 220;
    private static final double MIN_MAPPING_SPACING_M = 20.0;
    private static final double MAX_MAPPING_SPACING_M = 80.0;
    private static final double MAX_ROUTE_DISTANCE_M = 12000.0;
    private static final double MAX_FLIGHT_TIME_S = 20 * 60.0;
    private static final double MIN_ALTITUDE_M = 20.0;
    private static final double MAX_ALTITUDE_M = 500.0;
    private static final double MIN_SPEED_MPS = 1.0;
    private static final double MAX_SPEED_MPS = 15.0;
    private static final double MIN_GIMBAL_PITCH = -90.0;
    private static final double MAX_GIMBAL_PITCH = 30.0;
    private static final double DEFAULT_CIRCLE_RADIUS_M = 35.0;
    private static final double MIN_CIRCLE_RADIUS_M = 5.0;
    private static final double MAX_CIRCLE_RADIUS_M = 1000.0;

    public MappingPlanner(@NonNull AppCompatActivity activity, @NonNull MapWidget mapWidget) {
        this.activity = activity;
        this.mapWidget = mapWidget;
    }

    public boolean isPlanningActive() {
        return planningActive;
    }

    public void bindDrawer(@Nullable View shellRoot) {
        drawerShell = shellRoot;
        if (drawerShell == null) {
            return;
        }
        drawerSlide = drawerShell.findViewById(R.id.uxsdk_mapping_drawer_slide);
        View scrim = drawerShell.findViewById(R.id.uxsdk_mapping_drawer_scrim);
        View close = drawerShell.findViewById(R.id.uxsdk_mapping_drawer_btn_close);
        Button btnSquare = drawerShell.findViewById(R.id.uxsdk_mapping_btn_square);
        Button btnRect = drawerShell.findViewById(R.id.uxsdk_mapping_btn_rectangle);
        Button btnCircle = drawerShell.findViewById(R.id.uxsdk_mapping_btn_circle);
        Button btnUndo = drawerShell.findViewById(R.id.uxsdk_mapping_btn_undo);
        Button btnClear = drawerShell.findViewById(R.id.uxsdk_mapping_btn_clear);
        Button btnExit = drawerShell.findViewById(R.id.uxsdk_mapping_btn_exit);
        btnUpload = drawerShell.findViewById(R.id.uxsdk_mapping_btn_upload);
        btnStart = drawerShell.findViewById(R.id.uxsdk_mapping_btn_start);
        Button btnGenerate = drawerShell.findViewById(R.id.uxsdk_mapping_btn_generate_waypoints);
        tvCount = drawerShell.findViewById(R.id.uxsdk_mapping_tv_count);
        tvArea = drawerShell.findViewById(R.id.uxsdk_mapping_tv_area);
        tvPerimeter = drawerShell.findViewById(R.id.uxsdk_mapping_tv_perimeter);
        tvGeneratedCount = drawerShell.findViewById(R.id.uxsdk_mapping_tv_generated_count);
        tvGeneratedDistance = drawerShell.findViewById(R.id.uxsdk_mapping_tv_generated_distance);
        tvGeneratedEta = drawerShell.findViewById(R.id.uxsdk_mapping_tv_generated_eta);
        etAltitude = drawerShell.findViewById(R.id.uxsdk_mapping_et_altitude);
        etSpeed = drawerShell.findViewById(R.id.uxsdk_mapping_et_speed);
        etGimbalPitch = drawerShell.findViewById(R.id.uxsdk_mapping_et_gimbal_pitch);
        etSpacing = drawerShell.findViewById(R.id.uxsdk_mapping_et_spacing);
        spFinish = drawerShell.findViewById(R.id.uxsdk_mapping_sp_finish);
        spRcLost = drawerShell.findViewById(R.id.uxsdk_mapping_sp_rc_lost);
        uploadProgress = drawerShell.findViewById(R.id.uxsdk_mapping_upload_progress);
        drawerLog.setLogTag(TAG);
        drawerLog.bind(drawerShell.findViewById(R.id.uxsdk_mission_drawer_tv_log));

        setupMissionActionSpinners();

        if (close != null) close.setOnClickListener(v -> hideDrawer());
        if (scrim != null) scrim.setOnClickListener(v -> hideDrawer());
        if (btnSquare != null) btnSquare.setOnClickListener(v -> buildSquare());
        if (btnRect != null) btnRect.setOnClickListener(v -> buildRectangle());
        if (btnCircle != null) btnCircle.setOnClickListener(v -> buildCircle());
        if (btnUndo != null) btnUndo.setOnClickListener(v -> undoLastPoint());
        if (btnClear != null) btnClear.setOnClickListener(v -> clearPolygonOnly());
        if (btnExit != null) btnExit.setOnClickListener(v -> clearPlanning());
        if (btnUpload != null) btnUpload.setOnClickListener(v -> uploadMissionForActivePolygon());
        if (btnStart != null) btnStart.setOnClickListener(v -> startLastUploadedMission());
        if (btnGenerate != null) btnGenerate.setOnClickListener(v -> generateWaypointsForActivePolygon());
        refreshSummary();
        updateUploadUiIdle();
        updateMissionActionState();
    }

    private void setupMissionActionSpinners() {
        if (spFinish == null || spRcLost == null) {
            return;
        }
        finishChoices.clear();
        finishChoices.addAll(filterUnknown(WaylineFinishedAction.values()));
        lostChoices.clear();
        lostChoices.addAll(filterUnknown(WaylineExitOnRCLostAction.values()));
        if (finishChoices.isEmpty() || lostChoices.isEmpty()) {
            return;
        }

        if (!finishChoices.contains(missionGlobals.finishAction)) {
            missionGlobals.finishAction = WaylineFinishedAction.GO_HOME;
        }
        if (!lostChoices.contains(missionGlobals.lostAction)) {
            missionGlobals.lostAction = WaylineExitOnRCLostAction.GO_BACK;
        }

        ArrayAdapter<String> finishAdapter = new ArrayAdapter<>(activity,
                R.layout.uxsdk_waypoint_drawer_spinner_item, R.id.uxsdk_waypoint_spinner_text,
                toDisplayNames(finishChoices));
        finishAdapter.setDropDownViewResource(R.layout.uxsdk_waypoint_drawer_spinner_dropdown_item);
        spFinish.setAdapter(finishAdapter);

        ArrayAdapter<String> rcLostAdapter = new ArrayAdapter<>(activity,
                R.layout.uxsdk_waypoint_drawer_spinner_item, R.id.uxsdk_waypoint_spinner_text,
                toDisplayNames(lostChoices));
        rcLostAdapter.setDropDownViewResource(R.layout.uxsdk_waypoint_drawer_spinner_dropdown_item);
        spRcLost.setAdapter(rcLostAdapter);

        int popupBg = R.drawable.uxsdk_waypoint_drawer_spinner_popup_bg;
        spFinish.setPopupBackgroundResource(popupBg);
        spRcLost.setPopupBackgroundResource(popupBg);

        spFinish.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                WaylineFinishedAction selected = finishChoices.get(clampIndex(position, 0, finishChoices.size() - 1));
                if (!suppressMissionSpinnerCallbacks && missionGlobals.finishAction != selected) {
                    missionGlobals.finishAction = selected;
                    markPlanDirty();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        spRcLost.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                WaylineExitOnRCLostAction selected = lostChoices.get(clampIndex(position, 0, lostChoices.size() - 1));
                if (!suppressMissionSpinnerCallbacks && missionGlobals.lostAction != selected) {
                    missionGlobals.lostAction = selected;
                    markPlanDirty();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        int finishIndex = finishChoices.indexOf(missionGlobals.finishAction);
        int lostIndex = lostChoices.indexOf(missionGlobals.lostAction);
        suppressMissionSpinnerCallbacks = true;
        spFinish.setSelection(clampIndex(finishIndex, 0, finishChoices.size() - 1));
        spRcLost.setSelection(clampIndex(lostIndex, 0, lostChoices.size() - 1));
        suppressMissionSpinnerCallbacks = false;
    }

    public void startMappingPlanning() {
        planningActive = true;
        attachDragListener();
        uploadInProgress = false;
        startInProgress = false;
        drawerLog.clear();
        drawerLog.append("Mapping planning started — tap the map to add polygon vertices.");
        markPlanDirty();
        showDrawer(true);
        Toast.makeText(activity, R.string.uxsdk_mapping_tap_map_hint, Toast.LENGTH_LONG).show();
        maybeShowFirstTimeTutorial();
    }

    public void toggleDrawer() {
        if (!planningActive || drawerShell == null) return;
        if (drawerShell.getVisibility() == View.VISIBLE) {
            hideDrawer();
        } else {
            showDrawer(false);
        }
    }

    public boolean onMapClick(@NonNull DJILatLng latLng) {
        if (!planningActive) return false;
        DJIMap map = mapWidget.getMap();
        if (map == null) return true;

        EditablePolygon selectedPolygon = getPolygonById(selectedPolygonId);
        if (selectedPolygon != null && selectedVertexIndex >= 0 && selectedVertexIndex < selectedPolygon.points.size()) {
            selectedPolygon.points.set(selectedVertexIndex, latLng);
            if (selectedVertexIndex < selectedPolygon.markers.size()) {
                DJIMarker marker = selectedPolygon.markers.get(selectedVertexIndex);
                if (marker != null) {
                    try { marker.setPosition(latLng); } catch (Exception ignored) { }
                }
            }
            activePolygonId = selectedPolygon.id;
            selectedPolygonId = -1L;
            selectedVertexIndex = -1;
            redrawPolygon(selectedPolygon, map);
            drawerLog.append(String.format(Locale.US,
                    "Vertex %d moved (%.6f, %.6f).", selectedVertexIndex + 1,
                    latLng.getLatitude(), latLng.getLongitude()));
            Toast.makeText(activity, R.string.uxsdk_mapping_vertex_updated, Toast.LENGTH_SHORT).show();
            return true;
        }

        EditablePolygon active = getOrCreateActivePolygon();
        active.points.add(latLng);
        DJIMarker mk = addVertexMarker(map, latLng, active.points.size());
        if (mk != null) active.markers.add(mk);
        redrawPolygon(active, map);
        drawerLog.append(String.format(Locale.US,
                "Vertex %d added (%.6f, %.6f).", active.points.size(), latLng.getLatitude(), latLng.getLongitude()));
        return true;
    }

    public void clearPlanning() {
        drawerLog.append("Mapping planning cleared.");
        planningActive = false;
        detachDragListener();
        detachMarkerClickListener();
        clearAllPolygons();
        uploadInProgress = false;
        startInProgress = false;
        markPlanDirty();
        hideDrawerImmediate();
    }

    private void undoLastPoint() {
        EditablePolygon active = getActivePolygon();
        if (active == null || active.points.isEmpty()) {
            return;
        }
        int idx = active.points.size() - 1;
        active.points.remove(idx);
        if (selectedPolygonId == active.id && selectedVertexIndex == idx) {
            selectedPolygonId = -1L;
            selectedVertexIndex = -1;
        }
        if (selectedVertexIndex == idx) {
            selectedVertexIndex = -1;
        } else if (selectedVertexIndex > idx) {
            selectedVertexIndex -= 1;
        }
        if (idx < active.markers.size()) {
            DJIMarker marker = active.markers.remove(idx);
            if (marker != null) {
                try { marker.remove(); } catch (Exception ignored) { }
            }
        }
        DJIMap map = mapWidget.getMap();
        if (map != null) {
            redrawPolygon(active, map);
        } else {
            refreshSummary();
        }
        updateMarkerTitles(active);
        drawerLog.append("Last vertex removed.");
    }

    private void clearPolygonOnly() {
        EditablePolygon active = getActivePolygon();
        if (active == null) return;
        removePolygon(active);
        polygons.remove(active);
        activePolygonId = polygons.isEmpty() ? -1L : polygons.get(polygons.size() - 1).id;
        if (selectedPolygonId == active.id) {
            selectedPolygonId = -1L;
            selectedVertexIndex = -1;
        }
        refreshSummary();
        drawerLog.append("Polygon cleared.");
    }

    private void clearAllPolygons() {
        for (EditablePolygon polygon : new ArrayList<>(polygons)) {
            removePolygon(polygon);
        }
        polygons.clear();
        activePolygonId = -1L;
        selectedPolygonId = -1L;
        selectedVertexIndex = -1;
        refreshSummary();
    }

    private void buildSquare() {
        DJILatLng c = resolveShapeCenter();
        double half = 30.0;
        drawerLog.append("Square shape placed (60×60 m).");
        addShapePolygon(buildRectPoints(c, half, half));
    }

    private void buildRectangle() {
        DJILatLng c = resolveShapeCenter();
        drawerLog.append("Rectangle shape placed (90×50 m).");
        addShapePolygon(buildRectPoints(c, 45.0, 25.0));
    }

    private void buildCircle() {
        DJILatLng c = resolveShapeCenter();
        drawerLog.append(String.format(Locale.US, "Circle shape placed (r=%.0f m).", DEFAULT_CIRCLE_RADIUS_M));
        addCirclePolygon(c, DEFAULT_CIRCLE_RADIUS_M);
    }

    private void addShapePolygon(@NonNull List<DJILatLng> shapePoints) {
        DJIMap map = mapWidget.getMap();
        if (map == null) return;
        EditablePolygon polygon = new EditablePolygon(nextPolygonId++);
        polygon.points.addAll(shapePoints);
        for (int i = 0; i < polygon.points.size(); i++) {
            DJIMarker mk = addVertexMarker(map, polygon.points.get(i), i + 1);
            if (mk != null) polygon.markers.add(mk);
        }
        polygons.add(polygon);
        activePolygonId = polygon.id;
        redrawPolygon(polygon, map);
    }

    private void addCirclePolygon(@NonNull DJILatLng center, double radiusMeters) {
        DJIMap map = mapWidget.getMap();
        if (map == null) return;
        EditablePolygon polygon = new EditablePolygon(nextPolygonId++);
        polygon.isCircle = true;
        polygon.circleCenter = center;
        polygon.circleRadiusMeters = clamp(radiusMeters, MIN_CIRCLE_RADIUS_M, MAX_CIRCLE_RADIUS_M);
        polygon.circleCenterMarker = addCenterMarker(map, center);
        regenerateCircleGeometry(polygon, map);
        polygons.add(polygon);
        activePolygonId = polygon.id;
        refreshSummary();
    }

    private void redrawPolygon(@NonNull EditablePolygon editablePolygon, @NonNull DJIMap map) {
        // Geometry changed; previously generated mapping waypoints are no longer valid.
        clearGeneratedOverlays(editablePolygon);
        if (editablePolygon.polygon != null) {
            try { editablePolygon.polygon.remove(); } catch (Exception ignored) { }
            editablePolygon.polygon = null;
        }
        if (editablePolygon.points.size() >= 3) {
            DJIPolygonOptions options = new DJIPolygonOptions()
                    .addAll(editablePolygon.points)
                    .strokeWidth(4f)
                    .strokeColor(Color.argb(230, 0, 170, 255))
                    .fillColor(Color.argb(80, 0, 170, 255))
                    .zIndex(2f);
            editablePolygon.polygon = map.addPolygon(options);
        }
        refreshSummary();
    }

    private void refreshSummary() {
        EditablePolygon active = getActivePolygon();
        int activePointCount = active == null ? 0 : active.points.size();
        int generatedCount = active == null ? 0 : active.generatedWaypoints.size();
        double generatedDistance = active == null ? 0 : pathLengthMeters(active.generatedWaypoints);
        double speedMps = parseAndClamp(etSpeed, 6.0, MIN_SPEED_MPS, MAX_SPEED_MPS);
        double generatedEtaSec = speedMps > 0 ? generatedDistance / speedMps : 0;
        if (tvCount != null) {
            tvCount.setText(activity.getString(R.string.uxsdk_mapping_summary_vertices) + activePointCount);
        }
        if (tvArea != null) {
            double area = active == null ? 0 : approximateAreaSqMeters(active.points);
            if (area <= 0) {
                tvArea.setText(activity.getString(R.string.uxsdk_mapping_summary_area)
                        + activity.getString(R.string.uxsdk_waypoint_drawer_summary_na));
            } else {
                tvArea.setText(activity.getString(R.string.uxsdk_mapping_summary_area)
                        + String.format(Locale.US, "%.0f m²", area));
            }
        }
        if (tvPerimeter != null) {
            double perimeter = active == null ? 0 : perimeterMeters(active.points);
            if (perimeter <= 0) {
                tvPerimeter.setText(activity.getString(R.string.uxsdk_mapping_summary_perimeter)
                        + activity.getString(R.string.uxsdk_waypoint_drawer_summary_na));
            } else {
                tvPerimeter.setText(activity.getString(R.string.uxsdk_mapping_summary_perimeter)
                        + String.format(Locale.US, "%.1f m", perimeter));
            }
        }
        if (tvGeneratedCount != null) {
            tvGeneratedCount.setText(activity.getString(R.string.uxsdk_mapping_summary_generated_count) + generatedCount);
        }
        if (tvGeneratedDistance != null) {
            tvGeneratedDistance.setText(activity.getString(R.string.uxsdk_mapping_summary_generated_distance)
                    + (generatedDistance <= 0 ? activity.getString(R.string.uxsdk_waypoint_drawer_summary_na)
                    : String.format(Locale.US, "%.1f m", generatedDistance)));
        }
        if (tvGeneratedEta != null) {
            tvGeneratedEta.setText(activity.getString(R.string.uxsdk_mapping_summary_generated_eta)
                    + (generatedEtaSec <= 0 ? activity.getString(R.string.uxsdk_waypoint_drawer_summary_na)
                    : String.format(Locale.US, "%.0f s", generatedEtaSec)));
        }
        updateMissionActionState();
    }

    private void showDrawer(boolean animateFromOffscreen) {
        if (drawerShell == null || drawerSlide == null) return;
        drawerShell.setVisibility(View.VISIBLE);
        drawerSlide.post(() -> {
            float w = drawerSlide.getWidth() > 0 ? drawerSlide.getWidth()
                    : activity.getResources().getDimension(R.dimen.uxsdk_waypoint_drawer_width);
            if (animateFromOffscreen) {
                drawerSlide.setTranslationX(w);
                drawerSlide.animate().translationX(0f).setDuration(220).start();
            } else {
                drawerSlide.setTranslationX(0f);
            }
        });
    }

    private void hideDrawer() {
        if (drawerShell == null || drawerSlide == null) return;
        float w = drawerSlide.getWidth() > 0 ? drawerSlide.getWidth()
                : activity.getResources().getDimension(R.dimen.uxsdk_waypoint_drawer_width);
        drawerSlide.animate().translationX(w).setDuration(180).withEndAction(() -> {
            drawerShell.setVisibility(View.GONE);
            drawerSlide.setTranslationX(0f);
        }).start();
    }

    private void hideDrawerImmediate() {
        if (drawerShell != null) drawerShell.setVisibility(View.GONE);
        if (drawerSlide != null) drawerSlide.setTranslationX(0f);
    }

    private void attachDragListener() {
        DJIMap map = mapWidget.getMap();
        if (map == null || markerDragListener != null) return;
        markerDragListener = new DJIMap.OnMarkerDragListener() {
            @Override
            public void onMarkerDragStart(DJIMarker marker) {
                onVertexDragged(marker);
            }

            @Override
            public void onMarkerDrag(DJIMarker marker) {
                onVertexDragged(marker);
            }

            @Override
            public void onMarkerDragEnd(DJIMarker marker) {
                onVertexDragged(marker);
            }
        };
        map.setOnMarkerDragListener(markerDragListener);
        attachMarkerClickListener();
    }

    private void detachDragListener() {
        DJIMap map = mapWidget.getMap();
        if (map == null || markerDragListener == null) return;
        map.removeOnMarkerDragListener(markerDragListener);
        markerDragListener = null;
    }

    private void attachMarkerClickListener() {
        DJIMap map = mapWidget.getMap();
        if (map == null || markerClickListener != null) return;
        markerClickListener = marker -> {
            if (!planningActive || marker == null) return false;
            CircleRef circleRef = findCircleCenter(marker);
            if (circleRef != null) {
                activePolygonId = circleRef.polygonId;
                showCircleRadiusDialog(circleRef.polygonId);
                return true;
            }
            VertexRef ref = findVertex(marker);
            if (ref == null) return false;
            activePolygonId = ref.polygonId;
            selectedPolygonId = ref.polygonId;
            selectedVertexIndex = ref.vertexIndex;
            showPointActionDialog(ref.polygonId, ref.vertexIndex);
            return true;
        };
        map.setOnMarkerClickListener(markerClickListener);
    }

    private void detachMarkerClickListener() {
        DJIMap map = mapWidget.getMap();
        if (map == null || markerClickListener == null) return;
        map.removeOnMarkerClickListener(markerClickListener);
        markerClickListener = null;
    }

    private void showPointActionDialog(long polygonId, int index) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        Toast.makeText(activity, R.string.uxsdk_mapping_tap_map_to_move_point, Toast.LENGTH_SHORT).show();
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.uxsdk_mapping_point_dialog_title, index + 1))
                .setMessage(R.string.uxsdk_mapping_point_dialog_message)
                .setPositiveButton(R.string.uxsdk_mapping_delete_point, (dialog, which) -> deletePoint(polygonId, index))
                .setNegativeButton(R.string.uxsdk_mapping_cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void showCircleRadiusDialog(long polygonId) {
        EditablePolygon polygon = getPolygonById(polygonId);
        if (polygon == null || !polygon.isCircle || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format(Locale.US, "%.1f", polygon.circleRadiusMeters));
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(activity)
                .setTitle(R.string.uxsdk_mapping_circle_radius_title)
                .setMessage(R.string.uxsdk_mapping_circle_radius_message)
                .setView(input)
                .setPositiveButton(R.string.uxsdk_mapping_apply, (dialog, which) -> {
                    double parsed = parseDouble(input.getText() == null ? "" : input.getText().toString(),
                            polygon.circleRadiusMeters);
                    polygon.circleRadiusMeters = clamp(parsed, MIN_CIRCLE_RADIUS_M, MAX_CIRCLE_RADIUS_M);
                    DJIMap map = mapWidget.getMap();
                    if (map != null) {
                        regenerateCircleGeometry(polygon, map);
                    } else {
                        refreshSummary();
                    }
                })
                .setNegativeButton(R.string.uxsdk_mapping_cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void deletePoint(long polygonId, int index) {
        EditablePolygon polygon = getPolygonById(polygonId);
        if (polygon == null || index < 0 || index >= polygon.points.size()) return;
        polygon.points.remove(index);
        if (index < polygon.markers.size()) {
            DJIMarker marker = polygon.markers.remove(index);
            if (marker != null) {
                try { marker.remove(); } catch (Exception ignored) { }
            }
        }
        selectedPolygonId = -1L;
        selectedVertexIndex = -1;
        activePolygonId = polygon.id;
        updateMarkerTitles(polygon);
        DJIMap map = mapWidget.getMap();
        if (map != null) {
            redrawPolygon(polygon, map);
        } else {
            refreshSummary();
        }
        drawerLog.append("Vertex " + (index + 1) + " deleted.");
    }

    private void onVertexDragged(@Nullable DJIMarker marker) {
        if (marker == null || !planningActive) return;
        CircleRef circleRef = findCircleCenter(marker);
        if (circleRef != null) {
            EditablePolygon polygon = getPolygonById(circleRef.polygonId);
            if (polygon == null || !polygon.isCircle) return;
            DJILatLng p = marker.getPosition();
            if (p == null) return;
            polygon.circleCenter = p;
            activePolygonId = polygon.id;
            DJIMap map = mapWidget.getMap();
            if (map != null) {
                regenerateCircleGeometry(polygon, map);
            } else {
                refreshSummary();
            }
            return;
        }
        VertexRef ref = findVertex(marker);
        if (ref == null) return;
        EditablePolygon polygon = getPolygonById(ref.polygonId);
        if (polygon == null || ref.vertexIndex < 0 || ref.vertexIndex >= polygon.points.size()) return;
        DJILatLng p = marker.getPosition();
        if (p == null) return;
        polygon.points.set(ref.vertexIndex, p);
        activePolygonId = polygon.id;
        DJIMap map = mapWidget.getMap();
        if (map != null) {
            redrawPolygon(polygon, map);
        } else {
            refreshSummary();
        }
    }

    private void updateMarkerTitles(@NonNull EditablePolygon polygon) {
        for (int i = 0; i < polygon.markers.size(); i++) {
            DJIMarker marker = polygon.markers.get(i);
            if (marker != null) {
                try {
                    marker.setTitle(activity.getString(R.string.uxsdk_mapping_vertex_title, i + 1));
                } catch (Exception ignored) {
                    // Non-critical, map providers may handle title updates differently.
                }
            }
        }
    }

    @NonNull
    private DJILatLng resolveShapeCenter() {
        EditablePolygon active = getActivePolygon();
        if (active != null && !active.points.isEmpty()) {
            return active.points.get(0);
        }
        DJIMap map = mapWidget.getMap();
        if (map != null) {
            DJICameraPosition cameraPosition = map.getCameraPosition();
            if (cameraPosition != null && cameraPosition.target != null) {
                return cameraPosition.target;
            }
        }
        return new DJILatLng(24.8607, 67.0011); // Fallback if camera target is unavailable.
    }

    @Nullable
    private EditablePolygon getActivePolygon() {
        return getPolygonById(activePolygonId);
    }

    @Nullable
    private EditablePolygon getPolygonById(long polygonId) {
        if (polygonId < 0) return null;
        for (EditablePolygon polygon : polygons) {
            if (polygon.id == polygonId) return polygon;
        }
        return null;
    }

    @NonNull
    private EditablePolygon getOrCreateActivePolygon() {
        EditablePolygon active = getActivePolygon();
        if (active != null) return active;
        EditablePolygon created = new EditablePolygon(nextPolygonId++);
        polygons.add(created);
        activePolygonId = created.id;
        return created;
    }

    private void removePolygon(@NonNull EditablePolygon polygon) {
        for (DJIMarker mk : new ArrayList<>(polygon.markers)) {
            if (mk != null) {
                try { mk.remove(); } catch (Exception ignored) { }
            }
        }
        polygon.markers.clear();
        polygon.points.clear();
        if (polygon.circleCenterMarker != null) {
            try { polygon.circleCenterMarker.remove(); } catch (Exception ignored) { }
            polygon.circleCenterMarker = null;
        }
        if (polygon.polygon != null) {
            try { polygon.polygon.remove(); } catch (Exception ignored) { }
            polygon.polygon = null;
        }
        clearGeneratedOverlays(polygon);
    }

    @Nullable
    private DJIMarker addVertexMarker(@NonNull DJIMap map, @NonNull DJILatLng latLng, int markerIndex) {
        DJIMarkerOptions options = new DJIMarkerOptions()
                .position(latLng)
                .title(activity.getString(R.string.uxsdk_mapping_vertex_title, markerIndex))
                .draggable(true)
                .zIndex(4)
                .setInfoWindowEnable(false);
        DJIBitmapDescriptor icon = getWaypointMarkerIcon();
        if (icon != null) {
            options.icon(icon);
        }
        return map.addMarker(options);
    }

    @Nullable
    private DJIMarker addCenterMarker(@NonNull DJIMap map, @NonNull DJILatLng latLng) {
        DJIMarkerOptions options = new DJIMarkerOptions()
                .position(latLng)
                .title(activity.getString(R.string.uxsdk_mapping_circle_center_title))
                .draggable(true)
                .zIndex(5)
                .setInfoWindowEnable(false);
        DJIBitmapDescriptor icon = getWaypointMarkerIcon();
        if (icon != null) {
            options.icon(icon);
        }
        return map.addMarker(options);
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
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private VertexRef findVertex(@NonNull DJIMarker marker) {
        for (EditablePolygon polygon : polygons) {
            int idx = polygon.markers.indexOf(marker);
            if (idx >= 0) {
                return new VertexRef(polygon.id, idx);
            }
        }
        return null;
    }

    @Nullable
    private CircleRef findCircleCenter(@NonNull DJIMarker marker) {
        for (EditablePolygon polygon : polygons) {
            if (polygon.isCircle && polygon.circleCenterMarker == marker) {
                return new CircleRef(polygon.id);
            }
        }
        return null;
    }

    private static final class VertexRef {
        private final long polygonId;
        private final int vertexIndex;

        private VertexRef(long polygonId, int vertexIndex) {
            this.polygonId = polygonId;
            this.vertexIndex = vertexIndex;
        }
    }

    private static final class CircleRef {
        private final long polygonId;

        private CircleRef(long polygonId) {
            this.polygonId = polygonId;
        }
    }

    private static final class XY {
        private final double x;
        private final double y;

        private XY(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private void generateWaypointsForActivePolygon() {
        EditablePolygon active = getActivePolygon();
        DJIMap map = mapWidget.getMap();
        if (active == null || map == null || active.points.size() < 3) {
            Toast.makeText(activity, R.string.uxsdk_mapping_need_polygon_first, Toast.LENGTH_SHORT).show();
            drawerLog.append("Generate skipped — need a polygon with at least 3 vertices.");
            return;
        }

        double altitude = parseAndClamp(etAltitude, 70.0, MIN_ALTITUDE_M, MAX_ALTITUDE_M);
        double speed = parseAndClamp(etSpeed, 6.0, MIN_SPEED_MPS, MAX_SPEED_MPS);
        parseAndClamp(etGimbalPitch, -90.0, MIN_GIMBAL_PITCH, MAX_GIMBAL_PITCH); // global config retained in UI

        double spacing = parseAndClamp(etSpacing, 35.0, MIN_MAPPING_SPACING_M, MAX_MAPPING_SPACING_M);
        List<DJILatLng> generated = buildSerpentineWaypoints(active.points, altitude, spacing);
        if (generated.isEmpty()) {
            Toast.makeText(activity, R.string.uxsdk_mapping_need_polygon_first, Toast.LENGTH_SHORT).show();
            return;
        }

        if (generated.size() > MAX_GENERATED_WAYPOINTS) {
            generated = new ArrayList<>(generated.subList(0, MAX_GENERATED_WAYPOINTS));
            Toast.makeText(activity,
                    activity.getString(R.string.uxsdk_mapping_limit_waypoints, MAX_GENERATED_WAYPOINTS),
                    Toast.LENGTH_SHORT).show();
        }

        double routeDistance = pathLengthMeters(generated);
        if (routeDistance > MAX_ROUTE_DISTANCE_M) {
            Toast.makeText(activity,
                    activity.getString(R.string.uxsdk_mapping_limit_distance, MAX_ROUTE_DISTANCE_M),
                    Toast.LENGTH_LONG).show();
        }
        double etaSec = routeDistance / speed;
        if (etaSec > MAX_FLIGHT_TIME_S) {
            Toast.makeText(activity, R.string.uxsdk_mapping_limit_battery, Toast.LENGTH_LONG).show();
        }

        clearGeneratedOverlays(active);
        active.generatedWaypoints.addAll(generated);
        for (int i = 0; i < generated.size(); i++) {
            DJIMarker marker = addGeneratedWaypointMarker(map, generated.get(i), i + 1);
            if (marker != null) {
                active.generatedMarkers.add(marker);
            }
        }
        if (generated.size() >= 2) {
            DJIPolylineOptions lineOptions = new DJIPolylineOptions()
                    .addAll(generated)
                    .width(3f)
                    .color(Color.argb(235, 255, 214, 10))
                    .zIndex(3f);
            active.generatedRouteLine = map.addPolyline(lineOptions);
        }
        refreshSummary();
        drawerLog.append(String.format(Locale.US,
                "Generated %d survey waypoints (spacing %.0f m, route %.0f m).",
                generated.size(), spacing, routeDistance));
    }

    @NonNull
    private List<DJILatLng> buildSerpentineWaypoints(@NonNull List<DJILatLng> polygon, double altitude, double spacingMeters) {
        if (polygon.size() < 3) return Collections.emptyList();

        DJILatLng origin = polygon.get(0);
        double lat0 = Math.toRadians(origin.getLatitude());
        double lon0 = Math.toRadians(origin.getLongitude());
        double r = 6378137.0;

        List<XY> polyXY = new ArrayList<>();
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (DJILatLng p : polygon) {
            XY xy = toXY(p, lat0, lon0, r);
            polyXY.add(xy);
            minY = Math.min(minY, xy.y);
            maxY = Math.max(maxY, xy.y);
        }

        // User-configurable spacing with altitude-based fallback for sane defaults.
        double autoSpacing = Math.max(MIN_MAPPING_SPACING_M, Math.min(MAX_MAPPING_SPACING_M, altitude * 0.9));
        double lineSpacing = Math.max(MIN_MAPPING_SPACING_M, Math.min(MAX_MAPPING_SPACING_M,
                spacingMeters > 0 ? spacingMeters : autoSpacing));
        List<XY> out = new ArrayList<>();
        boolean reverse = false;
        for (double y = minY + (lineSpacing * 0.5); y <= maxY; y += lineSpacing) {
            List<Double> intersections = new ArrayList<>();
            for (int i = 0; i < polyXY.size(); i++) {
                XY a = polyXY.get(i);
                XY b = polyXY.get((i + 1) % polyXY.size());
                if ((a.y <= y && b.y > y) || (b.y <= y && a.y > y)) {
                    double t = (y - a.y) / (b.y - a.y);
                    intersections.add(a.x + t * (b.x - a.x));
                }
            }
            if (intersections.size() < 2) continue;
            Collections.sort(intersections);
            List<XY> row = new ArrayList<>();
            for (int i = 0; i + 1 < intersections.size(); i += 2) {
                double x1 = intersections.get(i);
                double x2 = intersections.get(i + 1);
                for (double x = x1; x <= x2; x += lineSpacing) {
                    row.add(new XY(x, y));
                }
                row.add(new XY(x2, y));
            }
            if (reverse) {
                Collections.reverse(row);
            }
            out.addAll(row);
            reverse = !reverse;
        }

        List<DJILatLng> result = new ArrayList<>();
        for (XY p : out) {
            result.add(fromXY(p, lat0, lon0, r));
        }
        return result;
    }

    private XY toXY(@NonNull DJILatLng p, double lat0, double lon0, double r) {
        double x = (Math.toRadians(p.getLongitude()) - lon0) * Math.cos(lat0) * r;
        double y = (Math.toRadians(p.getLatitude()) - lat0) * r;
        return new XY(x, y);
    }

    private DJILatLng fromXY(@NonNull XY p, double lat0, double lon0, double r) {
        double lat = Math.toDegrees((p.y / r) + lat0);
        double lon = Math.toDegrees((p.x / (Math.cos(lat0) * r)) + lon0);
        return new DJILatLng(lat, lon);
    }

    @Nullable
    private DJIMarker addGeneratedWaypointMarker(@NonNull DJIMap map, @NonNull DJILatLng latLng, int index) {
        DJIMarkerOptions options = new DJIMarkerOptions()
                .position(latLng)
                .title(activity.getString(R.string.uxsdk_waypoint_marker_title, index))
                .zIndex(3)
                .setInfoWindowEnable(false);
        DJIBitmapDescriptor icon = getGeneratedWaypointMarkerIcon();
        if (icon != null) {
            options.icon(icon);
        }
        return map.addMarker(options);
    }

    @Nullable
    private DJIBitmapDescriptor getGeneratedWaypointMarkerIcon() {
        if (generatedWaypointMarkerIcon != null) {
            return generatedWaypointMarkerIcon;
        }
        try {
            Drawable d = ContextCompat.getDrawable(activity, R.drawable.uxsdk_ic_mapping_generated_waypoint_marker);
            if (d == null) {
                return null;
            }
            generatedWaypointMarkerIcon = DJIBitmapDescriptorFactory.fromBitmap(ViewUtil.getBitmapFromVectorDrawable(d));
            return generatedWaypointMarkerIcon;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void clearGeneratedOverlays(@NonNull EditablePolygon polygon) {
        for (DJIMarker marker : new ArrayList<>(polygon.generatedMarkers)) {
            if (marker != null) {
                try { marker.remove(); } catch (Exception ignored) { }
            }
        }
        polygon.generatedMarkers.clear();
        polygon.generatedWaypoints.clear();
        if (polygon.generatedRouteLine != null) {
            try { polygon.generatedRouteLine.remove(); } catch (Exception ignored) { }
            polygon.generatedRouteLine = null;
        }
        markPlanDirty();
    }

    private void uploadMissionForActivePolygon() {
        if (uploadInProgress || startInProgress) {
            return;
        }
        EditablePolygon active = getActivePolygon();
        if (active == null || active.generatedWaypoints.isEmpty()) {
            Toast.makeText(activity, R.string.uxsdk_mapping_need_polygon_first, Toast.LENGTH_SHORT).show();
            drawerLog.append("Upload skipped — generate waypoints first.");
            return;
        }
        saveGeneratedKmzToCache(active, true);
        if (lastKmzPath == null) {
            drawerLog.append("Upload skipped — KMZ not saved.");
            return;
        }

        drawerLog.append("Uploading KMZ (" + active.generatedWaypoints.size() + " waypoints)…");
        uploadInProgress = true;
        lastUploadSucceeded = false;
        updateMissionActionState();
        if (uploadProgress != null) {
            uploadProgress.setVisibility(View.VISIBLE);
            uploadProgress.setProgress(0);
        }
        WaypointMissionManager.getInstance().pushKMZFileToAircraft(lastKmzPath,
                new CommonCallbacks.CompletionCallbackWithProgress<Double>() {
                    @Override
                    public void onProgressUpdate(Double progress) {
                        activity.runOnUiThread(() -> {
                            if (uploadProgress == null || progress == null) {
                                return;
                            }
                            uploadProgress.setVisibility(View.VISIBLE);
                            uploadProgress.setProgress(clampIndex((int) (progress * 100.0), 0, 100));
                        });
                    }

                    @Override
                    public void onSuccess() {
                        activity.runOnUiThread(() -> {
                            uploadInProgress = false;
                            planDirtySinceUpload = false;
                            lastUploadSucceeded = true;
                            if (uploadProgress != null) {
                                uploadProgress.setProgress(100);
                            }
                            updateMissionActionState();
                            drawerLog.append("Upload complete — ready to start.");
                            Toast.makeText(activity, R.string.uxsdk_mapping_upload_ok, Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onFailure(@NonNull IDJIError error) {
                        activity.runOnUiThread(() -> {
                            uploadInProgress = false;
                            lastUploadSucceeded = false;
                            updateUploadUiIdle();
                            updateMissionActionState();
                            String msg = error != null ? error.description() : "?";
                            drawerLog.append("Upload failed: " + msg);
                            Toast.makeText(activity,
                                    activity.getString(R.string.uxsdk_mapping_upload_fail, msg),
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private void startLastUploadedMission() {
        if (uploadInProgress || startInProgress) {
            return;
        }
        if (planDirtySinceUpload || !lastUploadSucceeded) {
            Toast.makeText(activity, R.string.uxsdk_mapping_save_first, Toast.LENGTH_SHORT).show();
            drawerLog.append("Start skipped — upload mission first.");
            return;
        }
        if (lastKmzPath == null) {
            Toast.makeText(activity, R.string.uxsdk_mapping_save_first, Toast.LENGTH_SHORT).show();
            drawerLog.append("Start skipped — KMZ not available.");
            return;
        }
        String missionId = missionIdFromPath(lastKmzPath);
        drawerLog.append("Starting mission " + missionId + "…");
        startInProgress = true;
        updateMissionActionState();
        WaypointMissionManager.getInstance().startMission(missionId, Collections.singletonList(0),
                new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onSuccess() {
                        activity.runOnUiThread(() -> {
                            startInProgress = false;
                            updateMissionActionState();
                            drawerLog.append("Mission started.");
                            Toast.makeText(activity, R.string.uxsdk_mapping_start_ok, Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onFailure(@NonNull IDJIError error) {
                        activity.runOnUiThread(() -> {
                            startInProgress = false;
                            updateMissionActionState();
                            String msg = error != null ? error.description() : "?";
                            drawerLog.append("Start failed: " + msg);
                            Toast.makeText(activity,
                                    activity.getString(R.string.uxsdk_mapping_start_fail, msg),
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private void saveGeneratedKmzToCache(@NonNull EditablePolygon polygon, boolean quiet) {
        if (polygon.generatedWaypoints.isEmpty()) {
            Toast.makeText(activity, R.string.uxsdk_mapping_need_polygon_first, Toast.LENGTH_SHORT).show();
            return;
        }
        double altitude = parseAndClamp(etAltitude, 70.0, MIN_ALTITUDE_M, MAX_ALTITUDE_M);
        double speed = parseAndClamp(etSpeed, 6.0, MIN_SPEED_MPS, MAX_SPEED_MPS);
        double gimbalPitch = parseAndClamp(etGimbalPitch, -90.0, MIN_GIMBAL_PITCH, MAX_GIMBAL_PITCH);

        try {
            List<WaypointPlanItem> planItems = buildMissionPlanItems(polygon.generatedWaypoints, altitude, speed, gimbalPitch);
            File out = new File(activity.getCacheDir(), "uxsdk_mapping_waypoints.kmz");
            WPMZManager.getInstance().generateKMZFile(
                    out.getAbsolutePath(),
                    WaypointKmzUtil.createWaylineMission(),
                    WaypointKmzUtil.createMissionConfig(missionGlobals),
                    WaypointKmzUtil.createTemplate(planItems, missionGlobals));
            lastKmzPath = out.getAbsolutePath();
            if (!quiet) {
                Toast.makeText(activity, lastKmzPath, Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            lastKmzPath = null;
            String msg = (e.getMessage() == null || e.getMessage().trim().isEmpty()) ? "unknown" : e.getMessage();
            Toast.makeText(activity, activity.getString(R.string.uxsdk_mapping_kmz_save_fail, msg),
                    Toast.LENGTH_LONG).show();
        }
    }

    @NonNull
    private List<WaypointPlanItem> buildMissionPlanItems(@NonNull List<DJILatLng> generated,
                                                         double altitude,
                                                         double speed,
                                                         double gimbalPitch) {
        List<WaypointPlanItem> out = new ArrayList<>();
        for (int i = 0; i < generated.size(); i++) {
            DJILatLng latLng = generated.get(i);
            WaylineWaypoint waypoint = new WaylineWaypoint();
            waypoint.setWaypointIndex(i);
            waypoint.setUseGlobalTurnParam(true);
            waypoint.setUseGlobalYawParam(false);
            waypoint.setLocation(new WaylineLocationCoordinate2D(latLng.getLatitude(), latLng.getLongitude()));
            waypoint.setHeight(altitude);
            waypoint.setEllipsoidHeight(altitude);
            waypoint.setSpeed(speed);
            waypoint.setGimbalPitchAngle(gimbalPitch);

            WaylineWaypointYawParam yawParam = new WaylineWaypointYawParam();
            yawParam.setEnableYawAngle(false);
            yawParam.setYawAngle(0d);
            yawParam.setYawMode(WaylineWaypointYawMode.FOLLOW_WAYLINE);
            yawParam.setYawPathMode(WaylineWaypointYawPathMode.FOLLOW_BAD_ARC);
            yawParam.setPoiLocation(new WaylineLocationCoordinate3D(
                    latLng.getLatitude(), latLng.getLongitude(), altitude));
            waypoint.setYawParam(yawParam);

            WaylineWaypointGimbalHeadingParam gh = new WaylineWaypointGimbalHeadingParam();
            gh.setHeadingMode(WaylineWaypointGimbalHeadingMode.FOLLOW_WAYLINE);
            gh.setPitchAngle(gimbalPitch);
            waypoint.setGimbalHeadingParam(gh);

            WaypointPlanItem item = new WaypointPlanItem();
            item.setWaylineWaypoint(waypoint);
            item.setActionInfos(new ArrayList<>());
            item.setTurnMode(missionGlobals.globalTurnMode);
            out.add(item);
        }
        return out;
    }

    private void markPlanDirty() {
        planDirtySinceUpload = true;
        lastUploadSucceeded = false;
        updateMissionActionState();
    }

    private void updateMissionActionState() {
        EditablePolygon active = getActivePolygon();
        boolean hasGenerated = active != null && !active.generatedWaypoints.isEmpty();
        if (btnUpload != null) {
            btnUpload.setEnabled(planningActive && hasGenerated && !uploadInProgress && !startInProgress);
        }
        if (btnStart != null) {
            btnStart.setEnabled(planningActive && hasGenerated && !planDirtySinceUpload
                    && lastUploadSucceeded && !uploadInProgress && !startInProgress);
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

    private static int clampIndex(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static <T extends Enum<T>> List<T> filterUnknown(@NonNull T[] values) {
        List<T> out = new ArrayList<>();
        for (T value : values) {
            if (value != null && !"UNKNOWN".equals(value.name())) {
                out.add(value);
            }
        }
        return out;
    }

    @NonNull
    private static List<String> toNames(@NonNull List<? extends Enum<?>> enums) {
        List<String> names = new ArrayList<>();
        for (Enum<?> e : enums) {
            names.add(e.name());
        }
        return names;
    }

    @NonNull
    private static List<String> toDisplayNames(@NonNull List<? extends Enum<?>> enums) {
        List<String> names = new ArrayList<>();
        for (Enum<?> e : enums) {
            names.add(humanizeEnumName(e.name()));
        }
        return names;
    }

    @NonNull
    private static String humanizeEnumName(@NonNull String enumName) {
        String[] parts = enumName.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            String lower = part.toLowerCase(Locale.US);
            sb.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
        }
        return sb.length() == 0 ? enumName : sb.toString();
    }

    private void updateUploadUiIdle() {
        if (uploadProgress != null) {
            uploadProgress.setVisibility(View.GONE);
            uploadProgress.setProgress(0);
        }
    }

    private void maybeShowFirstTimeTutorial() {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_MAPPING_TUTORIAL, AppCompatActivity.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_MAPPING_TUTORIAL_SHOWN, false)) {
                return;
            }
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            prefs.edit().putBoolean(KEY_MAPPING_TUTORIAL_SHOWN, true).apply();
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.uxsdk_mapping_tutorial_title)
                    .setMessage(R.string.uxsdk_mapping_tutorial_message)
                    .setPositiveButton(R.string.uxsdk_app_ok, null)
                    .show();
        } catch (Exception ignored) {
            // Do not block mission flow if tutorial state cannot be read/written.
        }
    }

    private void regenerateCircleGeometry(@NonNull EditablePolygon polygon, @NonNull DJIMap map) {
        if (!polygon.isCircle || polygon.circleCenter == null) return;
        List<DJILatLng> newPoints = buildCirclePoints(polygon.circleCenter, polygon.circleRadiusMeters, CIRCLE_SEGMENTS);
        polygon.points.clear();
        polygon.points.addAll(newPoints);

        if (polygon.markers.size() != newPoints.size()) {
            for (DJIMarker marker : new ArrayList<>(polygon.markers)) {
                if (marker != null) {
                    try { marker.remove(); } catch (Exception ignored) { }
                }
            }
            polygon.markers.clear();
            for (int i = 0; i < newPoints.size(); i++) {
                DJIMarker mk = addVertexMarker(map, newPoints.get(i), i + 1);
                if (mk != null) polygon.markers.add(mk);
            }
        } else {
            for (int i = 0; i < newPoints.size(); i++) {
                DJIMarker marker = polygon.markers.get(i);
                if (marker != null) {
                    try { marker.setPosition(newPoints.get(i)); } catch (Exception ignored) { }
                }
            }
        }

        if (polygon.circleCenterMarker != null) {
            try { polygon.circleCenterMarker.setPosition(polygon.circleCenter); } catch (Exception ignored) { }
        }
        redrawPolygon(polygon, map);
    }

    @NonNull
    private static List<DJILatLng> buildCirclePoints(@NonNull DJILatLng center, double radiusMeters, int segments) {
        List<DJILatLng> out = new ArrayList<>();
        for (int i = 0; i < segments; i++) {
            double a = (Math.PI * 2.0 * i) / segments;
            out.add(offsetMeters(center, radiusMeters * Math.cos(a), radiusMeters * Math.sin(a)));
        }
        return out;
    }

    private double parseAndClamp(@Nullable EditText et, double def, double min, double max) {
        String raw = (et == null || et.getText() == null) ? "" : et.getText().toString();
        double parsed = parseDouble(raw, def);
        double clamped = clamp(parsed, min, max);
        if (et != null) {
            et.setText(String.format(Locale.US, "%.1f", clamped));
        }
        return clamped;
    }

    private static double parseDouble(@Nullable String s, double def) {
        try {
            if (s == null || s.trim().isEmpty()) {
                return def;
            }
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static List<DJILatLng> buildRectPoints(DJILatLng center, double halfEastMeters, double halfNorthMeters) {
        List<DJILatLng> out = new ArrayList<>();
        out.add(offsetMeters(center, -halfEastMeters, -halfNorthMeters));
        out.add(offsetMeters(center, halfEastMeters, -halfNorthMeters));
        out.add(offsetMeters(center, halfEastMeters, halfNorthMeters));
        out.add(offsetMeters(center, -halfEastMeters, halfNorthMeters));
        return out;
    }

    private static DJILatLng offsetMeters(DJILatLng origin, double eastMeters, double northMeters) {
        double dLat = northMeters / 111320.0;
        double dLon = eastMeters / (111320.0 * Math.cos(Math.toRadians(origin.getLatitude())));
        return new DJILatLng(origin.getLatitude() + dLat, origin.getLongitude() + dLon);
    }

    private static double approximateAreaSqMeters(List<DJILatLng> pts) {
        if (pts == null || pts.size() < 3) return 0;
        // Equirectangular projection around first point, then shoelace.
        double lat0 = Math.toRadians(pts.get(0).getLatitude());
        double lon0 = Math.toRadians(pts.get(0).getLongitude());
        double r = 6378137.0;
        double area2 = 0;
        for (int i = 0; i < pts.size(); i++) {
            DJILatLng a = pts.get(i);
            DJILatLng b = pts.get((i + 1) % pts.size());
            double ax = (Math.toRadians(a.getLongitude()) - lon0) * Math.cos(lat0) * r;
            double ay = (Math.toRadians(a.getLatitude()) - lat0) * r;
            double bx = (Math.toRadians(b.getLongitude()) - lon0) * Math.cos(lat0) * r;
            double by = (Math.toRadians(b.getLatitude()) - lat0) * r;
            area2 += (ax * by - bx * ay);
        }
        return Math.abs(area2) * 0.5;
    }

    private static double perimeterMeters(List<DJILatLng> pts) {
        if (pts == null || pts.size() < 3) return 0;
        double sum = 0;
        for (int i = 0; i < pts.size(); i++) {
            DJILatLng a = pts.get(i);
            DJILatLng b = pts.get((i + 1) % pts.size());
            sum += WaypointMissionMath.haversineMeters(
                    a.getLatitude(), a.getLongitude(),
                    b.getLatitude(), b.getLongitude());
        }
        return sum;
    }

    private static double pathLengthMeters(@NonNull List<DJILatLng> points) {
        if (points.size() < 2) return 0;
        double sum = 0;
        for (int i = 1; i < points.size(); i++) {
            DJILatLng a = points.get(i - 1);
            DJILatLng b = points.get(i);
            sum += WaypointMissionMath.haversineMeters(
                    a.getLatitude(), a.getLongitude(),
                    b.getLatitude(), b.getLongitude());
        }
        return sum;
    }
}
