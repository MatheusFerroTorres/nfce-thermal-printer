package br.com.alkhemylab.nfceprinter.util;

public final class MeasurementUtils {
    private static final float POINTS_PER_INCH = 72f;
    private static final float MILLIMETERS_PER_INCH = 25.4f;

    private MeasurementUtils() {
    }

    public static float mmToPoints(double millimeters) {
        return (float) (millimeters * POINTS_PER_INCH / MILLIMETERS_PER_INCH);
    }

    public static double pointsToMm(double points) {
        return points * MILLIMETERS_PER_INCH / POINTS_PER_INCH;
    }

    public static int mmToDots(double millimeters, int dpi) {
        return (int) Math.round(millimeters * dpi / MILLIMETERS_PER_INCH);
    }
}
