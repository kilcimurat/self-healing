import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":core"))
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(libs.coroutines.swing)
            implementation(libs.logback)
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.selfhealing.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "SelfHealing"
            packageVersion = "1.0.0"
            // Drawn by icons/draw_icon.py (python3 icons/draw_icon.py icons/icon.png, then iconutil / magick).
            macOS { iconFile.set(project.file("icons/icon.icns")) }
            windows { iconFile.set(project.file("icons/icon.ico")) }
            linux { iconFile.set(project.file("icons/icon.png")) }
        }
    }
}

kotlin {
    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
