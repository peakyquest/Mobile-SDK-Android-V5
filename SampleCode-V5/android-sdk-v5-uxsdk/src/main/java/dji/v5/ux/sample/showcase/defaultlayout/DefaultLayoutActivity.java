/*
 * Copyright (c) 2018-2020 DJI
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package dji.v5.ux.sample.showcase.defaultlayout;

import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import org.jetbrains.annotations.NotNull;

import dji.sdk.keyvalue.value.common.CameraLensType;
import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.v5.manager.aircraft.flysafe.info.FlyZoneCategory;
import dji.v5.manager.datacenter.MediaDataCenter;
import dji.v5.manager.interfaces.ICameraStreamManager;
import dji.v5.network.DJINetworkManager;
import dji.v5.network.IDJINetworkStatusListener;
import dji.v5.utils.common.AndUtil;
import dji.v5.utils.common.JsonUtil;
import dji.v5.utils.common.LogPath;
import dji.v5.utils.common.LogUtils;
import dji.v5.ux.R;
import dji.v5.ux.accessory.RTKStartServiceHelper;
import dji.v5.ux.cameracore.widget.autoexposurelock.AutoExposureLockWidget;
import dji.v5.ux.cameracore.widget.cameracontrols.CameraControlsWidget;
import dji.v5.ux.cameracore.widget.cameracontrols.lenscontrol.LensControlWidget;
import dji.v5.ux.cameracore.widget.focusexposureswitch.FocusExposureSwitchWidget;
import dji.v5.ux.cameracore.widget.focusmode.FocusModeWidget;
import dji.v5.ux.cameracore.widget.fpvinteraction.FPVInteractionWidget;
import dji.v5.ux.core.base.SchedulerProvider;
import dji.v5.ux.core.communication.BroadcastValues;
import dji.v5.ux.core.communication.GlobalPreferenceKeys;
import dji.v5.ux.core.communication.ObservableInMemoryKeyedStore;
import dji.v5.ux.core.communication.UXKeys;
import dji.v5.ux.core.extension.ViewExtensions;
import dji.v5.ux.core.panel.systemstatus.SystemStatusListPanelWidget;
import dji.v5.ux.core.panel.topbar.TopBarPanelWidget;
import dji.v5.ux.core.util.CameraUtil;
import dji.v5.ux.core.util.DataProcessor;
import dji.v5.ux.core.util.ViewUtil;
import dji.v5.ux.core.widget.fpv.FPVWidget;
import dji.v5.ux.core.widget.hsi.HorizontalSituationIndicatorWidget;
import dji.v5.ux.core.widget.hsi.PrimaryFlightDisplayWidget;
import dji.v5.ux.core.widget.setting.SettingWidget;
import dji.v5.ux.core.widget.simulator.SimulatorIndicatorWidget;
import dji.v5.ux.core.widget.systemstatus.SystemStatusWidget;
import dji.v5.ux.gimbal.GimbalFineTuneWidget;
import dji.v5.ux.map.MapWidget;
import dji.v5.ux.mapkit.core.maps.DJIMap;
import dji.v5.ux.mapkit.core.maps.DJIUiSettings;
import dji.v5.ux.mapkit.core.models.DJILatLng;
import dji.v5.ux.sample.showcase.waypoint.MappingPlanner;
import dji.v5.ux.sample.showcase.waypoint.WaypointPlanner;
import dji.v5.ux.mapkit.maplibre.map.MaplibreMapDelegate;
import dji.v5.ux.mapkit.maplibre.map.MaplibreMapDelegateKt;
import dji.v5.ux.mapkit.maplibre.map.MaplibreStyle;
import dji.v5.ux.mapkit.maplibre.provider.MaplibreProvider;
import dji.v5.ux.training.simulatorcontrol.SimulatorControlWidget;
import dji.v5.ux.visualcamera.CameraNDVIPanelWidget;
import dji.v5.ux.visualcamera.CameraVisiblePanelWidget;
import dji.v5.ux.visualcamera.zoom.FocalZoomWidget;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

/**
 * Displays a sample layout of widgets similar to that of the various DJI apps.
 */
public class DefaultLayoutActivity extends AppCompatActivity {

    //region Fields
    private final String TAG = LogUtils.getTag(this);

    protected FPVWidget primaryFpvWidget;
    protected FPVInteractionWidget fpvInteractionWidget;
    protected FPVWidget secondaryFPVWidget;
    protected SystemStatusListPanelWidget systemStatusListPanelWidget;
    protected SimulatorControlWidget simulatorControlWidget;
    protected LensControlWidget lensControlWidget;
    protected AutoExposureLockWidget autoExposureLockWidget;
    protected FocusModeWidget focusModeWidget;
    protected FocusExposureSwitchWidget focusExposureSwitchWidget;
    protected CameraControlsWidget cameraControlsWidget;
    protected HorizontalSituationIndicatorWidget horizontalSituationIndicatorWidget;
    protected PrimaryFlightDisplayWidget pfvFlightDisplayWidget;
    protected CameraNDVIPanelWidget ndviCameraPanel;
    protected CameraVisiblePanelWidget visualCameraPanel;
    protected FocalZoomWidget focalZoomWidget;
    protected SettingWidget settingWidget;
    protected MapWidget mapWidget;
    protected TopBarPanelWidget topBarPanel;
    protected View takeOffWidget;
    protected View returnHomeWidget;
    protected View remainingFlightTimeWidget;
    protected LinearLayout mapControlsContainer;
    protected ImageView btnMapType;
    protected ImageView btnFlyZones;
    protected ImageView btnMission;
    /** Waypoint planning on the map (KMZ upload / start uses {@link dji.v5.manager.aircraft.waypoint3.WaypointMissionManager}). */
    private WaypointPlanner waypointPlanner;
    private View waypointMissionDrawerShell;
    private MappingPlanner mappingPlanner;
    private View mappingDrawerShell;
    protected ConstraintLayout fpvParentView;
    private DrawerLayout mDrawerLayout;
    private TextView gimbalAdjustDone;
    private GimbalFineTuneWidget gimbalFineTuneWidget;
    private ComponentIndexType lastDevicePosition = ComponentIndexType.UNKNOWN;
    private CameraLensType lastLensType = CameraLensType.UNKNOWN;

    // ── Swap state ────────────────────────────────────────────────────────────
    private static final long SWAP_DEBOUNCE_MS = 250L;
    private ViewMode currentViewMode = ViewMode.FPV_FULL;
    private long lastSwapRequestUptimeMs = 0L;
    private ConstraintLayout.LayoutParams mapMiniLayoutParams;
    private ConstraintLayout.LayoutParams fpvFullLayoutParams;
    // ─────────────────────────────────────────────────────────────────────────

    private CompositeDisposable compositeDisposable;
    private final DataProcessor<CameraSource> cameraSourceProcessor = DataProcessor.create(
            new CameraSource(ComponentIndexType.UNKNOWN, CameraLensType.UNKNOWN));

    private final IDJINetworkStatusListener networkStatusListener = isNetworkAvailable -> {
        if (isNetworkAvailable) {
            LogUtils.d(TAG, "isNetworkAvailable=" + true);
            RTKStartServiceHelper.INSTANCE.startRtkService(false);
        }
    };

    private List<ComponentIndexType> lastAvailableCameraList = new ArrayList<>();

    private final ICameraStreamManager.AvailableCameraUpdatedListener availableCameraUpdatedListener =
            new ICameraStreamManager.AvailableCameraUpdatedListener() {
                @Override
                public void onAvailableCameraUpdated(@NonNull List<ComponentIndexType> availableCameraList) {
                    lastAvailableCameraList = availableCameraList;
                    runOnUiThread(() -> updateFPVWidgetSource(availableCameraList));
                }

                @Override
                public void onCameraStreamEnableUpdate(
                        @NonNull Map<ComponentIndexType, Boolean> cameraStreamEnableMap) { }
            };

    private enum ViewMode {
        FPV_FULL,
        MAP_FULL,
        TRANSITIONING
    }
    //endregion

    //region Lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.uxsdk_activity_default_layout);

        fpvParentView = findViewById(R.id.fpv_holder);
        mDrawerLayout = findViewById(R.id.root_view);
        waypointMissionDrawerShell = findViewById(R.id.uxsdk_waypoint_drawer_shell);
        mappingDrawerShell = findViewById(R.id.uxsdk_mapping_drawer_shell);
        topBarPanel = findViewById(R.id.panel_top_bar);
        settingWidget = topBarPanel.getSettingWidget();
        primaryFpvWidget = findViewById(R.id.widget_primary_fpv);
        fpvInteractionWidget = findViewById(R.id.widget_fpv_interaction);
        secondaryFPVWidget = findViewById(R.id.widget_secondary_fpv);
        systemStatusListPanelWidget = findViewById(R.id.widget_panel_system_status_list);
        simulatorControlWidget = findViewById(R.id.widget_simulator_control);
        lensControlWidget = findViewById(R.id.widget_lens_control);
        ndviCameraPanel = findViewById(R.id.panel_ndvi_camera);
        visualCameraPanel = findViewById(R.id.panel_visual_camera);
        autoExposureLockWidget = findViewById(R.id.widget_auto_exposure_lock);
        focusModeWidget = findViewById(R.id.widget_focus_mode);
        focusExposureSwitchWidget = findViewById(R.id.widget_focus_exposure_switch);
        pfvFlightDisplayWidget = findViewById(R.id.widget_fpv_flight_display_widget);
        focalZoomWidget = findViewById(R.id.widget_focal_zoom);
        cameraControlsWidget = findViewById(R.id.widget_camera_controls);
        horizontalSituationIndicatorWidget = findViewById(R.id.widget_horizontal_situation_indicator);
        gimbalAdjustDone = findViewById(R.id.fpv_gimbal_ok_btn);
        gimbalFineTuneWidget = findViewById(R.id.setting_menu_gimbal_fine_tune);
        takeOffWidget = findViewById(R.id.widget_take_off);
        returnHomeWidget = findViewById(R.id.widget_return_to_home);
        remainingFlightTimeWidget = findViewById(R.id.widget_remaining_flight_time);
        mapControlsContainer = findViewById(R.id.map_controls_container);
        btnMapType = findViewById(R.id.btn_map_type);
        btnFlyZones = findViewById(R.id.btn_fly_zones);
        btnMission = findViewById(R.id.btn_mission);
        mapWidget = findViewById(R.id.widget_map);
        mapMiniLayoutParams = new ConstraintLayout.LayoutParams(
                (ConstraintLayout.LayoutParams) mapWidget.getLayoutParams());
        fpvFullLayoutParams = new ConstraintLayout.LayoutParams(
                (ConstraintLayout.LayoutParams) fpvParentView.getLayoutParams());

        initClickListener();

        MediaDataCenter.getInstance().getCameraStreamManager()
                .addAvailableCameraUpdatedListener(availableCameraUpdatedListener);

        primaryFpvWidget.setOnFPVStreamSourceListener(
                (devicePosition, lensType) ->
                        cameraSourceProcessor.onNext(new CameraSource(devicePosition, lensType)));

        // Small surface view on top so it is never obscured by the larger one
        secondaryFPVWidget.setSurfaceViewZOrderOnTop(true);
        secondaryFPVWidget.setSurfaceViewZOrderMediaOverlay(true);

        mapWidget.initMapLibreMap(getApplicationContext(), map -> {
            DJIUiSettings uiSetting = map.getUiSettings();
            if (uiSetting != null) {
                uiSetting.setZoomControlsEnabled(false);
            }
            // Map tap: expand mini-map first; waypoint taps only when map is already full-screen.
            map.setOnMapClickListener(this::handleMapWidgetMapClick);
        });
        mapWidget.onCreate(savedInstanceState);

        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        DJINetworkManager.getInstance().addNetworkStatusListener(networkStatusListener);
    }

    private void isGimableAdjustClicked(BroadcastValues broadcastValues) {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.END)) {
            mDrawerLayout.closeDrawers();
        }
        horizontalSituationIndicatorWidget.setVisibility(View.GONE);
        if (gimbalFineTuneWidget != null) {
            gimbalFineTuneWidget.setVisibility(View.VISIBLE);
        }
    }

    private void initClickListener() {
        // ── Swap: mini FPV click → revert to FPV-big layout ──────────────────
        fpvParentView.setOnClickListener(v -> requestViewMode(ViewMode.FPV_FULL));
        // ──────────────────────────────────────────────────────────────────────

        primaryFpvWidget.setOnClickListener(v -> {
            if (isMapExpanded()) {
                requestViewMode(ViewMode.FPV_FULL);
            }
        });

        secondaryFPVWidget.setOnClickListener(v -> {
            if (isFpvExpanded()) {
                swapVideoSource();
            } else {
                requestViewMode(ViewMode.FPV_FULL);
            }
        });

        if (settingWidget != null) {
            settingWidget.setOnClickListener(v -> toggleRightDrawer());
        }

        SystemStatusWidget systemStatusWidget = topBarPanel.getSystemStatusWidget();
        if (systemStatusWidget != null) {
            systemStatusWidget.setOnClickListener(
                    v -> ViewExtensions.toggleVisibility(systemStatusListPanelWidget));
        }

        SimulatorIndicatorWidget simulatorIndicatorWidget = topBarPanel.getSimulatorIndicatorWidget();
        if (simulatorIndicatorWidget != null) {
            simulatorIndicatorWidget.setOnClickListener(
                    v -> ViewExtensions.toggleVisibility(simulatorControlWidget));
        }

        gimbalAdjustDone.setOnClickListener(view -> {
            horizontalSituationIndicatorWidget.setVisibility(View.VISIBLE);
            if (gimbalFineTuneWidget != null) {
                gimbalFineTuneWidget.setVisibility(View.GONE);
            }
        });

        if (btnMapType != null) {
            btnMapType.setOnClickListener(v -> showMapTypeDialog());
        }
        if (btnFlyZones != null) {
            btnFlyZones.setOnClickListener(v -> showSelectFlyZoneDialog());
        }
        if (btnMission != null) {
            btnMission.setOnClickListener(v -> onMissionButtonClick());
        }
    }

    /**
     * Mission: choose type (waypoint / hot point / custom). While waypoint planning is active,
     * toggles the waypoint mission drawer (right slide-over panel).
     */
    protected void onMissionButtonClick() {
        if (waypointPlanner != null && waypointPlanner.isPlanningActive()) {
            waypointPlanner.toggleDrawer();
            return;
        }
        if (mappingPlanner != null && mappingPlanner.isPlanningActive()) {
            mappingPlanner.toggleDrawer();
            return;
        }
        showMissionTypeDialog();
    }

    private void handleMapWidgetMapClick(DJILatLng latLng) {
        // While the map is still the small overlay, a tap must expand it — not add a waypoint.
        // Waypoint planning would otherwise consume the event and block expansion after FPV↔map swaps.
        if (isFpvExpanded()) {
            requestViewMode(ViewMode.MAP_FULL);
            return;
        }
        if (waypointPlanner != null && waypointPlanner.onMapClick(latLng)) {
            return;
        }
        if (mappingPlanner != null && mappingPlanner.onMapClick(latLng)) {
            return;
        }
    }

    private void showMissionTypeDialog() {
        View root = LayoutInflater.from(this).inflate(R.layout.uxsdk_dialog_mission_type_picker, null, false);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(root)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        View waypointCard = root.findViewById(R.id.uxsdk_mission_picker_waypoint_card);
        View hotpointCard = root.findViewById(R.id.uxsdk_mission_picker_hotpoint_card);
        View mappingCard = root.findViewById(R.id.uxsdk_mission_picker_mapping_card);

        waypointCard.setOnClickListener(v -> {
            dialog.dismiss();
            if (mappingPlanner != null) {
                mappingPlanner.clearPlanning();
            }
            if (btnMission != null) {
                // Show custom waypoint image when mission mode is entered.
                btnMission.setImageResource(R.drawable.way);
                btnMission.setScaleType(ImageView.ScaleType.FIT_CENTER);
                btnMission.setPadding(6, 6, 6, 6);
            }
            if (waypointPlanner == null) {
                waypointPlanner = new WaypointPlanner(this, mapWidget);
            } else {
                waypointPlanner.clearPlanning();
            }
            waypointPlanner.bindDrawer(waypointMissionDrawerShell);
            waypointPlanner.startWaypointPlanning();
        });
        hotpointCard.setOnClickListener(v -> {
            dialog.dismiss();
            Toast.makeText(this, R.string.uxsdk_mission_type_not_implemented, Toast.LENGTH_SHORT).show();
        });
        mappingCard.setOnClickListener(v -> {
            dialog.dismiss();
            if (waypointPlanner != null) {
                waypointPlanner.clearPlanning();
            }
            if (btnMission != null) {
                btnMission.setImageResource(R.drawable.terrain);
                btnMission.setScaleType(ImageView.ScaleType.FIT_CENTER);
                btnMission.setPadding(6, 6, 6, 6);
            }
            if (mappingPlanner == null) {
                mappingPlanner = new MappingPlanner(this, mapWidget);
            } else {
                mappingPlanner.clearPlanning();
            }
            mappingPlanner.bindDrawer(mappingDrawerShell);
            mappingPlanner.startMappingPlanning();
        });
        dialog.show();
    }

    private void showMapTypeDialog() {
        String[] labels = {"Normal", "Satellite", "Hybrid"};
        String[] styleUrls = {
                MaplibreStyle.MAPBOX_STREETS,
                MaplibreStyle.SATELLITE,
                MaplibreStyle.SATELLITE_STREETS
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Map style");
        builder.setItems(labels, (dialog, which) -> {
            DJIMap map = mapWidget.getMap();
            if (map instanceof MaplibreMapDelegate) {
                ((MaplibreMapDelegate) map).setMapStyleUri(styleUrls[which]);
            } else if (map instanceof MaplibreMapDelegateKt) {
                ((MaplibreMapDelegateKt) map).setMapStyleUri(styleUrls[which]);
            }
        });
        builder.show();
    }

    private void showSelectFlyZoneDialog() {
        String[] categories = {"AUTHORIZATION", "WARNING", "ENHANCED_WARNING", "RESTRICTED"};
        FlyZoneCategory[] enumCategories = {FlyZoneCategory.AUTHORIZATION, FlyZoneCategory.WARNING, FlyZoneCategory.ENHANCED_WARNING, FlyZoneCategory.RESTRICTED};
        boolean[] checkedItems = new boolean[enumCategories.length];
        for (int fzIndex = 0; fzIndex < enumCategories.length; fzIndex++) {
            checkedItems[fzIndex] = mapWidget.getFlyZoneHelper().isFlyZoneVisible(enumCategories[fzIndex]);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Fly Zones");
        builder.setMultiChoiceItems(categories, checkedItems, (dialog, which, isChecked) -> mapWidget.getFlyZoneHelper().hideShowFlyZoneOfMap(enumCategories[which], isChecked));
        builder.setPositiveButton("OK", null);
        builder.show();
    }

    private void toggleRightDrawer() {
        mDrawerLayout.openDrawer(GravityCompat.END);
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (waypointPlanner != null) {
            waypointPlanner.clearPlanning();
        }
        mapWidget.onDestroy();
        MediaDataCenter.getInstance().getCameraStreamManager()
                .removeAvailableCameraUpdatedListener(availableCameraUpdatedListener);
        DJINetworkManager.getInstance().removeNetworkStatusListener(networkStatusListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapWidget.onResume();
        compositeDisposable = new CompositeDisposable();
        compositeDisposable.add(systemStatusListPanelWidget.closeButtonPressed()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(pressed -> {
                    if (pressed) ViewExtensions.hide(systemStatusListPanelWidget);
                }));
        compositeDisposable.add(simulatorControlWidget.getUIStateUpdates()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(state -> {
                    if (state instanceof SimulatorControlWidget.UIState.VisibilityUpdated) {
                        if (((SimulatorControlWidget.UIState.VisibilityUpdated) state).isVisible()) {
                            hideOtherPanels(simulatorControlWidget);
                        }
                    }
                }));
        compositeDisposable.add(cameraSourceProcessor.toFlowable()
                .observeOn(SchedulerProvider.io())
                .throttleLast(500, TimeUnit.MILLISECONDS)
                .subscribeOn(SchedulerProvider.io())
                .subscribe(result -> runOnUiThread(
                        () -> onCameraSourceUpdated(result.devicePosition, result.lensType))));
        compositeDisposable.add(ObservableInMemoryKeyedStore.getInstance()
                .addObserver(UXKeys.create(GlobalPreferenceKeys.GIMBAL_ADJUST_CLICKED))
                .observeOn(SchedulerProvider.ui())
                .subscribe(this::isGimableAdjustClicked));
        ViewUtil.setKeepScreen(this, true);
    }

    @Override
    protected void onPause() {
        if (compositeDisposable != null) {
            compositeDisposable.dispose();
            compositeDisposable = null;
        }
        mapWidget.onPause();
        super.onPause();
        ViewUtil.setKeepScreen(this, false);
    }
    //endregion

    // ══════════════════════════════════════════════════════════════════════════
    // region Map ↔ FPV swap
    // ══════════════════════════════════════════════════════════════════════════

    private void requestViewMode(@NonNull ViewMode targetMode) {
        if (targetMode == ViewMode.TRANSITIONING || currentViewMode == ViewMode.TRANSITIONING) {
            return;
        }
        if (currentViewMode == targetMode) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSwapRequestUptimeMs < SWAP_DEBOUNCE_MS) {
            return;
        }
        lastSwapRequestUptimeMs = now;
        applyViewMode(targetMode);
    }

    private boolean isMapExpanded() {
        return currentViewMode == ViewMode.MAP_FULL;
    }

    private boolean isFpvExpanded() {
        return currentViewMode == ViewMode.FPV_FULL;
    }

    private void applyViewMode(@NonNull ViewMode targetMode) {
        currentViewMode = ViewMode.TRANSITIONING;
        boolean mapExpanded = targetMode == ViewMode.MAP_FULL;
        applySwapLayout(mapExpanded);
        if (mapExpanded) {
            bringThumbnailToFront(fpvParentView);
            fpvInteractionWidget.setInteractionEnabled(false);
        } else {
            bringThumbnailToFront(mapWidget);
            fpvInteractionWidget.setInteractionEnabled(true);
        }
        updateWidgetsVisibility(mapExpanded);
        currentViewMode = targetMode;
    }

    private void updateWidgetsVisibility(boolean isMapExpanded) {
        if (isMapExpanded) {
            // Hide everything that shouldn't be there when map is expanded
            if (lensControlWidget != null) lensControlWidget.setVisibility(View.GONE);
            if (ndviCameraPanel != null) ndviCameraPanel.setVisibility(View.GONE);
            if (visualCameraPanel != null) visualCameraPanel.setVisibility(View.GONE);
            if (autoExposureLockWidget != null) autoExposureLockWidget.setVisibility(View.GONE);
            if (focusModeWidget != null) focusModeWidget.setVisibility(View.GONE);
            if (focusExposureSwitchWidget != null) focusExposureSwitchWidget.setVisibility(View.GONE);
            if (cameraControlsWidget != null) cameraControlsWidget.setVisibility(View.GONE);
            if (focalZoomWidget != null) focalZoomWidget.setVisibility(View.GONE);
            if (horizontalSituationIndicatorWidget != null)
                horizontalSituationIndicatorWidget.setVisibility(View.GONE);
            if (pfvFlightDisplayWidget != null) pfvFlightDisplayWidget.setVisibility(View.GONE);
            if (simulatorControlWidget != null) simulatorControlWidget.setVisibility(View.GONE);
            if (gimbalFineTuneWidget != null) gimbalFineTuneWidget.setVisibility(View.GONE);

            // Thumbnail Management:
            // Keep Primary FPV visible (it will shrink with the fpv_holder container)
            primaryFpvWidget.setVisibility(View.VISIBLE);
            // Hide the extra secondary FPV widget so it doesn't overlap in the thumbnail
            secondaryFPVWidget.setVisibility(View.GONE);

            if (mapControlsContainer != null) {
                mapControlsContainer.setVisibility(View.VISIBLE);
            }

        } else {
            // Reverting to FPV: Restore the "default" layout
            primaryFpvWidget.setVisibility(View.VISIBLE);
            if (horizontalSituationIndicatorWidget != null) {
                horizontalSituationIndicatorWidget.setVisibility(View.VISIBLE);
            }

            // Restore Secondary FPV visibility based on actual camera availability
            updateFPVWidgetSource(lastAvailableCameraList);

            // Use the existing logic to set visibility based on camera/lens type
            updateViewVisibility(lastDevicePosition, lastLensType);

            if (mapControlsContainer != null) {
                mapControlsContainer.setVisibility(View.GONE);
            }
        }
    }

    private void bringThumbnailToFront(View thumbnailView) {
        thumbnailView.bringToFront();
        thumbnailView.setElevation(10f);
    }

    private void applySwapLayout(boolean mapExpanded) {
        if (mapExpanded) {
            mapWidget.setLayoutParams(new ConstraintLayout.LayoutParams(fpvFullLayoutParams));
            fpvParentView.setLayoutParams(new ConstraintLayout.LayoutParams(mapMiniLayoutParams));
        } else {
            fpvParentView.setLayoutParams(new ConstraintLayout.LayoutParams(fpvFullLayoutParams));
            mapWidget.setLayoutParams(new ConstraintLayout.LayoutParams(mapMiniLayoutParams));
        }
    }
    // endregion swap ═══════════════════════════════════════════════════════════

    private void hideOtherPanels(@Nullable View widget) {
        View[] panels = { simulatorControlWidget };
        for (View panel : panels) {
            if (widget != panel) panel.setVisibility(View.GONE);
        }
    }

    private void updateFPVWidgetSource(List<ComponentIndexType> availableCameraList) {
        LogUtils.i(TAG, JsonUtil.toJson(availableCameraList));
        if (availableCameraList == null) return;

        ArrayList<ComponentIndexType> cameraList = new ArrayList<>(availableCameraList);

        if (cameraList.isEmpty()) {
            secondaryFPVWidget.setVisibility(View.GONE);
            return;
        }

        if (cameraList.size() == 1) {
            primaryFpvWidget.updateVideoSource(availableCameraList.get(0));
            secondaryFPVWidget.setVisibility(View.GONE);
            return;
        }

        ComponentIndexType primarySource = getSuitableSource(cameraList, ComponentIndexType.LEFT_OR_MAIN);
        primaryFpvWidget.updateVideoSource(primarySource);
        cameraList.remove(primarySource);

        ComponentIndexType secondarySource = getSuitableSource(cameraList, ComponentIndexType.FPV);
        secondaryFPVWidget.updateVideoSource(secondarySource);
        secondaryFPVWidget.setVisibility(View.VISIBLE);
    }

    private ComponentIndexType getSuitableSource(List<ComponentIndexType> cameraList,
                                                 ComponentIndexType defaultSource) {
        if (cameraList.contains(ComponentIndexType.LEFT_OR_MAIN)) return ComponentIndexType.LEFT_OR_MAIN;
        if (cameraList.contains(ComponentIndexType.RIGHT)) return ComponentIndexType.RIGHT;
        if (cameraList.contains(ComponentIndexType.UP)) return ComponentIndexType.UP;
        if (cameraList.contains(ComponentIndexType.PORT_1)) return ComponentIndexType.PORT_1;
        if (cameraList.contains(ComponentIndexType.PORT_2)) return ComponentIndexType.PORT_2;
        if (cameraList.contains(ComponentIndexType.PORT_3)) return ComponentIndexType.PORT_4;
        if (cameraList.contains(ComponentIndexType.PORT_4)) return ComponentIndexType.PORT_4;
        if (cameraList.contains(ComponentIndexType.VISION_ASSIST)) return ComponentIndexType.VISION_ASSIST;
        return defaultSource;
    }

    private void onCameraSourceUpdated(ComponentIndexType devicePosition, CameraLensType lensType) {
        LogUtils.i(LogPath.SAMPLE, "onCameraSourceUpdated", devicePosition, lensType);
        if (devicePosition == lastDevicePosition && lensType == lastLensType) return;
        lastDevicePosition = devicePosition;
        lastLensType = lensType;
        updateViewVisibility(devicePosition, lensType);
        updateInteractionEnabled();
        if (fpvInteractionWidget.isInteractionEnabled())
            fpvInteractionWidget.updateCameraSource(devicePosition, lensType);
        if (lensControlWidget.getVisibility() == View.VISIBLE)
            lensControlWidget.updateCameraSource(devicePosition, lensType);
        if (ndviCameraPanel.getVisibility() == View.VISIBLE)
            ndviCameraPanel.updateCameraSource(devicePosition, lensType);
        if (visualCameraPanel.getVisibility() == View.VISIBLE)
            visualCameraPanel.updateCameraSource(devicePosition, lensType);
        if (autoExposureLockWidget.getVisibility() == View.VISIBLE)
            autoExposureLockWidget.updateCameraSource(devicePosition, lensType);
        if (focusModeWidget.getVisibility() == View.VISIBLE)
            focusModeWidget.updateCameraSource(devicePosition, lensType);
        if (focusExposureSwitchWidget.getVisibility() == View.VISIBLE)
            focusExposureSwitchWidget.updateCameraSource(devicePosition, lensType);
        if (cameraControlsWidget.getVisibility() == View.VISIBLE)
            cameraControlsWidget.updateCameraSource(devicePosition, lensType);
        if (focalZoomWidget.getVisibility() == View.VISIBLE)
            focalZoomWidget.updateCameraSource(devicePosition, lensType);
        if (horizontalSituationIndicatorWidget.getVisibility() == View.VISIBLE)
            horizontalSituationIndicatorWidget.updateCameraSource(devicePosition, lensType);
    }

    private void updateViewVisibility(ComponentIndexType devicePosition, CameraLensType lensType) {
        pfvFlightDisplayWidget.setVisibility(
                CameraUtil.isFPVTypeView(devicePosition) ? View.VISIBLE : View.INVISIBLE);
        lensControlWidget.setVisibility(
                CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        ndviCameraPanel.setVisibility(
                CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        visualCameraPanel.setVisibility(
                CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        autoExposureLockWidget.setVisibility(
                CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        focusModeWidget.setVisibility(
                CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        focusExposureSwitchWidget.setVisibility(
                CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        cameraControlsWidget.setVisibility(
                CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        focalZoomWidget.setVisibility(
                CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        horizontalSituationIndicatorWidget.setSimpleModeEnable(
                CameraUtil.isFPVTypeView(devicePosition));
        ndviCameraPanel.setVisibility(
                CameraUtil.isSupportForNDVI(lensType) ? View.VISIBLE : View.INVISIBLE);
    }

    private void swapVideoSource() {
        ComponentIndexType primarySource = primaryFpvWidget.getWidgetModel().getCameraIndex();
        ComponentIndexType secondarySource = secondaryFPVWidget.getWidgetModel().getCameraIndex();
        if (primarySource != ComponentIndexType.UNKNOWN
                && secondarySource != ComponentIndexType.UNKNOWN) {
            primaryFpvWidget.updateVideoSource(secondarySource);
            secondaryFPVWidget.updateVideoSource(primarySource);
        }
    }

    private void updateInteractionEnabled() {
        fpvInteractionWidget.setInteractionEnabled(
                !CameraUtil.isFPVTypeView(primaryFpvWidget.getWidgetModel().getCameraIndex()));
    }

    private static class CameraSource {
        ComponentIndexType devicePosition;
        CameraLensType lensType;
        CameraSource(ComponentIndexType devicePosition, CameraLensType lensType) {
            this.devicePosition = devicePosition;
            this.lensType = lensType;
        }
    }

    @Override
    public void onBackPressed() {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.END)) {
            mDrawerLayout.closeDrawers();
        } else {
            super.onBackPressed();
        }
    }
}