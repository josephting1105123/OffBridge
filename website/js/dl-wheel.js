/* ==========================================================================
   dl-wheel.js — Turns the Download section's platform grid
   (#download .dl-grid) into a rotating WHEEL at every viewport width: cards
   fan out on a shallow 3D arc, the one facing the viewer dead-on is
   spotlighted (full size, full brightness, the site's existing highlight
   ring/glow), and its neighbors peek in from either side, rotated away and
   dimmed. (Originally mobile-only; promoted to all viewports — the CSS in
   dl-wheel.css sizes the deck per breakpoint, and the grid layout in
   download.css remains as the no-JS fallback.)

   - Scrolling over the wheel turns it: one card per notch, throttled so a
     fast trackpad flick steps card-by-card instead of flying past.
   - Drag (or a touch swipe) rotates the wheel 1:1 with the finger; release
     snaps to the nearest card.
   - Tapping a peeking side card spins it to center quickly. Its own download
     link and checksum details stay inert (non-interactive) until it IS
     centered — a stray tap while spinning can't trigger a download.
   - The auto-detected platform (set by platform.js) starts centered; if
     detection resolves after this builds, a "dl:platform-detected" event
     (dispatched by platform.js) spins the wheel to match.
   - Position dots reuse .mcar-dots from carousel.css (same look, no
     duplicated CSS) and double as the accessible/keyboard way to pick a
     card — the wheel itself is a mouse/touch convenience on top.
   - No autoplay: this selects which platform to download, so it must never
     move on its own.
   - prefers-reduced-motion: drag still tracks the finger 1:1 (it's direct
     input, not programmatic motion), but the settle/snap is instant.

   Deliberately a separate small class rather than folding into carousel.js's
   Deck: the pose math (rotateY arc vs. translateY stack) and the tap-to-
   select-a-neighbor gesture are different enough that sharing one class
   would mean threading a mode flag through most of its methods.
   ========================================================================== */
(function () {
  "use strict";

  var reduce =
    window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  // From 720px up (matching dl-wheel.css's desktop deck) the wheel uses the
  // *_WIDE pose: more lateral travel per step and a flatter rotation — a
  // bigger wheel radius, so the side cards sit further out instead of
  // tucking tight behind the center one. Mobile keeps the tighter arc.
  var wideMq = window.matchMedia("(min-width: 720px)");

  var STEP_DEG = 30; // rotateY degrees per step away from center (mobile)
  var STEP_DEG_WIDE = 24;
  var STEP_X = 54; // % of the card's own width, translateX per step (mobile)
  var STEP_X_WIDE = 70;
  var STEP_BLUR = 2.5; // px of blur per step away from center
  var STEP_Z = 70; // px pushed back (into the screen) per step
  var STEP_SCALE = 0.15; // scale falloff per step
  var STEP_DIM = 0.24; // brightness falloff per step
  var VISIBLE_STEPS = 1; // steps shown before fading toward opacity 0
  var SWIPE_RATIO = 0.5; // drag past this fraction of a step -> advance
  var SPIN_MS = 380;
  var SPIN_TRANS =
    "transform " +
    SPIN_MS +
    "ms var(--ease), opacity 260ms ease, filter 260ms ease, " +
    "border-color var(--dur-med) var(--ease-std), box-shadow var(--dur-med) var(--ease-std)";
  var HEIGHT_TRANS = "height " + SPIN_MS + "ms var(--ease)";
  var HEIGHT_PAD = 32; // px slack so the highlight glow isn't clipped

  ready(build);

  function build() {
    var grid = document.querySelector("#download .dl-grid");
    if (grid && !grid._dlwheel) new Wheel(grid);
  }

  /* --- The wheel ------------------------------------------------------------ */
  function Wheel(grid) {
    var self = this;
    this.grid = grid;
    grid._dlwheel = this;

    this.cards = slice(grid.children);
    this.n = this.cards.length;
    if (this.n < 2) {
      grid._dlwheel = null;
      return;
    }
    this.cards.forEach(function (el, i) {
      el._i = i;
      el._link = el.querySelector("[data-dl-link]");
      el._details = el.querySelector(".dl-verify");
    });

    var wheelEl = document.createElement("div");
    wheelEl.className = "dl-wheel";
    grid.parentNode.insertBefore(wheelEl, grid);
    wheelEl.appendChild(grid);
    this.wheel = wheelEl;
    grid.classList.add("dl-wheel-deck");

    // Measure AFTER the deck styling makes cards absolute/full-width, not
    // before — measuring earlier would capture whatever column width the
    // plain grid had at this viewport, not the wheel's actual card width.
    this.measure();
    this.deckW = wheelEl.clientWidth;

    // Start centered on whichever card platform.js already marked detected;
    // if detection is still pending (it's async), dl:platform-detected
    // below will spin us to it once it resolves.
    this.front = 0;
    for (var k = 0; k < this.n; k++) {
      if (this.cards[k].classList.contains("is-detected")) {
        this.front = k;
        break;
      }
    }

    // Position dots — built before the first layout(), which calls updateDots().
    var dots = document.createElement("div");
    dots.className = "mcar-dots";
    dots.setAttribute("role", "group");
    dots.setAttribute("aria-label", "Choose a platform");
    this.dotBtns = [];
    this.cards.forEach(function (el, i) {
      var b = document.createElement("button");
      b.type = "button";
      var h3 = el.querySelector("h3");
      b.setAttribute("aria-label", "Show " + (h3 ? h3.textContent : "platform " + (i + 1)));
      bindDot(self, b, i);
      dots.appendChild(b);
      self.dotBtns.push(b);
    });
    wheelEl.appendChild(dots);
    this.dots = dots;

    // Screen-reader-only status announcing the centered card on change.
    var status = document.createElement("div");
    status.className = "visually-hidden";
    status.setAttribute("aria-live", "polite");
    wheelEl.appendChild(status);
    this.status = status;

    this.layout(false);

    // Drag the wheel; a tap on a side card selects it, a tap on the front
    // card falls through to its own content (download link, checksum).
    this.onDown = Wheel.prototype.onDown.bind(this);
    this.onMove = Wheel.prototype.onMove.bind(this);
    this.onUp = Wheel.prototype.onUp.bind(this);
    grid.addEventListener("pointerdown", this.onDown);
    this.onClickCapture = function (e) {
      if (self._dragged) {
        // The gesture was a swipe, not a tap — don't let it activate anything.
        e.stopPropagation();
        e.preventDefault();
        self._dragged = false;
        return;
      }
      var card = e.target.closest ? e.target.closest(".dl-card") : null;
      if (!card || card.parentNode !== grid || card._i === self.front) return;
      // A peeking card: tapping it selects it instead of whatever's inside
      // (its link/details are inert already, but this also catches taps
      // that land on the card's non-interactive chrome — icon, title, badges).
      e.preventDefault();
      self.spinTo(card._i, true);
    };
    grid.addEventListener("click", this.onClickCapture, true);

    // Scrolling over the wheel turns it. preventDefault is deliberate — while
    // the pointer is over the deck the gesture belongs to the wheel, not the
    // page (that's the point of scroll-to-turn). One card per accumulated
    // notch, with a settle window so a fast trackpad flick steps
    // card-by-card instead of flying past everything.
    this._wheelAcc = 0;
    this._lastSpin = 0;
    this.onWheelScroll = function (e) {
      e.preventDefault();
      var d = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
      if (e.deltaMode === 1) d *= 24; // line-mode deltas (Firefox) -> ~px
      else if (e.deltaMode === 2) d *= 120; // page-mode -> ~px
      var now = Date.now();
      if (now - self._lastSpin < SPIN_MS * 0.7) return; // let the spin settle
      self._wheelAcc += d;
      if (Math.abs(self._wheelAcc) < 60) return;
      var dir = self._wheelAcc > 0 ? 1 : -1;
      self._wheelAcc = 0;
      self._lastSpin = now;
      self.spinTo((((self.front + dir) % self.n) + self.n) % self.n, true);
    };
    grid.addEventListener("wheel", this.onWheelScroll, { passive: false });

    this.onResize = function () {
      self.deckW = wheelEl.clientWidth;
      self.measure();
      self.layout(false);
    };
    window.addEventListener("resize", this.onResize);

    this.onDetected = function (e) {
      if (!e.detail || !e.detail.card) return;
      var idx = self.cards.indexOf(e.detail.card);
      if (idx < 0) return;
      self.spinTo(idx, true);
    };
    document.addEventListener("dl:platform-detected", this.onDetected);
  }

  // Re-read each card's own content height and the breakpoint-dependent pose
  // values. Called on init and on resize — line-wrapping (and so card height),
  // the card's layout width, and the mobile/wide pose all depend on viewport
  // width.
  Wheel.prototype.measure = function () {
    this._stepX = wideMq.matches ? STEP_X_WIDE : STEP_X;
    this._stepDeg = wideMq.matches ? STEP_DEG_WIDE : STEP_DEG;
    this._cardW = this.cards[0].offsetWidth || 0;
    for (var i = 0; i < this.n; i++) this.cards[i]._h = this.cards[i].offsetHeight;
  };

  Wheel.prototype.applyHeight = function (animate) {
    var h = this.cards[this.front]._h;
    this.grid.style.transition = animate && !reduce ? HEIGHT_TRANS : "none";
    this.grid.style.height = h + HEIGHT_PAD + "px";
  };

  // Shortest signed distance from `front` to card `i` around the circle —
  // e.g. for 3 cards, always one of -1, 0, +1. Works for a fractional
  // `front` too (used while dragging, for a continuous 1:1 pose).
  function signedOffset(i, front, n) {
    var raw = i - front;
    return raw - n * Math.round(raw / n);
  }

  // Pose one card at a signed step offset from center (0 = front-facing,
  // spotlighted). Negative = peeking in from the left, positive = right.
  Wheel.prototype.poseAt = function (el, offset, animate) {
    el.style.transition = animate && !reduce ? SPIN_TRANS : "none";
    var off = Math.max(-(VISIBLE_STEPS + 1.4), Math.min(VISIBLE_STEPS + 1.4, offset));
    var mag = Math.abs(off);
    var x = off * this._stepX;
    var z = -mag * STEP_Z;
    var deg = -off * this._stepDeg;
    var scale = Math.max(0.62, 1 - mag * STEP_SCALE);
    var bright = Math.max(0.55, 1 - mag * STEP_DIM);
    var blur = Math.min(mag, VISIBLE_STEPS + 1) * STEP_BLUR;
    var op = mag <= VISIBLE_STEPS ? 1 : Math.max(0, 1 - (mag - VISIBLE_STEPS) * 1.4);
    el.style.transform =
      "translateX(" + x.toFixed(2) + "%) translateZ(" + z.toFixed(1) + "px) " +
      "rotateY(" + deg.toFixed(2) + "deg) scale(" + scale.toFixed(3) + ")";
    el.style.opacity = String(op);
    el.style.filter =
      mag > 0.02
        ? "brightness(" + bright.toFixed(3) + ") blur(" + blur.toFixed(2) + "px)"
        : "none";
    el.style.zIndex = String(100 - Math.round(mag * 10));
  };

  // Continuous pose during a drag: every card rendered relative to a
  // fractional "virtual" front, no transition (1:1 finger tracking).
  Wheel.prototype.renderAt = function (virtualFront) {
    for (var i = 0; i < this.n; i++) {
      this.poseAt(this.cards[i], signedOffset(i, virtualFront, this.n), false);
    }
  };

  Wheel.prototype.layout = function (animate) {
    for (var i = 0; i < this.n; i++) {
      var isFront = i === this.front;
      var card = this.cards[i];
      if (card._link) card._link.toggleAttribute("inert", !isFront);
      if (card._details) card._details.toggleAttribute("inert", !isFront);
      card.classList.toggle("is-highlight", isFront);
      this.poseAt(card, signedOffset(i, this.front, this.n), animate);
    }
    this.applyHeight(animate);
    this.updateDots();
    this.announce();
  };

  Wheel.prototype.spinTo = function (idx, animate) {
    this.front = idx;
    this.layout(animate);
  };

  Wheel.prototype.updateDots = function () {
    for (var i = 0; i < this.dotBtns.length; i++) {
      this.dotBtns[i].setAttribute("aria-current", i === this.front ? "true" : "false");
    }
  };

  Wheel.prototype.announce = function () {
    if (this._lastAnnounced === this.front) return;
    this._lastAnnounced = this.front;
    var h3 = this.cards[this.front].querySelector("h3");
    this.status.textContent = h3 ? h3.textContent + " selected" : "";
  };

  /* --- Drag / swipe the wheel ----------------------------------------------- */
  Wheel.prototype.onDown = function (e) {
    if (e.pointerType === "mouse" && e.button !== 0) return;
    this.dragging = true;
    this.decided = false;
    this.horizontal = false;
    this._dragged = false;
    this._frac = 0;
    this.startX = e.clientX;
    this.startY = e.clientY;
    // One step of drag = the distance a card actually travels per step: its
    // measured layout width × the lateral travel fraction. (The old
    // deckW × 0.82 shortcut assumed the mobile card fraction and would have
    // demanded ~2× the finger travel on the desktop deck.)
    this.stepPx = Math.max(40, (this._cardW || this.deckW * 0.82) * (this._stepX / 100));
    document.addEventListener("pointermove", this.onMove, { passive: false });
    document.addEventListener("pointerup", this.onUp);
    document.addEventListener("pointercancel", this.onUp);
  };
  Wheel.prototype.onMove = function (e) {
    if (!this.dragging) return;
    var dx = e.clientX - this.startX;
    var dy = e.clientY - this.startY;
    if (!this.decided) {
      if (Math.abs(dx) < 6 && Math.abs(dy) < 6) return;
      this.decided = true;
      this.horizontal = Math.abs(dx) > Math.abs(dy);
      if (this.horizontal) {
        this._dragged = true; // a swipe, not a tap — suppress the trailing click
        try {
          this.wheel.setPointerCapture(e.pointerId);
        } catch (x) {}
      }
    }
    if (!this.horizontal) {
      this.endDrag(); // vertical gesture: let the page scroll
      return;
    }
    e.preventDefault();
    this._frac = -dx / this.stepPx;
    this.renderAt(this.front + this._frac);
  };
  Wheel.prototype.onUp = function (e) {
    if (!this.dragging) return;
    var canceled = e.type === "pointercancel";
    var wasHorizontal = this.horizontal;
    var frac = this._frac;
    this.endDrag();
    if (!wasHorizontal) return;
    if (!canceled && Math.abs(frac) >= SWIPE_RATIO) {
      var target = Math.round(this.front + frac);
      target = ((target % this.n) + this.n) % this.n;
      this.front = target;
    }
    this.layout(true); // settle: snap to the target (or back to front if the drag was too small)
  };
  Wheel.prototype.endDrag = function () {
    this.dragging = false;
    this._frac = 0;
    document.removeEventListener("pointermove", this.onMove, { passive: false });
    document.removeEventListener("pointerup", this.onUp);
    document.removeEventListener("pointercancel", this.onUp);
  };

  /* --- Teardown (restore the original grid) ----------------------------------
     No longer wired to a breakpoint (the wheel now runs at every width); kept
     as the documented undo for everything the constructor wraps/poses. */
  Wheel.prototype.destroy = function () {
    this.endDrag();
    document.removeEventListener("dl:platform-detected", this.onDetected);
    window.removeEventListener("resize", this.onResize);
    var g = this.grid;
    var wheelEl = this.wheel;
    g.removeEventListener("pointerdown", this.onDown);
    g.removeEventListener("click", this.onClickCapture, true);
    g.removeEventListener("wheel", this.onWheelScroll);
    removeNode(this.dots);
    removeNode(this.status);
    this.cards.forEach(function (el) {
      if (el._link) el._link.removeAttribute("inert");
      if (el._details) el._details.removeAttribute("inert");
      ["transform", "opacity", "filter", "transition", "z-index"].forEach(function (p) {
        el.style.removeProperty(p);
      });
    });
    g.classList.remove("dl-wheel-deck");
    g.style.removeProperty("height");
    g.style.removeProperty("transition");
    if (wheelEl && wheelEl.parentNode) {
      wheelEl.parentNode.insertBefore(g, wheelEl); // unwrap
      wheelEl.parentNode.removeChild(wheelEl);
    }
    g._dlwheel = null;
  };

  /* --- Helpers ---------------------------------------------------------------- */
  function bindDot(self, btn, idx) {
    btn.addEventListener("click", function () {
      self.spinTo(idx, true);
    });
  }
  function removeNode(n) {
    if (n && n.parentNode) n.parentNode.removeChild(n);
  }
  function slice(nl) {
    return Array.prototype.slice.call(nl);
  }
  function ready(fn) {
    // Wait for DOMContentLoaded so platform.js (also DCL) wires the cards
    // first; only skip waiting once the document is fully complete.
    if (document.readyState === "complete") fn();
    else document.addEventListener("DOMContentLoaded", fn);
  }
})();
