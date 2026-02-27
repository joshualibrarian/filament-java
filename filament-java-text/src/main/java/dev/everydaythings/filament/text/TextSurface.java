package dev.everydaythings.filament.text;

/**
 * Maps 2D text-space coordinates to 3D positions and normals on an arbitrary surface.
 *
 * <p>Text layout produces glyph positions in a 2D plane (X = horizontal advance,
 * Y = vertical baseline offset, both in world units after fontSize scaling).
 * A {@code TextSurface} projects those flat coordinates onto a 3D surface,
 * enabling text to wrap around cylinders, spheres, or any custom geometry.
 *
 * <p>For flat (planar) text, use {@link FlatTextSurface}.
 */
public interface TextSurface {

    /**
     * Map a 2D text-space coordinate to a 3D world position.
     *
     * @param x horizontal position in world units (cursor advance direction)
     * @param y vertical position in world units (baseline direction)
     * @param out float[3] receiving the 3D position (x, y, z)
     */
    void position(float x, float y, float[] out);

    /**
     * Compute the surface normal at the given text-space coordinate.
     *
     * @param x horizontal position in world units
     * @param y vertical position in world units
     * @param out float[3] receiving the unit normal vector
     */
    void normal(float x, float y, float[] out);

    /**
     * Number of subdivisions per em-unit of glyph size for curved surfaces.
     *
     * <p>When 0 (the default), each glyph is a single flat quad (4 vertices, 2 triangles).
     * Values greater than 0 cause glyphs to be tessellated into grids that conform
     * to surface curvature. Higher values produce smoother results at the cost of
     * more geometry.
     *
     * @return subdivisions per em (0 = flat quad per glyph)
     */
    default int subdivisionsPerEm() {
        return 0;
    }
}
