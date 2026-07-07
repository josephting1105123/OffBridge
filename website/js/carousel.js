/* ==========================================================================
   carousel.js — On phones (<=760px), turn the Connectivity (#features .bento)
   and Private-by-design (#security .assure-grid) card grids into a stacked
   DECK OF CARDS: the top card sits on a short stack of the others peeking out
   below it.

   - Swipe LEFT  -> the top card retracts down and out the bottom, revealing the next.
   - Swipe RIGHT -> the top card tucks down to the back (bottom) of the deck.
   - Either way the deck advances to the next card; it's forward-only and, because
     the deck cycles by a modulo pointer, inherently circular (no clones).
   - Autoplays (tuck) every 2.5s; pauses off-screen, on a hidden tab, and while dragging.
   - Desktop (>760px) keeps the original grid + tap-to-expand popout. This builds on
     entering the mobile breakpoint and tears down on leaving (handles rotation).
   - prefers-reduced-motion: no autoplay, instant advance (still swipeable).
   ========================================================================== */
(function () {
  "use strict";

  var mq = window.matchMedia("(max-width: 760px)");
  var reduce =
    window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  var AUTO_MS = 2500; // time each card is shown before auto-advancing
  var DECK_TRANS =
    "transform 520ms cubic-bezier(0.22, 1, 0.36, 1), opacity 420ms ease";
  var PEEK = 20; // px each deeper card is offset downward
  var STEP = 0.035; // scale reduction per depth
  var VISIBLE = 2; // depths shown behind the front card (front + 2 peeks)
  var SWIPE_RATIO = 0.18; // drag past this fraction of the deck width -> advance
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
      // If a desktop popout is open as we cross into mobile, close it first so the
      // deck doesn't wrap an expanded (position:fixed) card. Dispatch is synchronous.
      document.dispatchEvent(new Event("carousel:takeover"));
      buildAll();
    } else {
      destroyAll();
    }
  }

  function buildAll() {
    if (document.body.style.overflow === "hidden") {
      document.body.style.overflow = ""; // safety: release any popout scroll-lock
      document.body.style.paddingRight = "";
    }
    TARGETS.forEach(function (sel) {
      var grid = document.querySelector(sel);
      if (grid && !grid._mcar) instances.push(new Deck(grid));
    });
  }
  function destroyAll() {
    instances.forEach(function (c) {
      c.destroy();
    });
    instances = [];
  }

  /* --- One deck over a card grid ------------------------------------------ */
  function Deck(grid) {
    var self = this;
    this.grid = grid;
    grid._mcar = this;

    // Real cards (drop any leftover popout placeholder).
    this.cards = slice(grid.children).filter(function (el) {
      return !el.classList.contains("popout-placeholder");
    });
    slice(grid.querySelectorAll(".popout-placeholder")).forEach(removeNode);
    this.n = this.cards.length;
    if (this.n < 2) {
      grid._mcar = null;
      return;
    }

    // Measure the tallest card BEFORE the deck styling makes them absolute, then
    // give the deck a fixed height and each card a uniform min-height so the stack
    // is even and its footprint doesn't jump as cards cycle.
    var maxH = 0;
    this.cards.forEach(function (el) {
      maxH = Math.max(maxH, el.offsetHeight);
    });
    this.maxH = maxH;

    var mcar = document.createElement("div");
    mcar.className = "mcar";
    grid.parentNode.insertBefore(mcar, grid);
    mcar.appendChild(grid);
    this.mcar = mcar;
    grid.classList.add("mcar-deck");
    grid.style.height = maxH + VISIBLE * PEEK + 10 + "px";

    this.cards.forEach(function (el, i) {
      el._i = i;
      el.style.minHeight = maxH + "px";
    });

    this.front = 0;
    this.deckW = mcar.clientWidth;

    // Dots — built before the first layout(), which calls updateDots().
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

    this.layout(false);

    // Drag on the top card.
    this.onDown = Deck.prototype.onDown.bind(this);
    this.onMove = Deck.prototype.onMove.bind(this);
    this.onUp = Deck.prototype.onUp.bind(this);
    grid.addEventListener("pointerdown", this.onDown);

    this.onResize = function () {
      if (!mq.matches) return;
      self.deckW = mcar.clientWidth;
      self.layout(false);
    };
    window.addEventListener("resize", this.onResize);

    this.onVis = function () {
      if (document.hidden) self.pause();
      else self.play();
    };
    document.addEventListener("visibilitychange", this.onVis);

    if ("IntersectionObserver" in window) {
      this.io = new IntersectionObserver(
        function (ents) {
          self.inView = ents[0].isIntersecting;
          if (self.inView) self.play();
          else self.pause();
        },
        { threshold: 0.3 }
      );
      this.io.observe(mcar);
    } else {
      this.inView = true;
      this.play();
    }
  }

  Deck.prototype.depthOf = function (i) {
    return (i - this.front + this.n) % this.n;
  };

  // Place one card at a stack depth (0 = front). Deeper than VISIBLE = hidden
  // behind, parked at the last visible pose so it fades in when promoted.
  Deck.prototype.poseAt = function (el, d, animate) {
    el.style.transition = animate && !reduce ? DECK_TRANS : "none";
    var vis = d < VISIBLE ? d : VISIBLE;
    el.style.transform =
      "translateY(" + vis * PEEK + "px) scale(" + (1 - vis * STEP).toFixed(3) + ")";
    el.style.opacity = d > VISIBLE ? "0" : "1";
    el.style.zIndex = String(100 - d);
  };

  Deck.prototype.layout = function (animate) {
    for (var i = 0; i < this.n; i++) {
      if (this.cards[i]._exiting) continue; // don't disturb a card mid-retract
      this.poseAt(this.cards[i], this.depthOf(i), animate);
    }
    this.updateDots();
  };

  // Advance to the next card. dir "left" flings the top card out the bottom;
  // anything else (right / autoplay) tucks it down to the back of the deck.
  Deck.prototype.advance = function (dir) {
    if (this.n < 2) return;
    var self = this;
    var exiting = this.cards[this.front];
    this.front = (this.front + 1) % this.n;

    if (dir === "left" && !reduce) {
      exiting._exiting = true;
      exiting.style.transition = DECK_TRANS;
      exiting.style.zIndex = "200"; // stay on top while it flies out
      exiting.style.opacity = "0";
      exiting.style.transform =
        "translate(-8%, 92%) scale(0.9) rotate(-4deg)";
      onDeckExit(exiting, function () {
        exiting._exiting = false;
        self.poseAt(exiting, self.depthOf(exiting._i), false); // snap to back
      });
    }
    this.layout(true);
  };

  Deck.prototype.updateDots = function () {
    if (!this.dotBtns) return;
    for (var i = 0; i < this.dotBtns.length; i++) {
      this.dotBtns[i].setAttribute(
        "aria-current",
        i === this.front ? "true" : "false"
      );
    }
  };

  /* --- Autoplay ----------------------------------------------------------- */
  Deck.prototype.play = function () {
    if (reduce || this.timer || this.dragging || this.inView === false) return;
    var self = this;
    this.timer = window.setInterval(function () {
      self.advance(); // tuck to the back
    }, AUTO_MS);
  };
  Deck.prototype.pause = function () {
    if (this.timer) {
      window.clearInterval(this.timer);
      this.timer = 0;
    }
    window.clearTimeout(this._rt);
  };
  Deck.prototype.resumeSoon = function () {
    var self = this;
    this.pause();
    this._rt = window.setTimeout(function () {
      self.play();
    }, AUTO_MS);
  };

  /* --- Drag / swipe the top card ------------------------------------------ */
  Deck.prototype.onDown = function (e) {
    if (e.pointerType === "mouse" && e.button !== 0) return;
    this.dragging = true;
    this.decided = false;
    this.horizontal = false;
    this.startX = e.clientX;
    this.startY = e.clientY;
    this.frontEl = this.cards[this.front];
    this.pause();
    document.addEventListener("pointermove", this.onMove, { passive: false });
    document.addEventListener("pointerup", this.onUp);
    document.addEventListener("pointercancel", this.onUp);
  };
  Deck.prototype.onMove = function (e) {
    if (!this.dragging) return;
    var dx = e.clientX - this.startX;
    var dy = e.clientY - this.startY;
    if (!this.decided) {
      if (Math.abs(dx) < 6 && Math.abs(dy) < 6) return;
      this.decided = true;
      this.horizontal = Math.abs(dx) > Math.abs(dy);
      if (this.horizontal) {
        try {
          this.frontEl.setPointerCapture(e.pointerId);
        } catch (x) {}
        this.frontEl.style.transition = "none";
        this.frontEl.style.zIndex = "200";
      }
    }
    if (!this.horizontal) {
      this.endDrag(); // vertical gesture: let the page scroll
      this.resumeSoon();
      return;
    }
    e.preventDefault();
    this.frontEl.style.transform =
      "translate(" + dx + "px," + Math.abs(dx) * 0.06 + "px) rotate(" +
      dx * 0.03 + "deg)";
  };
  Deck.prototype.onUp = function (e) {
    if (!this.dragging) return;
    var canceled = e.type === "pointercancel";
    var dx = e.clientX - this.startX;
    var wasHorizontal = this.horizontal;
    this.endDrag();
    if (wasHorizontal) {
      var thresh = Math.max(SWIPE_MIN, this.deckW * SWIPE_RATIO);
      if (!canceled && dx <= -thresh) this.advance("left");
      else if (!canceled && dx >= thresh) this.advance("right");
      else this.poseAt(this.frontEl, 0, true); // snap the top card back
    }
    this.resumeSoon();
  };
  Deck.prototype.endDrag = function () {
    this.dragging = false;
    document.removeEventListener("pointermove", this.onMove, { passive: false });
    document.removeEventListener("pointerup", this.onUp);
    document.removeEventListener("pointercancel", this.onUp);
  };

  /* --- Teardown (restore the original grid) ------------------------------- */
  Deck.prototype.destroy = function () {
    this.pause();
    window.clearTimeout(this._rt);
    this.endDrag();
    var g = this.grid;
    var mcar = this.mcar;
    if (this.io) this.io.disconnect();
    document.removeEventListener("visibilitychange", this.onVis);
    window.removeEventListener("resize", this.onResize);
    g.removeEventListener("pointerdown", this.onDown);
    removeNode(this.dots);
    this.cards.forEach(function (el) {
      el._exiting = false;
      ["transform", "opacity", "transition", "z-index", "min-height"].forEach(
        function (p) {
          el.style.removeProperty(p);
        }
      );
    });
    g.classList.remove("mcar-deck");
    g.style.removeProperty("height");
    if (mcar && mcar.parentNode) {
      mcar.parentNode.insertBefore(g, mcar); // unwrap
      mcar.parentNode.removeChild(mcar);
    }
    g._mcar = null;
  };

  /* --- Helpers ------------------------------------------------------------ */
  function onDeckExit(el, cb) {
    var done = false;
    function fin() {
      if (done) return;
      done = true;
      el.removeEventListener("transitionend", te);
      cb();
    }
    function te(ev) {
      if (ev.target === el && ev.propertyName === "transform") fin();
    }
    el.addEventListener("transitionend", te);
    window.setTimeout(fin, 700); // fallback if transitionend is missed
  }
  function bindDot(self, btn, idx) {
    btn.addEventListener("click", function () {
      self.pause();
      // Step the deck forward to the tapped card (tuck animation each step).
      var guard = self.n;
      while (self.front !== idx && guard-- > 0) self.advance("right");
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
