plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":varstore-api"))
    compileOnly(project(":varstore-paper"))
    compileOnly(project(":varstore-codec"))
}
