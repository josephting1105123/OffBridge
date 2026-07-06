plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

group = "com.EntropicSoftwareLab.ClipboardShare"
version = "3.5.2"

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

    // JSON persistence (transfer history, config) — routed through the shared version catalog so
    // both clients pin one gson version.
    implementation(libs.gson)
    // QR generation for off-grid "send via QR" (pure-JVM, no Android deps) — routed through the shared
    // version catalog (zxing 3.5.3, same alias :app uses) so both clients pin one zxing version.
    implementation(libs.zxing.core)
    // Windows DPAPI (Crypt32) for encrypting pairing keys at rest in config.json. Windows-only;
    // SecurityVault degrades to plaintext on non-Windows (e.g. Linux CI) so tests still run.
    implementation(libs.jna.platform)

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.joseph.clipboard.windows.MainKt"
        System.getenv("JAVA_HOME")?.let { javaHome = it }
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "Clipboard Share"
            packageVersion = "3.5.2"
            description = "Off-grid file transfer and clipboard sync between Windows and Android"
            vendor = "EntropicSoftwareLab" // must match the Inno installer's MyAppPublisher
            windows {
                menu = true
                shortcut = true
                menuGroup = "Clipboard Share"
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "12255bbe-e53b-408b-8866-27ab965d1394"
                // No iconFile is set (the repo has no .ico asset yet), so the packaged app and its
                // Start Menu/desktop shortcuts use the generic default icon. When an app.ico lands,
                // wire it with:
                //   iconFile.set(project.file("src/main/resources/windows/app.ico"))
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
