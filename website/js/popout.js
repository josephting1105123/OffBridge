/* ==========================================================================
   popout.js — expand a Connectivity tile or a Private-by-design card into a
   centred, enlarged panel and back, using a FLIP transition.

   How it works:
   - Click / Enter / Space on a card → the REAL node is lifted to position:fixed
     and a same-sized placeholder (cloned classes) holds its grid slot so nothing
     around it reflows.
   - We measure the card's start rect (First), apply the expanded geometry to get
     its end rect (Last), Invert with a transform back onto the start, then Play
     to identity — so only transform/opacity move on the compositor.
   - Close (× / scrim / Esc) runs the FLIP in reverse toward the placeholder, then
     restores the node to its slot.
   - prefers-reduced-motion swaps the FLIP for a short crossfade.
   ========================================================================== */
(function () {
  "use strict";

  var reduce =
    window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  var EASE = "cubic-bezier(0.16, 1, 0.3, 1)"; // ease-out-expo, matches --ease
  var FADE = "cubic-bezier(0.4, 0, 0.2, 1)"; // even ease for collapse crossfade
  var DUR = 520; // ms — expand/collapse flight time
  var MARGIN = 16; // min gap from panel to viewport edge

  var scrim = null;
  var navH = 64;
  var open = null; // { card, ph, closeBtn, prevFocus, targetW }
  var busy = false; // true while a FLIP is animating

  document.addEventListener("DOMContentLoaded", function () {
    var cards = toArr(
      document.querySelectorAll(
        "#features .tile, #security .assure, #platforms .plat-card"
      )
    );
    if (!cards.length) return;

    navH =
      parseInt(
        getComputedStyle(document.documentElement).getPropertyValue("--nav-h"),
        10
      ) || 64;

    scrim = document.createElement("div");
    scrim.className = "popout-scrim";
    scrim.setAttribute("aria-hidden", "true");
    document.body.appendChild(scrim);
    scrim.addEventListener("click", collapse);

    cards.forEach(setupCard);

    document.addEventListener("keydown", function (e) {
      if (!open) return;
      if (e.key === "Escape" && !busy) {
        e.preventDefault();
        collapse();
      } else if (e.key === "Tab" && open.closeBtn) {
        // Single-action dialog: keep focus on the close button (basic trap).
        e.preventDefault();
        open.closeBtn.focus();
      }
    });
    window.addEventListener("resize", function () {
      if (open && !busy) reposition();
    });
  });

  function setupCard(card) {
    card.classList.add("expandable");
    card.setAttribute("role", "button");
    card.setAttribute("tabindex", "0");
    card.setAttribute("aria-expanded", "false");

    var title = headingText(card);
    if (title) card.setAttribute("aria-label", title + " — expand for details");

    var hint = document.createElement("span");
    hint.className = "expand-hint";
    hint.setAttribute("aria-hidden", "true");
    hint.innerHTML = '<svg class="icon"><use href="#i-expand"></use></svg>';
    card.appendChild(hint);

    trackSpotlight(card);

    card.addEventListener("click", function (e) {
      if (e.target.closest(".popout__close")) return; // handled separately
      if (busy) return;
      if (open) return; // ignore clicks on an already-open card / while one is open
      expand(card);
    });
    card.addEventListener("keydown", function (e) {
      if (e.key !== "Enter" && e.key !== " " && e.key !== "Spacebar") return;
      e.preventDefault();
      if (busy) return;
      if (open && open.card === card) collapse();
      else if (!open) expand(card);
    });
  }

  /* --- Cursor-tracking spotlight: lerp --mx/--my toward the pointer -------
     The rendered position (curX/curY) eases toward the pointer target
     (tgtX/tgtY) a fraction each frame, so the glow trails the cursor with a
     slight, Antigravity-style delay instead of snapping. Each pointermove
     retargets and (re)starts the loop; the loop self-stops once it has settled
     within 0.1% of the target — so a resting pointer costs nothing, and after
     the pointer leaves the glow simply glides to a stop at its last spot. */
  function trackSpotlight(card) {
    var curX = 50,
      curY = 50; // rendered spotlight position (% of card)
    var tgtX = 50,
      tgtY = 50; // pointer target (% of card)
    var raf = 0;
    var K = reduce ? 1 : 0.13; // per-frame ease fraction (lower = more lag)

    function frame() {
      var dx = tgtX - curX;
      var dy = tgtY - curY;
      curX += dx * K;
      curY += dy * K;
      card.style.setProperty("--mx", curX.toFixed(2) + "%");
      card.style.setProperty("--my", curY.toFixed(2) + "%");
      if (Math.abs(dx) > 0.1 || Math.abs(dy) > 0.1) {
        raf = window.requestAnimationFrame(frame);
      } else {
        raf = 0;
      }
    }

    card.addEventListener("pointermove", function (e) {
      var r = card.getBoundingClientRect();
      if (!r.width || !r.height) return;
      tgtX = clamp01to100(((e.clientX - r.left) / r.width) * 100);
      tgtY = clamp01to100(((e.clientY - r.top) / r.height) * 100);
      if (!raf) raf = window.requestAnimationFrame(frame);
    });
  }

  function clamp01to100(v) {
    return v < 0 ? 0 : v > 100 ? 100 : v;
  }

  /* --- Expand -------------------------------------------------------------- */
  function expand(card) {
    busy = true;
    var rect0 = card.getBoundingClientRect();

    // Lock the page scroll, compensating for the scrollbar so nothing shifts.
    var sbw = window.innerWidth - document.documentElement.clientWidth;
    document.body.style.overflow = "hidden";
    if (sbw > 0) document.body.style.paddingRight = sbw + "px";

    // Placeholder keeps the grid slot (same classes → same span, same cell).
    var ph = document.createElement(card.tagName.toLowerCase());
    ph.className =
      card.className.replace(/\bexpandable\b/, "").trim() + " popout-placeholder";
    ph.style.height = rect0.height + "px";
    ph.setAttribute("aria-hidden", "true");
    card.parentNode.insertBefore(ph, card);

    // Apply expanded geometry, then measure the end rect.
    var vw = document.documentElement.clientWidth;
    var vh = window.innerHeight;
    var width = Math.min(vw * 0.92, Math.max(560, rect0.width + 200));

    card.classList.add("is-expanded");
    card.style.transition = "none";
    card.style.transform = "none";
    card.style.width = width + "px";
    card.style.left = Math.round((vw - width) / 2) + "px";
    card.style.top = "0px";
    var h1 = card.getBoundingClientRect().height;
    card.style.top = clampTop(h1, vh) + "px";

    // Close button.
    var closeBtn = document.createElement("button");
    closeBtn.type = "button";
    closeBtn.className = "popout__close";
    closeBtn.setAttribute("aria-label", "Close");
    closeBtn.innerHTML = '<svg class="icon"><use href="#i-x"></use></svg>';
    closeBtn.addEventListener("click", function (e) {
      e.stopPropagation();
      collapse();
    });
    card.appendChild(closeBtn);

    // Dialog semantics.
    card.setAttribute("role", "dialog");
    card.setAttribute("aria-modal", "true");
    card.setAttribute("aria-label", headingText(card) || "Details");
    card.setAttribute("aria-expanded", "true");

    open = {
      card: card,
      ph: ph,
      closeBtn: closeBtn,
      prevFocus: document.activeElement,
      targetW: width
    };

    requestAnimationFrame(function () {
      scrim.classList.add("is-on");
    });

    if (reduce) {
      card.style.transition = "opacity 200ms linear";
      card.style.opacity = "0";
      requestAnimationFrame(function () {
        card.style.opacity = "1";
      });
      settleOpen(card);
      return;
    }

    // FLIP: invert onto the start rect, then play to identity.
    var rect1 = card.getBoundingClientRect();
    card.style.transformOrigin = "0 0";
    card.style.transform = invertTransform(rect0, rect1);
    void card.offsetWidth; // force reflow so the invert is the starting frame
    card.style.transition = "transform " + DUR + "ms " + EASE;
    card.style.transform = "none";
    onTransformEnd(card, function () {
      card.style.transition = "";
      settleOpen(card);
    });
    // Safety net: never let `busy` stick if transitionend misfires (would
    // otherwise deadlock collapse, which refuses to run while busy).
    window.setTimeout(function () {
      if (busy && open && open.card === card) {
        card.style.transition = "";
        settleOpen(card);
      }
    }, DUR + 140);
  }

  function settleOpen(card) {
    busy = false;
    if (open && open.card === card && open.closeBtn) open.closeBtn.focus();
  }

  /* --- Collapse ------------------------------------------------------------
     Smooth minimize. A reverse-FLIP looked rough: it slid the *expanded* panel
     (mismatched, squished content) into the slot, then hard-swapped to the mini
     card at the very end — reading as something banging in from the side. So
     instead the panel fades out IN PLACE, we restore the mini card to its slot
     while it's fully invisible (the content/layout swap the user must not see),
     then fade the mini card back in. */
  function collapse() {
    if (!open || busy) return;
    busy = true;
    var current = open;
    open = null;
    var card = current.card;
    var ph = current.ph;
    var swapped = false;

    scrim.classList.remove("is-on");

    // Put the real node back in its grid slot and drop all the overlay state.
    // Leaves opacity/transition alone — the fades manage those.
    function restore() {
      card.classList.remove("is-expanded");
      [
        "position",
        "top",
        "left",
        "width",
        "height",
        "transform",
        "transform-origin",
        "z-index"
      ].forEach(function (p) {
        card.style.removeProperty(p);
      });
      card.setAttribute("role", "button");
      card.removeAttribute("aria-modal");
      card.setAttribute(
        "aria-label",
        (headingText(card) || "") + " — expand for details"
      );
      card.setAttribute("aria-expanded", "false");
      if (current.closeBtn && current.closeBtn.parentNode) {
        current.closeBtn.parentNode.removeChild(current.closeBtn);
      }
      if (ph && ph.parentNode) ph.parentNode.removeChild(ph);
      document.body.style.overflow = "";
      document.body.style.paddingRight = "";
      if (current.prevFocus && current.prevFocus.focus) {
        current.prevFocus.focus({ preventScroll: true });
      }
    }

    // Runs once the panel is invisible: swap it for the mini card in its slot,
    // then fade the mini card back in from 0.
    function swap() {
      if (swapped) return;
      swapped = true;
      restore();
      if (reduce) {
        card.style.removeProperty("opacity");
        card.style.removeProperty("transition");
        busy = false;
        return;
      }
      card.style.transition = "none";
      card.style.opacity = "0"; // normal priority now that is-expanded is gone
      void card.offsetWidth; // commit the 0 so the fade-in animates
      card.style.transition = "opacity 220ms " + FADE;
      window.requestAnimationFrame(function () {
        card.style.opacity = "1";
      });
      var finish = function () {
        card.style.removeProperty("opacity");
        card.style.removeProperty("transition");
        busy = false;
      };
      onTransitionEndProp(card, "opacity", finish);
      window.setTimeout(finish, 260); // safety net
    }

    if (reduce) {
      swap();
      return;
    }

    // Fade + gently shrink the panel in place. is-expanded pins
    // `opacity: 1 !important`, so override it with an inline !important to let
    // the fade actually take; the content stays expanded until it's invisible.
    var OUT = 240;
    card.style.transformOrigin = "50% 50%";
    card.style.transition =
      "opacity " + OUT + "ms " + FADE + ", transform " + OUT + "ms " + FADE;
    void card.offsetWidth;
    card.style.setProperty("opacity", "0", "important");
    card.style.transform = "scale(0.96)";
    onTransitionEndProp(card, "opacity", swap);
    window.setTimeout(swap, OUT + 120); // safety net if transitionend misfires
  }

  /* --- Reposition an open panel on resize (no animation) ------------------- */
  function reposition() {
    var card = open.card;
    var vw = document.documentElement.clientWidth;
    var vh = window.innerHeight;
    var width = Math.min(vw * 0.92, open.targetW);
    card.style.transition = "none";
    card.style.transform = "none";
    card.style.width = width + "px";
    card.style.left = Math.round((vw - width) / 2) + "px";
    card.style.top = "0px";
    var h = card.getBoundingClientRect().height;
    card.style.top = clampTop(h, vh) + "px";
  }

  /* --- Helpers ------------------------------------------------------------- */
  function invertTransform(from, to) {
    var dx = from.left - to.left;
    var dy = from.top - to.top;
    var sx = from.width / to.width;
    var sy = from.height / to.height;
    return "translate(" + dx + "px," + dy + "px) scale(" + sx + "," + sy + ")";
  }

  function clampTop(h, vh) {
    var top = Math.round((vh - h) / 2);
    if (top < navH + 12) top = navH + 12;
    if (top + h > vh - MARGIN) top = Math.max(MARGIN, vh - MARGIN - h);
    return top;
  }

  function onTransformEnd(el, cb) {
    onTransitionEndProp(el, "transform", cb);
  }

  function onTransitionEndProp(el, prop, cb) {
    el.addEventListener("transitionend", function te(ev) {
      if (ev.target !== el || ev.propertyName !== prop) return;
      el.removeEventListener("transitionend", te);
      cb();
    });
  }

  function headingText(card) {
    var h = card.querySelector("h3, h2");
    return h ? h.textContent.trim() : "";
  }

  function toArr(nl) {
    return Array.prototype.slice.call(nl);
  }
})();
