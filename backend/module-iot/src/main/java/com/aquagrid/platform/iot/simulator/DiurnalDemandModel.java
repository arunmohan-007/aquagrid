package com.aquagrid.platform.iot.simulator;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Random;

/**
 * A realistic diurnal household-water-demand curve.
 *
 * <p>Real water consumption is not flat: it has two pronounced peaks (the morning wash/shower rush
 * around 07:00 and the evening cooking/laundry peak around 20:00) and a deep night-time trough
 * (the minimum-night-flow window around 03:00 that NRW analysis depends on). A simulator that emits
 * uniform random flow teaches the analytics nothing; this model produces the curve those analytics
 * are built to detect.
 *
 * <p>The curve is a sum of Gaussian bumps centred on the characteristic activity times, scaled by a
 * per-household baseline consumption. Seasonality and climate are added in Module 28 (consumption
 * analytics); this is the within-day shape.
 */
public final class DiurnalDemandModel {

    /**
     * Returns the expected flow rate (L/min) for the given instant, before per-reading noise.
     *
     * @param baselineDailyLitres the household's typical daily consumption, e.g. 600 L
     * @param instant             the moment to evaluate, in the household's timezone
     * @param zone                the timezone — the curve is local-clock driven (people shower at 7am
     *                            local, not 7am UTC)
     */
    public double baselineFlow(double baselineDailyLitres, java.time.Instant instant, ZoneId zone) {
        ZonedDateTime local = instant.atZone(zone);
        LocalTime t = local.toLocalTime();
        double hour = t.getHour() + t.getMinute() / 60.0 + t.getSecond() / 3600.0;

        // Normalised demand intensity at this hour: sum of Gaussian bumps.
        // Centres and relative weights reflect observed residential demand in tropical urban supplies.
        double intensity = 0.0;
        intensity += 0.45 * gaussian(hour, 7.0, 1.2);     // morning peak
        intensity += 0.30 * gaussian(hour, 12.5, 1.5);    // midday cooking/cleaning
        intensity += 0.55 * gaussian(hour, 20.0, 1.8);    // evening peak (largest)
        intensity += 0.05 * gaussian(hour, 3.0, 1.0);     // night-time base (toilet flushes, leaks)
        // A small constant floor representing continuous background use.
        intensity += 0.04;

        // Convert daily litres to an instantaneous rate. The integral of intensity over 24h is
        // ~1.0 by construction (the weights are tuned to it), so multiplying the daily volume by
        // the instantaneous intensity yields L/min in the right magnitude.
        return baselineDailyLitres * intensity / (24.0 * 60.0);
    }

    /** Standard Gaussian: peak 1 at {@code centre}, falling off with {@code width} stddev (hours). */
    private static double gaussian(double hour, double centre, double width) {
        double d = (hour - centre) / width;
        return Math.exp(-0.5 * d * d);
    }

    /**
     * Adds realistic per-reading jitter to a baseline flow.
     *
     * <p>Flow is bursty at the sub-minute scale (a tap opens, a toilet flushes), so the noise is
     * multiplicative and right-skewed — never negative, occasionally large. A symmetric Gaussian
     * would produce negative flow half the time, which is physically meaningless.
     */
    public double jitter(double baselineFlow, Random rng) {
        // Multiplicative log-normal noise: exp(N(0, 0.5)) has median 1, right tail.
        double noise = Math.exp(rng.nextGaussian() * 0.5);
        return Math.max(0, baselineFlow * noise);
    }

    /**
     * Minimum-night-flow baseline: the unavoidable flow in the small hours, dominated by leaks and
     * legitimate night use. NRW analysis (Module 27) compares real MNF against this expectation.
     */
    public double minimumNightFlow(double baselineDailyLitres) {
        return baselineDailyLitres * 0.04 / (24.0 * 60.0);
    }
}
