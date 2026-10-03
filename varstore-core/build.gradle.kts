plugins {
    `java-library`
}

dependencies {
    api(project(":varstore-api"))
    implementation(project(":varstore-postgres"))
    testImplementation(project(":varstore-testkit"))
    testImplementation(project(":varstore-cache"))
}
