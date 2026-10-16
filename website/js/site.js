/* ==========================================================================
   site.js — the Windows | Android switch and the live app demos.
   Every scene holds both renders; html[data-os] decides which one shows.
   Demos share one state per scene and update both renders together, so a
   switch mid-demo lands on the same moment in the other app.
   ========================================================================== */
(function () {
  "use strict";

  var root = document.documentElement;
  var reduce =
    window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  function $(sel, scope) {
    return (scope || document).querySelector(sel);
  }
  function $$(sel, scope) {
    return Array.prototype.slice.call((scope || document).querySelectorAll(sel));
  }
  function os() {
    return root.getAttribute("data-os") === "android" ? "android" : "windows";
  }
  function say(el, text) {
    if (el) el.textContent = text;
  }

  /* --- Platform switch -------------------------------------------------- */
  function syncSwitch() {
    $$("[data-os-pick]").forEach(function (b) {
      b.setAttribute("aria-checked", String(b.getAttribute("data-os-pick") === os()));
    });
  }

  // persist=false follows device detection without recording a choice.
  function setOS(next, persist) {
    if (next === os()) return;
    var apply = function () {
      root.setAttribute("data-os", next);
      syncSwitch();
    };
    if (persist !== false) {
      try {
        localStorage.setItem("ob-os", next);
      } catch (e) {}
    }
    if (!reduce && document.startViewTransition) {
      document.startViewTransition(apply);
    } else {
      apply();
    }
  }

  function initSwitch() {
    syncSwitch();
    var picks = $$("[data-os-pick]");
    picks.forEach(function (b, i) {
      b.addEventListener("click", function () {
        setOS(b.getAttribute("data-os-pick"));
      });
      // Radio-group keyboard pattern: arrows move and select.
      b.addEventListener("keydown", function (e) {
        var step = { ArrowLeft: -1, ArrowUp: -1, ArrowRight: 1, ArrowDown: 1 }[e.key];
        var j;
        if (step) j = (i + step + picks.length) % picks.length;
        else if (e.key === "Home") j = 0;
        else if (e.key === "End") j = picks.length - 1;
        else return;
        e.preventDefault();
        var other = picks[j];
        other.focus();
        setOS(other.getAttribute("data-os-pick"));
      });
    });
    // Download cards: once platform.js detects the device and the visitor
    // hasn't picked yet, follow it.
    document.addEventListener("dl:platform-detected", function (e) {
      var saved = null;
      try {
        saved = localStorage.getItem("ob-os");
      } catch (err) {}
      if (saved) return;
      var card = e.detail && e.detail.card;
      var key = card && card.getAttribute("data-dl-card");
      if (key === "android" || key === "windows") setOS(key, false);
    });
  }

  /* --- Toggles inside the app renders ----------------------------------- */
  function initToggles() {
    $$("[data-toggle]").forEach(function (t) {
      t.addEventListener("click", function () {
        t.setAttribute("aria-checked", String(t.getAttribute("aria-checked") !== "true"));
      });
    });
  }

  /* --- Hero: a copy hops to the other device ---------------------------- */
  var ICON = function (id) {
    return '<svg class="ic" aria-hidden="true"><use href="#' + id + '"></use></svg>';
  };

  function clipRow(text, kind) {
    var row = document.createElement("div");
    var t = document.createElement("span");
    t.className = "clip__t";
    t.textContent = text;
    var droid = kind === "droid";
    row.className = droid ? "clip clip--droid" : "clip";
    if (droid) row.innerHTML = '<span class="clip__tile">' + ICON("m-paste") + "</span>";
    row.appendChild(t);
    row.insertAdjacentHTML(
      "beforeend",
      '<span class="ib">' + ICON("m-copy") + '</span><span class="ib ib--del">' +
        ICON(droid ? "m-delete-o" : "m-delete") + "</span>"
    );
    return row;
  }

  function addClip(kind, text) {
    var list = $('[data-cliplist="' + kind + '"]');
    if (!list) return;
    var row = clipRow(text, kind);
    row.classList.add("is-new");
    list.insertBefore(row, list.firstChild);
    // The lists are fixed-height app screens; keep the newest five.
    while (list.children.length > 5) list.removeChild(list.lastChild);
    if (kind === "droid") {
      var count = $("[data-clipcount]");
      if (count) count.textContent = String(Math.min(99, parseInt(count.textContent, 10) + 1));
    }
  }

  function initHero() {
    var form = $("[data-try]");
    if (!form) return;
    var input = $("#try-in");
    var live = $("[data-try-live]");
    var busy = false;

    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var text = (input.value || "").trim();
      if (!text) return;
      var from = os() === "windows" ? "win" : "droid";
      var to = from === "win" ? "droid" : "win";
      addClip(from, text);

      var fromEl = $('[data-cliplist="' + from + '"]');
      var toEl = $('[data-cliplist="' + to + '"]');
      var visible = function (el) {
        var r = el.getBoundingClientRect();
        return r.width > 0 && r.bottom > 0 && r.top < window.innerHeight;
      };
      var done = function () {
        addClip(to, text);
        say(live, (to === "droid" ? "Pixel 10" : "Windows PC") + " received: " + text);
      };
      // A second copy during a flight lands straight away rather than queueing.
      if (reduce || busy || !fromEl || !toEl || !visible(fromEl) || !visible(toEl) || !Element.prototype.animate) {
        done();
        return;
      }

      // A sealed chip leaves the sender's list and lands on the receiver's.
      busy = true;
      var a = fromEl.getBoundingClientRect();
      var b = toEl.getBoundingClientRect();
      var chip = document.createElement("div");
      chip.className = "flight";
      chip.setAttribute("aria-hidden", "true");
      chip.innerHTML = ICON("m-lock");
      var s = document.createElement("span");
      s.textContent = text;
      chip.appendChild(s);
      document.body.appendChild(chip);
      var x0 = a.left + 8, y0 = a.top + 4;
      var x1 = b.left + 8, y1 = b.top + 4;
      var lift = Math.max(8, Math.min(y0, y1) - 70);
      var anim = chip.animate(
        [
          { transform: "translate(" + x0 + "px," + y0 + "px) scale(.9)", opacity: 0 },
          { transform: "translate(" + x0 + "px," + y0 + "px) scale(1)", opacity: 1, offset: 0.12 },
          { transform: "translate(" + (x0 + x1) / 2 + "px," + lift + "px) scale(1.04)", offset: 0.55 },
          { transform: "translate(" + x1 + "px," + y1 + "px) scale(1)", opacity: 1, offset: 0.9 },
          { transform: "translate(" + x1 + "px," + y1 + "px) scale(.96)", opacity: 0 }
        ],
        { duration: 1300, easing: "cubic-bezier(0.45, 0, 0.2, 1)" }
      );
      anim.onfinish = anim.oncancel = function () {
        chip.remove();
        busy = false;
        done();
      };
    });
  }

  /* --- Send: a transfer that survives a dropped link -------------------- */
  function initTransfer() {
    var TOTAL = 600;
    var st = { phase: "idle", mb: 0, timer: 0, last: 0 };
    var drop = $("[data-x-drop]");
    var restart = $("[data-x-restart]");
    var live = $("[data-x-live]");
    var roots = $$("[data-xfer-root]").map(function (r) {
      return {
        el: r,
        p: r.getAttribute("data-xfer-root"),
        dlg: $("[data-x-dialog]", r),
        btn: $("[data-x-btn]", r),
        bar: $("[data-x-bar]", r),
        pct: $("[data-x-pct]", r),
        status: $("[data-x-status]", r)
      };
    });

    function fmt(platform) {
      var pct = Math.floor((st.mb / TOTAL) * 100);
      if (platform === "android") {
        return (st.phase === "run" ? "Transferring: " : "") + pct + "% (" + st.mb.toFixed(1) + "MB / " + TOTAL.toFixed(1) + "MB)";
      }
      return pct + "% (" + st.mb.toFixed(1) + " MB / " + TOTAL.toFixed(1) + " MB)";
    }

    var STATUS = {
      windows: {
        run: "↕ Transferring",
        paused: "Paused: Network Dropped",
        done: "✓ Done"
      },
      android: {
        run: "",
        paused: "Paused: Network Dropped",
        done: "Done"
      }
    };

    function render() {
      roots.forEach(function (r) {
        var p = r.p;
        var btn = r.btn;
        r.dlg.hidden = st.phase === "idle";
        r.el.classList.toggle("is-paused", st.phase === "paused");
        r.el.classList.toggle("is-done", st.phase === "done");
        r.bar.style.setProperty("--p", (st.mb / TOTAL) * 100 + "%");
        say(r.pct, fmt(p));
        say(r.status, STATUS[p][st.phase] || "");
        btn.className = "abtn";
        if (st.phase === "run") {
          btn.classList.add("abtn--red");
          btn.textContent = "Cancel";
        } else if (st.phase === "paused") {
          btn.textContent = "Resume";
        } else {
          btn.textContent = p === "android" ? "Done" : "Close";
        }
      });
      if (drop) drop.disabled = st.phase !== "run";
    }

    function tick(now) {
      if (st.phase !== "run") return;
      var dt = Math.min(64, now - (st.last || now));
      st.last = now;
      // ~7 s end to end; a full-speed LAN would be quicker, this is for reading.
      st.mb = Math.min(TOTAL, st.mb + dt * 0.086);
      if (st.mb >= TOTAL) {
        st.phase = "done";
        say(live, "Transfer complete.");
      }
      render();
      if (st.phase === "run") st.timer = requestAnimationFrame(tick);
    }

    function run() {
      st.phase = "run";
      st.last = 0;
      render();
      if (reduce) {
        // No ticking: jump to a mid point, and finish on Resume.
        if (st.mb < TOTAL * 0.42) st.mb = TOTAL * 0.42;
        else {
          st.mb = TOTAL;
          st.phase = "done";
        }
        render();
        return;
      }
      cancelAnimationFrame(st.timer);
      st.timer = requestAnimationFrame(tick);
    }

    function reset() {
      cancelAnimationFrame(st.timer);
      st.phase = "idle";
      st.mb = 0;
      render();
    }

    roots.forEach(function (r) {
      $("[data-x-start]", r.el).addEventListener("click", function () {
        if (st.phase === "idle") {
          say(live, "Sending Holiday_clip.mp4.");
          run();
        }
      });
      r.btn.addEventListener("click", function () {
        if (st.phase === "run") {
          reset();
          say(live, "Transfer cancelled.");
        } else if (st.phase === "paused") {
          say(live, "Resumed from " + Math.floor((st.mb / TOTAL) * 100) + " percent.");
          run();
        } else {
          reset();
        }
      });
    });

    if (drop) {
      drop.addEventListener("click", function () {
        if (st.phase !== "run") return;
        cancelAnimationFrame(st.timer);
        st.phase = "paused";
        render();
        say(live, "Network dropped. Paused at " + Math.floor((st.mb / TOTAL) * 100) + " percent. Press Resume.");
      });
    }
    if (restart) restart.addEventListener("click", reset);

    render();
  }

  /* --- Pairing: the same six digits on both screens --------------------- */
  function initPairing() {
    var code = "";
    var live = $("[data-pair-live]");
    var modal = $("[data-pair-modal]");
    var startBtn = $("[data-pair-start]");
    var peerAccept = $("[data-peer-accept]");
    var peer = $("[data-peer]");
    var count = $("[data-paircount]");
    var ask = $('[data-dpair="ask"]');
    var ok = $('[data-dpair="ok"]');
    var again = $("[data-dpair-again]");

    function newCode() {
      var n = 0;
      if (window.crypto && crypto.getRandomValues) {
        var a = new Uint32Array(1);
        crypto.getRandomValues(a);
        n = a[0] % 1000000;
      } else {
        n = Math.floor(Math.random() * 1000000);
      }
      code = ("000000" + n).slice(-6);
      $$("[data-sas]").forEach(function (box) {
        box.innerHTML = "";
        for (var i = 0; i < 6; i++) {
          var b = document.createElement("b");
          b.textContent = code[i];
          box.appendChild(b);
        }
        if (box.classList.contains("sas--page")) {
          box.setAttribute("aria-label", "Pairing code " + code.split("").join(" "));
        }
      });
    }

    // One paired flag drives both apps, so a switch lands on the same moment.
    function setPaired(on) {
      if (startBtn) {
        startBtn.textContent = on ? "Unpair" : "Pair";
        startBtn.classList.toggle("is-unpair", on);
      }
      if (count) count.textContent = on ? "2" : "1";
      if (ask) ask.hidden = on;
      if (ok) ok.hidden = !on;
      if (again) again.hidden = !on;
      if (on && modal) modal.hidden = true;
      if (peerAccept) peerAccept.disabled = true;
      if (peer) peer.classList.remove("is-match");
      if (!on) newCode();
    }

    newCode();

    // Windows side
    if (startBtn) {
      startBtn.addEventListener("click", function () {
        if (startBtn.classList.contains("is-unpair")) {
          setPaired(false);
          say(live, "Unpaired Pixel 10.");
          return;
        }
        modal.hidden = false;
        if (peerAccept) peerAccept.disabled = false;
        if (peer) peer.classList.add("is-match");
        say(live, "Requesting pair. Code " + code.split("").join(" ") + ". Check Pixel 10 shows the same.");
      });
    }
    var cancel = $("[data-pair-cancel]");
    if (cancel) {
      cancel.addEventListener("click", function () {
        modal.hidden = true;
        if (peerAccept) peerAccept.disabled = true;
        if (peer) peer.classList.remove("is-match");
        newCode();
      });
    }
    if (peerAccept) {
      peerAccept.addEventListener("click", function () {
        setPaired(true);
        say(live, "Paired with Pixel 10.");
      });
    }

    // Android side
    var acc = $("[data-dpair-accept]");
    var dec = $("[data-dpair-decline]");
    if (acc) {
      acc.addEventListener("click", function () {
        setPaired(true);
        say(live, "Paired with Windows PC.");
      });
    }
    if (dec) {
      dec.addEventListener("click", function () {
        newCode();
        say(live, "Declined. A new request came in with a new code.");
      });
    }
    if (again) {
      again.addEventListener("click", function () {
        setPaired(false);
      });
    }
  }

  /* --- Off-grid: QR art and the two flows ------------------------------- */
  // Decorative code: finder squares plus seeded noise. It encodes nothing.
  function qrSVG() {
    var N = 25, seed = 7, cells = "";
    function rnd() {
      seed = (seed * 16807) % 2147483647;
      return seed / 2147483647;
    }
    function finder(x, y) {
      return (
        '<rect x="' + x + '" y="' + y + '" width="7" height="7"/>' +
        '<rect x="' + (x + 1) + '" y="' + (y + 1) + '" width="5" height="5" fill="#fff"/>' +
        '<rect x="' + (x + 2) + '" y="' + (y + 2) + '" width="3" height="3"/>'
      );
    }
    for (var y = 0; y < N; y++) {
      for (var x = 0; x < N; x++) {
        var inFinder = (x < 8 && y < 8) || (x > N - 9 && y < 8) || (x < 8 && y > N - 9);
        if (!inFinder && rnd() > 0.52) cells += "M" + x + " " + y + "h1v1h-1z";
      }
    }
    return (
      '<svg viewBox="0 0 ' + N + " " + N + '" shape-rendering="crispEdges" fill="currentColor">' +
      finder(0, 0) + finder(N - 7, 0) + finder(0, N - 7) +
      '<path d="' + cells + '"/></svg>'
    );
  }

  function initOffgrid() {
    var art = qrSVG();
    $$("[data-qr]").forEach(function (q) {
      q.innerHTML = art;
    });

    var scene = $("#offgrid");
    if (!scene) return;
    function show(scope, name) {
      $$("[data-off]", scope).forEach(function (el) {
        el.hidden = el.getAttribute("data-off") !== name;
      });
    }
    var win = $('.win[data-for="windows"]', scene);
    var droid = $('.droid[data-for="android"]', scene);

    // Both apps follow one step: the QR screen, or the start of the flow.
    // Android's "same network" list has no Windows twin, so Windows stays put.
    function step(name) {
      show(win, name === "qr" ? "qr" : "via");
      show(droid, name);
    }
    var qrBtn = $("[data-off-qr]", win);
    if (qrBtn) qrBtn.addEventListener("click", function () { step("qr"); });
    $$("[data-off-back]", win).forEach(function (b) {
      b.addEventListener("click", function () { step("net"); });
    });

    var pick = "diff";
    $$("[data-net]", droid).forEach(function (b) {
      b.addEventListener("click", function () {
        pick = b.getAttribute("data-net");
        $$("[data-net]", droid).forEach(function (o) {
          o.setAttribute("aria-pressed", String(o === b));
        });
      });
    });
    var next = $("[data-net-next]", droid);
    if (next) next.addEventListener("click", function () { step(pick === "diff" ? "qr" : "same"); });
    $$("[data-off-back]", droid).forEach(function (b) {
      b.addEventListener("click", function () { step("net"); });
    });
  }

  /* --- Small things ------------------------------------------------------ */
  function initYear() {
    var y = $("#year");
    if (y) y.textContent = String(new Date().getFullYear());
  }

  function boot() {
    initSwitch();
    initToggles();
    initHero();
    initTransfer();
    initPairing();
    initOffgrid();
    initYear();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
