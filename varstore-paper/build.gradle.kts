plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":varstore-core"))
    implementation(project(":varstore-postgres"))
    implementation(project(":varstore-cache"))
    implementation(project(":varstore-codec"))
    testImplementation(libs.paper)
}
