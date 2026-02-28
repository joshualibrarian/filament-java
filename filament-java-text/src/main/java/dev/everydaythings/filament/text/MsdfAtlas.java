package dev.everydaythings.filament.text;

import dev.everydaythings.filament.Engine;
import dev.everydaythings.filament.Texture;
import dev.everydaythings.filament.TextureSampler;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.msdfgen.*;

import org.lwjgl.util.freetype.FreeType;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_LayerIterator;
import org.lwjgl.util.freetype.FT_Palette_Data;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.util.msdfgen.MSDFGen.*;
import static org.lwjgl.util.msdfgen.MSDFGenExt.*;

/**
 * Runtime MSDF font atlas generator using LWJGL's FreeType + msdfgen bindings.
 *
 * <p>Loads a TTF/OTF font, generates multi-channel signed distance fields for
 * each glyph, packs them into an atlas texture, and uploads to Filament.
 *
 * <p>The atlas can grow dynamically via {@link #ensureGlyphs(Engine, String)} when
 * new codepoints are encountered at runtime.
 *
 * @see MsdfFontManager
 * @see MsdfTextRenderer
 */
public class MsdfAtlas {

    private static final Logger log = Logger.getLogger(MsdfAtlas.class.getName());

    /** Pixel size of each glyph cell in the atlas. Larger values give higher SDF
     *  resolution, improving small-text rendering at the cost of atlas memory.
     *  64px with PX_RANGE=4 gives ~2.5 screen pixels of SDF range at 20px font size. */
    private static final int GLYPH_SIZE = 64;

    /** Pixel range for SDF — how many pixels the distance field extends beyond edges.
     *  Larger values give the shader more "room" to manipulate edges (bolden, AA).
     *  With GLYPH_SIZE=64, PX_RANGE=4 uses 8px per side for SDF, leaving 48px for glyph. */
    private static final double PX_RANGE = 4.0;

    /** Maximum atlas dimension. */
    private static final int MAX_ATLAS_SIZE = 4096;

    // ==================================================================================
    // Glyph Metrics
    // ==================================================================================

    /**
     * Per-glyph rendering metrics.
     *
     * @param codepoint    Unicode codepoint
     * @param advance      Horizontal advance in em units
     * @param planeLeft    Quad left edge in em units (relative to cursor)
     * @param planeBottom  Quad bottom edge in em units
     * @param planeRight   Quad right edge in em units
     * @param planeTop     Quad top edge in em units
     * @param uvLeft       Atlas UV left (0-1)
     * @param uvBottom     Atlas UV bottom (0-1)
     * @param uvRight      Atlas UV right (0-1)
     * @param uvTop        Atlas UV top (0-1)
     */
    public record GlyphMetrics(
            int codepoint,
            double advance,
            double planeLeft, double planeBottom, double planeRight, double planeTop,
            double uvLeft, double uvBottom, double uvRight, double uvTop
    ) {}

    // ==================================================================================
    // State
    // ==================================================================================

    private final Map<Integer, GlyphMetrics> glyphs = new HashMap<>();
    /** Glyph metrics keyed by glyph INDEX (for COLRv0 layer glyphs). */
    private final Map<Integer, GlyphMetrics> glyphsByIndex = new HashMap<>();
    private Texture texture;

    /** Atlas pixel data (RGBA, 4 bytes per pixel). */
    private byte[] atlasPixels;
    private int atlasWidth;
    private int atlasHeight;
    private int glyphsPerRow;
    private int nextSlot = 0;

    /** msdfgen FreeType handles (kept alive for dynamic glyph generation). */
    private long ftHandle;
    private long fontHandle;

    /** LWJGL FreeType face — kept alive for glyph presence checking via
     *  FT_Get_Char_Index (detects .notdef to avoid generating wrong glyphs). */
    private long ftLibHandle;
    private FT_Face ftFace;

    /** Font data buffer — MUST stay alive as long as fontHandle is valid.
     *  FreeType keeps an internal pointer to this memory. */
    private ByteBuffer fontDataBuffer;

    /** Font units per em (e.g. 2048 for TrueType, 1000 for CFF). */
    private double unitsPerEm = 1.0;

    /** Font ascent in em units (distance from baseline to top of tallest glyph). */
    private double ascent = 0.8;

    private double lineHeight = 1.0;

    /** Whether this font has a COLRv0 color table. */
    private boolean hasColorTable;

    /** CPAL palette colors (ARGB), indexed by color_index from COLR layer records. */
    private int[] paletteColors;

    // ==================================================================================
    // Factory
    // ==================================================================================

    /**
     * Generate an MSDF atlas from font data at runtime.
     *
     * @param engine   Filament engine for texture creation
     * @param fontData Raw TTF/OTF font file bytes
     * @param charset  Initial characters to generate (e.g. ASCII printable range)
     * @return New MsdfAtlas ready for rendering
     */
    public static MsdfAtlas generate(Engine engine, byte[] fontData, String charset) {
        MsdfAtlas atlas = new MsdfAtlas();
        atlas.initFreeType(fontData);

        // Pre-size atlas for charset
        int glyphCount = charset.codePoints().map(cp -> 1).sum();
        atlas.allocateAtlas(glyphCount);

        // Generate each glyph
        charset.codePoints().forEach(cp -> atlas.generateGlyph(cp));

        // Upload to Filament
        atlas.uploadTexture(engine);

        log.info(() -> String.format("MSDF atlas generated: %dx%d (%d glyphs, %d slots)",
                atlas.atlasWidth, atlas.atlasHeight, atlas.glyphs.size(), atlas.nextSlot));

        return atlas;
    }

    /**
     * Generate an atlas with the default ASCII printable charset.
     */
    public static MsdfAtlas generate(Engine engine, byte[] fontData) {
        // ASCII printable range (32-126) plus a few extras
        StringBuilder sb = new StringBuilder();
        for (int i = 32; i <= 126; i++) {
            sb.appendCodePoint(i);
        }
        return generate(engine, fontData, sb.toString());
    }

    // ==================================================================================
    // FreeType + msdfgen Init
    // ==================================================================================

    private void initFreeType(byte[] fontData) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Init msdfgen's FreeType
            PointerBuffer ftPtr = stack.mallocPointer(1);
            int result = msdf_ft_init(ftPtr);
            if (result != MSDF_SUCCESS) {
                throw new RuntimeException("Failed to init msdfgen FreeType: " + result);
            }
            ftHandle = ftPtr.get(0);

            // Load font from memory — keep buffer alive as field (FreeType holds a pointer)
            fontDataBuffer = ByteBuffer.allocateDirect(fontData.length)
                    .order(ByteOrder.nativeOrder());
            fontDataBuffer.put(fontData);
            fontDataBuffer.flip();

            PointerBuffer fontPtr = stack.mallocPointer(1);
            result = msdf_ft_load_font_data(ftHandle, fontDataBuffer, fontPtr);
            if (result != MSDF_SUCCESS) {
                throw new RuntimeException("Failed to load font data: " + result);
            }
            fontHandle = fontPtr.get(0);

            // Read font metrics via LWJGL FreeType (units_per_EM, lineHeight).
            // Also keep the FT_Face alive for glyph presence checking via
            // FT_Get_Char_Index (returns 0 for codepoints not in the font,
            // which prevents generating MSDF for .notdef glyphs).
            PointerBuffer ftLibPtr = stack.mallocPointer(1);
            if (FreeType.FT_Init_FreeType(ftLibPtr) == 0) {
                ftLibHandle = ftLibPtr.get(0);
                PointerBuffer facePtr = stack.mallocPointer(1);
                fontDataBuffer.rewind();
                if (FreeType.FT_New_Memory_Face(ftLibHandle, fontDataBuffer, 0, facePtr) == 0) {
                    ftFace = FT_Face.create(facePtr.get(0));
                    unitsPerEm = ftFace.units_per_EM();
                    ascent = (double) ftFace.ascender() / unitsPerEm;
                    lineHeight = (double) ftFace.height() / unitsPerEm;
                    log.info(() -> String.format("Font metrics: unitsPerEm=%.0f, ascent=%.3f, lineHeight=%.3f",
                            unitsPerEm, ascent, lineHeight));

                    // Check for COLRv0 color table and load CPAL palette
                    hasColorTable = FreeType.FT_HAS_COLOR(ftFace);
                    if (hasColorTable) {
                        loadPalette();
                    }
                }
                fontDataBuffer.rewind();
            }
        }
    }

    // ==================================================================================
    // Atlas Allocation
    // ==================================================================================

    private void allocateAtlas(int estimatedGlyphs) {
        // Add padding for future growth
        int totalSlots = Math.max(estimatedGlyphs + 32, 128);
        glyphsPerRow = (int) Math.ceil(Math.sqrt(totalSlots));
        atlasWidth = glyphsPerRow * GLYPH_SIZE;
        atlasHeight = glyphsPerRow * GLYPH_SIZE;

        // Clamp to max size
        if (atlasWidth > MAX_ATLAS_SIZE) {
            atlasWidth = MAX_ATLAS_SIZE;
            glyphsPerRow = atlasWidth / GLYPH_SIZE;
            int rows = (totalSlots + glyphsPerRow - 1) / glyphsPerRow;
            atlasHeight = Math.min(rows * GLYPH_SIZE, MAX_ATLAS_SIZE);
        }

        atlasPixels = new byte[atlasWidth * atlasHeight * 4]; // RGBA
    }

    // ==================================================================================
    // Glyph Generation
    // ==================================================================================

    /**
     * Check if the font actually contains this codepoint (not .notdef).
     * FreeType's FT_Get_Char_Index returns 0 when the codepoint has no
     * mapping in the font's cmap — msdfgen would generate the .notdef
     * glyph in that case, which produces garbled output.
     */
    private boolean hasGlyph(int codepoint) {
        if (ftFace == null) return true; // can't check, assume present
        return FreeType.FT_Get_Char_Index(ftFace, codepoint) != 0;
    }

    private void generateGlyph(int codepoint) {
        if (glyphs.containsKey(codepoint)) return;

        // Skip codepoints not in the font (avoids generating .notdef)
        if (!hasGlyph(codepoint)) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Load glyph shape from font
            DoubleBuffer advanceBuf = stack.mallocDouble(1);
            PointerBuffer shapePtr = stack.mallocPointer(1);

            int result = msdf_ft_font_load_glyph(
                    fontHandle, codepoint, MSDF_FONT_SCALING_NONE,
                    advanceBuf, shapePtr);

            if (result != MSDF_SUCCESS) {
                // Glyph not found in font — skip silently
                return;
            }

            long shape = shapePtr.get(0);
            double advance = advanceBuf.get(0);

            try {
                // Prepare shape for MSDF generation
                msdf_shape_normalize(shape);
                msdf_shape_edge_colors_simple(shape, 3.0);

                // Get shape bounds
                MSDFGenBounds bounds = MSDFGenBounds.calloc(stack);
                msdf_shape_get_bounds(shape, bounds);

                double l = bounds.l();
                double b = bounds.b();
                double r = bounds.r();
                double t = bounds.t();

                // Handle empty glyphs (space, etc.)
                if (r <= l || t <= b) {
                    glyphs.put(codepoint, new GlyphMetrics(
                            codepoint, advance / unitsPerEm, 0, 0, 0, 0, 0, 0, 0, 0));
                    return;
                }

                // Compute transform: map shape bounds → glyph cell with padding.
                //
                // msdfgen's Projection convention: bitmap = scale * (shape + translate)
                // where translate is in SHAPE SPACE (font units), not bitmap space.
                double padding = PX_RANGE;
                double shapeW = r - l;
                double shapeH = t - b;

                // Scale to fit within glyph cell, leaving room for px range
                double scale = (GLYPH_SIZE - 2 * padding) / Math.max(shapeW, shapeH);
                // Center the shape in the cell. Convention: bitmap = scale * (shape + translate)
                double translateX = GLYPH_SIZE / (2.0 * scale) - (l + r) / 2.0;
                double translateY = GLYPH_SIZE / (2.0 * scale) - (b + t) / 2.0;

                // Build transform: scale + translation + distance mapping.
                //
                // The C++ DistanceMapping(Range) constructor maps the MIDPOINT
                // of [lower, upper] to 0.5, lower to 0.0, upper to 1.0:
                //   scale_dm = 1/(upper-lower)
                //   translate_dm = 0.5 - 0.5*(upper+lower)/(upper-lower)
                //   mapped(d) = d * scale_dm + translate_dm
                //
                // For MSDF, edge (d=0) must map to 0.5, so the midpoint must be 0.
                // Use a SYMMETRIC range: [-R, +R] where R = PX_RANGE / scale.
                //
                // Distances are in font units (shape space). PX_RANGE pixels in
                // bitmap space = PX_RANGE / scale font units.
                //
                // Result: edge→0.5, ±PX_RANGE pixels from edge→0..1
                double rangeInFontUnits = PX_RANGE / scale;
                MSDFGenTransform transform = MSDFGenTransform.calloc(stack);
                transform.scale(v -> v.x(scale).y(scale));
                transform.translation(v -> v.x(translateX).y(translateY));
                transform.distance_mapping(dm -> dm
                        .lower(-rangeInFontUnits)
                        .upper(rangeInFontUnits));

                // Allocate output bitmap (MTSDF = 4 channels: RGB multi-channel + A true distance)
                MSDFGenBitmap bitmap = MSDFGenBitmap.calloc(stack);
                result = msdf_bitmap_alloc(MSDF_BITMAP_TYPE_MTSDF, GLYPH_SIZE, GLYPH_SIZE, bitmap);
                if (result != MSDF_SUCCESS) {
                    log.warning(() -> "Failed to allocate MTSDF bitmap for codepoint " + codepoint);
                    return;
                }

                try {
                    // Generate MTSDF (4-channel: RGB multi-channel + A true signed distance).
                    // The 4th channel resolves median-of-three artifacts at curve-line
                    // junctions (letters like h, b, p, y, g, d).
                    result = msdf_generate_mtsdf(bitmap, shape, transform);
                    if (result != MSDF_SUCCESS) {
                        log.warning(() -> "Failed to generate MTSDF for codepoint " + codepoint);
                        return;
                    }

                    // Apply error correction
                    msdf_error_correction(bitmap, shape, transform);

                    // Read pixels and copy into atlas
                    PointerBuffer pixelsPtr = stack.mallocPointer(1);
                    msdf_bitmap_get_pixels(bitmap, pixelsPtr);
                    long pixelsAddr = pixelsPtr.get(0);

                    copyGlyphToAtlas(pixelsAddr, codepoint, l, b, r, t, advance, scale,
                            translateX, translateY);
                } finally {
                    msdf_bitmap_free(bitmap);
                }
            } finally {
                msdf_shape_free(shape);
            }
        }
    }

    private void copyGlyphToAtlas(long pixelsAddr, int codepoint,
                                   double shapeL, double shapeB, double shapeR, double shapeT,
                                   double advance, double scale,
                                   double translateX, double translateY) {
        // Determine atlas slot position
        int slot = nextSlot++;
        int slotX = (slot % glyphsPerRow) * GLYPH_SIZE;
        int slotY = (slot / glyphsPerRow) * GLYPH_SIZE;

        // Copy pixel data (MTSDF outputs float RGBA, 4 floats per pixel).
        // RGB = multi-channel SDF, A = true signed distance.
        // msdfgen bitmap is bottom-to-top, we store top-to-bottom in atlas.
        //
        // With dm=Range(-R, +R), the DistanceMapping gives:
        //   mapped(d) = d / (2*R) + 0.5
        // Edge (d=0) → 0.5, inside → >0.5, outside → <0.5.
        // Clamp to [0,1] and quantize to bytes.
        for (int y = 0; y < GLYPH_SIZE; y++) {
            for (int x = 0; x < GLYPH_SIZE; x++) {
                int srcIdx = (y * GLYPH_SIZE + x) * 4; // 4 floats per pixel (MTSDF)
                long srcAddr = pixelsAddr + (long) srcIdx * Float.BYTES;

                float fr = floatFromAddr(srcAddr);
                float fg = floatFromAddr(srcAddr + Float.BYTES);
                float fb = floatFromAddr(srcAddr + 2L * Float.BYTES);
                float fa = floatFromAddr(srcAddr + 3L * Float.BYTES);

                // Clamp and quantize to bytes
                byte br = (byte)(Math.max(0f, Math.min(1f, fr)) * 255 + 0.5f);
                byte bg = (byte)(Math.max(0f, Math.min(1f, fg)) * 255 + 0.5f);
                byte bb = (byte)(Math.max(0f, Math.min(1f, fb)) * 255 + 0.5f);
                byte ba = (byte)(Math.max(0f, Math.min(1f, fa)) * 255 + 0.5f);

                // Atlas Y is flipped (msdfgen is bottom-up, atlas is top-down)
                int atlasY = slotY + (GLYPH_SIZE - 1 - y);
                int atlasX = slotX + x;
                int dstIdx = (atlasY * atlasWidth + atlasX) * 4;

                if (dstIdx + 3 < atlasPixels.length) {
                    atlasPixels[dstIdx] = br;
                    atlasPixels[dstIdx + 1] = bg;
                    atlasPixels[dstIdx + 2] = bb;
                    atlasPixels[dstIdx + 3] = ba; // true signed distance (MTSDF 4th channel)
                }
            }
        }

        // Tight plane bounds: actual glyph shape + PX_RANGE padding (not the full cell).
        // Using the full 48px cell would cause massive quad overlap between adjacent glyphs.
        double emScale = 1.0 / unitsPerEm;
        double pxPadInShape = PX_RANGE / scale; // PX_RANGE pixels in shape/font units

        double tightL = shapeL - pxPadInShape;
        double tightR = shapeR + pxPadInShape;
        double tightB = shapeB - pxPadInShape;
        double tightT = shapeT + pxPadInShape;

        // Plane bounds in em units (relative to pen position at origin)
        double planeL = tightL * emScale;
        double planeR = tightR * emScale;
        double planeB = tightB * emScale;
        double planeT = tightT * emScale;

        // Where do tight bounds fall in bitmap space?
        // Convention: bitmap = scale * (shape + translate)
        double bmpL = Math.max(0, scale * (tightL + translateX));
        double bmpR = Math.min(GLYPH_SIZE, scale * (tightR + translateX));
        double bmpB = Math.max(0, scale * (tightB + translateY));
        double bmpT = Math.min(GLYPH_SIZE, scale * (tightT + translateY));

        // UV coordinates within the atlas for the tight region.
        // Inset by 0.5 texels to prevent bilinear filtering from sampling
        // data from adjacent glyph cells in the atlas.
        double halfTexelX = 0.5 / atlasWidth;
        double halfTexelY = 0.5 / atlasHeight;

        // X axis: straightforward + half-texel inset
        double uvL = (slotX + bmpL) / atlasWidth + halfTexelX;
        double uvR = (slotX + bmpR) / atlasWidth - halfTexelX;
        // Y axis: atlas stores top-down (row 0 = top). UV V=0 is top-left
        // of texture. Stored UVs are in image-space (small = top of atlas).
        // The renderer V-flips (1.0 - v) to get OpenGL convention.
        double uvTop = (slotY + (GLYPH_SIZE - bmpT)) / atlasHeight + halfTexelY;
        double uvBottom = (slotY + (GLYPH_SIZE - bmpB)) / atlasHeight - halfTexelY;

        glyphs.put(codepoint, new GlyphMetrics(
                codepoint, advance * emScale,
                planeL, planeB, planeR, planeT,
                uvL, uvBottom, uvR, uvTop));
    }

    private static float floatFromAddr(long addr) {
        return org.lwjgl.system.MemoryUtil.memGetFloat(addr);
    }

    // ==================================================================================
    // Filament Texture Upload
    // ==================================================================================

    private void uploadTexture(Engine engine) {
        // Create Filament texture (RGBA8, NO mipmaps — MSDF textures must not
        // be mipmapped because the multi-channel encoding breaks when averaged).
        // RGBA8 is used instead of RGB8 because RGB8 is poorly supported on
        // Vulkan (many drivers don't support VK_FORMAT_R8G8B8_UNORM and may
        // fall back to sRGB interpretation, corrupting distance field values).
        texture = new Texture.Builder()
                .width(atlasWidth)
                .height(atlasHeight)
                .format(Texture.InternalFormat.RGBA8)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .levels(1)
                .build(engine);

        ByteBuffer pixelBuf = ByteBuffer.allocateDirect(atlasPixels.length)
                .order(ByteOrder.nativeOrder());
        pixelBuf.put(atlasPixels);
        pixelBuf.flip();

        texture.setImage(engine, 0,
                new Texture.PixelBufferDescriptor(
                        pixelBuf,
                        Texture.Format.RGBA,
                        Texture.Type.UBYTE));
    }

    // ==================================================================================
    // Dynamic Glyph Generation
    // ==================================================================================

    /**
     * Ensure all codepoints in the given text are in the atlas.
     * Generates missing glyphs and re-uploads the texture if needed.
     *
     * @param engine Filament engine (for texture re-upload)
     * @param text   Text containing codepoints to ensure
     * @return true if atlas was modified (texture re-uploaded)
     */
    public boolean ensureGlyphs(Engine engine, String text) {
        int before = nextSlot;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!glyphs.containsKey(cp) && fontHandle != 0) {
                generateGlyph(cp);
            }
            i += Character.charCount(cp);
        }

        boolean modified = (nextSlot != before);
        if (modified) {
            if (texture != null) {
                engine.destroyTexture(texture);
            }
            uploadTexture(engine);
        }

        return modified;
    }

    // ==================================================================================
    // Debug
    // ==================================================================================

    /**
     * Dump the atlas to a PPM file for visual inspection.
     * PPM is a simple text-based image format viewable by most image viewers.
     */
    public void debugDumpAtlas(String path) {
        if (atlasPixels == null) return;
        try (var out = new java.io.FileOutputStream(path)) {
            String header = "P6\n" + atlasWidth + " " + atlasHeight + "\n255\n";
            out.write(header.getBytes());
            // Write RGB only (skip alpha) for PPM format
            for (int i = 0; i < atlasPixels.length; i += 4) {
                out.write(atlasPixels[i]);     // R
                out.write(atlasPixels[i + 1]); // G
                out.write(atlasPixels[i + 2]); // B
            }
            log.info(() -> String.format("Atlas dumped to: %s (%dx%d, %d glyphs)",
                    path, atlasWidth, atlasHeight, glyphs.size()));
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to dump atlas: " + e.getMessage(), e);
        }
    }

    // ==================================================================================
    // COLRv0 Color Layer Support
    // ==================================================================================

    /**
     * Load CPAL palette 0 into {@link #paletteColors}.
     * Called during init when FT_HAS_COLOR is true.
     */
    private void loadPalette() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FT_Palette_Data paletteData = FT_Palette_Data.calloc(stack);
            int dataResult = FreeType.FT_Palette_Data_Get(ftFace, paletteData);
            if (dataResult != 0) {
                log.warning(() -> "FT_Palette_Data_Get failed: " + dataResult);
                hasColorTable = false;
                return;
            }

            int numEntries = paletteData.num_palette_entries() & 0xFFFF;
            if (numEntries == 0) {
                hasColorTable = false;
                return;
            }

            // Select palette 0 — returns pointer to array of FT_Color
            PointerBuffer palettePtr = stack.mallocPointer(1);
            int selectResult = FreeType.FT_Palette_Select(ftFace, (short) 0, palettePtr);
            if (selectResult != 0) {
                log.warning(() -> "FT_Palette_Select failed: " + selectResult);
                hasColorTable = false;
                return;
            }

            // Read FT_Color array (each entry is 4 bytes: BGRA)
            long paletteAddr = palettePtr.get(0);
            paletteColors = new int[numEntries];
            for (int i = 0; i < numEntries; i++) {
                // FT_Color is { blue, green, red, alpha } — 1 byte each
                long addr = paletteAddr + (long) i * 4;
                int b = org.lwjgl.system.MemoryUtil.memGetByte(addr) & 0xFF;
                int g = org.lwjgl.system.MemoryUtil.memGetByte(addr + 1) & 0xFF;
                int r = org.lwjgl.system.MemoryUtil.memGetByte(addr + 2) & 0xFF;
                int a = org.lwjgl.system.MemoryUtil.memGetByte(addr + 3) & 0xFF;
                paletteColors[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }

            log.info(() -> String.format("Loaded CPAL palette: %d entries", numEntries));
        }
    }

    /**
     * Get the COLRv0 color layers for a codepoint.
     *
     * @return color layer info, or null if this codepoint has no color layers
     */
    public ColrGlyphInfo getColorLayers(int codepoint) {
        if (!hasColorTable || ftFace == null || paletteColors == null) return null;

        // Get the base glyph index for this codepoint
        int baseGlyphIndex = (int) FreeType.FT_Get_Char_Index(ftFace, codepoint);
        if (baseGlyphIndex == 0) return null;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer glyphIndexBuf = stack.mallocInt(1);
            IntBuffer colorIndexBuf = stack.mallocInt(1);
            FT_LayerIterator iterator = FT_LayerIterator.calloc(stack);

            List<ColrGlyphInfo.Layer> layers = new ArrayList<>();
            while (FreeType.FT_Get_Color_Glyph_Layer(ftFace, baseGlyphIndex,
                    glyphIndexBuf, colorIndexBuf, iterator)) {
                int layerGlyphIndex = glyphIndexBuf.get(0);
                int colorIndex = colorIndexBuf.get(0) & 0xFFFF;

                int argbColor;
                if (colorIndex == 0xFFFF) {
                    // Foreground color sentinel
                    argbColor = ColrGlyphInfo.FOREGROUND_COLOR;
                } else if (colorIndex < paletteColors.length) {
                    argbColor = paletteColors[colorIndex];
                } else {
                    argbColor = 0xFF000000; // fallback: opaque black
                }

                layers.add(new ColrGlyphInfo.Layer(layerGlyphIndex, argbColor));
            }

            return layers.isEmpty() ? null : new ColrGlyphInfo(List.copyOf(layers));
        }
    }

    /**
     * Generate MSDF for a glyph by its glyph INDEX (not codepoint).
     * Used for COLRv0 layer glyphs that are referenced by index.
     *
     * @return metrics for the generated glyph, or null on failure
     */
    public GlyphMetrics generateGlyphByIndex(int glyphIndex) {
        if (glyphsByIndex.containsKey(glyphIndex)) return glyphsByIndex.get(glyphIndex);
        if (fontHandle == 0) return null;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer advanceBuf = stack.mallocDouble(1);
            PointerBuffer shapePtr = stack.mallocPointer(1);

            int result = msdf_ft_font_load_glyph_by_index(
                    fontHandle, glyphIndex, MSDF_FONT_SCALING_NONE,
                    advanceBuf, shapePtr);

            if (result != MSDF_SUCCESS) return null;

            long shape = shapePtr.get(0);
            double advance = advanceBuf.get(0);

            try {
                msdf_shape_normalize(shape);
                msdf_shape_edge_colors_simple(shape, 3.0);

                MSDFGenBounds bounds = MSDFGenBounds.calloc(stack);
                msdf_shape_get_bounds(shape, bounds);

                double l = bounds.l(), b = bounds.b(), r = bounds.r(), t = bounds.t();

                if (r <= l || t <= b) {
                    var metrics = new GlyphMetrics(
                            glyphIndex, advance / unitsPerEm, 0, 0, 0, 0, 0, 0, 0, 0);
                    glyphsByIndex.put(glyphIndex, metrics);
                    return metrics;
                }

                double padding = PX_RANGE;
                double shapeW = r - l;
                double shapeH = t - b;
                double scale = (GLYPH_SIZE - 2 * padding) / Math.max(shapeW, shapeH);
                double translateX = GLYPH_SIZE / (2.0 * scale) - (l + r) / 2.0;
                double translateY = GLYPH_SIZE / (2.0 * scale) - (b + t) / 2.0;

                double rangeInFontUnits = PX_RANGE / scale;
                MSDFGenTransform transform = MSDFGenTransform.calloc(stack);
                transform.scale(v -> v.x(scale).y(scale));
                transform.translation(v -> v.x(translateX).y(translateY));
                transform.distance_mapping(dm -> dm.lower(-rangeInFontUnits).upper(rangeInFontUnits));

                MSDFGenBitmap bitmap = MSDFGenBitmap.calloc(stack);
                result = msdf_bitmap_alloc(MSDF_BITMAP_TYPE_MTSDF, GLYPH_SIZE, GLYPH_SIZE, bitmap);
                if (result != MSDF_SUCCESS) return null;

                try {
                    result = msdf_generate_mtsdf(bitmap, shape, transform);
                    if (result != MSDF_SUCCESS) return null;

                    msdf_error_correction(bitmap, shape, transform);

                    PointerBuffer pixelsPtr = stack.mallocPointer(1);
                    msdf_bitmap_get_pixels(bitmap, pixelsPtr);
                    long pixelsAddr = pixelsPtr.get(0);

                    var metrics = copyGlyphToAtlasByIndex(pixelsAddr, glyphIndex,
                            l, b, r, t, advance, scale, translateX, translateY);
                    glyphsByIndex.put(glyphIndex, metrics);
                    return metrics;
                } finally {
                    msdf_bitmap_free(bitmap);
                }
            } finally {
                msdf_shape_free(shape);
            }
        }
    }

    /**
     * Copy a glyph-by-index into the atlas and return its metrics.
     * Similar to {@link #copyGlyphToAtlas} but stores in {@link #glyphsByIndex}.
     */
    private GlyphMetrics copyGlyphToAtlasByIndex(long pixelsAddr, int glyphIndex,
                                                  double shapeL, double shapeB,
                                                  double shapeR, double shapeT,
                                                  double advance, double scale,
                                                  double translateX, double translateY) {
        int slot = nextSlot++;
        int slotX = (slot % glyphsPerRow) * GLYPH_SIZE;
        int slotY = (slot / glyphsPerRow) * GLYPH_SIZE;

        for (int y = 0; y < GLYPH_SIZE; y++) {
            for (int x = 0; x < GLYPH_SIZE; x++) {
                int srcIdx = (y * GLYPH_SIZE + x) * 4;
                long srcAddr = pixelsAddr + (long) srcIdx * Float.BYTES;

                float fr = floatFromAddr(srcAddr);
                float fg = floatFromAddr(srcAddr + Float.BYTES);
                float fb = floatFromAddr(srcAddr + 2L * Float.BYTES);
                float fa = floatFromAddr(srcAddr + 3L * Float.BYTES);

                byte br = (byte)(Math.max(0f, Math.min(1f, fr)) * 255 + 0.5f);
                byte bg = (byte)(Math.max(0f, Math.min(1f, fg)) * 255 + 0.5f);
                byte bb = (byte)(Math.max(0f, Math.min(1f, fb)) * 255 + 0.5f);
                byte ba = (byte)(Math.max(0f, Math.min(1f, fa)) * 255 + 0.5f);

                int atlasY = slotY + (GLYPH_SIZE - 1 - y);
                int atlasX = slotX + x;
                int dstIdx = (atlasY * atlasWidth + atlasX) * 4;

                if (dstIdx + 3 < atlasPixels.length) {
                    atlasPixels[dstIdx] = br;
                    atlasPixels[dstIdx + 1] = bg;
                    atlasPixels[dstIdx + 2] = bb;
                    atlasPixels[dstIdx + 3] = ba;
                }
            }
        }

        double emScale = 1.0 / unitsPerEm;
        double pxPadInShape = PX_RANGE / scale;
        double tightL = shapeL - pxPadInShape, tightR = shapeR + pxPadInShape;
        double tightB = shapeB - pxPadInShape, tightT = shapeT + pxPadInShape;

        double planeL = tightL * emScale, planeR = tightR * emScale;
        double planeB = tightB * emScale, planeT = tightT * emScale;

        double bmpL = Math.max(0, scale * (tightL + translateX));
        double bmpR = Math.min(GLYPH_SIZE, scale * (tightR + translateX));
        double bmpB = Math.max(0, scale * (tightB + translateY));
        double bmpT = Math.min(GLYPH_SIZE, scale * (tightT + translateY));

        double halfTexelX = 0.5 / atlasWidth;
        double halfTexelY = 0.5 / atlasHeight;
        double uvL = (slotX + bmpL) / atlasWidth + halfTexelX;
        double uvR = (slotX + bmpR) / atlasWidth - halfTexelX;
        double uvTop = (slotY + (GLYPH_SIZE - bmpT)) / atlasHeight + halfTexelY;
        double uvBottom = (slotY + (GLYPH_SIZE - bmpB)) / atlasHeight - halfTexelY;

        return new GlyphMetrics(glyphIndex, advance * emScale,
                planeL, planeB, planeR, planeT, uvL, uvBottom, uvR, uvTop);
    }

    /** Get metrics for a glyph by index, or null if not generated. */
    public GlyphMetrics glyphByIndex(int glyphIndex) {
        return glyphsByIndex.get(glyphIndex);
    }

    /** Whether this font has a COLRv0 color table. */
    public boolean hasColorTable() {
        return hasColorTable;
    }

    /**
     * Force re-upload of the atlas texture to Filament.
     * Call after generating layer glyphs via {@link #generateGlyphByIndex(int)}.
     */
    public void reuploadTexture(Engine engine) {
        if (texture != null) {
            engine.destroyTexture(texture);
        }
        uploadTexture(engine);
    }

    // ==================================================================================
    // Queries
    // ==================================================================================

    /** Get metrics for a codepoint, or null if not in atlas. */
    public GlyphMetrics glyph(int codepoint) {
        return glyphs.get(codepoint);
    }

    /** The Filament texture containing the MSDF atlas. */
    public Texture texture() {
        return texture;
    }

    /** Font ascent in em units (baseline to top of tallest glyph). */
    public double ascent() {
        return ascent;
    }

    /** Line height in em units. */
    public double lineHeight() {
        return lineHeight;
    }

    /** Total pixel range for MSDF (both sides of edge, needed by shader).
     *  Matches msdf-atlas-gen convention: full range from sd=0 to sd=1. */
    public double pxRange() {
        return 2 * PX_RANGE;
    }

    /** Glyph cell size in pixels. */
    public int glyphSize() {
        return GLYPH_SIZE;
    }

    /** Atlas width in pixels (needed by shader for screenPxRange computation). */
    public int atlasWidth() {
        return atlasWidth;
    }

    /** Atlas height in pixels. */
    public int atlasHeight() {
        return atlasHeight;
    }

    /**
     * Measure text width in em units.
     */
    public double measureWidth(String text) {
        double width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            GlyphMetrics g = glyphs.get(cp);
            if (g != null) {
                width += g.advance();
            }
            i += Character.charCount(cp);
        }
        return width;
    }

    // ==================================================================================
    // Cleanup
    // ==================================================================================

    /**
     * Clean up all resources.
     */
    public void destroy(Engine engine) {
        if (texture != null) {
            engine.destroyTexture(texture);
            texture = null;
        }
        if (fontHandle != 0) {
            msdf_ft_font_destroy(fontHandle);
            fontHandle = 0;
        }
        if (ftHandle != 0) {
            msdf_ft_deinit(ftHandle);
            ftHandle = 0;
        }
        if (ftFace != null) {
            FreeType.FT_Done_Face(ftFace);
            ftFace = null;
        }
        if (ftLibHandle != 0) {
            FreeType.FT_Done_FreeType(ftLibHandle);
            ftLibHandle = 0;
        }
        fontDataBuffer = null;
        atlasPixels = null;
        glyphs.clear();
        glyphsByIndex.clear();
    }
}
