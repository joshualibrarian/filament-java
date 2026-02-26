/**
 * MSDF text rendering for Filament.
 *
 * <p>Generates multi-channel signed distance field (MSDF/MTSDF) font atlases at runtime
 * using LWJGL's FreeType and msdfgen bindings, uploads them as Filament textures, and
 * provides glyph metrics for text layout.
 *
 * <h2>Quick Start</h2>
 * <pre>{@code
 * // Load font and generate atlas
 * MsdfFontManager fonts = new MsdfFontManager(engine);
 * fonts.loadDefaultFont();  // discovers system fonts with fallback chain
 *
 * // Load bundled materials
 * MsdfTextRenderer renderer = new MsdfTextRenderer(engine);
 *
 * // Resolve glyphs and measure text
 * fonts.ensureGlyphs("Hello, world!");
 * float width = fonts.measureWidth("Hello", 20f);
 *
 * // Get per-glyph metrics for quad emission
 * MsdfFontManager.ResolvedGlyph rg = fonts.resolveGlyph('H');
 * MsdfAtlas.GlyphMetrics g = rg.metrics();
 * // Use g.planeLeft/Right/Top/Bottom for quad bounds (em units)
 * // Use g.uvLeft/Right/Top/Bottom for texture coordinates
 * // Bind rg.atlas().texture() to renderer.msdfTextMaterial()
 * }</pre>
 *
 * <h2>msdfgen Transform Convention</h2>
 * <p>This library uses msdfgen's projection convention:
 * <b>{@code bitmap = scale * (shape + translate)}</b> where translate is in
 * <em>shape/font-unit space</em>, not bitmap/pixel space. This is critical for
 * correct glyph positioning — the pixel-space convention produces identical results
 * for width-dominated glyphs but breaks for height-dominated glyphs like 'h', 'b', 'j'.
 *
 * <h2>UV Convention</h2>
 * <p>Stored UV coordinates are in image space (V=0 at top). Filament uses OpenGL
 * convention (V=0 at bottom), so renderers must flip: {@code glV = 1.0f - storedV}.
 *
 * <h2>Color Space</h2>
 * <p>Filament vertex colors arrive as sRGB bytes. The bundled materials
 * ({@code msdf_text} and {@code flat_color}) apply sRGB-to-linear conversion
 * and premultiplied alpha internally.
 *
 * @see MsdfAtlas
 * @see MsdfFontManager
 * @see MsdfTextRenderer
 */
package dev.everydaythings.filament.text;
