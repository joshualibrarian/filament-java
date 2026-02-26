package dev.everydaythings.filament.text;

import dev.everydaythings.filament.Engine;
import dev.everydaythings.filament.Material;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.logging.Logger;

/**
 * Loads the bundled MSDF text and flat color materials for Filament rendering.
 *
 * <p>The two materials handle the most common text rendering needs:
 * <ul>
 *   <li>{@code msdf_text} — MSDF glyph rendering with sRGB→linear conversion,
 *       bold bias, and premultiplied alpha. Expects vertex colors in ABGR format.</li>
 *   <li>{@code flat_color} — unlit colored quads with sRGB→linear conversion
 *       and premultiplied alpha. Used for backgrounds, borders, shapes.</li>
 * </ul>
 *
 * <p>Both materials expect Filament's standard vertex color format (ABGR byte order).
 * Use {@link #argbToAbgr(int)} to convert from standard Java ARGB format.
 *
 * @see MsdfAtlas
 * @see MsdfFontManager
 */
public class MsdfTextRenderer {

    private static final Logger log = Logger.getLogger(MsdfTextRenderer.class.getName());

    private final Engine engine;
    private Material msdfTextMaterial;
    private Material flatColorMaterial;

    /**
     * Create a renderer and load the bundled materials.
     *
     * @param engine Filament engine
     */
    public MsdfTextRenderer(Engine engine) {
        this.engine = engine;
        this.msdfTextMaterial = loadMaterial("materials/msdf_text.filamat");
        this.flatColorMaterial = loadMaterial("materials/flat_color.filamat");
    }

    /** The MSDF text material (for glyph quads). */
    public Material msdfTextMaterial() {
        return msdfTextMaterial;
    }

    /** The flat color material (for backgrounds, borders, shapes). */
    public Material flatColorMaterial() {
        return flatColorMaterial;
    }

    /**
     * Convert ARGB (Java/Skia standard) to ABGR (Filament vertex color format).
     *
     * <p>Filament's vertex color attribute expects bytes in ABGR order when
     * using {@code VertexBuffer.AttributeType.UBYTE4} with normalization.
     */
    public static int argbToAbgr(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /**
     * Destroy both materials. Call before destroying the engine.
     */
    public void destroy() {
        if (msdfTextMaterial != null) {
            engine.destroyMaterial(msdfTextMaterial);
            msdfTextMaterial = null;
        }
        if (flatColorMaterial != null) {
            engine.destroyMaterial(flatColorMaterial);
            flatColorMaterial = null;
        }
    }

    private Material loadMaterial(String resourcePath) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Material resource not found: " + resourcePath);
            }
            byte[] data = in.readAllBytes();
            log.info(() -> String.format("Loaded material '%s': %d bytes", resourcePath, data.length));
            ByteBuffer buf = ByteBuffer.allocateDirect(data.length)
                    .order(ByteOrder.nativeOrder());
            buf.put(data);
            buf.flip();
            return new Material.Builder()
                    .payload(buf, buf.remaining())
                    .build(engine);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load material: " + resourcePath, e);
        }
    }
}
