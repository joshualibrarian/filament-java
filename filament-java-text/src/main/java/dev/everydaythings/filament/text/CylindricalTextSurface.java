package dev.everydaythings.filament.text;

/**
 * Wraps text around a cylinder.
 *
 * <p>Text-space X maps to arc angle around the cylinder ({@code theta = x / radius}),
 * and Y maps to translation along the cylinder axis. The text starts at
 * {@code startDir} and wraps in the direction of {@code startDir × axis}.
 *
 * <p>Example — text wrapping around a vertical cylinder at the origin:
 * <pre>{@code
 * new CylindricalTextSurface(
 *     new float[]{0, 0, 0},   // center
 *     new float[]{0, 1, 0},   // axis (vertical)
 *     2.0f,                    // radius
 *     new float[]{0, 0, 1}    // startDir (text starts facing +Z)
 * )
 * }</pre>
 */
public class CylindricalTextSurface implements TextSurface {

    private final float cx, cy, cz;
    private final float ax, ay, az;
    private final float radius;
    // Orthonormal basis in the plane perpendicular to axis:
    // d = startDir (normalized), b = axis × d
    private final float dx, dy, dz;
    private final float bx, by, bz;
    private final int subdivisions;

    /**
     * Create a cylindrical text surface.
     *
     * @param center   float[3] — center of the cylinder
     * @param axis     float[3] — unit vector along the cylinder axis (Y direction for text)
     * @param radius   cylinder radius
     * @param startDir float[3] — direction from center to where text begins (perpendicular to axis)
     */
    public CylindricalTextSurface(float[] center, float[] axis, float radius, float[] startDir) {
        this.cx = center[0]; this.cy = center[1]; this.cz = center[2];
        this.ax = axis[0];   this.ay = axis[1];   this.az = axis[2];
        this.radius = radius;

        // Normalize startDir and ensure it's perpendicular to axis
        float sdx = startDir[0], sdy = startDir[1], sdz = startDir[2];
        // Remove axis component: d = startDir - (startDir·axis)*axis
        float dot = sdx * ax + sdy * ay + sdz * az;
        sdx -= dot * ax;
        sdy -= dot * ay;
        sdz -= dot * az;
        float len = (float) Math.sqrt(sdx * sdx + sdy * sdy + sdz * sdz);
        this.dx = sdx / len;
        this.dy = sdy / len;
        this.dz = sdz / len;

        // b = axis × d
        this.bx = ay * dz - az * dy;
        this.by = az * dx - ax * dz;
        this.bz = ax * dy - ay * dx;

        // Heuristic: ceil(1.0 / radius) clamped to [1, 8]
        this.subdivisions = Math.max(1, Math.min(8, (int) Math.ceil(1.0 / radius)));
    }

    @Override
    public void position(float x, float y, float[] out) {
        float theta = x / radius;
        float cosT = (float) Math.cos(theta);
        float sinT = (float) Math.sin(theta);

        // Point on cylinder: center + radius*(cosT*d + sinT*b) + y*axis
        out[0] = cx + radius * (cosT * dx + sinT * bx) + y * ax;
        out[1] = cy + radius * (cosT * dy + sinT * by) + y * ay;
        out[2] = cz + radius * (cosT * dz + sinT * bz) + y * az;
    }

    @Override
    public void normal(float x, float y, float[] out) {
        float theta = x / radius;
        float cosT = (float) Math.cos(theta);
        float sinT = (float) Math.sin(theta);

        // Outward radial normal: cosT*d + sinT*b
        out[0] = cosT * dx + sinT * bx;
        out[1] = cosT * dy + sinT * by;
        out[2] = cosT * dz + sinT * bz;
    }

    @Override
    public int subdivisionsPerEm() {
        return subdivisions;
    }
}
