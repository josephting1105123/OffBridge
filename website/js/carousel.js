/* ==========================================================================
   carousel.js — On phones (<=760px), turn the Connectivity (#features .bento)
   and Private-by-design (#security .assure-grid) card grids into a swipeable,
   auto-advancing, infinite (circular) carousel — one card per view.

   - Autoplays to the next card every 2.5s and loops forever (last -> first).
   - Touch-drag to swipe either way; both directions wrap infinitely.
   - Infinite loop: a clone of the last card is prepended and a clone of the
     first appended; landing on a clone teleports (no animation) to the matching
     real card, so the wrap is seamless in both directions.
   - Desktop (>760px) keeps the original grid + tap-to-expand popout. This
     builds when entering the mobile breakpoint and tears down when leaving, so
     rotating a phone to landscape restores the grid cleanly.
   - prefers-reduced-motion: no autoplay, no slide animation (still swipeable).
   - Autoplay pauses while dragging, while the tab is hidden, and while the
     carousel is scrolled off-screen.
   ========================================================================== */
(function () {
  "use strict";

  var mq = window.matchMedia("(max-width: 760px)");
  var reduce =
    window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  var AUTO_MS = 2500; // time each card is shown before auto-advancing
  var DUR = 460; // slide animation duration (ms)
  var EASE = "cubic-bezier(0.16, 1, 0.3, 1)";
  var SWIPE_RATIO = 0.18; // drag past this fraction of a card width -> change card
  var SWIPE_MIN = 36; // ...or this many px, whichever is larger

  var TARGETS = ["#features .bento", "#security .assure-grid"];
  var instances = [];

  ready(function () {
    if (mq.addEventListener) mq.addEventListener("change", sync);
    else if (mq.addListener) mq.addListener(sync); // older Safari
    sync();
  });

  function sync() {
    if (mq.matches) {
      // If a desktop popout is open as we cross into mobile, close it first so
      // the carousel never wraps an expanded (position:fixed) card + placeholder.
      // Dispatch is synchronous, so popout collapses before buildAll runs.
      document.dispatchEvent(new Event("carousel:takeover"));
      buildAll();
    } else {
      destroyAll();
    }
  }

  function buildAll() {
    // Safety net: release any popout scroll-lock before the carousel takes over.
    if (document.body.style.overflow === "hidden") {
      document.body.style.overflow = "";
      document.body.style.paddingRight = "";
    }
    TARGETS.forEach(function (sel) {
      var grid = document.querySelector(sel);
      if (grid && !grid._mcar) instances.push(new Carousel(grid));
    });
  }
  function destroyAll() {
    instances.forEach(function (c) {
      c.destroy();
    });
    instances = [];
  }

  /* --- One carousel over a card grid -------------------------------------- */
  function Carousel(grid) {
    var self = this;
    this.grid = grid;
    grid._mcar = this;

    // Exclude any leftover popout placeholder (from a popout open across the
    // breakpoint) so the slide count and clones stay correct.
    var reals = slice(grid.children).filter(function (el) {
      return !el.classList.contains("popout-placeholder");
    });
    slice(grid.querySelectorAll(".popout-placeholder")).forEach(removeNode);
    this.n = reals.length;
    if (this.n < 2) {
      grid._mcar = null;
      return; // nothing to loop through
    }

    // Viewport wrapper (clips the off-screen slides).
    var mcar = document.createElement("div");
    mcar.className = "mcar";
    grid.parentNode.insertBefore(mcar, grid);
    mcar.appendChild(grid);
    this.mcar = mcar;
    grid.classList.add("mcar-track");

    // Clones for the seamless wrap.
    this.cloneLast = cloneSlide(reals[this.n - 1]);
    this.cloneFirst = cloneSlide(reals[0]);
    grid.insertBefore(this.cloneLast, reals[0]);
    grid.appendChild(this.cloneFirst);

    this.index = 1; // first real card sits at slot 1 (slot 0 is the prepended clone)

    // Dots.
    var dots = document.createElement("div");
    dots.className = "mcar-dots";
    dots.setAttribute("role", "tablist");
    this.dotBtns = [];
    for (var i = 0; i < this.n; i++) {
      var b = document.createElement("button");
      b.type = "button";
      b.setAttribute("aria-label", "Show card " + (i + 1) + " of " + this.n);
      bindDot(self, b, i);
      dots.appendChild(b);
      this.dotBtns.push(b);
    }
    mcar.appendChild(dots);
    this.dots = dots;

    this.slideW = mcar.clientWidth;
    this.apply(false);
    this.updateDots();

    // Bind handlers (per-instance so add/removeEventListener match).
    this.onDown = Carousel.prototype.onDown.bind(this);
    this.onMove = Carousel.prototype.onMove.bind(this);
    this.onUp = Carousel.prototype.onUp.bind(this);
    grid.addEventListener("pointerdown", this.onDown);

    this.onTEnd = function (e) {
      if (e.target === grid && e.propertyName === "transform") self.afterSlide();
    };
    grid.addEventListener("transitionend", this.onTEnd);

    this.onResize = function () {
      if (!mq.matches) return; // leaving the breakpoint is handled by sync()
      self.slideW = mcar.clientWidth;
      self.afterSlide(); // reconcile clone-parking; apply(false) inside repositions
      self.apply(false);
    };
    window.addEventListener("resize", this.onResize);

    this.onVis = function () {
      if (document.hidden) self.pause();
      else self.play();
    };
    document.addEventListener("visibilitychange", this.onVis);

    // Only autoplay while on screen.
    if ("IntersectionObserver" in window) {
      this.io = new IntersectionObserver(
        function (ents) {
          self.inView = ents[0].isIntersecting;
          if (self.inView) self.play();
          else self.pause();
        },
        { threshold: 0.35 }
      );
      this.io.observe(mcar);
    } else {
      this.inView = true;
      this.play();
    }
  }

  Carousel.prototype.apply = function (animate) {
    var g = this.grid;
    g.style.transition =
      animate && !reduce ? "transform " + DUR + "ms " + EASE : "none";
    g.style.transform =
      "translate3d(" + -this.index * this.slideW + "px,0,0)";
  };

  Carousel.prototype.goTo = function (i, animate) {
    this.index = i;
    this.apply(animate);
    this.updateDots();
    if (!animate || reduce) this.afterSlide(); // no transition -> settle now
  };
  // afterSlide() first reconciles any interrupted wrap: if a prior slide's
  // transition was cancelled (resize/tap/scroll) the index can be parked on a
  // clone; normalize it to the identical real slide before advancing so we never
  // walk index past the clones into blank space.
  Carousel.prototype.next = function () {
    this.afterSlide();
    this.goTo(this.index + 1, true);
  };
  Carousel.prototype.prev = function () {
    this.afterSlide();
    this.goTo(this.index - 1, true);
  };

  // After a slide finishes, jump off a clone onto the matching real card.
  // Guarded against re-entry: apply(false) cancels any in-flight transition,
  // which can synchronously re-fire transitionend -> afterSlide; without the
  // flag that could recurse.
  Carousel.prototype.afterSlide = function () {
    if (this._settling) return;
    this._settling = true;
    if (this.index === this.n + 1) {
      this.index = 1;
      this.apply(false);
    } else if (this.index === 0) {
      this.index = this.n;
      this.apply(false);
    }
    this.updateDots();
    this._settling = false;
  };

  Carousel.prototype.realIndex = function () {
    // Slot 0 (last-clone) -> n-1 and slot n+1 (first-clone) -> 0 fall out of the
    // modulo, so no special-casing is needed.
    return (((this.index - 1) % this.n) + this.n) % this.n;
  };
  Carousel.prototype.updateDots = function () {
    var ri = this.realIndex();
    for (var i = 0; i < this.dotBtns.length; i++) {
      this.dotBtns[i].setAttribute("aria-current", i === ri ? "true" : "false");
    }
  };

  /* --- Autoplay ----------------------------------------------------------- */
  Carousel.prototype.play = function () {
    if (reduce || this.timer || this.dragging || this.inView === false) return;
    var self = this;
    this.timer = window.setInterval(function () {
      self.next();
    }, AUTO_MS);
  };
  Carousel.prototype.pause = function () {
    if (this.timer) {
      window.clearInterval(this.timer);
      this.timer = 0;
    }
    window.clearTimeout(this._rt); // also cancel a pending resume, so pause holds
  };
  Carousel.prototype.resumeSoon = function () {
    var self = this;
    this.pause(); // also clears any pending _rt
    this._rt = window.setTimeout(function () {
      self.play();
    }, AUTO_MS);
  };

  /* --- Drag / swipe ------------------------------------------------------- */
  Carousel.prototype.onDown = function (e) {
    if (e.pointerType === "mouse" && e.button !== 0) return;
    this.afterSlide(); // reconcile any clone-parked state before we grab baseTx
    this.dragging = true;
    this.decided = false;
    this.horizontal = false;
    this.startX = e.clientX;
    this.startY = e.clientY;
    this.baseTx = -this.index * this.slideW;
    this.pause();
    this.grid.style.transition = "none";
    document.addEventListener("pointermove", this.onMove, { passive: false });
    document.addEventListener("pointerup", this.onUp);
    document.addEventListener("pointercancel", this.onUp);
  };
  Carousel.prototype.onMove = function (e) {
    if (!this.dragging) return;
    var dx = e.clientX - this.startX;
    var dy = e.clientY - this.startY;
    if (!this.decided) {
      if (Math.abs(dx) < 6 && Math.abs(dy) < 6) return;
      this.decided = true;
      this.horizontal = Math.abs(dx) > Math.abs(dy);
      if (this.horizontal) {
        try {
          this.grid.setPointerCapture(e.pointerId);
        } catch (x) {}
      }
    }
    if (!this.horizontal) {
      // Vertical gesture: release so the page scrolls normally.
      this.endDrag();
      this.resumeSoon();
      return;
    }
    e.preventDefault();
    this.grid.style.transform =
      "translate3d(" + (this.baseTx + dx) + "px,0,0)";
  };
  Carousel.prototype.onUp = function (e) {
    if (!this.dragging) return;
    var canceled = e.type === "pointercancel"; // system took the gesture; don't commit
    var dx = e.clientX - this.startX;
    var wasHorizontal = this.horizontal;
    this.endDrag();
    if (wasHorizontal) {
      var thresh = Math.max(SWIPE_MIN, this.slideW * SWIPE_RATIO);
      if (!canceled && dx <= -thresh) this.next();
      else if (!canceled && dx >= thresh) this.prev();
      else this.apply(true); // snap back to the current card
    }
    this.resumeSoon();
  };
  Carousel.prototype.endDrag = function () {
    this.dragging = false;
    document.removeEventListener("pointermove", this.onMove, { passive: false });
    document.removeEventListener("pointerup", this.onUp);
    document.removeEventListener("pointercancel", this.onUp);
  };

  /* --- Teardown (restore the original grid) ------------------------------- */
  Carousel.prototype.destroy = function () {
    this.pause();
    window.clearTimeout(this._rt);
    this.endDrag();
    var g = this.grid;
    var mcar = this.mcar;
    if (this.io) this.io.disconnect();
    document.removeEventListener("visibilitychange", this.onVis);
    window.removeEventListener("resize", this.onResize);
    g.removeEventListener("pointerdown", this.onDown);
    g.removeEventListener("transitionend", this.onTEnd);
    removeNode(this.cloneLast);
    removeNode(this.cloneFirst);
    removeNode(this.dots);
    g.classList.remove("mcar-track");
    g.style.removeProperty("transform");
    g.style.removeProperty("transition");
    if (mcar && mcar.parentNode) {
      mcar.parentNode.insertBefore(g, mcar); // unwrap
      mcar.parentNode.removeChild(mcar);
    }
    g._mcar = null;
  };

  /* --- Helpers ------------------------------------------------------------ */
  function cloneSlide(el) {
    var c = el.cloneNode(true);
    c.classList.add("mcar-clone");
    c.setAttribute("aria-hidden", "true");
    // Strip interactivity/identity that cloneNode copied (popout attrs, ids).
    c.removeAttribute("id");
    c.removeAttribute("role");
    c.removeAttribute("tabindex");
    c.removeAttribute("aria-expanded");
    c.removeAttribute("aria-label");
    var ids = c.querySelectorAll("[id]");
    for (var i = 0; i < ids.length; i++) ids[i].removeAttribute("id");
    return c; // cloneNode does not copy event listeners, so clones are inert
  }

  function bindDot(self, btn, idx) {
    btn.addEventListener("click", function () {
      self.pause();
      self.goTo(idx + 1, true);
      self.resumeSoon();
    });
  }
  function removeNode(n) {
    if (n && n.parentNode) n.parentNode.removeChild(n);
  }
  function slice(nl) {
    return Array.prototype.slice.call(nl);
  }
  function ready(fn) {
    // Wait for DOMContentLoaded so popout.js (also DCL) wires the real cards
    // first; only skip waiting once the document is fully complete.
    if (document.readyState === "complete") fn();
    else document.addEventListener("DOMContentLoaded", fn);
  }
})();
