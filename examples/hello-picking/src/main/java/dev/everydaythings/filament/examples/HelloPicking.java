/*
 * Copyright (C) 2024 filament-java contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.everydaythings.filament.examples;

import dev.everydaythings.filament.*;

import org.lwjgl.glfw.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWNativeX11.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Demonstrates object picking: click on colored cubes to select them.
 *
 * Controls:
 *   Left-click:  Pick object (highlights selected cube)
 *   Right-drag:  Orbit camera
 *   Scroll:      Zoom
 */
public class HelloPicking {

    private static final int WINDOW_WIDTH = 1024;
    private static final int WINDOW_HEIGHT = 768;

    // Orbit camera state
    private static double theta = Math.PI / 4;
    private static double phi = Math.PI / 5;
    private static double radius = 8.0;
    private static final double targetX = 0, targetY = 0, targetZ = 0;
    private static double lastMouseX, lastMouseY;
    private static boolean rightDragging = false;

    // Picking state
    private static final AtomicReference<View.PickingQueryResult> pickResult =
            new AtomicReference<>();
    private static int selectedEntity = 0;

    // Entity → original color mapping
    private static final Map<Integer, float[]> entityColors = new HashMap<>();
    // Entity → material instance mapping
    private static final Map<Integer, MaterialInstance> entityMaterials = new HashMap<>();
    // Entity → name mapping
    private static final Map<Integer, String> entityNames = new HashMap<>();

    // Cube colors (RGBA)
    private static final float[][] COLORS = {
            {0.9f, 0.2f, 0.2f, 1.0f},  // red
            {0.2f, 0.7f, 0.2f, 1.0f},  // green
            {0.2f, 0.3f, 0.9f, 1.0f},  // blue
            {0.9f, 0.8f, 0.1f, 1.0f},  // yellow
            {0.8f, 0.3f, 0.8f, 1.0f},  // purple
            {0.1f, 0.8f, 0.8f, 1.0f},  // cyan
    };
    private static final String[] COLOR_NAMES = {
            "Red", "Green", "Blue", "Yellow", "Purple", "Cyan"
    };
    private static final float[] HIGHLIGHT_COLOR = {1.0f, 1.0f, 1.0f, 1.0f};

    public static void main(String[] args) throws Exception {
        GLFWErrorCallback.createPrint(System.err).set();
        glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11);

        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);

        long window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT,
                "filament-java — Picking Demo", NULL, NULL);
        if (window == NULL) {
            glfwTerminate();
            throw new RuntimeException("Failed to create GLFW window");
        }

        long nativeWindow = glfwGetX11Window(window);

        Filament.init();

        Engine engine = new Engine.Builder().backend(Engine.Backend.VULKAN).build();
        if (engine == null) {
            engine = new Engine.Builder().backend(Engine.Backend.OPENGL).build();
        }

        SwapChain swapChain = engine.createSwapChainFromRawPointer(nativeWindow, 0);
        Renderer renderer = engine.createRenderer();
        Scene scene = engine.createScene();
        View view = engine.createView();
        Camera camera = engine.createCamera(engine.getEntityManager().create());

        view.setScene(scene);
        view.setCamera(camera);
        view.setViewport(new Viewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT));

        // Lighting
        int sunlight = EntityManager.get().create();
        new LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 1.0f, 1.0f)
                .intensity(100000.0f)
                .direction(-0.5f, -1.0f, -0.5f)
                .castShadows(true)
                .build(engine, sunlight);
        scene.addEntity(sunlight);

        IndirectLight ibl = new IndirectLight.Builder()
                .intensity(30000.0f)
                .build(engine);
        scene.setIndirectLight(ibl);

        // Load lit_color material
        byte[] materialData = loadResource("materials/lit_color.filamat");
        ByteBuffer materialBuf = ByteBuffer.allocateDirect(materialData.length)
                .order(ByteOrder.nativeOrder());
        materialBuf.put(materialData);
        materialBuf.flip();

        Material material = new Material.Builder()
                .payload(materialBuf, materialBuf.remaining())
                .build(engine);

        // Create shared cube geometry
        VertexBuffer cubeVB = createCubeVertexBuffer(engine);
        IndexBuffer cubeIB = createCubeIndexBuffer(engine);

        // Create 6 colored cubes in a 3x2 grid
        int[] cubeEntities = new int[COLORS.length];
        float[][] positions = {
                {-2.0f, 0.0f, -1.5f},
                { 0.0f, 0.0f, -1.5f},
                { 2.0f, 0.0f, -1.5f},
                {-2.0f, 0.0f,  1.5f},
                { 0.0f, 0.0f,  1.5f},
                { 2.0f, 0.0f,  1.5f},
        };

        TransformManager tm = engine.getTransformManager();

        for (int i = 0; i < COLORS.length; i++) {
            cubeEntities[i] = EntityManager.get().create();
            int entity = cubeEntities[i];

            // Create a unique material instance for each cube
            MaterialInstance mi = material.createInstance();
            mi.setParameter("baseColor", COLORS[i][0], COLORS[i][1],
                    COLORS[i][2], COLORS[i][3]);

            new RenderableManager.Builder(1)
                    .boundingBox(new Box(0, 0, 0, 0.5f, 0.5f, 0.5f))
                    .geometry(0, RenderableManager.PrimitiveType.TRIANGLES,
                            cubeVB, cubeIB)
                    .material(0, mi)
                    .build(engine, entity);

            // Position via transform
            int ti = tm.getInstance(entity);
            float[] transform = makeTranslation(positions[i][0], positions[i][1], positions[i][2]);
            tm.setTransform(ti, transform);

            scene.addEntity(entity);

            // Track for picking
            entityColors.put(entity, COLORS[i]);
            entityMaterials.put(entity, mi);
            entityNames.put(entity, COLOR_NAMES[i] + " cube");
        }

        // Camera setup
        double aspect = (double) WINDOW_WIDTH / WINDOW_HEIGHT;
        camera.setProjection(45.0, aspect, 0.1, 100.0, Camera.Fov.VERTICAL);

        // Clear color
        Renderer.ClearOptions clearOptions = new Renderer.ClearOptions();
        clearOptions.clearColor = new float[]{0.15f, 0.15f, 0.2f, 1.0f};
        clearOptions.clear = true;
        clearOptions.discard = true;
        renderer.setClearOptions(clearOptions);

        // Setup mouse callbacks
        setupMouseCallbacks(window, view);

        System.out.println("Picking demo — click on cubes to select them.");
        System.out.println("  Left-click:  Pick object");
        System.out.println("  Right-drag:  Orbit camera");
        System.out.println("  Scroll:      Zoom");

        // Render loop
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();

            // Process pick results on the main thread
            View.PickingQueryResult result = pickResult.getAndSet(null);
            if (result != null) {
                handlePickResult(result);
            }

            updateCamera(camera);

            if (renderer.beginFrame(swapChain, System.nanoTime())) {
                renderer.render(view);
                renderer.endFrame();
            }
        }

        // Cleanup
        for (int entity : cubeEntities) {
            engine.destroyEntity(entity);
        }
        for (MaterialInstance mi : entityMaterials.values()) {
            engine.destroyMaterialInstance(mi);
        }
        engine.destroyMaterial(material);
        engine.destroyVertexBuffer(cubeVB);
        engine.destroyIndexBuffer(cubeIB);
        engine.destroyEntity(sunlight);
        engine.destroyIndirectLight(ibl);
        engine.destroyRenderer(renderer);
        engine.destroyView(view);
        engine.destroyScene(scene);
        engine.destroyCameraComponent(camera.getEntity());
        engine.destroySwapChain(swapChain);
        engine.destroy();

        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private static void handlePickResult(View.PickingQueryResult result) {
        int entity = result.renderable;

        // Restore previous selection
        if (selectedEntity != 0 && entityMaterials.containsKey(selectedEntity)) {
            float[] origColor = entityColors.get(selectedEntity);
            entityMaterials.get(selectedEntity).setParameter("baseColor",
                    origColor[0], origColor[1], origColor[2], origColor[3]);
        }

        if (entity != 0 && entityMaterials.containsKey(entity)) {
            // Highlight new selection
            entityMaterials.get(entity).setParameter("baseColor",
                    HIGHLIGHT_COLOR[0], HIGHLIGHT_COLOR[1],
                    HIGHLIGHT_COLOR[2], HIGHLIGHT_COLOR[3]);
            selectedEntity = entity;

            String name = entityNames.getOrDefault(entity, "Unknown");
            System.out.printf("Picked: %s (entity %d, depth %.4f)%n",
                    name, entity, result.depth);
        } else {
            // Clicked on empty space
            selectedEntity = 0;
            System.out.println("Picked: nothing");
        }
    }

    private static void setupMouseCallbacks(long window, View view) {
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            double[] xpos = new double[1], ypos = new double[1];
            glfwGetCursorPos(win, xpos, ypos);
            lastMouseX = xpos[0];
            lastMouseY = ypos[0];

            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                // Picking: flip Y for GL convention (origin at bottom-left)
                int pickX = (int) xpos[0];
                int pickY = WINDOW_HEIGHT - 1 - (int) ypos[0];
                // Handler must be a non-null Executor; Runnable::run executes
                // the callback directly on the backend thread.
                Executor direct = Runnable::run;
                view.pick(pickX, pickY, direct, result -> pickResult.set(result));
            } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                rightDragging = (action == GLFW_PRESS);
            }
        });

        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            double dx = xpos - lastMouseX;
            double dy = ypos - lastMouseY;
            lastMouseX = xpos;
            lastMouseY = ypos;

            if (rightDragging) {
                theta -= dx * 0.01;
                phi += dy * 0.01;
                phi = Math.max(-Math.PI / 2 + 0.01, Math.min(Math.PI / 2 - 0.01, phi));
            }
        });

        glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
            radius *= (1.0 - yoffset * 0.1);
            radius = Math.max(0.5, radius);
        });
    }

    private static void updateCamera(Camera camera) {
        double eyeX = targetX + radius * Math.cos(phi) * Math.sin(theta);
        double eyeY = targetY + radius * Math.sin(phi);
        double eyeZ = targetZ + radius * Math.cos(phi) * Math.cos(theta);

        camera.lookAt(eyeX, eyeY, eyeZ, targetX, targetY, targetZ, 0, 1, 0);
    }

    // --- Cube geometry ---

    private static VertexBuffer createCubeVertexBuffer(Engine engine) {
        // 24 vertices (4 per face)
        float[] positions = {
                // Front face (z = +0.5)
                -0.5f, -0.5f,  0.5f,   0.5f, -0.5f,  0.5f,
                 0.5f,  0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
                // Back face (z = -0.5)
                 0.5f, -0.5f, -0.5f,  -0.5f, -0.5f, -0.5f,
                -0.5f,  0.5f, -0.5f,   0.5f,  0.5f, -0.5f,
                // Top face (y = +0.5)
                -0.5f,  0.5f,  0.5f,   0.5f,  0.5f,  0.5f,
                 0.5f,  0.5f, -0.5f,  -0.5f,  0.5f, -0.5f,
                // Bottom face (y = -0.5)
                -0.5f, -0.5f, -0.5f,   0.5f, -0.5f, -0.5f,
                 0.5f, -0.5f,  0.5f,  -0.5f, -0.5f,  0.5f,
                // Right face (x = +0.5)
                 0.5f, -0.5f,  0.5f,   0.5f, -0.5f, -0.5f,
                 0.5f,  0.5f, -0.5f,   0.5f,  0.5f,  0.5f,
                // Left face (x = -0.5)
                -0.5f, -0.5f, -0.5f,  -0.5f, -0.5f,  0.5f,
                -0.5f,  0.5f,  0.5f,  -0.5f,  0.5f, -0.5f,
        };

        // Tangent frames as quaternions (FLOAT4).
        // Each quaternion encodes the rotation from the default tangent frame
        // (T=+X, B=+Y, N=+Z) to the face's tangent frame.
        float S = 0.7071068f; // sqrt(0.5)
        float[] tangents = {
                // Front  (N=+Z): identity
                0, 0, 0, 1,  0, 0, 0, 1,  0, 0, 0, 1,  0, 0, 0, 1,
                // Back   (N=-Z): 180° around Y
                0, 1, 0, 0,  0, 1, 0, 0,  0, 1, 0, 0,  0, 1, 0, 0,
                // Top    (N=+Y): -90° around X
                -S, 0, 0, S,  -S, 0, 0, S,  -S, 0, 0, S,  -S, 0, 0, S,
                // Bottom (N=-Y): 90° around X
                S, 0, 0, S,  S, 0, 0, S,  S, 0, 0, S,  S, 0, 0, S,
                // Right  (N=+X): 90° around Y
                0, S, 0, S,  0, S, 0, S,  0, S, 0, S,  0, S, 0, S,
                // Left   (N=-X): -90° around Y
                0, -S, 0, S,  0, -S, 0, S,  0, -S, 0, S,  0, -S, 0, S,
        };

        ByteBuffer posBuf = ByteBuffer.allocateDirect(positions.length * 4)
                .order(ByteOrder.nativeOrder());
        for (float f : positions) posBuf.putFloat(f);
        posBuf.flip();

        ByteBuffer tanBuf = ByteBuffer.allocateDirect(tangents.length * 4)
                .order(ByteOrder.nativeOrder());
        for (float f : tangents) tanBuf.putFloat(f);
        tanBuf.flip();

        VertexBuffer vb = new VertexBuffer.Builder()
                .vertexCount(24)
                .bufferCount(2)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0,
                        VertexBuffer.AttributeType.FLOAT3, 0, 12)
                .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1,
                        VertexBuffer.AttributeType.FLOAT4, 0, 16)
                .build(engine);

        vb.setBufferAt(engine, 0, posBuf);
        vb.setBufferAt(engine, 1, tanBuf);

        return vb;
    }

    private static IndexBuffer createCubeIndexBuffer(Engine engine) {
        short[] indices = {
                0,  1,  2,   2,  3,  0,   // front
                4,  5,  6,   6,  7,  4,   // back
                8,  9,  10,  10, 11, 8,   // top
                12, 13, 14,  14, 15, 12,  // bottom
                16, 17, 18,  18, 19, 16,  // right
                20, 21, 22,  22, 23, 20,  // left
        };

        ByteBuffer indexBuf = ByteBuffer.allocateDirect(indices.length * 2)
                .order(ByteOrder.nativeOrder());
        for (short s : indices) indexBuf.putShort(s);
        indexBuf.flip();

        IndexBuffer ib = new IndexBuffer.Builder()
                .indexCount(36)
                .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                .build(engine);

        ib.setBuffer(engine, indexBuf);
        return ib;
    }

    private static float[] makeTranslation(float x, float y, float z) {
        // Column-major 4x4 identity + translation
        return new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                x, y, z, 1,
        };
    }

    private static byte[] loadResource(String path) throws IOException {
        try (InputStream in = HelloPicking.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Resource not found: " + path);
            }
            return in.readAllBytes();
        }
    }
}
