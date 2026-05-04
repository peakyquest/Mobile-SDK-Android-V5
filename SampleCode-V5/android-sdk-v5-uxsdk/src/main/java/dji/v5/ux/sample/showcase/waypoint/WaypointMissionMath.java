package dji.v5.ux.sample.showcase.waypoint;

import java.util.List;

import dji.sdk.wpmz.value.mission.WaylineLocationCoordinate2D;
import dji.sdk.wpmz.value.mission.WaylineWaypoint;
import dji.v5.ux.mapkit.core.models.DJILatLng;

/**
 * Geodesic helpers for waypoint planning UI (Haversine on WGS84 sphere).
 */
public final class WaypointMissionMath {

    private static final double EARTH_RADIUS_M = 6371000.0;

    private WaypointMissionMath() {
    }

    /**
     * Sum of great-circle segment lengths along the waypoint order (meters).
     */
    public static double totalPathLengthMeters(List<WaypointPlanItem> planItems) {
        if (planItems == null || planItems.size() < 2) {
            return 0d;
        }
        double sum = 0d;
        DJILatLng prev = null;
        for (WaypointPlanItem item : planItems) {
            if (item == null || item.getWaylineWaypoint() == null) {
                continue;
            }
            WaylineLocationCoordinate2D loc = item.getWaylineWaypoint().getLocation();
            if (loc == null) {
                continue;
            }
            DJILatLng cur = new DJILatLng(loc.getLatitude(), loc.getLongitude());
            if (prev != null) {
                sum += haversineMeters(prev.getLatitude(), prev.getLongitude(), cur.getLatitude(), cur.getLongitude());
            }
            prev = cur;
        }
        return sum;
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r1 = Math.toRadians(lat1);
        double r2 = Math.toRadians(lat2);
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(r1) * Math.cos(r2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }
}
