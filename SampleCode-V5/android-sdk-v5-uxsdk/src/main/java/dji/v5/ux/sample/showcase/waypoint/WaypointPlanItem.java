package dji.v5.ux.sample.showcase.waypoint;

import java.util.List;

import dji.sdk.wpmz.value.mission.WaylineActionInfo;
import dji.sdk.wpmz.value.mission.WaylineWaypoint;

/**
 * One planned waypoint plus optional per-point actions (same shape as sample {@code WaypointInfoModel}).
 */
public class WaypointPlanItem {
    private WaylineWaypoint waylineWaypoint;
    private List<WaylineActionInfo> actionInfos;

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
}
