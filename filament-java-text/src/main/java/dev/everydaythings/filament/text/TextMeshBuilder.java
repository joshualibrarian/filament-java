package dev.everydaythings.filament.text;

import dev.everydaythings.filament.Box;
import dev.everydaythings.filament.Engine;
import dev.everydaythings.filament.EntityManager;
import dev.everydaythings.filament.IndexBuffer;
import dev.everydaythings.filament.MaterialInstance;
import dev.everydaythings.filament.RenderableManager;
import dev.everydaythings.filament.TextureSampler;
import dev.everydaythings.filament.VertexBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder that generates a renderable {@link TextMesh} from a text string,
 * font manager, and optional {@link TextSurface} for curved projection.
 *
 * <p>Usage:
 * <pre>{@code
 * TextMesh mesh = new TextMeshBuilder(engine, textRenderer)
 *     .text("Hello, World!")
 *     .fontManager(fonts)
 *     .fontSize(0.5f)
 *     .color(0xFFFFFFFF)
 *     .surface(new FlatTextSurface(origin, right, up))
 *     .build();
 * scene.addEntity(mesh.entity());
 * }</pre>
 *
 * @see TextMesh
 * @see TextSurface
 */
public class TextMeshBuilder {

    // Vertex stride: POSITION(FLOAT3=12) + COLOR(UBYTE4=4) + UV0(FLOAT2=8) = 24 bytes
    private static final int VERTEX_STRIDE = 24;

    private final Engine engine;
    private final MsdfTextRenderer textRenderer;

    private String text;
    private MsdfFontManager fontManager;
    private float fontSize = 1.0f;
    private int colorArgb = 0xFFFFFFFF;
    private TextSurface surface;

    /**
     * Create a builder.
     *
     * @param engine       Filament engine
     * @param textRenderer provides the MSDF text material
     */
    public TextMeshBuilder(Engine engine, MsdfTextRenderer textRenderer) {
        this.engine = engine;
        this.textRenderer = textRenderer;
    }

    /** Set the text string to render. */
    public TextMeshBuilder text(String text) {
        this.text = text;
        return this;
    }

    /** Set the font manager (provides atlas resolution and glyph metrics). */
    public TextMeshBuilder fontManager(MsdfFontManager fontManager) {
        this.fontManager = fontManager;
        return this;
    }

    /** Set the font size in world units (default 1.0). */
    public TextMeshBuilder fontSize(float fontSize) {
        this.fontSize = fontSize;
        return this;
    }

    /** Set the text color in ARGB format (default 0xFFFFFFFF = opaque white). */
    public TextMeshBuilder color(int argb) {
        this.colorArgb = argb;
        return this;
    }

    /** Set the surface to project text onto (default: null = flat XY plane at origin). */
    public TextMeshBuilder surface(TextSurface surface) {
        this.surface = surface;
        return this;
    }

    /**
     * Build the text mesh.
     *
     * @return a {@link TextMesh} owning all Filament resources
     * @throws IllegalStateException if text or fontManager is not set
     */
    public TextMesh build() {
        if (text == null || text.isEmpty()) {
            throw new IllegalStateException("text must be set and non-empty");
        }
        if (fontManager == null) {
            throw new IllegalStateException("fontManager must be set");
        }

        TextSurface surf = this.surface;
        if (surf == null) {
            surf = new FlatTextSurface(
                    new float[]{0, 0, 0},
                    new float[]{1, 0, 0},
                    new float[]{0, 1, 0}
            );
        }

        // Ensure all glyphs are generated in the atlas(es)
        fontManager.ensureGlyphs(text);

        // Step 1: Layout — resolve glyphs and compute cursor positions
        List<LayoutGlyph> layoutGlyphs = layout();

        // Step 2: Group by atlas
        Map<MsdfAtlas, List<LayoutGlyph>> atlasGroups = new LinkedHashMap<>();
        for (LayoutGlyph lg : layoutGlyphs) {
            atlasGroups.computeIfAbsent(lg.atlas, k -> new ArrayList<>()).add(lg);
        }

        // Step 3: Tessellate — compute total vertex/index counts
        int subdPerEm = surf.subdivisionsPerEm();
        int totalVertices = 0;
        int totalIndices = 0;

        for (LayoutGlyph lg : layoutGlyphs) {
            int[] gridSize = glyphGridSize(lg, subdPerEm);
            int rows = gridSize[0];
            int cols = gridSize[1];
            totalVertices += (cols + 1) * (rows + 1);
            totalIndices += cols * rows * 6;
        }

        if (totalVertices == 0) {
            // All whitespace or no renderable glyphs — return an empty mesh
            int entity = EntityManager.get().create();
            return new TextMesh(entity,
                    new VertexBuffer.Builder().vertexCount(0).bufferCount(0).build(engine),
                    new IndexBuffer.Builder().indexCount(0).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine),
                    List.of());
        }

        // Allocate buffers
        ByteBuffer vertexData = ByteBuffer.allocateDirect(totalVertices * VERTEX_STRIDE)
                .order(ByteOrder.nativeOrder());
        boolean useUint = totalVertices > 65535;
        ByteBuffer indexData;
        if (useUint) {
            indexData = ByteBuffer.allocateDirect(totalIndices * 4).order(ByteOrder.nativeOrder());
        } else {
            indexData = ByteBuffer.allocateDirect(totalIndices * 2).order(ByteOrder.nativeOrder());
        }

        int colorAbgr = MsdfTextRenderer.argbToAbgr(colorArgb);
        float[] pos = new float[3];

        // Track bounding box
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        // Track per-atlas index ranges: [offset, count] in index units
        Map<MsdfAtlas, int[]> atlasIndexRanges = new LinkedHashMap<>();
        int vertexOffset = 0;
        int indexOffset = 0;

        for (Map.Entry<MsdfAtlas, List<LayoutGlyph>> entry : atlasGroups.entrySet()) {
            MsdfAtlas atlas = entry.getKey();
            List<LayoutGlyph> glyphs = entry.getValue();
            int groupIndexStart = indexOffset;

            for (LayoutGlyph lg : glyphs) {
                int[] gridSize = glyphGridSize(lg, subdPerEm);
                int rows = gridSize[0];
                int cols = gridSize[1];

                MsdfAtlas.GlyphMetrics gm = lg.metrics;
                float glyphLeft = lg.cursorX + (float) gm.planeLeft() * fontSize;
                float glyphRight = lg.cursorX + (float) gm.planeRight() * fontSize;
                float glyphBottom = lg.cursorY + (float) gm.planeBottom() * fontSize;
                float glyphTop = lg.cursorY + (float) gm.planeTop() * fontSize;

                // UV bounds — flip V for OpenGL convention
                float uvLeft = (float) gm.uvLeft();
                float uvRight = (float) gm.uvRight();
                float uvBottomFlipped = 1.0f - (float) gm.uvBottom();
                float uvTopFlipped = 1.0f - (float) gm.uvTop();

                // Emit vertices in row-major order: (cols+1) × (rows+1) grid
                for (int row = 0; row <= rows; row++) {
                    float vFrac = (float) row / rows;
                    for (int col = 0; col <= cols; col++) {
                        float uFrac = (float) col / cols;

                        float worldX = glyphLeft + uFrac * (glyphRight - glyphLeft);
                        float worldY = glyphBottom + vFrac * (glyphTop - glyphBottom);
                        surf.position(worldX, worldY, pos);

                        vertexData.putFloat(pos[0]);
                        vertexData.putFloat(pos[1]);
                        vertexData.putFloat(pos[2]);
                        vertexData.putInt(colorAbgr);
                        vertexData.putFloat(uvLeft + uFrac * (uvRight - uvLeft));
                        vertexData.putFloat(uvBottomFlipped + vFrac * (uvTopFlipped - uvBottomFlipped));

                        // Update bounding box
                        if (pos[0] < minX) minX = pos[0];
                        if (pos[1] < minY) minY = pos[1];
                        if (pos[2] < minZ) minZ = pos[2];
                        if (pos[0] > maxX) maxX = pos[0];
                        if (pos[1] > maxY) maxY = pos[1];
                        if (pos[2] > maxZ) maxZ = pos[2];
                    }
                }

                // Emit indices — two triangles per grid cell
                int stride = cols + 1;
                for (int row = 0; row < rows; row++) {
                    for (int col = 0; col < cols; col++) {
                        int tl = vertexOffset + row * stride + col;
                        int tr = tl + 1;
                        int bl = tl + stride;
                        int br = bl + 1;

                        if (useUint) {
                            indexData.putInt(tl); indexData.putInt(tr); indexData.putInt(bl);
                            indexData.putInt(bl); indexData.putInt(tr); indexData.putInt(br);
                        } else {
                            indexData.putShort((short) tl); indexData.putShort((short) tr); indexData.putShort((short) bl);
                            indexData.putShort((short) bl); indexData.putShort((short) tr); indexData.putShort((short) br);
                        }
                        indexOffset += 6;
                    }
                }

                vertexOffset += (cols + 1) * (rows + 1);
            }

            atlasIndexRanges.put(atlas, new int[]{groupIndexStart, indexOffset - groupIndexStart});
        }

        vertexData.flip();
        indexData.flip();

        // Step 4: Create Filament resources
        VertexBuffer vb = new VertexBuffer.Builder()
                .vertexCount(totalVertices)
                .bufferCount(1)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0,
                        VertexBuffer.AttributeType.FLOAT3, 0, VERTEX_STRIDE)
                .attribute(VertexBuffer.VertexAttribute.COLOR, 0,
                        VertexBuffer.AttributeType.UBYTE4, 12, VERTEX_STRIDE)
                .normalized(VertexBuffer.VertexAttribute.COLOR)
                .attribute(VertexBuffer.VertexAttribute.UV0, 0,
                        VertexBuffer.AttributeType.FLOAT2, 16, VERTEX_STRIDE)
                .build(engine);
        vb.setBufferAt(engine, 0, vertexData);

        IndexBuffer ib = new IndexBuffer.Builder()
                .indexCount(totalIndices)
                .bufferType(useUint ? IndexBuffer.Builder.IndexType.UINT : IndexBuffer.Builder.IndexType.USHORT)
                .build(engine);
        ib.setBuffer(engine, indexData);

        // Create material instances — one per atlas
        TextureSampler sampler = new TextureSampler(
                TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE);
        List<MaterialInstance> materialInstances = new ArrayList<>();
        for (MsdfAtlas atlas : atlasIndexRanges.keySet()) {
            MaterialInstance mi = textRenderer.msdfTextMaterial().createInstance();
            mi.setParameter("msdfAtlas", atlas.texture(), sampler);
            mi.setParameter("pxRange", (float) atlas.pxRange());
            mi.setParameter("atlasSize", (float) atlas.atlasWidth(), (float) atlas.atlasHeight());
            mi.setParameter("screenPxRange", (float) atlas.pxRange());
            materialInstances.add(mi);
        }

        // Create entity and renderable
        int entity = EntityManager.get().create();

        float pad = 0.001f;
        Box boundingBox = new Box(
                (minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f,
                (maxX - minX) * 0.5f + pad, (maxY - minY) * 0.5f + pad, (maxZ - minZ) * 0.5f + pad
        );

        int primitiveCount = atlasIndexRanges.size();
        RenderableManager.Builder builder = new RenderableManager.Builder(primitiveCount)
                .boundingBox(boundingBox)
                .culling(false)
                .castShadows(false)
                .receiveShadows(false);

        int primIdx = 0;
        for (Map.Entry<MsdfAtlas, int[]> entry : atlasIndexRanges.entrySet()) {
            int[] range = entry.getValue();
            builder.geometry(primIdx, RenderableManager.PrimitiveType.TRIANGLES,
                    vb, ib, range[0], range[1]);
            builder.material(primIdx, materialInstances.get(primIdx));
            primIdx++;
        }

        builder.build(engine, entity);

        return new TextMesh(entity, vb, ib, materialInstances);
    }

    // --- Internal helpers ---

    /** Resolved glyph with cursor position in world units. */
    private static class LayoutGlyph {
        final MsdfAtlas atlas;
        final MsdfAtlas.GlyphMetrics metrics;
        final float cursorX; // world units
        final float cursorY; // world units

        LayoutGlyph(MsdfAtlas atlas, MsdfAtlas.GlyphMetrics metrics, float cursorX, float cursorY) {
            this.atlas = atlas;
            this.metrics = metrics;
            this.cursorX = cursorX;
            this.cursorY = cursorY;
        }
    }

    /** Walk codepoints, resolve glyphs, accumulate cursor. */
    private List<LayoutGlyph> layout() {
        List<LayoutGlyph> result = new ArrayList<>();
        float cursorX = 0;
        float cursorY = 0;

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);

            if (cp == '\n') {
                MsdfAtlas defaultAtlas = fontManager.defaultAtlas();
                double lineHeight = (defaultAtlas != null) ? defaultAtlas.lineHeight() : 1.0;
                cursorX = 0;
                cursorY -= (float) (lineHeight * fontSize);
                continue;
            }

            MsdfFontManager.ResolvedGlyph resolved = fontManager.resolveGlyph(cp);
            if (resolved == null) {
                // Unknown glyph — skip
                continue;
            }

            MsdfAtlas.GlyphMetrics gm = resolved.metrics();

            // Only emit geometry for visible glyphs (non-zero plane bounds)
            if (gm.planeLeft() != gm.planeRight() && gm.planeBottom() != gm.planeTop()) {
                result.add(new LayoutGlyph(resolved.atlas(), gm, cursorX, cursorY));
            }

            cursorX += (float) (gm.advance() * fontSize);
        }

        return result;
    }

    /** Compute grid dimensions [rows, cols] for a glyph given subdivision level. */
    private int[] glyphGridSize(LayoutGlyph lg, int subdPerEm) {
        if (subdPerEm <= 0) {
            return new int[]{1, 1};
        }
        MsdfAtlas.GlyphMetrics gm = lg.metrics;
        float glyphWidth = (float) (gm.planeRight() - gm.planeLeft());
        float glyphHeight = (float) (gm.planeTop() - gm.planeBottom());
        int cols = Math.max(1, Math.round(glyphWidth * subdPerEm));
        int rows = Math.max(1, Math.round(glyphHeight * subdPerEm));
        return new int[]{rows, cols};
    }
}
