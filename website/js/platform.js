/* ==========================================================================
   platform.js — reads CS_CONFIG to wire the download cards, detects the
   visitor's OS to highlight the right one, and fills version/size badges.
   Buttons are real <a download> links and work without JS; this only enriches.
   ========================================================================== */
(function () {
  "use strict";

  document.addEventListener("DOMContentLoaded", function () {
    var cfg = window.CS_CONFIG;
    if (!cfg) return;

    var aReady = wireCard("android", cfg.android);
    var wReady = wireCard("windows", cfg.windows);

    // Hide the "links not wired yet" setup banner once both are configured.
    var todo = document.querySelector(".dl-todo");
    if (todo && aReady && wReady) todo.hidden = true;

    detectOS().then(highlight);
  });

  function wireCard(key, data) {
    var card = document.querySelector('[data-dl-card="' + key + '"]');
    if (!card || !data) return false;

    // Resolve URL: explicit url wins; else try repo+stable filename.
    var url = data.url;
    if ((!url || url === "#") && window.CS_CONFIG.latestUrl) {
      var asset =
        key === "android"
          ? "ClipboardShare-android.apk"
          : "ClipboardShare-windows-setup.exe";
      var built = window.CS_CONFIG.latestUrl(asset);
      if (built) url = built;
    }

    var link = card.querySelector("[data-dl-link]");
    var ready = url && url !== "#";

    if (link) {
      if (ready) {
        link.setAttribute("href", url);
        link.setAttribute("download", "");
        link.removeAttribute("aria-disabled");
        link.classList.remove("is-disabled");
        link.querySelector("[data-dl-label]").textContent = data.label;
      } else {
        link.setAttribute("href", "#download");
        link.setAttribute("aria-disabled", "true");
        link.classList.add("is-disabled");
        link.querySelector("[data-dl-label]").textContent = "Coming soon";
      }
      // Descriptive accessible name.
      link.setAttribute(
        "aria-label",
        "Download " +
          window.CS_CONFIG.meta.appName +
          " " +
          data.version +
          " for " +
          data.os +
          ", " +
          data.fileType +
          (data.size && data.size !== "TBD" ? ", " + data.size : "")
      );
    }

    setText(card, "[data-dl-version]", "v" + data.version);
    var sizeEl = card.querySelector("[data-dl-size]");
    if (sizeEl) {
      if (data.size && data.size !== "TBD") sizeEl.textContent = data.size;
      else sizeEl.remove();
    }
    setText(card, "[data-dl-type]", data.fileType);
    setText(card, "[data-dl-note]", data.note || "");

    // SHA-256 verify block (privacy-minded), only if present.
    var sha = card.querySelector("[data-dl-sha]");
    if (sha) {
      if (data.sha256) sha.querySelector("code").textContent = data.sha256;
      else sha.remove();
    }

    // Surface a global TODO banner if neither link is wired.
    if (!ready) card.setAttribute("data-not-ready", "true");
    return ready;
  }

  function setText(scope, sel, text) {
    var el = scope.querySelector(sel);
    if (el) el.textContent = text;
  }

  /* OS detection: userAgentData (modern) -> high-entropy -> UA regex. */
  function detectOS() {
    var uaData = navigator.userAgentData;
    if (uaData && uaData.platform) {
      var p = uaData.platform.toLowerCase();
      if (uaData.mobile && /android/.test(p)) return Promise.resolve("android");
      if (/windows/.test(p)) return Promise.resolve("windows");
      if (uaData.getHighEntropyValues) {
        return uaData
          .getHighEntropyValues(["platform"])
          .then(function (v) {
            return classify((v.platform || "").toLowerCase(), uaData.mobile);
          })
          .catch(function () {
            return fromUA();
          });
      }
    }
    return Promise.resolve(fromUA());
  }

  function classify(platform, mobile) {
    if (/android/.test(platform) || (mobile && /android/.test(platform)))
      return "android";
    if (/windows/.test(platform)) return "windows";
    return fromUA();
  }

  function fromUA() {
    var ua = navigator.userAgent || "";
    if (/Android/i.test(ua)) return "android";
    if (/Windows NT|Win64|Win32/i.test(ua)) return "windows";
    return "other";
  }

  function highlight(os) {
    var note = document.querySelector("[data-dl-othernote]");
    if (os === "android" || os === "windows") {
      var card = document.querySelector('[data-dl-card="' + os + '"]');
      if (card) {
        card.classList.add("is-detected");
        var tag = card.querySelector("[data-dl-detected]");
        if (tag) tag.textContent = "Detected — recommended for you";
        // Put the detected card first on small screens.
        var grid = card.parentNode;
        if (grid && window.matchMedia("(max-width: 719px)").matches) {
          grid.insertBefore(card, grid.firstChild);
        }
      }
      if (note) note.hidden = true;
    } else if (note) {
      note.hidden = false;
    }
  }
})();
