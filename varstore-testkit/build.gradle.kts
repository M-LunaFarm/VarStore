plugins {
    `java-library`
}

dependencies {
    api(project(":varstore-api"))
    implementation(project(":varstore-core"))
    implementation(project(":varstore-postgres"))
    implementation(project(":varstore-cache"))
}

tasks.register<JavaExec>("faultHarness") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("kr.lunaf.varstore.testkit.FaultHarness")
    args(providers.gradleProperty("faultMode").getOrElse("commit-response"))
}

val runtimeClasspath = tasks.register("writeRuntimeClasspath") {
    dependsOn("classes")
    doLast {
        layout.buildDirectory.file("runtime-classpath.txt").get().asFile.writeText(
            sourceSets["main"].runtimeClasspath.asPath,
        )
    }
}

tasks.named("assemble") {
    dependsOn(runtimeClasspath)
}
