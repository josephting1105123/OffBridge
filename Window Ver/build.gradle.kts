plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

group = "com.EntropicSoftwareLab.ClipboardShare"
version = "3.4.0"

layout.buildDirectory.set(file("build2"))



kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.materialIconsExtended)

    implementation("com.google.code.gson:gson:2.10.1")
    // QR generation for off-grid "send via QR" (pure-JVM, no Android deps)
    implementation("com.google.zxing:core:3.5.3")

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.joseph.clipboard.windows.MainKt"
        System.getenv("JAVA_HOME")?.let { javaHome = it }
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "Clipboard Share"
            packageVersion = "3.4.0"
            windows {
                menu = true
                shortcut = true
                menuGroup = "Clipboard Share"
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "12255bbe-e53b-408b-8866-27ab965d1394"
            }
        }
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

afterEvaluate {
    tasks.named("packageMsi") {
        doFirst {
            val resourcesDir = file("${layout.buildDirectory.get()}/compose/tmp/resources")
            resourcesDir.mkdirs()
            file("src/main/resources/windows/main.wxs").copyTo(file("${resourcesDir.absolutePath}/main.wxs"), overwrite = true)
            file("src/main/resources/windows/License.rtf").copyTo(file("${resourcesDir.absolutePath}/License.rtf"), overwrite = true)
        }
    }
}
