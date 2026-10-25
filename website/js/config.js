/* ==========================================================================
   config.js — SINGLE SOURCE OF TRUTH for download links + metadata.
   Edit ONLY this file when you cut a new release.

   How to get the URLs (no GitHub browsing for your visitors):
   1. Create a GitHub Release and attach the Windows installer, keeping the
      asset filename STABLE across releases (don't put the version in the name)
      so the "latest" link always resolves:
        OffBridge-windows-setup.exe
      (Android is offered only through its Google Play listing, so there is no
      Android binary to attach.)
   2. The stable direct-download URL pattern is:
        https://github.com/<owner>/<repo>/releases/latest/download/<asset-name>
      Clicking it downloads the file instantly — the visitor never sees a
      GitHub page.
   3. Paste that URL below, set the real file size, and (optionally) the
      SHA-256 for the privacy-minded. Leaving url as "#" shows a "coming soon"
      notice and disables the button.
   ========================================================================== */

window.CS_CONFIG = {
  // Set these once you know your repo. Used only to BUILD the latest-download
  // URLs below if you prefer; you can also paste full URLs directly.
  repo: {
    owner: "josephting1105123",
    name: "OffBridge-Showcase",
  },

  android: {
    label: "Get it on Google Play",
    os: "Android",
    fileType: "Google Play",
    // Tracks what Play actually SERVES: a build has to clear review and finish rollout before
    // this may move. Bump it only once the new version is live on the store listing.
    version: "3.7.5",
    size: "TBD", // store link — no size/sha shown
    url: "https://play.google.com/store/apps/details?id=com.EntropicSoftwareLab.ClipboardShare",
    sha256: "",
    note: "Android 10+ (API 29). Free.",
  },

  windows: {
    label: "Download for Windows",
    os: "Windows",
    fileType: "Installer (.exe)",
    version: "3.7.10",
    size: "81 MB",
    // Direct release asset — downloads instantly, hosted free on GitHub.
    url: "https://github.com/josephting1105123/OffBridge-Showcase/releases/latest/download/OffBridgeSetup.exe",
    sha256: "f50b42e8c2a63e1d8ca56a879d2355736e096588b5ee6e26894009b64c2f2a85",
    note: "Windows 10/11 (64-bit). Bundled runtime — no Java needed.",
  },

  meta: {
    appName: "OffBridge",
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
