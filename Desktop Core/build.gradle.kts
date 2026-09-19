plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

group = "com.EntropicSoftwareLab.ClipboardShare"

// Matches the Window Ver convention so all three desktop-side modules write to the same build-dir
// name and are covered by one .gitignore idiom.
layout.buildDirectory.set(file("build2"))

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `api`, not `implementation`: :window-ver and :linux-ver build their own Compose UI on top of
    // the shared screens in this module, so they need these on their compile classpath too.
    api(compose.desktop.currentOs)
    api(compose.material)
    api(compose.material3)
    api(compose.ui)
    api(compose.foundation)
    api(compose.materialIconsExtended)

    // JSON persistence (transfer history, config) — routed through the shared version catalog so
    // both clients pin one gson version.
    api(libs.gson)
    // QR generation for off-grid "send via QR" (pure-JVM, no Android deps) — routed through the shared
    // version catalog (zxing 3.5.3, same alias :app uses) so both clients pin one zxing version.
    api(libs.zxing.core)

    // Deliberately NO jna-platform here. The Win32 bindings belong to :window-ver alone, so the
    // Linux artifact never ships them.

    testImplementation(libs.junit)
}
