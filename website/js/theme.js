/* ==========================================================================
   theme.js — light/dark toggle. Respects prefers-color-scheme on first visit,
   persists an explicit choice in localStorage. Applied as early as possible.
   ========================================================================== */
(function () {
  "use strict";

  var KEY = "cs-theme";
  var root = document.documentElement;

  function systemPrefersLight() {
    return (
      window.matchMedia &&
      window.matchMedia("(prefers-color-scheme: light)").matches
    );
  }

  function resolveInitial() {
    var saved = null;
    try {
      saved = localStorage.getItem(KEY);
    } catch (e) {}
    if (saved === "light" || saved === "dark") return saved;
    return systemPrefersLight() ? "light" : "dark";
  }

  function apply(theme) {
    if (theme === "light") {
      root.setAttribute("data-theme", "light");
    } else {
      root.removeAttribute("data-theme");
    }
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute("content", theme === "light" ? "#F8FAFC" : "#141414");
    var btn = document.getElementById("theme-toggle");
    if (btn) btn.setAttribute("aria-pressed", String(theme === "light"));
  }

  // Apply immediately to avoid a flash.
  var current = resolveInitial();
  apply(current);

  function toggle() {
    current = current === "light" ? "dark" : "light";
    apply(current);
    try {
      localStorage.setItem(KEY, current);
    } catch (e) {}
  }

  document.addEventListener("DOMContentLoaded", function () {
    var btn = document.getElementById("theme-toggle");
    if (btn) {
      apply(current); // re-sync aria once button exists
      btn.addEventListener("click", toggle);
    }
  });

  // Follow the system if the user never made an explicit choice.
  if (window.matchMedia) {
    var mq = window.matchMedia("(prefers-color-scheme: light)");
    var onChange = function (e) {
      var saved = null;
      try {
        saved = localStorage.getItem(KEY);
      } catch (err) {}
      if (saved !== "light" && saved !== "dark") {
        current = e.matches ? "light" : "dark";
        apply(current);
      }
    };
    if (mq.addEventListener) mq.addEventListener("change", onChange);
    else if (mq.addListener) mq.addListener(onChange);
  }
})();
