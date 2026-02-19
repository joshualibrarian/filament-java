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
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWNativeX11.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Renders a colored triangle in a GLFW window using Filament.
 */
public class HelloTriangle {

    private static final int WINDOW_WIDTH = 800;
    private static final int WINDOW_HEIGHT = 600;

    public static void main(String[] args) throws Exception {
        // Initialize GLFW
        GLFWErrorCallback.createPrint(System.err).set();

        // Filament's Vulkan backend on Linux only supports X11 surfaces.
        // Force GLFW to use X11 (via XWayland on Wayland systems).
        glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11);

        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        // No OpenGL context — Filament manages its own graphics context
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);

        long window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT,
                "filament-java — Hello Triangle", NULL, NULL);
        if (window == NULL) {
            glfwTerminate();
            throw new RuntimeException("Failed to create GLFW window");
        }

        // Get native window handle for Filament SwapChain
        long nativeWindow = glfwGetX11Window(window);

        // Initialize Filament
        Filament.init();

        // Create engine with Vulkan backend
        Engine engine = new Engine.Builder().backend(Engine.Backend.VULKAN).build();
        if (engine == null) {
            // Fall back to OpenGL
            engine = new Engine.Builder().backend(Engine.Backend.OPENGL).build();
        }

        SwapChain swapChain = engine.createSwapChainFromRawPointer(nativeWindow, 0);
        Renderer renderer = engine.createRenderer();
        Scene scene = engine.createScene();
        View view = engine.createView();
        Camera camera = engine.createCamera(engine.getEntityManager().create());

        // Setup view
        view.setScene(scene);
        view.setCamera(camera);
        view.setViewport(new Viewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT));

        // Setup camera — orthographic projection
        double aspect = (double) WINDOW_WIDTH / WINDOW_HEIGHT;
        camera.setProjection(Camera.Projection.ORTHO,
                -aspect, aspect, -1.0, 1.0, 0.0, 1.0);

        // Create triangle geometry
        // 3 vertices: position (float2) + color (uint32 ABGR)
        // Each vertex = 2 floats (8 bytes) + 1 int (4 bytes) = 12 bytes
        float[] positions = {
                 0.0f,  0.6f,   // top
                -0.6f, -0.4f,   // bottom-left
                 0.6f, -0.4f,   // bottom-right
        };

        int[] colors = {
                0xFF0000FF,  // red   (ABGR)
                0xFF00FF00,  // green (ABGR)
                0xFFFF0000,  // blue  (ABGR)
        };

        short[] indices = {0, 1, 2};

        // Create vertex buffer
        ByteBuffer positionBuf = ByteBuffer.allocateDirect(positions.length * 4)
                .order(ByteOrder.nativeOrder());
        for (float f : positions) positionBuf.putFloat(f);
        positionBuf.flip();

        ByteBuffer colorBuf = ByteBuffer.allocateDirect(colors.length * 4)
                .order(ByteOrder.nativeOrder());
        for (int c : colors) colorBuf.putInt(c);
        colorBuf.flip();

        VertexBuffer vertexBuffer = new VertexBuffer.Builder()
                .vertexCount(3)
                .bufferCount(2)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0,
                        VertexBuffer.AttributeType.FLOAT2, 0, 8)
                .attribute(VertexBuffer.VertexAttribute.COLOR, 1,
                        VertexBuffer.AttributeType.UBYTE4, 0, 4)
                .normalized(VertexBuffer.VertexAttribute.COLOR)
                .build(engine);

        vertexBuffer.setBufferAt(engine, 0, positionBuf);
        vertexBuffer.setBufferAt(engine, 1, colorBuf);

        // Create index buffer
        ByteBuffer indexBuf = ByteBuffer.allocateDirect(indices.length * 2)
                .order(ByteOrder.nativeOrder());
        for (short s : indices) indexBuf.putShort(s);
        indexBuf.flip();

        IndexBuffer indexBuffer = new IndexBuffer.Builder()
                .indexCount(3)
                .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                .build(engine);

        indexBuffer.setBuffer(engine, indexBuf);

        // Load material
        byte[] materialData = loadResource("materials/baked_color.filamat");
        ByteBuffer materialBuf = ByteBuffer.allocateDirect(materialData.length)
                .order(ByteOrder.nativeOrder());
        materialBuf.put(materialData);
        materialBuf.flip();

        Material material = new Material.Builder()
                .payload(materialBuf, materialBuf.remaining())
                .build(engine);

        // Create renderable entity
        int triangle = EntityManager.get().create();

        new RenderableManager.Builder(1)
                .boundingBox(new Box(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.01f))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES,
                        vertexBuffer, indexBuffer, 0, 3)
                .material(0, material.getDefaultInstance())
                .culling(false)
                .build(engine, triangle);

        scene.addEntity(triangle);

        // Set background color via clear options
        Renderer.ClearOptions clearOptions = new Renderer.ClearOptions();
        clearOptions.clearColor = new float[]{0.1f, 0.1f, 0.15f, 1.0f};
        clearOptions.clear = true;
        clearOptions.discard = true;
        renderer.setClearOptions(clearOptions);

        System.out.println("Rendering... Close the window to exit.");

        // Render loop
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();

            if (renderer.beginFrame(swapChain, System.nanoTime())) {
                renderer.render(view);
                renderer.endFrame();
            }
        }

        // Cleanup
        engine.destroyEntity(triangle);
        engine.destroyRenderer(renderer);
        engine.destroyView(view);
        engine.destroyScene(scene);
        engine.destroyCameraComponent(camera.getEntity());
        engine.destroySwapChain(swapChain);
        engine.destroyIndexBuffer(indexBuffer);
        engine.destroyVertexBuffer(vertexBuffer);
        engine.destroyMaterial(material);

        engine.destroy();

        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private static byte[] loadResource(String path) throws IOException {
        try (InputStream in = HelloTriangle.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Resource not found: " + path);
            }
            return in.readAllBytes();
        }
    }
}
