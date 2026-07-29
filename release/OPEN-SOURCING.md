# Open-sourcing OffBridge

Goal: publish the app source under Apache-2.0 without publishing internal notes, and qualify
for a Certum Open Source code-signing certificate (~US$50/yr), which is the route left open
after Azure Artifact Signing turned out to be USA/Canada-only for individuals. See
[WINDOWS-SIGNING-SAC.md](WINDOWS-SIGNING-SAC.md).

The public repo is a **curated snapshot with no history**. This repo stays private and keeps
the full 131-commit history, `release/`, `.claude/`, `CLAUDE.md`, `PRODUCT.md` and the website.

## Done already

- [x] Licence chosen: **Apache-2.0** — patent grant, and clause 6 withholds trademark rights,
      so the "OffBridge" name is not licensed along with the code.
- [x] `LICENSE` downloaded verbatim from apache.org (sha256 `cfc7749b…3d30`, 202 lines).
      Re-verified 29 Jul: the file still hashes to the canonical text, unmodified.
- [x] `NOTICE` written: copyright, the one bundled font (IBM Plex Sans) and its SIL OFL
      attribution, and the third-party dependency licences. The Geist/Inter/Roboto files were
      deleted from both clients, so only Plex is redistributed now.
- [x] `licenses/OFL-1.1.txt` — the OFL requires its text to ship alongside the font files.
- [x] `CLAUDE.md`, `.claude/`, `skill-observations/` untracked and gitignored.
- [x] `release/seed-public-repo.ps1` written and test-run: 501 files staged, nothing private.
- [x] History scanned: no key material in any of the 131 commits, no hardcoded tokens.

## Before pushing

1. ~~**Confirm Roboto's licence.**~~ Moot — `roboto_variable.ttf` was deleted along with the
   Geist and Inter files. `find . -iname "*.ttf"` now returns only `ibm_plex_sans_variable.ttf`
   (one copy per client) plus `website/fonts/geist_variable.ttf`, and the website is excluded
   from the public tree.
2. **Confirm Facebook Shimmer's licence.** `NOTICE` claims BSD for `com.facebook.shimmer:shimmer:0.5.0`.
   No POM or embedded licence file is in the Gradle cache to check it against, and Meta relicensed
   several Android libraries from BSD to MIT. Read the `LICENSE` file in `facebook/shimmer-android`
   at the 0.5.0 tag and correct the `NOTICE` line if it says MIT. This is the one dependency claim
   that is currently unverified.
3. **Commit the groundwork here** — `LICENSE`, `NOTICE`, `licenses/`, the `.gitignore`
   change and the 20 untracked agent files.
4. **Create the GitHub repo**: public, **empty** — no README, no .gitignore, no licence, or
   the push will conflict.

## Publishing

```powershell
.\release\seed-public-repo.ps1 -Destination C:\Users\josep\AndroidStudioProjects\OffBridge-OSS -RepoSlug <owner>/<repo>
```

Read the UNCLASSIFIED list it prints. Anything at the repo root that is in neither the include
nor the exclude list is named there and left out — that is deliberate, so new files surface
instead of being published by accident. The first run flagged `.impeccable`,
`Context and References File`, `Installation package (Latest only)`, `JDK Passkey`,
`PORTFOLIO.md` and `SESSION-LOG.md`.

Then review the tree, and only then:

```powershell
cd C:\Users\josep\AndroidStudioProjects\OffBridge-OSS
git remote add origin https://github.com/<owner>/<repo>.git
git push -u origin main
```

## After it is public

1. Check the live repo — spot-check that `release/`, `.claude/` and `website/` are absent.
2. Apply to Certum with the public repo URL as proof of an active open-source project.
   Take the SimplySign **cloud** variant; a smartcard and reader shipped to Malaysia is slower
   and no better. Budget notarised ID and a utility bill.
3. Add the repo link to the website and the app's about screen.
4. Once the certificate lands, wire signing into the build: the jpackage launcher, the skiko
   DLL, then the installer. Details in [WINDOWS-SIGNING-SAC.md](WINDOWS-SIGNING-SAC.md).

## Keeping the two repos in sync — unsolved

`seed-public-repo.ps1` refuses a non-empty destination, so it seeds once and does not update.
The likely model is a **snapshot per release**: re-copy the tree into the existing public
checkout, commit, push — public history at release granularity rather than per-commit. That
sync mode still needs writing, and the release ritual in
[../.claude/skills/commit-windows-version/SKILL.md](../.claude/skills/commit-windows-version/SKILL.md)
needs a step for it.

Alternative worth weighing: make the **public** repo the source of truth for app code and
keep this one for internal docs only. More upheaval now, no ongoing sync tax.

## Things a licence does not do

- The name and branding stay unprotected — anyone can fork, rebuild and list "OffBridge"
  on Play. Apache-2.0 clause 6 helps but is not a trademark registration.
- Your Android signing key was never committed and stays private, so nobody can push updates
  to the existing Play listing. That is the one that would have been unrecoverable.
- ML Kit barcode scanning is a proprietary Google SDK. It is fine to depend on under
  Apache-2.0, but it is why SignPath Foundation's free signing (no proprietary components)
  is likely closed to this project.
