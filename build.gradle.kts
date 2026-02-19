subprojects {
    group = property("group") as String
    version = property("version") as String

    repositories {
        mavenCentral()
    }

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }

        tasks.withType<JavaCompile> {
            options.encoding = "UTF-8"
        }

        tasks.withType<Test> {
            useJUnitPlatform()
        }

        dependencies {
            "testImplementation"(platform("org.junit:junit-bom:${property("junitVersion")}"))
            "testImplementation"("org.junit.jupiter:junit-jupiter")
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }
    }
}
