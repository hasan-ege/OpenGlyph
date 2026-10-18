package org.openglyph.toys;

/** Sun and moon arithmetic for the Solar Path and Moon Phase toys (approximate, no tables). */
public final class Sky {
    private Sky() {}

    static final double SYNODIC_DAYS = 29.530588853;
    /** A new moon: 2000-01-06 18:14 UTC. */
    static final long NEW_MOON_MS = 947182440000L;

    /** Moon age as a fraction of the cycle: 0 new, 0.25 first quarter, 0.5 full, 0.75 last. */
    public static double moonPhase(long epochMs) {
        double days = (epochMs - NEW_MOON_MS) / 86400000.0;
        double p = (days / SYNODIC_DAYS) % 1.0;
        return p < 0 ? p + 1 : p;
    }

    /** Fraction of the moon's disc that is lit, 0..1. */
    public static double illumination(double phase) {
        return (1 - Math.cos(2 * Math.PI * phase)) / 2;
    }

    /**
     * Sun elevation in degrees (negative below the horizon), from the declination and the hour
     * angle with the equation of time; good to about a degree, plenty for 25 pixels.
     */
    public static double sunElevation(double latDeg, double lonDeg, long epochMs) {
        double days = epochMs / 86400000.0;
        int dayOfYear = (int) Math.floor(days - Math.floor(days / 365.2425) * 365.2425) + 1;
        double utcHours = (days - Math.floor(days)) * 24;
        double decl = Math.toRadians(23.44) * Math.sin(2 * Math.PI * (284 + dayOfYear) / 365.0);
        double b = 2 * Math.PI * (dayOfYear - 81) / 364.0;
        double eotMin = 9.87 * Math.sin(2 * b) - 7.53 * Math.cos(b) - 1.5 * Math.sin(b);
        double solarHours = utcHours + lonDeg / 15.0 + eotMin / 60.0;
        double hourAngle = Math.toRadians(15 * (solarHours - 12));
        double lat = Math.toRadians(latDeg);
        double sinEl = Math.sin(lat) * Math.sin(decl)
                + Math.cos(lat) * Math.cos(decl) * Math.cos(hourAngle);
        return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, sinEl))));
    }

    /** Local solar time of day 0..24 (12 = the sun is highest). */
    public static double solarHours(double lonDeg, long epochMs) {
        double days = epochMs / 86400000.0;
        double h = (days - Math.floor(days)) * 24 + lonDeg / 15.0;
        h %= 24;
        return h < 0 ? h + 24 : h;
    }
}
