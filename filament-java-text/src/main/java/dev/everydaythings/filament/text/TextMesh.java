package dev.everydaythings.filament.text;

import dev.everydaythings.filament.Engine;
import dev.everydaythings.filament.EntityManager;
import dev.everydaythings.filament.IndexBuffer;
import dev.everydaythings.filament.MaterialInstance;
import dev.everydaythings.filament.VertexBuffer;

import java.util.List;

/**
 * Result of {@link TextMeshBuilder#build()} — holds all Filament resources for a rendered text string.
 *
 * <p>Add the entity to a scene with {@code scene.addEntity(mesh.entity())}.
 * When done, call {@link #destroy(Engine)} to release all owned resources.
 */
public class TextMesh {

    private final int entity;
    private final VertexBuffer vertexBuffer;
    private final IndexBuffer indexBuffer;
    private final List<MaterialInstance> materialInstances;

    TextMesh(int entity, VertexBuffer vertexBuffer, IndexBuffer indexBuffer,
             List<MaterialInstance> materialInstances) {
        this.entity = entity;
        this.vertexBuffer = vertexBuffer;
        this.indexBuffer = indexBuffer;
        this.materialInstances = materialInstances;
    }

    /** The renderable entity. Add to a {@code Scene} to display. */
    public int entity() {
        return entity;
    }

    /** The vertex buffer containing all glyph geometry. */
    public VertexBuffer vertexBuffer() {
        return vertexBuffer;
    }

    /** The index buffer containing all glyph triangles. */
    public IndexBuffer indexBuffer() {
        return indexBuffer;
    }

    /** Material instances (one per atlas used). */
    public List<MaterialInstance> materialInstances() {
        return materialInstances;
    }

    /**
     * Destroy all owned Filament resources.
     *
     * @param engine the Filament engine
     */
    public void destroy(Engine engine) {
        engine.destroyEntity(entity);
        EntityManager.get().destroy(entity);
        engine.destroyVertexBuffer(vertexBuffer);
        engine.destroyIndexBuffer(indexBuffer);
        for (MaterialInstance mi : materialInstances) {
            engine.destroyMaterialInstance(mi);
        }
    }
}
