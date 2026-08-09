package dev.wassal.dispatch.domain.model;

/** WGS84 coordinate. Named fields — {@code [lon, lat]} ordering is the classic geospatial trap. */
public record GeoPoint(double lat, double lon) {
    public GeoPoint {
        if (lat < -90 || lat > 90) throw new IllegalArgumentException("lat out of range: " + lat);
        if (lon < -180 || lon > 180) throw new IllegalArgumentException("lon out of range: " + lon);
    }
}
