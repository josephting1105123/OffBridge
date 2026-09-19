---
name: verify
description: Build-verify both platforms after multi-file changes. Runs the Android Gradle build and the Windows (:window-ver) module build, reports any compile/AAPT2 errors, and fixes self-introduced syntax issues before declaring done. Use after finishing a multi-file change, before claiming a task complete.
---

# Verify

Run the Android Gradle build and the Windows module build. Report any compile/AAPT2 errors and fix self-introduced syntax issues before declaring done.

## Pick the task by what changed — not by what runs fastest

- **Pure Kotlin changes:** compile-only is sufficient (`:app:compileDebugKotlin` / `:window-ver:compileKotlin`).
- **Anything under `res/**` (strings, layouts, drawables, values): run `:app:assembleDebug`.** Compile-only stops at R-file generation and never reaches the AAPT2 flatten — an unescaped `'` / `&` / stray `%` in a translated value passes compile and breaks the real build. "It compiles" and "it builds" are different guarantees.
- **Cross-platform changes:** batch both platforms into ONE invocation so Gradle's configuration cost is paid once:
  ```bash
  JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat :app:assembleDebug :window-ver:compileKotlin
  ```
  (substitute the compile-only pair for pure-Kotlin diffs). Keep separate invocations only for single-platform work.

## Steps

1. **Android build** (task chosen per the rules above):
   ```bash
   JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat :app:assembleDebug
   ```
   Faster compile-only check (pure-Kotlin diffs only): `:app:compileDebugKotlin`

2. **Windows module build** (Compose Desktop `:window-ver`):
   ```bash
   JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat :window-ver:compileKotlin
   ```
   For a full desktop build instead of compile-only: `:window-ver:assemble`

3. **Report** the result of each build (BUILD SUCCESSFUL / the exact error lines). Pre-existing deprecation warnings are fine to note and ignore; **self-introduced** compile/AAPT2 errors (stray comment delimiters, missing imports, unresolved references, bad XML) must be fixed, then re-run the affected build.

4. Do **not** declare the task done until both relevant builds compile clean. If a change is Android-only or Windows-only, build just that platform and say which you skipped and why.

## Interpreting results

- **`UP-TO-DATE` on a test/build task is a cached green, not fresh proof.** Gradle is asserting the *previous* run passed. When the deliverable needs genuine evidence ("tests pass, verified this session"), defeat the cache — `:window-ver:cleanTest :window-ver:test` or `--rerun-tasks` — and take exact counts from the JUnit XML under the module's `test-results` dir, not the console summary line. Distinguish "compiles + packages" from "tests executed".
- **A compile error at a line this session never touched, in a file reported as externally modified** (the user's IDE has it open): re-run the same build once before investigating. The compile may have raced an IDE auto-save; only debug a failure that reproduces.
