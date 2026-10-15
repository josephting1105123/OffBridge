/* ==========================================================================
   flow.js — drives the "How it works" walkthrough and the hero hop chip.

   Walkthrough: .howviz[data-stage] picks which frame of the diagram shows
   (css/flow.css does the animation). While on screen it autoplays: the active
   step's progress bar is a CSS animation, and its animationend advances the
   stage, so pausing the bar (hover on the diagram, keyboard focus on a step,
   scrolled away) pauses the walkthrough with no timer bookkeeping. Choosing a
   step yourself stops autoplay; the Play/Pause link restarts or stops it.

   Hero hop: a chip carrying a copied line leaves the PC screenshot, seals
   (lock + scrambled ciphertext) for the flight, and lands on the phone as
   plain text. Web Animations API along a sampled curve, a few loops, then rest.

   prefers-reduced-motion: no autoplay, no scramble, no hop; steps still work.
   ========================================================================== */
(function () {
  "use strict";

  var reduce =
    window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  var HEX = "0123456789abcdef";

  document.addEventListener("DOMContentLoaded", function () {
    initWalkthrough();
    initHop();
  });

  function scramble(len) {
    var out = "";
    for (var i = 0; i < len; i++) {
      out += i % 5 === 4 ? " " : HEX.charAt((Math.random() * 16) | 0);
    }
    return out;
  }

  /* --- Walkthrough ------------------------------------------------------- */
  function initWalkthrough() {
    var root = document.querySelector("[data-howviz]");
    if (!root) return;
    var steps = Array.prototype.slice.call(root.querySelectorAll(".hstep"));
    var toggle = root.querySelector("[data-howviz-toggle]");
    var flow = root.querySelector(".flow");
    var cipher = root.querySelector("[data-cipher]");
    var count = steps.length;
    var visible = false;
    var cipherTimer = null;

    root.classList.add("is-ready");

    function stage() {
      return parseInt(root.getAttribute("data-stage"), 10) || 1;
    }

    function setStage(n) {
      root.setAttribute("data-stage", String(n));
      steps.forEach(function (b) {
        b.setAttribute(
          "aria-pressed",
          String(parseInt(b.getAttribute("data-step"), 10) === n)
        );
      });
      syncCipher();
    }

    // Live ciphertext only while the sealed packet is actually on screen.
    function syncCipher() {
      var want = !reduce && visible && stage() === 4 && cipher;
      if (want && !cipherTimer) {
        cipherTimer = window.setInterval(function () {
          cipher.textContent = scramble(14);
        }, 90);
      } else if (!want && cipherTimer) {
        window.clearInterval(cipherTimer);
        cipherTimer = null;
      }
    }

    function setAuto(on) {
      root.classList.toggle("is-auto", on);
    }

    steps.forEach(function (b) {
      b.addEventListener("click", function () {
        setAuto(false);
        setStage(parseInt(b.getAttribute("data-step"), 10));
      });
    });

    // Arrow keys move between steps, like a segmented control.
    root.addEventListener("keydown", function (e) {
      var i = steps.indexOf(document.activeElement);
      if (i < 0) return;
      var next = -1;
      if (e.key === "ArrowDown" || e.key === "ArrowRight") next = (i + 1) % count;
      else if (e.key === "ArrowUp" || e.key === "ArrowLeft") next = (i - 1 + count) % count;
      else if (e.key === "Home") next = 0;
      else if (e.key === "End") next = count - 1;
      if (next < 0) return;
      e.preventDefault();
      steps[next].focus();
      steps[next].click();
    });

    if (reduce || !("IntersectionObserver" in window)) {
      visible = true;
      return;
    }

    if (toggle) {
      toggle.hidden = false;
      toggle.addEventListener("click", function () {
        setAuto(!root.classList.contains("is-auto"));
      });
    }

    root.addEventListener("animationend", function (e) {
      if (!e.target.classList || !e.target.classList.contains("hstep__bar")) return;
      if (!root.classList.contains("is-auto")) return;
      setStage((stage() % count) + 1);
    });

    // Hold while someone is looking closely: pointer on the diagram, or
    // keyboard focus on a step (but not on the Play/Pause link itself).
    var hover = false;
    var focused = false;
    function syncHold() {
      root.classList.toggle("is-held", hover || focused);
    }
    if (flow) {
      flow.addEventListener("pointerenter", function () { hover = true; syncHold(); });
      flow.addEventListener("pointerleave", function () { hover = false; syncHold(); });
    }
    root.addEventListener("focusin", function (e) {
      focused = steps.indexOf(e.target) >= 0;
      syncHold();
    });
    root.addEventListener("focusout", function () {
      focused = false;
      syncHold();
    });

    root.classList.add("is-off");
    setAuto(true);

    new IntersectionObserver(
      function (entries) {
        entries.forEach(function (en) {
          visible = en.isIntersecting;
          root.classList.toggle("is-off", !visible);
          syncCipher();
        });
      },
      { threshold: 0.35 }
    ).observe(root);
  }

  /* --- Hero hop ---------------------------------------------------------- */
  function initHop() {
    var hop = document.querySelector("[data-hop]");
    if (!hop || reduce || !hop.animate) return;
    var stageEl = hop.parentElement;
    var phone = stageEl.querySelector(".device--phone");
    var desk = stageEl.querySelector(".device--desktop");
    var text = hop.querySelector(".hop__text");
    if (!phone || !desk || !text) return;

    var PLAIN = text.textContent;
    var MAX_LOOPS = 3;
    var DUR = 3400;
    var loops = 0;
    var running = false;
    var visible = false;
    var mobile = window.matchMedia("(max-width: 760px)");

    function easeInOut(t) {
      return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    function frames() {
      var s = stageEl.getBoundingClientRect();
      var d = desk.getBoundingClientRect();
      var p = phone.getBoundingClientRect();
      var a = { x: d.left - s.left + d.width * 0.4, y: d.top - s.top + d.height * 0.42 };
      var b = { x: p.left - s.left + p.width * 0.5, y: p.top - s.top + p.height * 0.34 };
      // Control point lifts the path into an arc above both screens.
      var c = { x: (a.x + b.x) / 2, y: Math.min(a.y, b.y) - Math.max(70, s.height * 0.18) };

      function at(pt, scale) {
        return "translate(" + pt.x.toFixed(1) + "px," + pt.y.toFixed(1) + "px) scale(" + scale + ")";
      }

      var kf = [
        { offset: 0, opacity: 0, transform: at(a, 0.8) },
        { offset: 0.1, opacity: 1, transform: at(a, 1) },
        { offset: 0.24, opacity: 1, transform: at(a, 1) }
      ];
      var N = 14;
      for (var i = 1; i <= N; i++) {
        var t = easeInOut(i / N);
        var u = 1 - t;
        kf.push({
          offset: 0.24 + 0.56 * (i / N),
          opacity: 1,
          transform: at(
            {
              x: u * u * a.x + 2 * u * t * c.x + t * t * b.x,
              y: u * u * a.y + 2 * u * t * c.y + t * t * b.y
            },
            1
          )
        });
      }
      kf.push({ offset: 0.94, opacity: 1, transform: at(b, 1) });
      kf.push({ offset: 1, opacity: 0, transform: at(b, 0.9) });
      return kf;
    }

    function cycle() {
      if (!visible || loops >= MAX_LOOPS || mobile.matches) {
        running = false;
        return;
      }
      running = true;
      loops++;

      hop.classList.remove("is-sealed", "is-done");
      text.textContent = PLAIN;
      var anim = hop.animate(frames(), { duration: DUR, fill: "both" });

      var scrambleTimer = null;
      var t1 = window.setTimeout(function () {
        hop.classList.add("is-sealed");
        scrambleTimer = window.setInterval(function () {
          text.textContent = scramble(PLAIN.length);
        }, 80);
      }, DUR * 0.2);
      var t2 = window.setTimeout(function () {
        window.clearInterval(scrambleTimer);
        hop.classList.remove("is-sealed");
        hop.classList.add("is-done");
        text.textContent = PLAIN;
      }, DUR * 0.8);

      anim.onfinish = function () {
        window.clearTimeout(t1);
        window.clearTimeout(t2);
        window.clearInterval(scrambleTimer);
        window.setTimeout(cycle, 1600);
      };
    }

    new IntersectionObserver(
      function (entries) {
        entries.forEach(function (en) {
          visible = en.isIntersecting;
          if (visible && !running) {
            // First flight waits for the headline to finish rising.
            window.setTimeout(cycle, loops === 0 ? 1100 : 300);
            running = true;
          }
        });
      },
      { threshold: 0.4 }
    ).observe(stageEl);
  }
})();
