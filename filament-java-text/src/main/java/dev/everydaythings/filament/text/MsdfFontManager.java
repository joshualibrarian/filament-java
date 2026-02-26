package dev.everydaythings.filament.text;

import dev.everydaythings.filament.Engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Manages multiple MSDF font atlases with fallback chain support.
 *
 * <p>Fonts are registered by name and added to a fallback chain. When resolving
 * a glyph, the chain is walked in registration order until a font containing
 * that glyph is found — just like browser font-family fallback.
 *
 * @see MsdfAtlas
 * @see MsdfTextRenderer
 */
public class MsdfFontManager {

    private static final Logger log = Logger.getLogger(MsdfFontManager.class.getName());

    private final Engine engine;
    private final Map<String, MsdfAtlas> atlases = new HashMap<>();

    /** Ordered fallback chain — first atlas with the glyph wins. */
    private final List<MsdfAtlas> fallbackChain = new ArrayList<>();

    /**
     * A resolved glyph: the atlas it came from + the metrics.
     * Needed because different glyphs may come from different atlases
     * (different textures), so the painter needs to know which atlas
     * to bind for each glyph quad.
     */
    public record ResolvedGlyph(MsdfAtlas atlas, MsdfAtlas.GlyphMetrics metrics) {}

    public MsdfFontManager(Engine engine) {
        this.engine = engine;
    }

    // ==================================================================================
    // Font Registration
    // ==================================================================================

    /**
     * Register a font from raw TTF/OTF bytes.
     * The font is appended to the fallback chain.
     *
     * @param name     logical name for lookup (e.g., "mono", "symbols")
     * @param fontData raw font file bytes
     * @return the generated atlas
     */
    public MsdfAtlas registerFont(String name, byte[] fontData) {
        MsdfAtlas atlas = MsdfAtlas.generate(engine, fontData);
        atlases.put(name, atlas);
        fallbackChain.add(atlas);
        log.info(() -> String.format("Registered MSDF font: %s (chain position %d)", name, fallbackChain.size()));
        return atlas;
    }

    /**
     * Register a font from a classpath resource.
     */
    public MsdfAtlas registerFont(String name, String resourcePath) {
        byte[] data = loadResource(resourcePath);
        if (data == null) {
            log.warning(() -> "Font resource not found: " + resourcePath);
            return null;
        }
        return registerFont(name, data);
    }

    /**
     * Load default fonts with fallback chain:
     * <ol>
     *   <li>FiraCode Nerd Font Mono (primary — ASCII + Nerd Font icons)</li>
     *   <li>Bundled Symbols Nerd Font (Nerd Font-specific icons in PUA)</li>
     *   <li>Symbola (outline emoji — visible at any scale)</li>
     *   <li>Noto Sans Symbols2 (miscellaneous symbols)</li>
     *   <li>DejaVu Sans (broad Unicode coverage fallback)</li>
     * </ol>
     *
     * <p>Each font in the chain only generates MSDF for glyphs it actually
     * contains (hasGlyph check prevents .notdef rendering).
     */
    public void loadDefaultFont() {
        // Primary: monospace font with Nerd Font icons
        String home = System.getProperty("user.home", "");
        String[] primaryPaths = {
                home + "/.local/share/fonts/FiraCode/FiraCodeNerdFontMono-Regular.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf",
                "/usr/share/fonts/TTF/DejaVuSansMono.ttf",
                "/usr/share/fonts/truetype/freefont/FreeMono.ttf",
        };
        loadFirstAvailable("default", primaryPaths);

        // Nerd Font Symbols (powerline, devicons, codicons, etc.)
        byte[] symbolsData = loadResource("fonts/SymbolsNerdFontMono-Regular.ttf");
        if (symbolsData != null) {
            registerFont("symbols", symbolsData);
            log.info("Fallback: bundled Symbols Nerd Font Mono");
        }

        // Emoji fallback: Symbola has TrueType outline glyphs for standard
        // Unicode emoji, unlike NotoColorEmoji which is bitmap.
        String[] emojiFallbackPaths = {
                "/usr/share/fonts/truetype/ancient-scripts/Symbola_hint.ttf",
                "/usr/share/fonts/truetype/ancient-scripts/Symbola.ttf",
                "/usr/share/fonts/truetype/noto/NotoSansSymbols2-Regular.ttf",
        };
        loadFirstAvailable("emoji", emojiFallbackPaths);

        // Broad Unicode coverage fallback
        String[] broadFallbackPaths = {
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/freefont/FreeSans.ttf",
        };
        loadFirstAvailable("unicode", broadFallbackPaths);

        if (fallbackChain.isEmpty()) {
            log.warning("No fonts found for MSDF rendering");
        } else {
            log.info(() -> "MSDF font fallback chain: " + fallbackChain.size() + " fonts");
        }
    }

    private void loadFirstAvailable(String name, String[] paths) {
        for (String path : paths) {
            try {
                java.io.File f = new java.io.File(path);
                if (f.exists()) {
                    byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
                    registerFont(name, data);
                    log.info(() -> String.format("MSDF font '%s': %s", name, path));
                    return;
                }
            } catch (IOException e) {
                // try next
            }
        }
        log.fine(() -> String.format("No font found for '%s', tried %d paths", name, paths.length));
    }

    // ==================================================================================
    // Glyph Resolution (Fallback Chain)
    // ==================================================================================

    /**
     * Resolve a glyph by walking the fallback chain.
     * Ensures the glyph is generated in the first atlas whose font contains it.
     *
     * @return resolved glyph (atlas + metrics), or null if no font has it
     */
    public ResolvedGlyph resolveGlyph(int codepoint) {
        for (MsdfAtlas atlas : fallbackChain) {
            // Check if already generated
            MsdfAtlas.GlyphMetrics g = atlas.glyph(codepoint);
            if (g != null) {
                return new ResolvedGlyph(atlas, g);
            }
        }

        // Not in any atlas yet — try generating in each font
        for (MsdfAtlas atlas : fallbackChain) {
            atlas.ensureGlyphs(engine, new String(Character.toChars(codepoint)));
            MsdfAtlas.GlyphMetrics g = atlas.glyph(codepoint);
            if (g != null) {
                return new ResolvedGlyph(atlas, g);
            }
        }

        return null;
    }

    /**
     * Ensure all codepoints in text are resolved across the fallback chain.
     */
    public void ensureGlyphs(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            resolveGlyph(cp);
            i += Character.charCount(cp);
        }
    }

    /**
     * Measure text width in pixels using the fallback chain.
     */
    public float measureWidth(String text, float fontSize) {
        float width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            ResolvedGlyph rg = resolveGlyph(cp);
            if (rg != null) {
                width += (float)(rg.metrics().advance() * fontSize);
            }
            i += Character.charCount(cp);
        }
        return width;
    }

    // ==================================================================================
    // Atlas Access
    // ==================================================================================

    /** Get atlas by name. */
    public MsdfAtlas atlas(String name) {
        return atlases.get(name);
    }

    /** Get the first atlas in the chain (primary font). */
    public MsdfAtlas defaultAtlas() {
        return fallbackChain.isEmpty() ? null : fallbackChain.getFirst();
    }

    /** The full fallback chain. */
    public List<MsdfAtlas> fallbackChain() {
        return fallbackChain;
    }

    // ==================================================================================
    // Cleanup
    // ==================================================================================

    public void destroy() {
        for (MsdfAtlas atlas : atlases.values()) {
            atlas.destroy(engine);
        }
        atlases.clear();
        fallbackChain.clear();
    }

    // ==================================================================================
    // Private
    // ==================================================================================

    private byte[] loadResource(String resourcePath) {
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) return null;
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
