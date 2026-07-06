/* ==========================================================================
   main.js — nav scroll state, mobile menu, year stamp, decorative QR matrices.
   ========================================================================== */
(function () {
  "use strict";

  document.addEventListener("DOMContentLoaded", function () {
    initNavScroll();
    initMobileMenu();
    initSmoothAnchors();
    stampYear();
    initLadder();
    initCursorGlow();
  });

  /* --- Page-wide cursor glow: a soft light that trails the pointer ----------
     The rendered position eases toward the pointer each frame (same lerp feel
     as the card spotlights), so the glow follows wherever the cursor goes with
     a slight lag. Mouse-only (no persistent cursor on touch) and skipped under
     reduced motion. The rAF loop self-stops when the pointer rests. */
  function initCursorGlow() {
    var glow = document.querySelector(".cursor-glow");
    if (!glow) return;
    var mm = window.matchMedia;
    if (mm && mm("(prefers-reduced-motion: reduce)").matches) return;
    if (mm && !mm("(hover: hover) and (pointer: fine)").matches) return;

    var curX = 0,
      curY = 0,
      tgtX = 0,
      tgtY = 0;
    var raf = 0;
    var on = false;
    var K = 0.16; // per-frame ease fraction — lower trails further behind

    function frame() {
      var dx = tgtX - curX;
      var dy = tgtY - curY;
      curX += dx * K;
      curY += dy * K;
      glow.style.transform =
        "translate3d(" + curX.toFixed(1) + "px," + curY.toFixed(1) + "px,0)";
      if (Math.abs(dx) > 0.3 || Math.abs(dy) > 0.3) {
        raf = window.requestAnimationFrame(frame);
      } else {
        raf = 0;
      }
    }

    window.addEventListener(
      "pointermove",
      function (e) {
        if (e.pointerType && e.pointerType !== "mouse") return;
        tgtX = e.clientX;
        tgtY = e.clientY;
        if (!on) {
          // First move: snap so the glow doesn't streak in from the corner.
          on = true;
          curX = tgtX;
          curY = tgtY;
          glow.style.transform =
            "translate3d(" + curX + "px," + curY + "px,0)";
          glow.classList.add("is-on");
        }
        if (!raf) raf = window.requestAnimationFrame(frame);
      },
      { passive: true }
    );

    // Fade out when the pointer leaves the window; restore when it returns.
    var root = document.documentElement;
    root.addEventListener("mouseleave", function () {
      glow.classList.remove("is-on");
    });
    root.addEventListener("mouseenter", function () {
      if (on) glow.classList.add("is-on");
    });
  }

  /* --- Precise in-page anchor scrolling ----------------------------------
     Native `#anchor` jumps land on the target's top edge. Our sections carry
     several rem of top padding, so a raw jump to #download parks you on empty
     space above the heading. Instead we scroll to the section's HEADING and
     offset by the fixed nav height, so every link lands the content just below
     the bar. Falls back to the element itself for non-section targets. */
  function initSmoothAnchors() {
    var reduce =
      window.matchMedia &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    var navH =
      parseInt(
        getComputedStyle(document.documentElement).getPropertyValue("--nav-h"),
        10
      ) || 64;

    document.addEventListener("click", function (e) {
      var link = e.target.closest && e.target.closest('a[href^="#"]');
      if (!link) return;
      var hash = link.getAttribute("href");
      if (!hash || hash === "#") return;

      var target;
      try {
        target = document.querySelector(hash);
      } catch (err) {
        return; // malformed selector
      }
      if (!target) return;

      // For a section, prefer its heading so we skip the section's top padding.
      var anchor = target;
      if (target.matches("section")) {
        anchor =
          target.querySelector(".section-head, .dl-head, h1, h2") || target;
      }

      e.preventDefault();
      var y =
        window.scrollY + anchor.getBoundingClientRect().top - (navH + 14);
      window.scrollTo({
        top: Math.max(0, Math.round(y)),
        behavior: reduce ? "auto" : "smooth"
      });
      // Keep the URL and focus in sync without a second scroll jump.
      history.replaceState(null, "", hash);
      if (target.getAttribute("tabindex") === null) {
        target.setAttribute("tabindex", "-1");
      }
      if (target.focus) target.focus({ preventScroll: true });
    });
  }

  /* --- Transport-ladder loop: cycle the active rung while on screen ------- */
  function initLadder() {
    var box = document.querySelector("[data-ladder]");
    if (!box) return;
    var steps = Array.prototype.slice.call(box.querySelectorAll(".ladder-step"));
    if (!steps.length) return;

    var reduce =
      window.matchMedia &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    if (reduce || !("IntersectionObserver" in window)) {
      steps[0].classList.add("is-active");
      return;
    }

    var idx = 0;
    var timer = null;

    function tick() {
      steps.forEach(function (s, i) {
        s.classList.toggle("is-active", i === idx);
      });
      idx = (idx + 1) % steps.length;
    }

    var io = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (e) {
          if (e.isIntersecting && !timer) {
            tick();
            timer = window.setInterval(tick, 1300);
          } else if (!e.isIntersecting && timer) {
            window.clearInterval(timer);
            timer = null;
          }
        });
      },
      { threshold: 0.3 }
    );
    io.observe(box);
  }

  /* --- Nav: add glass background after scrolling past the hero top -------- */
  function initNavScroll() {
    var nav = document.querySelector(".nav");
    if (!nav) return;
    var ticking = false;
    function update() {
      nav.classList.toggle("is-scrolled", window.scrollY > 12);
      ticking = false;
    }
    window.addEventListener(
      "scroll",
      function () {
        if (!ticking) {
          window.requestAnimationFrame(update);
          ticking = true;
        }
      },
      { passive: true }
    );
    update();
  }

  /* --- Mobile menu: slide panel, ESC to close, focus management ---------- */
  function initMobileMenu() {
    var burger = document.getElementById("nav-burger");
    var menu = document.getElementById("mobile-menu");
    if (!burger || !menu) return;

    function setOpen(open) {
      menu.classList.toggle("is-open", open);
      burger.setAttribute("aria-expanded", String(open));
      menu.setAttribute("aria-hidden", String(!open));
      document.body.style.overflow = open ? "hidden" : "";
      if (open) {
        var first = menu.querySelector("a, button");
        if (first) first.focus();
      }
    }

    burger.addEventListener("click", function () {
      setOpen(!menu.classList.contains("is-open"));
    });

    menu.addEventListener("click", function (e) {
      if (e.target.closest("a")) setOpen(false);
    });

    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && menu.classList.contains("is-open")) {
        setOpen(false);
        burger.focus();
      }
    });

    // Close if resized up to desktop.
    window.matchMedia("(min-width: 920px)").addEventListener("change", function (e) {
      if (e.matches) setOpen(false);
    });
  }

  /* --- Year in footer ---------------------------------------------------- */
  function stampYear() {
    var el = document.getElementById("year");
    if (el) el.textContent = String(new Date().getFullYear());
  }

})();
