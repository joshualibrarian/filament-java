package dev.everydaythings.filament.text;

import java.util.List;

/**
 * COLRv0 color layer information for a single emoji glyph.
 *
 * <p>A COLRv0 emoji is decomposed into stacked vector layers, each painted
 * with a color from the font's CPAL palette. Each layer is a standard glyph
 * outline that can be rendered as an MSDF glyph with its own vertex color.
 *
 * @param layers ordered list of layers (back-to-front compositing order)
 */
public record ColrGlyphInfo(List<Layer> layers) {

    /**
     * A single color layer in a COLRv0 glyph.
     *
     * @param glyphIndex the glyph index in the font (for {@code msdf_ft_font_load_glyph_by_index})
     * @param argbColor  the ARGB color from the CPAL palette (0xFFFF0000 sentinel = use foreground)
     */
    public record Layer(int glyphIndex, int argbColor) {}

    /** Sentinel ARGB value meaning "use the foreground/tint color". */
    public static final int FOREGROUND_COLOR = 0x00000000;

    /** Whether this glyph has color layers. */
    public boolean isColor() {
        return layers != null && !layers.isEmpty();
    }
}
