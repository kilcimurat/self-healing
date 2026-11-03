plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.logback)
}

application {
    mainClass.set("io.selfhealing.server.MainKt")
}
