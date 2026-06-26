/* ==========================================================================
   main.js — nav scroll state, mobile menu, year stamp, decorative QR matrices.
   ========================================================================== */
(function () {
  "use strict";

  document.addEventListener("DOMContentLoaded", function () {
    initNavScroll();
    initMobileMenu();
    stampYear();
    initLadder();
  });

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
