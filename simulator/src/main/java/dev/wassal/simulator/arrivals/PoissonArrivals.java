package dev.wassal.simulator.arrivals;

import java.util.Random;

/**
 * Order arrivals as a Poisson process (FR-019).
 *
 * <p>Poisson rather than a fixed interval because bursts are what create contention, and a fixed
 * interval would quietly smooth away the very thing the stress profile is trying to produce.
 * Inter-arrival times are exponential; drawn from the shared seeded stream so runs reproduce.
 */
public final class PoissonArrivals {

    private final double baseRatePerMinute;
    private final double peakMultiplier;

    public PoissonArrivals(double baseRatePerMinute, double peakMultiplier) {
        this.baseRatePerMinute = baseRatePerMinute;
        this.peakMultiplier = peakMultiplier;
    }

    /**
     * @return how many orders arrive in this tick
     */
    public int arrivalsInTick(Random random, double tickSeconds, boolean inPeak) {
        double ratePerSecond = baseRatePerMinute / 60.0 * (inPeak ? peakMultiplier : 1.0);
        double lambda = ratePerSecond * tickSeconds;

        // Knuth's method. Fine at these lambdas and, more importantly, it consumes a
        // predictable number of draws from the shared stream.
        double limit = Math.exp(-lambda);
        double product = 1.0;
        int count = 0;
        do {
            count++;
            product *= random.nextDouble();
        } while (product > limit);
        return count - 1;
    }
}
