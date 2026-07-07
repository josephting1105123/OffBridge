/* ==========================================================================
   config.js — SINGLE SOURCE OF TRUTH for download links + metadata.
   Edit ONLY this file when you cut a new release.

   How to get the URLs (no GitHub browsing for your visitors):
   1. Create a GitHub Release and attach the two binaries, keeping the asset
      filenames STABLE across releases (don't put the version in the name) so
      the "latest" link always resolves:
        ClipboardShare-android.apk
        ClipboardShare-windows-setup.exe
   2. The stable direct-download URL pattern is:
        https://github.com/<owner>/<repo>/releases/latest/download/<asset-name>
      Clicking it downloads the file instantly — the visitor never sees a
      GitHub page.
   3. Paste those two URLs below, set the real file sizes, and (optionally) the
      SHA-256 for the privacy-minded. Leaving url as "#" shows a "coming soon"
      notice and disables the button.
   ========================================================================== */

window.CS_CONFIG = {
  // Set these once you know your repo. Used only to BUILD the latest-download
  // URLs below if you prefer; you can also paste full URLs directly.
  repo: {
    owner: "josephting1105123",
    name: "ClipboardShare-Showcase",
  },

  android: {
    label: "Get it on Google Play",
    os: "Android",
    fileType: "Google Play",
    version: "3.5.3",
    size: "TBD", // store link — no size/sha shown
    // Store listing, not a direct APK: the button opens Google Play.
    url: "https://play.google.com/store/apps/details?id=com.EntropicSoftwareLab.ClipboardShare",
    sha256: "",
    note: "Android 10+ (API 29). Free on Google Play.",
  },

  windows: {
    label: "Download for Windows",
    os: "Windows",
    fileType: "Installer (.exe)",
    version: "3.5.6",
    size: "83 MB",
    // Direct release asset — downloads instantly, hosted free on GitHub.
    url: "https://github.com/josephting1105123/ClipboardShare-Showcase/releases/latest/download/ClipboardShareSetup.exe",
    sha256: "0e6c70f2a649fea469e0f42b4f57e26a472380d007f7f4cd00e8fd0f53342249",
    note: "Windows 10/11 (64-bit). Bundled runtime — no Java needed.",
  },

  meta: {
    appName: "Clipboard Share",
    vendor: "EntropicSoftwareLab",
    tagline: "Off-grid clipboard sync & file transfer. No cloud. No internet.",
    changelogUrl: "#",
  },
};

/* Helper: build a stable "latest release" asset URL from repo + filename.
   Returns null if the repo placeholders are unset. */
window.CS_CONFIG.latestUrl = function (assetName) {
  var r = window.CS_CONFIG.repo;
  if (!r || /REPLACE/.test(r.owner) || /REPLACE/.test(r.name)) return null;
  return (
    "https://github.com/" +
    r.owner +
    "/" +
    r.name +
    "/releases/latest/download/" +
    assetName
  );
};
