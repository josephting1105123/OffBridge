/* ==========================================================================
   water.js — animated "water surface" background with cursor-driven ripples.

   A single full-screen WebGL layer behind all content: a gently moving water
   surface (layered waves) plus expanding ripples spawned wherever the pointer
   moves, so the cursor reads as an object breaking the surface — it sits in a
   small dimple and leaves a wake of rings. Palette is on-brand and dark (deep
   slate water with emerald/teal glints) so text over it stays readable.

   Degrades gracefully: a static CSS gradient when WebGL is unavailable, and a
   single still frame (no loop, no ripples) under prefers-reduced-motion.
   ========================================================================== */
(function () {
  "use strict";

  var canvas = document.querySelector(".water-bg");
  if (!canvas) return;

  var reduce =
    window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  var gl =
    canvas.getContext("webgl", {
      antialias: false,
      alpha: true,
      depth: false,
      stencil: false,
      premultipliedAlpha: false,
      powerPreference: "low-power"
    }) || canvas.getContext("experimental-webgl");

  if (!gl) {
    canvas.classList.add("water-bg--fallback"); // CSS shows a static gradient
    return;
  }

  var MAX_RIP = 12;

  var VERT =
    "attribute vec2 a_pos;" + "void main(){ gl_Position = vec4(a_pos, 0.0, 1.0); }";

  var FRAG = [
    "precision highp float;",
    "uniform vec2 u_res;",
    "uniform float u_time;",
    "uniform float u_dark;",
    "uniform vec2 u_mouse;",
    "uniform int u_ripCount;",
    "uniform vec3 u_rip[" + MAX_RIP + "];", // x, y, startTime

    "float ambient(vec2 p, float t){",
    "  float h = 0.0;",
    "  h += 0.55 * sin(p.x*5.5 + t*0.55) * cos(p.y*4.7 - t*0.42);",
    "  h += 0.28 * sin(p.x*10.5 - t*0.85 + 1.3) * cos(p.y*8.8 + t*0.66);",
    "  h += 0.14 * sin((p.x + p.y)*15.0 + t*1.15);",
    "  return h * 0.36;",
    "}",

    "float ripples(vec2 p, float t){",
    "  float h = 0.0;",
    "  for(int i=0;i<" + MAX_RIP + ";i++){",
    "    if(i >= u_ripCount) break;",
    "    vec3 r = u_rip[i];",
    "    float age = t - r.z;",
    "    float dist = length(p - r.xy);",
    "    float front = age * 0.72;",             // ring speed
    "    float ring = sin((dist - front) * 52.0);",
    "    float env = exp(-abs(dist - front) * 9.0) * exp(-age * 1.7);",
    "    h += 0.3 * ring * env;",
    "  }",
    "  return h;",
    "}",

    "float height(vec2 p, float t){",
    "  float h = ambient(p, t) + ripples(p, t);",
    "  float md = length(p - u_mouse);",
    "  h -= 0.5 * exp(-md * 20.0);",             // the object resting in the surface
    "  return h;",
    "}",

    "void main(){",
    "  vec2 p = (gl_FragCoord.xy - 0.5*u_res) / u_res.y;",
    "  float t = u_time;",
    "  float e = 1.6 / u_res.y;",
    "  float hc = height(p, t);",
    "  float hx = height(p + vec2(e, 0.0), t);",
    "  float hy = height(p + vec2(0.0, e), t);",
    "  vec2 g = vec2(hx - hc, hy - hc) / e;",
    "  vec3 n = normalize(vec3(-g * 0.06, 1.0));",
    "  vec3 L = normalize(vec3(0.45, 0.75, 0.85));",
    "  vec3 Hh = normalize(L + vec3(0.0, 0.0, 1.0));",
    "  float spec = pow(max(dot(n, Hh), 0.0), 55.0);",
    "  float fres = pow(1.0 - max(n.z, 0.0), 3.0);",
    "  float depth = clamp(hc * 0.5 + 0.5, 0.0, 1.0);",
    "  vec3 glint = mix(vec3(0.16, 0.83, 0.52), vec3(0.17, 0.80, 0.74), 0.5);",
    "  vec3 col;",
    "  if(u_dark > 0.5){",
    "    vec3 deep = vec3(0.048, 0.058, 0.068);",
    "    vec3 shallow = vec3(0.034, 0.082, 0.076);",
    "    col = mix(deep, shallow, depth * 0.6);",
    "    col += glint * spec * 0.55;",
    "    col += glint * fres * 0.04;",
    "  } else {",
    "    vec3 baseL = vec3(0.90, 0.94, 0.97);",
    "    col = baseL - vec3(0.05, 0.03, 0.02) * (1.0 - depth);",
    "    col += vec3(0.03, 0.22, 0.18) * spec * 0.45;",
    "  }",
    "  gl_FragColor = vec4(col, 1.0);",
    "}"
  ].join("\n");

  function compile(type, src) {
    var s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
      // Compilation failed — fall back to the CSS gradient.
      return null;
    }
    return s;
  }

  var vs = compile(gl.VERTEX_SHADER, VERT);
  var fs = compile(gl.FRAGMENT_SHADER, FRAG);
  if (!vs || !fs) {
    canvas.classList.add("water-bg--fallback");
    return;
  }
  var prog = gl.createProgram();
  gl.attachShader(prog, vs);
  gl.attachShader(prog, fs);
  gl.linkProgram(prog);
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
    canvas.classList.add("water-bg--fallback");
    return;
  }
  gl.useProgram(prog);

  // Full-screen triangle.
  var buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(
    gl.ARRAY_BUFFER,
    new Float32Array([-1, -1, 3, -1, -1, 3]),
    gl.STATIC_DRAW
  );
  var aPos = gl.getAttribLocation(prog, "a_pos");
  gl.enableVertexAttribArray(aPos);
  gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0);

  var uRes = gl.getUniformLocation(prog, "u_res");
  var uTime = gl.getUniformLocation(prog, "u_time");
  var uDark = gl.getUniformLocation(prog, "u_dark");
  var uMouse = gl.getUniformLocation(prog, "u_mouse");
  var uRipCount = gl.getUniformLocation(prog, "u_ripCount");
  var uRip = gl.getUniformLocation(prog, "u_rip");

  var dpr = Math.min(window.devicePixelRatio || 1, 1.5);
  var vw = 0;
  var vh = 0;

  function resize() {
    vw = window.innerWidth;
    vh = window.innerHeight;
    canvas.width = Math.round(vw * dpr);
    canvas.height = Math.round(vh * dpr);
    gl.viewport(0, 0, canvas.width, canvas.height);
    gl.uniform2f(uRes, canvas.width, canvas.height);
  }
  resize();
  window.addEventListener("resize", resize, { passive: true });

  // Ripple ring buffer (positions in the shader's normalized space).
  var rips = new Float32Array(MAX_RIP * 3);
  var ripCount = 0;
  var mouseX = -9999;
  var mouseY = -9999;

  // Convert a client (CSS px) point to the shader's coordinate space, matching
  // p = (gl_FragCoord - 0.5*res)/res.y with fragCoord's bottom-left origin.
  function toShader(cx, cy, out, o) {
    out[o] = (cx - 0.5 * vw) / vh;
    out[o + 1] = (0.5 * vh - cy) / vh;
  }

  var mouseTmp = [0, 0];
  var start = performance.now();

  function spawn(cx, cy) {
    var t = (performance.now() - start) / 1000;
    // Shift the newest ripples to the front, drop the oldest if full.
    var n = Math.min(ripCount + 1, MAX_RIP);
    for (var i = n - 1; i > 0; i--) {
      rips[i * 3] = rips[(i - 1) * 3];
      rips[i * 3 + 1] = rips[(i - 1) * 3 + 1];
      rips[i * 3 + 2] = rips[(i - 1) * 3 + 2];
    }
    toShader(cx, cy, rips, 0);
    rips[2] = t;
    ripCount = n;
  }

  if (!reduce) {
    var lastX = 0;
    var lastY = 0;
    var have = false;
    var lastSpawnMs = -1e9;
    window.addEventListener(
      "pointermove",
      function (e) {
        if (e.pointerType && e.pointerType === "touch") return; // avoid touch spam
        mouseX = e.clientX;
        mouseY = e.clientY;
        var now = performance.now();
        var d2 = have ? (e.clientX - lastX) * (e.clientX - lastX) + (e.clientY - lastY) * (e.clientY - lastY) : 1e9;
        // Drop a ripple only after ~46px of travel AND at least 300ms since the
        // last one, so the wake stays sparse instead of a dense pile-up.
        if (d2 > 2100 && now - lastSpawnMs >= 300) {
          spawn(e.clientX, e.clientY);
          lastX = e.clientX;
          lastY = e.clientY;
          have = true;
          lastSpawnMs = now;
        }
      },
      { passive: true }
    );
    window.addEventListener(
      "pointerdown",
      function (e) {
        if (e.pointerType && e.pointerType === "touch") return;
        spawn(e.clientX, e.clientY); // a firmer splash on click
      },
      { passive: true }
    );
  }

  function isDark() {
    return document.documentElement.getAttribute("data-theme") !== "light"
      ? 1
      : 0;
  }

  function draw(now) {
    var t = (now - start) / 1000;
    gl.uniform1f(uTime, t);
    gl.uniform1f(uDark, isDark());
    if (mouseX < -9000) {
      gl.uniform2f(uMouse, -9999, -9999);
    } else {
      toShader(mouseX, mouseY, mouseTmp, 0);
      gl.uniform2f(uMouse, mouseTmp[0], mouseTmp[1]);
    }
    // Prune ripples older than ~3.5s so the loop stays cheap.
    while (ripCount > 0 && t - rips[(ripCount - 1) * 3 + 2] > 3.5) {
      ripCount--;
    }
    gl.uniform1i(uRipCount, ripCount);
    gl.uniform3fv(uRip, rips);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  if (reduce) {
    // One still frame; no animation loop, no ripples.
    draw(start);
    return;
  }

  var raf = 0;
  function loop(now) {
    draw(now);
    raf = window.requestAnimationFrame(loop);
  }
  function play() {
    if (!raf) raf = window.requestAnimationFrame(loop);
  }
  function pause() {
    if (raf) {
      window.cancelAnimationFrame(raf);
      raf = 0;
    }
  }
  document.addEventListener("visibilitychange", function () {
    if (document.hidden) pause();
    else play();
  });
  // Recover from a lost GL context by falling back rather than freezing.
  canvas.addEventListener(
    "webglcontextlost",
    function (e) {
      e.preventDefault();
      pause();
      canvas.classList.add("water-bg--fallback");
    },
    false
  );

  play();
})();
