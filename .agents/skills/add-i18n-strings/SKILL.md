---
name: add-i18n-strings
description: Add, change, or remove user-facing strings across both platforms' localization files (10 Android strings.xml + 10 Windows translation blocks). Use for any user-visible string change in this repo.
---

# Add i18n Strings

One logical string change in this repo fans out to **10 Android locale files** (`app/src/main/res/values*/strings.xml`: en, zh-rCN, zh-rTW, es, fr, ja, de, pt, in, ru) plus **10 contiguous language blocks** in `Window Ver/src/main/resources/translation.json.txt`. Correctness by grep-count beats correctness by memory.

## The ritual

1. **Script it with Node** (no Python on this machine). Anchor each insertion on a **per-locale unique existing line** — the translated value of a neighbouring key (e.g. each locale's `send_via_title` translation), never the English text or a line number.

2. **Android XML rules:** escape apostrophes as `\'` (French breaks AAPT2 otherwise — passes compile, fails `mergeDebugResources`); placeholders are `%1$s`/`%2$d` (positional); units like MB/GB stay untranslated per existing convention.

3. **Windows translation.json.txt rules:** the loader is a fragile regex/indexOf parser — every key must land INSIDE its contiguous language block, with strict comma discipline; placeholders are `%s` (non-positional). The **repo-root `translation.json.txt` is a secondary copy** (the classpath loads `Window Ver/src/main/resources/`); keep both in sync or note the skip. **Windows tutorial/onboarding step text is hardcoded English** — do not add tutorial keys there; Android tutorial text IS localized.

4. **Removals: split-and-filter, never multiline regex.** `content.split(/(?<=\n)/)` keeping terminators, filter by `line.includes(key)`, rejoin — JS multiline `^` matches inside CRLF pairs and corrupts mixed-EOL files. Assert the expected removal count in the script and abort on mismatch.

5. **Verify by count, then by build:**
   - `grep -c` the key: expect exactly 10 hits per platform file set (× number of keys).
   - `git diff --numstat`: expect exactly N insertions/deletions, no whole-file churn (EOL damage shows here).
   - Build with `:app:assembleDebug` (NOT compile-only — resource changes need the AAPT2 flatten) + `:window-ver:test` (the translation-parser test exercises the blocks). See the `verify` skill.

## Sibling updates that ride along

Tutorials/onboarding text and the website privacy policy (10 languages) are part of the same fan-out invariant when the feature's copy changes — check whether they need the same edit (AGENTS.md Cross-Platform rule).
