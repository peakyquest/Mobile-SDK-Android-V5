package dji.v5.ux.sample.showcase.waypoint;

import java.util.List;

import dji.sdk.wpmz.value.mission.WaylineActionInfo;
import dji.sdk.wpmz.value.mission.WaylineWaypoint;
import dji.sdk.wpmz.value.mission.WaylineWaypointTurnMode;

/**
 * One planned waypoint plus optional per-point actions (same shape as sample {@code WaypointInfoModel}).
 */
public class WaypointPlanItem {
    private WaylineWaypoint waylineWaypoint;
    private List<WaylineActionInfo> actionInfos;
    /** Path turn style for KMZ template global turn (first waypoint with non-null wins at export). */
    private WaylineWaypointTurnMode turnMode = WaylineWaypointTurnMode.TO_POINT_AND_STOP_WITH_DISCONTINUITY_CURVATURE;

    public WaylineWaypoint getWaylineWaypoint() {
        return waylineWaypoint;
    }

    public void setWaylineWaypoint(WaylineWaypoint waylineWaypoint) {
        this.waylineWaypoint = waylineWaypoint;
    }

    public List<WaylineActionInfo> getActionInfos() {
        return actionInfos;
    }

    public void setActionInfos(List<WaylineActionInfo> actionInfos) {
        this.actionInfos = actionInfos;
    }

    public WaylineWaypointTurnMode getTurnMode() {
        return turnMode;
    }

    public void setTurnMode(WaylineWaypointTurnMode turnMode) {
        if (turnMode != null) {
            this.turnMode = turnMode;
        }
    }
}
