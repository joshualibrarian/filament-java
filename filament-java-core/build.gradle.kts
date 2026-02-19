plugins {
    `java-library`
    `maven-publish`
}

description = "Java API classes for Google Filament desktop bindings"

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "filament-java-core"
        }
    }
}
