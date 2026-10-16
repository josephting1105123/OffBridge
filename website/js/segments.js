/* ==========================================================================
   segments.js — sticky, segment-by-segment scrolling on desktop.
   Each scene is a segment. Inside one the page scrolls normally; at its edge
   further wheel input is held back and has to build past a threshold before
   the page moves on to the next segment, otherwise it lets go. The page never
   comes to rest straddling two segments. Phones, touch-first devices and
   narrow windows keep plain scrolling.
   ========================================================================== */
(function () {
  "use strict";

  var root = document.documentElement;
  var gate = window.matchMedia("(min-width: 1000px) and (hover: hover) and (pointer: fine)");
  var reduceQ = window.matchMedia("(prefers-reduced-motion: reduce)");

  var GAP_MS = 160;      // a pause this long ends one wheel gesture
  var LOCK_MS = 400;     // after a glide, ignore leftover momentum at most this long
  var MOMENTUM_WEIGHT = 0.35; // momentum that ran into an edge pulls at this strength
  var RELEASE_MS = 260;  // no input for this long and an unfinished pull lets go
  var IDLE_MS = 180;     // scroll idle time before straddle-settling

  var scenes = [];
  var tops = [];         // scroll position where each segment starts
  var ends = [];         // last scroll position that still shows only that segment
  var enabled = false;

  var pull = 0;          // >0 toward the next segment, <0 toward the previous one
  var pullFrom = -1;     // segment the pull started in
  var lastWheel = 0;
  var lastAbs = 0;           // size of the previous wheel delta
  var arrivedMoving = false; // gesture ran into the edge mid-scroll (likely momentum)
  var lockUntil = 0;         // swallow the tail of the gesture that just moved us
  var animating = false;
  var releaseTimer = 0;
  var idleTimer = 0;

  var pill, pillFill, pillLabel;

  function threshold() {
    return Math.max(100, Math.min(160, window.innerHeight * 0.15));
  }

  function padTop() {
    return parseFloat(getComputedStyle(root).scrollPaddingTop) || 0;
  }

  function maxScroll() {
    return Math.max(0, root.scrollHeight - window.innerHeight);
  }

  function measure() {
    var pad = padTop();
    var y = window.scrollY;
    var max = maxScroll();
    tops = scenes.map(function (s, i) {
      return i === 0 ? 0 : Math.min(max, Math.round(s.getBoundingClientRect().top + y - pad));
    });
    ends = tops.map(function (t, i) {
      var next = i + 1 < tops.length ? tops[i + 1] : max + window.innerHeight;
      return Math.min(max, Math.max(t, next - window.innerHeight));
    });
  }

  function indexAt(y) {
    var i = 0;
    for (var k = 0; k < tops.length; k++) if (tops[k] <= y + 1) i = k;
    return i;
  }

  function titleOf(i) {
    var s = scenes[i];
    var id = s.getAttribute("aria-labelledby");
    var el = id && document.getElementById(id);
    var t = ((el && el.textContent) || "").replace(/\s+/g, " ").trim();
    return t.length > 48 ? t.slice(0, 47) + "…" : t;
  }

  function buildPill() {
    pill = document.createElement("div");
    pill.className = "seg-pull";
    pill.setAttribute("aria-hidden", "true");
    pillLabel = document.createElement("span");
    pillLabel.className = "seg-pull__label";
    var track = document.createElement("span");
    track.className = "seg-pull__track";
    pillFill = document.createElement("span");
    pillFill.className = "seg-pull__fill";
    track.appendChild(pillFill);
    pill.appendChild(pillLabel);
    pill.appendChild(track);
    document.body.appendChild(pill);
  }

  function showPull() {
    if (!pill) return;
    var target = pullFrom + (pull > 0 ? 1 : -1);
    if (pull === 0 || target < 0 || target >= scenes.length) {
      pill.classList.remove("is-on");
      return;
    }
    pill.classList.toggle("is-up", pull < 0);
    pillLabel.textContent = (pull > 0 ? "↓ " : "↑ ") + titleOf(target);
    pillFill.style.transform = "scaleX(" + Math.min(1, Math.abs(pull) / threshold()) + ")";
    pill.classList.add("is-on");
  }

  function resetPull() {
    pull = 0;
    pullFrom = -1;
    showPull();
  }

  function scrollToY(y, smooth, done) {
    animating = true;
    var finish = function () {
      animating = false;
      if (done) done();
    };
    if (!smooth || reduceQ.matches) {
      window.scrollTo({ top: y, behavior: "instant" });
      requestAnimationFrame(finish);
      return;
    }
    window.scrollTo({ top: y, behavior: "smooth" });
    // scrollend isn't everywhere yet; poll until the position stops moving.
    var last = -1, still = 0, start = performance.now();
    (function wait() {
      var cur = window.scrollY;
      still = Math.abs(cur - last) < 0.5 ? still + 1 : 0;
      last = cur;
      if ((still >= 3 && Math.abs(cur - y) < 2) || performance.now() - start > 1500) finish();
      else requestAnimationFrame(wait);
    })();
  }

  function goTo(i, fromBelow) {
    i = Math.max(0, Math.min(scenes.length - 1, i));
    resetPull();
    lockUntil = Infinity;
    // Moving up lands on the end of the previous segment, so reading stays continuous.
    scrollToY(fromBelow ? ends[i] : tops[i], true, function () {
      lockUntil = performance.now() + LOCK_MS;
    });
  }

  function scrollableAncestor(el, dy) {
    for (; el && el !== document.body && el !== root; el = el.parentElement) {
      if (el.nodeType !== 1) continue;
      var oy = getComputedStyle(el).overflowY;
      if ((oy === "auto" || oy === "scroll") && el.scrollHeight > el.clientHeight + 1) {
        if (dy > 0 ? el.scrollTop + el.clientHeight < el.scrollHeight - 1 : el.scrollTop > 0) return true;
      }
    }
    return false;
  }

  function normDelta(e) {
    var d = e.deltaY;
    if (e.deltaMode === 1) d *= 16;
    else if (e.deltaMode === 2) d *= window.innerHeight;
    return d;
  }

  function onWheel(e) {
    if (!enabled || e.defaultPrevented || e.ctrlKey) return;
    if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) return;
    var dy = normDelta(e);
    if (!dy || scrollableAncestor(e.target, dy)) return;

    var now = performance.now();
    var abs = Math.abs(dy);
    // A pause, or input speeding up again, means fresh intent. Touchpad momentum
    // only ever slows down, and a new swipe often starts before it ends.
    var fresh = now - lastWheel > GAP_MS || (abs > 4 && abs > lastAbs * 1.3);
    lastWheel = now;
    lastAbs = abs;
    if (fresh) arrivedMoving = false;
    if (fresh && !animating) lockUntil = 0;

    if (animating || now < lockUntil) {
      e.preventDefault();
      return;
    }

    var y = window.scrollY;
    var i = indexAt(y);
    var down = dy > 0;
    var edge = down ? ends[i] : tops[i];
    var hasNext = down ? i + 1 < scenes.length : i > 0;

    // Still room inside this segment: scroll natively, clamped at its edge.
    if (down ? y + dy <= edge + 0.5 : y + dy >= edge - 0.5) {
      if (pull !== 0) resetPull();
      return;
    }
    if (!hasNext) return; // first/last segment: let the browser handle the page ends

    e.preventDefault();
    var overshoot = down ? y + dy - edge : edge - (y + dy);
    if (Math.abs(y - edge) > 0.5) {
      // The gesture ran into the edge while moving: stop at the edge, and let
      // the rest of this stream pull only weakly.
      window.scrollTo({ top: edge, behavior: "instant" });
      if (!fresh) arrivedMoving = true;
    }
    if (arrivedMoving) overshoot *= MOMENTUM_WEIGHT;

    if (pull !== 0 && (pull > 0) !== down) resetPull();
    if (pull === 0) pullFrom = i;
    pull += down ? overshoot : -overshoot;
    showPull();

    clearTimeout(releaseTimer);
    if (Math.abs(pull) >= threshold()) {
      goTo(pullFrom + (down ? 1 : -1), !down);
    } else {
      releaseTimer = setTimeout(resetPull, RELEASE_MS);
    }
  }

  function typing(t) {
    return t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName));
  }

  function onKey(e) {
    if (!enabled || e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey || typing(e.target)) return;
    var k = e.key;
    var down = k === "PageDown" || (k === " " && !e.shiftKey);
    var up = k === "PageUp" || (k === " " && e.shiftKey);
    if (!down && !up) return;
    if (e.target && e.target.closest && e.target.closest("button, a, [role=button], summary") && k === " ") return;
    e.preventDefault();
    if (animating) return;
    var y = window.scrollY;
    var i = indexAt(y);
    var step = window.innerHeight * 0.85;
    if (down) {
      if (y < ends[i] - 0.5) scrollToY(Math.min(ends[i], y + step), true);
      else if (i + 1 < scenes.length) goTo(i + 1, false);
    } else {
      if (y > tops[i] + 0.5) scrollToY(Math.max(tops[i], y - step), true);
      else if (i > 0) goTo(i - 1, true);
    }
  }

  // Scrollbar drags and arrow keys can still leave the page between two
  // segments; once user scrolling stops, settle into whichever shows more.
  function onScroll() {
    if (!enabled) return;
    paintActive();
    clearTimeout(idleTimer);
    idleTimer = setTimeout(settle, IDLE_MS);
  }

  function settle() {
    if (!enabled || animating || pull !== 0) return;
    var y = window.scrollY;
    var i = indexAt(y);
    if (y <= ends[i] + 0.5 || i + 1 >= scenes.length) return;
    // Between end of segment i and start of i+1.
    var toEnd = y - ends[i];
    var toNext = tops[i + 1] - y;
    scrollToY(toEnd <= toNext ? ends[i] : tops[i + 1], true);
  }

  /* --- Story: Send, Pair and Off-grid share one pinned stage -------------
     On desktop the three scenes' app screens move into a single sticky pane
     beside their copy, and the screen follows whichever scene is in view.
     Below the breakpoint everything goes back where the markup put it. */
  var STORY = ["send", "pair", "offgrid"];
  var story = null; // { wrap, pin, moved: [{ stage, parent, next, sec }], secs }
  var current = -1;

  function buildStory() {
    var secs = STORY.map(function (id) { return document.getElementById(id); });
    if (secs.some(function (s) { return !s || !s.querySelector(".stage"); })) return;
    var wrap = document.createElement("div");
    wrap.className = "story";
    var copy = document.createElement("div");
    copy.className = "story__copy";
    var pane = document.createElement("div");
    pane.className = "story__stage";
    var pin = document.createElement("div");
    pin.className = "story__pin";
    pane.appendChild(pin);
    secs[0].parentNode.insertBefore(wrap, secs[0]);
    wrap.appendChild(copy);
    wrap.appendChild(pane);
    var moved = secs.map(function (sec, i) {
      var stage = sec.querySelector(".stage");
      var rec = { stage: stage, parent: stage.parentNode, next: stage.nextSibling, sec: sec };
      stage.setAttribute("data-story", STORY[i]);
      copy.appendChild(sec);
      pin.appendChild(stage);
      return rec;
    });
    story = { wrap: wrap, pin: pin, moved: moved, secs: secs };
  }

  function unbuildStory() {
    if (!story) return;
    var anchor = story.wrap;
    story.secs.forEach(function (sec) {
      anchor.parentNode.insertBefore(sec, anchor);
    });
    story.moved.forEach(function (m) {
      m.parent.insertBefore(m.stage, m.next);
      m.stage.removeAttribute("data-story");
      m.stage.classList.remove("is-active");
      m.stage.removeAttribute("inert");
    });
    anchor.parentNode.removeChild(anchor);
    story = null;
  }

  /* --- Rail: numbered chapters down the left edge ------------------------ */
  var rail = null;

  function railLabel(i) {
    var s = scenes[i];
    if (s.classList.contains("scene--hero")) return "Intro";
    var link = document.querySelector('.topbar__sub a[href="#' + s.id + '"]');
    return (link && link.textContent.trim()) || titleOf(i);
  }

  function buildRail() {
    rail = document.createElement("nav");
    rail.className = "seg-rail";
    rail.setAttribute("aria-label", "Page sections");
    var ol = document.createElement("ol");
    scenes.forEach(function (s, i) {
      var li = document.createElement("li");
      li.style.setProperty("--i", String(i));
      var a = document.createElement("a");
      a.href = s.id ? "#" + s.id : "#top";
      var n = document.createElement("span");
      n.className = "seg-rail__n";
      n.textContent = (i + 1 < 10 ? "0" : "") + (i + 1);
      var t = document.createElement("span");
      t.className = "seg-rail__t";
      t.textContent = railLabel(i);
      a.appendChild(n);
      a.appendChild(t);
      a.addEventListener("click", function (e) {
        if (!enabled) return;
        e.preventDefault();
        goTo(i, false);
      });
      li.appendChild(a);
      ol.appendChild(li);
    });
    rail.appendChild(ol);
    // The dial's face repeats the section at the marker, large. The links
    // on the ring already name it, so the face is hidden from readers.
    var face = document.createElement("div");
    face.className = "seg-rail__face";
    face.setAttribute("aria-hidden", "true");
    face.innerHTML = '<b class="seg-rail__fn"></b><span class="seg-rail__ft"></span>';
    rail.appendChild(face);
    document.body.appendChild(rail);
  }

  // The segment holding the middle of the viewport drives the rail and the
  // story stage, so the screen swaps while the glide is halfway, not after.
  function paintActive() {
    if (!enabled) return;
    var i = indexAt(window.scrollY + window.innerHeight / 2);
    if (i === current) return;
    current = i;
    if (rail) {
      // The rail is half a watch ring: turning it by --at steps brings this
      // segment's number round to the marker at 3 o'clock. --d is each
      // number's distance from the marker; at 3 steps it has turned off
      // the screen's edge, so it fades and leaves the tab order.
      rail.style.setProperty("--at", String(i));
      var face = rail.querySelector(".seg-rail__face");
      var num = (i + 1 < 10 ? "0" : "") + (i + 1);
      // Re-measures repaint too; only a real change replays the entrance.
      if (face.firstChild.textContent !== num) {
        face.firstChild.textContent = num;
        face.lastChild.textContent = railLabel(i);
        face.classList.remove("is-in");
        void face.offsetWidth;
        face.classList.add("is-in");
      }
      Array.prototype.forEach.call(rail.querySelectorAll("li"), function (li, k) {
        var a = li.firstChild;
        li.style.setProperty("--d", String(Math.abs(k - i)));
        a.tabIndex = Math.abs(k - i) > 2 ? -1 : 0;
        if (k === i) a.setAttribute("aria-current", "true");
        else a.removeAttribute("aria-current");
      });
    }
    if (story) {
      var id = scenes[i] && scenes[i].id;
      var at = STORY.indexOf(id);
      // Before the story, show its first screen; after it, keep its last.
      if (at < 0) at = i < scenes.indexOf(story.secs[0]) ? 0 : STORY.length - 1;
      story.moved.forEach(function (m, k) {
        var on = k === at;
        m.stage.classList.toggle("is-active", on);
        if (on) m.stage.removeAttribute("inert");
        else m.stage.setAttribute("inert", "");
      });
    }
  }

  function enable() {
    if (enabled) return;
    enabled = true;
    root.classList.add("is-segmented");
    buildStory();
    measure();
    if (!pill) buildPill();
    if (!rail) buildRail();
    current = -1;
    paintActive();
  }

  function disable() {
    if (!enabled) return;
    enabled = false;
    root.classList.remove("is-segmented");
    resetPull();
    unbuildStory();
    if (rail) {
      rail.parentNode.removeChild(rail);
      rail = null;
    }
    current = -1;
  }

  function sync() {
    if (gate.matches && scenes.length > 1) enable();
    else disable();
  }

  function init() {
    scenes = Array.prototype.slice.call(document.querySelectorAll("main section.scene"));
    sync();
    if (gate.addEventListener) gate.addEventListener("change", sync);
    else if (gate.addListener) gate.addListener(sync);

    window.addEventListener("wheel", onWheel, { passive: false });
    window.addEventListener("keydown", onKey);
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", function () {
      if (enabled) measure();
    });
    // Late layout shifts (fonts, images, demo state) move segment edges.
    if (window.ResizeObserver) {
      new ResizeObserver(function () {
        if (enabled) {
          measure();
          current = -1;
          paintActive();
        }
      }).observe(document.querySelector("main") || document.body);
    }
    window.addEventListener("load", function () {
      if (enabled) measure();
    });

    // Switching Windows/Android swaps every screen and reflows the page, and
    // scroll anchoring then lands somewhere else. Stay on the same segment.
    var osHold = -1, osHoldTimer;
    document.addEventListener("click", function (e) {
      if (!enabled || !e.target.closest || !e.target.closest("[data-os-pick]")) return;
      osHold = current;
      // Picking the OS already shown changes nothing; don't leave the hold armed.
      clearTimeout(osHoldTimer);
      osHoldTimer = setTimeout(function () { osHold = -1; }, 1000);
    }, true);
    if (window.MutationObserver) {
      new MutationObserver(function () {
        if (!enabled || osHold < 0) return;
        var i = osHold;
        osHold = -1;
        requestAnimationFrame(function () {
          requestAnimationFrame(function () {
            measure();
            window.scrollTo({ top: tops[i], behavior: "instant" });
            current = -1;
            paintActive();
          });
        });
      }).observe(root, { attributes: true, attributeFilter: ["data-os"] });
    }
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();
