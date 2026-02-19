plugins {
    `java-library`
    `maven-publish`
}

description = "glTF 2.0 model loading (gltfio) for filament-java"

dependencies {
    api(project(":filament-java-core"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "filament-java-gltfio"
        }
    }
}
