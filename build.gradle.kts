plugins {
    base
    alias(libs.plugins.shadow) apply false
}

allprojects {
    group = "kr.lunaf.varstore"
    version = "1.3.1"
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.skriptlang.org/releases")
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    }
}

// Shared defaults; module-specific dependencies and tasks live in each module.
val sharedLibraries = libs
val paperProjects = setOf(
    ":varstore-paper",
    ":varstore-testkit-paper",
    ":varstore-placeholderapi",
    ":varstore-skript",
    ":examples:preferences",
    ":examples:rewards",
    ":examples:quests",
    ":examples:structured",
)

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        withSourcesJar()
        withJavadocJar()
    }
    dependencies {
        "testImplementation"(platform(sharedLibraries.junit.bom))
        "testImplementation"(sharedLibraries.junit.jupiter)
        "testRuntimeOnly"(sharedLibraries.junit.launcher)
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "512m"
    }
    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }

    if (path in paperProjects) {
        dependencies {
            "compileOnly"(sharedLibraries.paper)
        }
        tasks.withType<ProcessResources>().configureEach {
            filesMatching("plugin.yml") {
                expand("version" to project.version)
            }
        }
        tasks.withType<Jar>().configureEach {
            manifest.attributes["paperweight-mappings-namespace"] = "mojang"
        }
    }

    // Paper and CLI distributions share the same bundled JDBC dependencies.
    plugins.withId("com.gradleup.shadow") {
        tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
            archiveClassifier.set("")
            relocate("com.zaxxer.hikari", "kr.lunaf.varstore.internal.hikari")
            relocate("org.postgresql", "kr.lunaf.varstore.internal.postgresql")
            mergeServiceFiles()
        }
        tasks.named<Jar>("jar") {
            archiveClassifier.set("thin")
        }
        tasks.named("assemble") {
            dependsOn("shadowJar")
        }
    }
}
