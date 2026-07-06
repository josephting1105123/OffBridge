/* ==========================================================================
   reveal.js — scroll-SCRUBBED motion engine with lerp smoothing.
   Each [data-reveal] / [data-parallax] element gets a CSS var --p (0..1) tied
   to its position in the viewport. Rather than snap --p to the raw scroll
   position, we ease the displayed --p toward its scroll TARGET each frame
   (exponential smoothing), which turns coarse scroll steps into fluid,
   decelerating motion. The rAF loop self-stops once everything has settled and
   restarts on the next scroll/resize, so idle pages cost nothing.
   Count-ups fire once when revealed. Honors prefers-reduced-motion (everything
   snaps to its final frame; no loop).
   ========================================================================== */
(function () {
  "use strict";

  var reduce =
    window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  // Per-frame fraction of the remaining gap to close. ~0.12 settles in ~18
  // frames (~300ms) — a silkier, more gradual ease-out. Raise toward 0.18 for a
  // snappier feel, drop toward 0.10 for even softer motion.
  var SMOOTH = 0.12;
  var EPS = 0.0015; // within this of target = settled (stop animating that el)

  var reveals = [];
  var parallax = [];
  var counted = [];
  var running = false;
  var needsMeasure = true;

  document.addEventListener("DOMContentLoaded", function () {
    reveals = toArr(document.querySelectorAll("[data-reveal]")).map(function (el) {
      return { el: el, cur: 0, tgt: 0 };
    });
    parallax = toArr(document.querySelectorAll("[data-parallax]")).map(function (el) {
      return { el: el, cur: 0.5, tgt: 0.5 };
    });
    counted = toArr(document.querySelectorAll("[data-countup]")).map(function (el) {
      return { el: el, done: false };
    });

    if (reduce || !("requestAnimationFrame" in window)) {
      reveals.forEach(function (r) {
        r.el.style.setProperty("--p", "1");
      });
      parallax.forEach(function (p) {
        p.el.style.setProperty("--p", "0.5");
      });
      counted.forEach(function (c) {
        c.el.textContent = c.el.getAttribute("data-countup");
      });
      return;
    }

    measure(); // seed targets (+ any count-ups already in view)
    start();
    window.addEventListener("scroll", request, { passive: true });
    window.addEventListener("resize", request, { passive: true });
    // Late layout (fonts / lazy images) can shift positions after first paint.
    window.setTimeout(request, 300);
    window.addEventListener("load", request);
  });

  // Mark targets stale and make sure the loop is spinning.
  function request() {
    needsMeasure = true;
    start();
  }

  function start() {
    if (!running) {
      running = true;
      window.requestAnimationFrame(tick);
    }
  }

  // Read layout and compute each element's scroll TARGET (no visual writes here,
  // so it only runs when scroll/resize marked things stale).
  function measure() {
    var vh = window.innerHeight || document.documentElement.clientHeight;
    var startY = vh * 0.92; // element top here -> 0
    var endY = vh * 0.5; //   element top here -> 1
    var span = startY - endY;

    for (var i = 0; i < reveals.length; i++) {
      var r = reveals[i];
      var rect = r.el.getBoundingClientRect();
      if (rect.height === 0 && rect.width === 0) continue; // hidden/off-layout
      r.tgt = clamp01((startY - rect.top) / span);
    }

    for (var j = 0; j < parallax.length; j++) {
      var p = parallax[j];
      var pr = p.el.getBoundingClientRect();
      // pass-through: 0 entering bottom -> 1 leaving top (0.5 = centered)
      p.tgt = clamp01((vh - pr.top) / (vh + pr.height));
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

    needsMeasure = false;
  }

  function tick() {
    if (needsMeasure) measure();

    var moving = false;

    for (var i = 0; i < reveals.length; i++) {
      var r = reveals[i];
      var d = r.tgt - r.cur;
      if (d > EPS || d < -EPS) {
        r.cur += d * SMOOTH;
        moving = true;
      } else {
        r.cur = r.tgt;
      }
      r.el.style.setProperty("--p", r.cur.toFixed(4));
    }

    for (var j = 0; j < parallax.length; j++) {
      var p = parallax[j];
      var pd = p.tgt - p.cur;
      if (pd > EPS || pd < -EPS) {
        p.cur += pd * SMOOTH;
        moving = true;
      } else {
        p.cur = p.tgt;
      }
      p.el.style.setProperty("--p", p.cur.toFixed(4));
    }

    // Keep looping while anything is still easing, or while a fresh measure is
    // pending; otherwise park until the next scroll/resize wakes us.
    if (moving || needsMeasure) {
      window.requestAnimationFrame(tick);
    } else {
      running = false;
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
    var startTs = null;
    function frame(ts) {
      if (startTs === null) startTs = ts;
      var p = Math.min((ts - startTs) / dur, 1);
      var eased = p === 1 ? 1 : 1 - Math.pow(2, -10 * p);
      el.textContent = prefix + Math.round(num * eased) + suffix;
      if (p < 1) window.requestAnimationFrame(frame);
      else el.textContent = target;
    }
    window.requestAnimationFrame(frame);
  }
})();
