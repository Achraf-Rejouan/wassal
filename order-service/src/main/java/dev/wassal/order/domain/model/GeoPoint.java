package dev.wassal.order.domain.model;

/**
 * WGS84 coordinate. Named fields rather than an array, because {@code [lon, lat]} ordering is the
 * most reliably-mistaken convention in geospatial work (docs/06-api-contract.md).
 */
public record GeoPoint(double lat, double lon) {
    public GeoPoint {
        if (lat < -90 || lat > 90) {
            throw new IllegalArgumentException("lat out of range: " + lat);
        }
        if (lon < -180 || lon > 180) {
            throw new IllegalArgumentException("lon out of range: " + lon);
        }
    }
}
