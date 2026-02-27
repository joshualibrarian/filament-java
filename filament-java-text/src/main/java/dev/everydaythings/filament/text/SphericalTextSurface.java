package dev.everydaythings.filament.text;

/**
 * Wraps text around a sphere along a latitude band.
 *
 * <p>Text-space X maps to longitude (arc around the equator or a latitude ring),
 * and Y maps to latitude offset from the base latitude. The text starts at
 * {@code startLongitude} radians and wraps eastward.
 *
 * <p>The sphere uses a standard geographic coordinate system where latitude 0 is
 * the equator, +π/2 is the north pole, and longitude increases counter-clockwise
 * when viewed from above.
 *
 * <p>Example — text on the equator of a sphere at the origin:
 * <pre>{@code
 * new SphericalTextSurface(
 *     new float[]{0, 0, 0},  // center
 *     2.0f,                   // radius
 *     0.0f,                   // latitude (equator)
 *     0.0f                    // startLongitude (radians)
 * )
 * }</pre>
 */
public class SphericalTextSurface implements TextSurface {

    private final float cx, cy, cz;
    private final float radius;
    private final float baseLat;
    private final float startLon;
    private final int subdivisions;

    /**
     * Create a spherical text surface.
     *
     * @param center         float[3] — center of the sphere
     * @param radius         sphere radius
     * @param latitude       base latitude in radians (0 = equator, +π/2 = north pole)
     * @param startLongitude starting longitude in radians
     */
    public SphericalTextSurface(float[] center, float radius, float latitude, float startLongitude) {
        this.cx = center[0]; this.cy = center[1]; this.cz = center[2];
        this.radius = radius;
        this.baseLat = latitude;
        this.startLon = startLongitude;

        // Heuristic: ceil(2.0 / radius) clamped to [1, 10]
        this.subdivisions = Math.max(1, Math.min(10, (int) Math.ceil(2.0 / radius)));
    }

    @Override
    public void position(float x, float y, float[] out) {
        // X text-space → longitude offset: arc = x, so dLon = x / (radius * cos(lat))
        float lat = baseLat + y / radius;
        float cosLat = (float) Math.cos(lat);
        float lonOffset = (cosLat != 0) ? x / (radius * cosLat) : 0;
        float lon = startLon + lonOffset;

        float sinLat = (float) Math.sin(lat);
        float cosLon = (float) Math.cos(lon);
        float sinLon = (float) Math.sin(lon);

        // Standard sphere: X = cos(lat)*cos(lon), Y = sin(lat), Z = cos(lat)*sin(lon)
        out[0] = cx + radius * cosLat * cosLon;
        out[1] = cy + radius * sinLat;
        out[2] = cz + radius * cosLat * sinLon;
    }

    @Override
    public void normal(float x, float y, float[] out) {
        float lat = baseLat + y / radius;
        float cosLat = (float) Math.cos(lat);
        float lonOffset = (cosLat != 0) ? x / (radius * cosLat) : 0;
        float lon = startLon + lonOffset;

        float sinLat = (float) Math.sin(lat);
        float cosLon = (float) Math.cos(lon);
        float sinLon = (float) Math.sin(lon);

        // Outward unit normal on sphere
        out[0] = cosLat * cosLon;
        out[1] = sinLat;
        out[2] = cosLat * sinLon;
    }

    @Override
    public int subdivisionsPerEm() {
        return subdivisions;
    }
}
