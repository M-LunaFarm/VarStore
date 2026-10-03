plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":varstore-core"))
    implementation(project(":varstore-postgres"))
}

tasks.withType<Jar>().configureEach {
    manifest.attributes["Main-Class"] = "kr.lunaf.varstore.tools.Main"
}
