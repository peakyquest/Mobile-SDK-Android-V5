package dji.v5.ux.sample.showcase.waypoint;

import dji.sdk.wpmz.value.mission.WaylineExitOnRCLostAction;
import dji.sdk.wpmz.value.mission.WaylineFinishedAction;
import dji.sdk.wpmz.value.mission.WaylineWaypointTurnMode;

/**
 * Mission-wide settings used when building a waypoint KMZ (mirrors sample {@code MissionGlobalModel}).
 */
public class WaypointMissionGlobals {
    public double globalSpeed = 5.0;
    public WaylineFinishedAction finishAction = WaylineFinishedAction.GO_HOME;
    public WaylineExitOnRCLostAction lostAction = WaylineExitOnRCLostAction.GO_BACK;
    public WaylineWaypointTurnMode globalTurnMode =
            WaylineWaypointTurnMode.TO_POINT_AND_STOP_WITH_DISCONTINUITY_CURVATURE;
}
