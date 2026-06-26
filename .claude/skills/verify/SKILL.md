---
name: verify
description: Build-verify both platforms after multi-file changes. Runs the Android Gradle build and the Windows (:window-ver) module build, reports any compile/AAPT2 errors, and fixes self-introduced syntax issues before declaring done. Use after finishing a multi-file change, before claiming a task complete.
---

# Verify

Run the Android Gradle build and the Windows module build. Report any compile/AAPT2 errors and fix self-introduced syntax issues before declaring done.

## Steps

1. **Android build** (full APK assembles resources + Kotlin; use the compile-only task for a faster check):
   ```bash
   JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat :app:assembleDebug
   ```
   Faster compile-only check (skips packaging/AAPT2): `:app:compileDebugKotlin`

2. **Windows module build** (Compose Desktop `:window-ver`):
   ```bash
   JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat :window-ver:compileKotlin
   ```
   For a full desktop build instead of compile-only: `:window-ver:assemble`

3. **Report** the result of each build (BUILD SUCCESSFUL / the exact error lines). Pre-existing deprecation warnings are fine to note and ignore; **self-introduced** compile/AAPT2 errors (stray comment delimiters, missing imports, unresolved references, bad XML) must be fixed, then re-run the affected build.

4. Do **not** declare the task done until both relevant builds compile clean. If a change is Android-only or Windows-only, build just that platform and say which you skipped and why.
