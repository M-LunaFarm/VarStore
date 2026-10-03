plugins {
    `java-library`
}

dependencies {
    api(project(":varstore-api"))
    implementation(libs.hikari)
    implementation(libs.postgresql)
}
