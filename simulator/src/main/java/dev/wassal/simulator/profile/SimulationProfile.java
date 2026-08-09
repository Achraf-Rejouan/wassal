package dev.wassal.simulator.profile;

/**
 * A run configuration (FR-019, FR-021).
 *
 * <p>{@code stress} is the profile that proves INV-1 and INV-2: many orders converging on few
 * couriers in a tight radius. Natural traffic at 50 orders/min produces the double-assignment race
 * approximately never, so it has to be manufactured deliberately.
 */
public record SimulationProfile(
        String name,
        long seed,
        int courierCount,
        double ordersPerMinute,
        double peakMultiplier,
        double acceptRate,
        double ignoreRate,
        double declineRate,
        double postAcceptCancelRate,
        int positionIntervalSeconds,
        double radiusMetres) {

    /** 300 couriers reporting every 3s IS the 100 msg/s of NFR-003 — derived, not coincidental. */
    public static SimulationProfile standard(long seed) {
        return new SimulationProfile(
                "standard", seed, 300, 50, 1.0, 0.60, 0.25, 0.15, 0.05, 3, 3_000);
    }

    /** 2–3x arrivals, as in an evening peak. */
    public static SimulationProfile peak(long seed) {
        return new SimulationProfile("peak", seed, 300, 50, 2.5, 0.60, 0.25, 0.15, 0.05, 3, 3_000);
    }

    /**
     * Maximum contention: 20 couriers, 10x the order rate, tight radius. Ten-plus orders per
     * available courier is what forces the claim race to actually happen.
     */
    public static SimulationProfile stress(long seed) {
        return new SimulationProfile("stress", seed, 20, 500, 1.0, 0.90, 0.05, 0.05, 0.02, 2, 500);
    }

    public static SimulationProfile byName(String name, long seed) {
        return switch (name) {
            case "peak" -> peak(seed);
            case "stress" -> stress(seed);
            default -> standard(seed);
        };
    }
}
