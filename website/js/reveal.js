/* ==========================================================================
   reveal.js — scroll-SCRUBBED motion engine.
   Each [data-reveal] / [data-parallax] element gets a CSS var --p (0..1) tied
   directly to its position in the viewport, updated every animation frame on
   scroll. CSS maps --p to opacity / transform, so motion advances as you
   scroll down and rewinds as you scroll up. Count-ups fire once when revealed.
   Honors prefers-reduced-motion (everything snaps to its final frame).
   ========================================================================== */
(function () {
  "use strict";

  var reduce =
    window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  var reveals = [];
  var parallax = [];
  var counted = [];

  document.addEventListener("DOMContentLoaded", function () {
    reveals = toArr(document.querySelectorAll("[data-reveal]"));
    parallax = toArr(document.querySelectorAll("[data-parallax]"));
    counted = toArr(document.querySelectorAll("[data-countup]")).map(function (el) {
      return { el: el, done: false };
    });

    if (reduce || !("requestAnimationFrame" in window)) {
      reveals.forEach(function (el) {
        el.style.setProperty("--p", "1");
      });
      parallax.forEach(function (el) {
        el.style.setProperty("--p", "0.5");
      });
      counted.forEach(function (c) {
        c.el.textContent = c.el.getAttribute("data-countup");
      });
      return;
    }

    update();
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onScroll, { passive: true });
    // a couple of delayed passes so late layout (fonts/images) is accounted for
    window.setTimeout(update, 300);
    window.addEventListener("load", update);
  });

  var ticking = false;
  function onScroll() {
    if (!ticking) {
      window.requestAnimationFrame(function () {
        update();
        ticking = false;
      });
      ticking = true;
    }
  }

  function update() {
    var vh = window.innerHeight || document.documentElement.clientHeight;

    for (var i = 0; i < reveals.length; i++) {
      var el = reveals[i];
      var r = el.getBoundingClientRect();
      if (r.height === 0 && r.width === 0) continue;
      // 0 when the element's top is near the bottom of the viewport,
      // 1 by the time it has risen to ~55% up. Clamped + reversible.
      var start = vh * 0.92;
      var end = vh * 0.5;
      var p = clamp01((start - r.top) / (start - end));
      el.style.setProperty("--p", p.toFixed(3));
    }

    for (var j = 0; j < parallax.length; j++) {
      var pel = parallax[j];
      var pr = pel.getBoundingClientRect();
      // pass-through progress: 0 entering bottom -> 1 leaving top (0.5 = centered)
      var pp = clamp01((vh - pr.top) / (vh + pr.height));
      pel.style.setProperty("--p", pp.toFixed(3));
    }

    for (var k = 0; k < counted.length; k++) {
      var c = counted[k];
      if (c.done) continue;
      var cr = c.el.getBoundingClientRect();
      if (cr.top < vh * 0.9 && cr.bottom > 0) {
        c.done = true;
        countUp(c.el);
      }
    }
  }

  function clamp01(v) {
    return v < 0 ? 0 : v > 1 ? 1 : v;
  }
  function toArr(nl) {
    return Array.prototype.slice.call(nl);
  }

  function countUp(el) {
    var target = el.getAttribute("data-countup");
    var num = parseFloat(target.replace(/[^0-9.]/g, ""));
    if (isNaN(num)) {
      el.textContent = target;
      return;
    }
    var prefix = target.slice(0, target.search(/[0-9.]/));
    var suffix = target.slice(prefix.length + String(num).length);
    var dur = 1100;
    var start = null;
    function frame(ts) {
      if (start === null) start = ts;
      var p = Math.min((ts - start) / dur, 1);
      var eased = p === 1 ? 1 : 1 - Math.pow(2, -10 * p);
      el.textContent = prefix + Math.round(num * eased) + suffix;
      if (p < 1) window.requestAnimationFrame(frame);
      else el.textContent = target;
    }
    window.requestAnimationFrame(frame);
  }
})();
