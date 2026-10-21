# OffBridge — marketing website

A single-page marketing site for **OffBridge** (Android + Windows off-grid
clipboard sync & file transfer). Plain HTML/CSS/JS — **no build step, no
dependencies**. It opens by double-clicking `index.html` and deploys to GitHub
Pages (or any static host) as-is.

## What's here

```
website/
├── index.html          # the whole page: icon sprite, both app renders per section
├── css/                # tokens → base → layout → components (shared with privacy.html)
│                       # app.css       the app screens, rebuilt from the apps' own tokens
│                       # site.css      page chrome, platform switch, scene layout
│                       # download.css, carousel.css, dl-wheel.css  the Download section
├── js/
│   ├── config.js       # ← EDIT THIS to wire the download links (see below)
│   ├── theme.js        # light/dark toggle (privacy.html; index.html inlines a copy)
│   ├── site.js         # Windows | Android switch + the live demos
│   ├── platform.js     # OS auto-detect + download-card wiring from config.js
│   ├── dl-wheel.js     # the Download card wheel
│   └── water.js        # animated water background
├── fonts/              # IBM Plex Sans (numerals, Android pairing screen); Geist (privacy.html)
├── assets/favicon.svg · assets/og-image.png
├── site.webmanifest · robots.txt · sitemap.xml
```

The page shows the apps by rebuilding their screens in HTML, not with
screenshots. Every section holds a Windows render and an Android render;
`html[data-os]` picks one, and the switch in the top bar flips it (with a View
Transition where supported). Colours, radii, type and strings come from the
apps' own theme files and string tables, so when the apps change, update
`css/app.css` and the copy in `index.html` to match. Demo device names are
always "Pixel 10" and "Windows PC". Everything respects
`prefers-reduced-motion`.

## 1. Wire the download buttons (the only required edit)

Visitors get a **one-click direct download** — they never see GitHub. The files
just have to live somewhere with a stable URL. Recommended: **GitHub Releases**.

1. Create a GitHub Release for the app repo and attach the two binaries. Keep the
   asset filenames **stable across releases** (don't put the version in the name)
   so a "latest" link always resolves, e.g.:
   - `OffBridge-android.apk`
   - `OffBridge-windows-setup.exe`
2. Open [`js/config.js`](js/config.js) and either:
   - set `repo.owner` / `repo.name` (the page auto-builds the
     `…/releases/latest/download/<asset>` URLs), **or**
   - paste full URLs directly into `android.url` and `windows.url`.
3. Set the real `size` (e.g. `"124 MB"`), and optionally a `sha256` for the
   "Verify checksum" details.

Until a real URL is set, the button shows **"Coming soon"** (disabled) and a
setup notice appears in the Download section. No other file needs editing.

> Ship a **signed release** APK (`./gradlew :app:assembleRelease`), not the debug
> build, for public distribution. The 124 MB Windows installer exceeds GitHub's
> 100 MB in-repo file limit, which is why downloads point at Release assets rather
> than files committed to this folder.

## 2. Deploy to GitHub Pages

Paths are all **relative**, so the site works at a project sub-path
(`user.github.io/repo/`) with no changes — don't add a `<base>` tag or absolute
paths. The empty `.nojekyll` file tells Pages to serve the folder verbatim.

> **One absolute-URL edit for SEO/social.** The canonical link, Open Graph /
> Twitter tags (in `index.html` `<head>`), `sitemap.xml`, and `robots.txt` each
> contain a `REPLACE_SITE_URL` placeholder — these four are the only things that
> must be absolute. Replace it with your deployed origin (e.g.
> `https://owner.github.io/repo`) so link previews and search indexing resolve.
> The social-card image lives at `assets/og-image.png` (1200×630).

**Option A — GitHub Actions (recommended, keeps the `website/` folder):**
add `.github/workflows/pages.yml` to the repo root:

```yaml
name: Deploy website
on:
  push:
    branches: [main]
    paths: ["website/**", ".github/workflows/pages.yml"]
permissions:
  contents: read
  pages: write
  id-token: write
jobs:
  deploy:
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deploy.outputs.page_url }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/configure-pages@v5
      - uses: actions/upload-pages-artifact@v3
        with:
          path: website
      - id: deploy
        uses: actions/deploy-pages@v4
```

Then in the repo: **Settings → Pages → Build and deployment → Source = GitHub
Actions**.

**Option B — Deploy from a branch (no Actions):** copy the contents of
`website/` into a top-level `docs/` folder, then **Settings → Pages → Source =
Deploy from a branch → `main` / `docs`**.

## Local preview

- Double-click `index.html`, **or**
- `cd website && python -m http.server 8080` → open <http://localhost:8080>.

## Customizing

- **Brand/colors/fonts:** all tokens live in [`css/tokens.css`](css/tokens.css)
  (dark theme is default; `[data-theme="light"]` overrides follow). Swap the font
  by replacing `fonts/geist_variable.ttf` and the `@font-face` `src`.
- **Copy & versions:** edit text directly in `index.html`; the version chips in
  the Download cards are driven by `js/config.js`.
- **Social card:** add `assets/og-image.png` (1200×630) and an
  `<meta property="og:image" content="assets/og-image.png">` tag in the
  `<head>` if you want a rich link preview.
