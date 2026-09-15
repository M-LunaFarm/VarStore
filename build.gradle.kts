plugins {
    base
    id("com.gradleup.shadow") version "8.3.6" apply false
}
allprojects {
    group = "kr.lunaf.varstore"
    version = "1.0.0"
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        withSourcesJar()
        withJavadocJar()
    }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform(); maxHeapSize = "512m" }
    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("mavenJava") { from(components["java"]) }
    }
}
project(":varstore-postgres") {
    dependencies {
        "api"(project(":varstore-api"))
        "implementation"("com.zaxxer:HikariCP:6.2.1")
        "implementation"("org.postgresql:postgresql:42.7.7")
    }
}
project(":varstore-core") {
    dependencies { "api"(project(":varstore-api")); "implementation"(project(":varstore-postgres")); "testImplementation"(project(":varstore-testkit")) }
}
project(":varstore-testkit") {
    dependencies { "api"(project(":varstore-api")); "implementation"(project(":varstore-core")); "implementation"(project(":varstore-postgres")) }
    tasks.register<JavaExec>("faultHarness") {
        classpath = project.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
        mainClass.set("kr.lunaf.varstore.testkit.FaultHarness")
        args(providers.gradleProperty("faultMode").getOrElse("commit-response"))
    }
    val runtimeClasspath = tasks.register("writeRuntimeClasspath") {
        dependsOn("classes")
        doLast {
            layout.buildDirectory.file("runtime-classpath.txt").get().asFile.writeText(
                project.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath.asPath)
        }
    }
    tasks.named("assemble") { dependsOn(runtimeClasspath) }
}
listOf(":varstore-paper", ":varstore-tools").forEach { name ->
    project(name) {
        apply(plugin = "com.gradleup.shadow")
        dependencies { "implementation"(project(":varstore-core")); "implementation"(project(":varstore-postgres")) }
        tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
            archiveClassifier.set("")
            relocate("com.zaxxer.hikari", "kr.lunaf.varstore.internal.hikari")
            relocate("org.postgresql", "kr.lunaf.varstore.internal.postgresql")
            mergeServiceFiles()
        }
        tasks.named<Jar>("jar") { archiveClassifier.set("thin") }
        tasks.named("assemble") { dependsOn("shadowJar") }
    }
}
project(":varstore-paper") {
    dependencies { "compileOnly"("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT") }
    tasks.withType<ProcessResources>().configureEach { filesMatching("plugin.yml") { expand("version" to project.version) } }
    tasks.withType<Jar>().configureEach { manifest.attributes["paperweight-mappings-namespace"] = "mojang" }
}
project(":varstore-tools") {
    tasks.withType<Jar>().configureEach { manifest.attributes["Main-Class"] = "kr.lunaf.varstore.tools.Main" }
}
project(":varstore-testkit-paper") {
    dependencies { "compileOnly"(project(":varstore-api")); "compileOnly"("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT") }
    tasks.withType<ProcessResources>().configureEach { filesMatching("plugin.yml") { expand("version" to project.version) } }
    tasks.withType<Jar>().configureEach { manifest.attributes["paperweight-mappings-namespace"] = "mojang" }
}
listOf(":examples:preferences", ":examples:rewards").forEach { name ->
    project(name) {
        dependencies { "compileOnly"(project(":varstore-api")); "compileOnly"(project(":varstore-paper")); "compileOnly"("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT") }
        tasks.withType<ProcessResources>().configureEach { filesMatching("plugin.yml") { expand("version" to project.version) } }
        tasks.withType<Jar>().configureEach { manifest.attributes["paperweight-mappings-namespace"] = "mojang" }
    }
}
