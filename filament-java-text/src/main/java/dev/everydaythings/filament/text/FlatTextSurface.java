package dev.everydaythings.filament.text;

/**
 * A planar text surface defined by an origin point and two basis vectors.
 *
 * <p>Text-space X maps along the {@code right} vector, Y maps along the {@code up} vector.
 * The surface normal is {@code right × up} (cross product), computed once at construction.
 * Each glyph is a single flat quad (no subdivision).
 *
 * <p>Example — text on the XY plane at the origin:
 * <pre>{@code
 * new FlatTextSurface(
 *     new float[]{0, 0, 0},   // origin
 *     new float[]{1, 0, 0},   // right (X axis)
 *     new float[]{0, 1, 0}    // up (Y axis)
 * )
 * }</pre>
 */
public class FlatTextSurface implements TextSurface {

    private final float ox, oy, oz;
    private final float rx, ry, rz;
    private final float ux, uy, uz;
    private final float nx, ny, nz;

    /**
     * Create a flat text surface.
     *
     * @param origin float[3] — world position of text origin (bottom-left of first glyph)
     * @param right  float[3] — unit vector for horizontal text direction
     * @param up     float[3] — unit vector for vertical text direction
     */
    public FlatTextSurface(float[] origin, float[] right, float[] up) {
        this.ox = origin[0]; this.oy = origin[1]; this.oz = origin[2];
        this.rx = right[0];  this.ry = right[1];  this.rz = right[2];
        this.ux = up[0];     this.uy = up[1];     this.uz = up[2];

        // Normal = right × up
        float cnx = ry * uz - rz * uy;
        float cny = rz * ux - rx * uz;
        float cnz = rx * uy - ry * ux;
        float len = (float) Math.sqrt(cnx * cnx + cny * cny + cnz * cnz);
        if (len > 0) {
            this.nx = cnx / len;
            this.ny = cny / len;
            this.nz = cnz / len;
        } else {
            this.nx = 0; this.ny = 0; this.nz = 1;
        }
    }

    @Override
    public void position(float x, float y, float[] out) {
        out[0] = ox + x * rx + y * ux;
        out[1] = oy + x * ry + y * uy;
        out[2] = oz + x * rz + y * uz;
    }

    @Override
    public void normal(float x, float y, float[] out) {
        out[0] = nx;
        out[1] = ny;
        out[2] = nz;
    }

    @Override
    public int subdivisionsPerEm() {
        return 0;
    }
}
