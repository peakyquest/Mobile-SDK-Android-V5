package dji.v5.ux.sample.showcase.waypoint;

import dji.sdk.wpmz.value.mission.WaylineExitOnRCLostAction;
import dji.sdk.wpmz.value.mission.WaylineFinishedAction;

/**
 * Mission-wide settings used when building a waypoint KMZ (mirrors sample {@code MissionGlobalModel}).
 */
public class WaypointMissionGlobals {
    public double globalSpeed = 5.0;
    public WaylineFinishedAction finishAction = WaylineFinishedAction.GO_HOME;
    public WaylineExitOnRCLostAction lostAction = WaylineExitOnRCLostAction.GO_BACK;
}
