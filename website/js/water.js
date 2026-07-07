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

  var MAX_RIP = 28;

  var VERT =
    "attribute vec2 a_pos;" + "void main(){ gl_Position = vec4(a_pos, 0.0, 1.0); }";

  var FRAG = [
    "precision highp float;",
    "uniform vec2 u_res;",
    "uniform float u_time;",
    "uniform float u_dark;",
    "uniform vec2 u_mouse;",
    "uniform int u_ripCount;",
    "uniform vec4 u_rip[" + MAX_RIP + "];", // x, y, startTime, travelDir

    "float ambient(vec2 p, float t){",
    "  float h = 0.0;",
    // Fewer, larger, slower swells — deep calm water rather than pond texture.
    "  h += 0.55 * sin(p.x*2.8 + t*0.38) * cos(p.y*2.4 - t*0.28);",
    "  h += 0.28 * sin(p.x*5.2 - t*0.58 + 1.3) * cos(p.y*4.4 + t*0.46);",
    "  h += 0.14 * sin((p.x + p.y)*7.6 + t*0.80);",
    "  return h * 0.36;",
    "}",

    "float ripples(vec2 p, float t){",
    "  float h = 0.0;",
    "  for(int i=0;i<" + MAX_RIP + ";i++){",
    "    if(i >= u_ripCount) break;",
    "    vec4 r = u_rip[i];",
    "    float age = t - r.z;",
    "    vec2 d = p - r.xy;",
    // Faint elongation along the pointer's travel — NO angular wobble, so the
    // wake reads as gently moving water rather than stamped shapes.
    "    float c = cos(r.w), s = sin(r.w);",
    "    vec2 q = vec2(d.x*c + d.y*s, -d.x*s + d.y*c);",
    "    q.x /= 1.3;",
    // A STATIONARY pluck: a localized displacement (no travelling front, so it
    // doesn't tear the pattern) that relaxes very slowly, like a plucked string
    // easing back to rest with one gentle swing.
    "    float spatial = exp(-dot(q, q) * 200.0);",
    "    float env = cos(age * 1.2) * exp(-age * 0.7);",
    "    h += 0.11 * spatial * env;",
    "  }",
    "  return h;",
    "}",

    "float height(vec2 p, float t){",
    // No persistent dimple at the cursor — when you stop, the surface eases all
    // the way back to its resting pattern.
    "  return ambient(p, t) + ripples(p, t);",
    "}",

    "void main(){",
    "  vec2 p = (gl_FragCoord.xy - 0.5*u_res) / u_res.y;",
    "  float t = u_time;",
    "  float e = 1.6 / u_res.y;",
    "  float hc = height(p, t);",
    "  float hx = height(p + vec2(e, 0.0), t);",
    "  float hy = height(p + vec2(0.0, e), t);",
    "  vec2 g = vec2(hx - hc, hy - hc) / e;",
    // Slightly stronger tilt compensates for the calmer (lower-frequency) swell,
    // so the surface keeps some life without the busy sparkle.
    "  vec3 n = normalize(vec3(-g * 0.09, 1.0));",
    // The light keeps a fixed tilt (so flat water stays dark) but its AZIMUTH
    // rotates with position + time — the highlight direction depends on where
    // you are on the surface instead of sitting in one fixed corner.
    "  float az = p.x*1.7 - p.y*1.3 + t*0.08;",
    "  vec3 L = normalize(vec3(0.72*cos(az), 0.72*sin(az), 0.85));",
    "  vec3 Hh = normalize(L + vec3(0.0, 0.0, 1.0));",
    "  float spec = pow(max(dot(n, Hh), 0.0), 45.0);",
    "  float fres = pow(1.0 - max(n.z, 0.0), 3.0);",
    "  float depth = clamp(hc * 0.5 + 0.5, 0.0, 1.0);",
    // Colour shifts across the surface with position + height, so different
    // areas of the design read as different emerald/teal shades.
    "  float hue = 0.5 + 0.5 * sin(p.x*2.2 - p.y*1.7 + hc*4.0 + t*0.15);",
    "  vec3 glint = mix(vec3(0.10, 0.78, 0.55), vec3(0.20, 0.78, 0.92), hue);",
    "  vec3 col;",
    "  if(u_dark > 0.5){",
    "    vec3 deep = vec3(0.045, 0.056, 0.066);",
    "    vec3 shallow = mix(vec3(0.030, 0.082, 0.072), vec3(0.052, 0.070, 0.098), hue);",
    "    col = mix(deep, shallow, depth * 0.6);",
    "    col += glint * spec * 0.28;",
    "    col += glint * fres * 0.04;",
    "  } else {",
    "    vec3 baseL = vec3(0.90, 0.94, 0.97);",
    "    col = baseL - mix(vec3(0.05,0.03,0.02), vec3(0.02,0.05,0.04), hue) * (1.0 - depth);",
    "    col += glint * spec * 0.4;",
    "  }",
    // Depth vignette: gently sink the edges/corners so the surface reads as deep
    // water and gives content a calmer, darker frame. Aspect-correct via p.
    "  float vig = 1.0 - smoothstep(0.55, 1.18, length(p));",
    "  col *= mix(0.66, 1.0, vig);",
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

  // Ripple ring buffer: (x, y, startTime, travelDir) per ripple, in the
  // shader's normalized space.
  var rips = new Float32Array(MAX_RIP * 4);
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

  function spawn(cx, cy, dir) {
    var t = (performance.now() - start) / 1000;
    // Shift the newest ripples to the front, drop the oldest if full.
    var n = Math.min(ripCount + 1, MAX_RIP);
    for (var i = n - 1; i > 0; i--) {
      rips[i * 4] = rips[(i - 1) * 4];
      rips[i * 4 + 1] = rips[(i - 1) * 4 + 1];
      rips[i * 4 + 2] = rips[(i - 1) * 4 + 2];
      rips[i * 4 + 3] = rips[(i - 1) * 4 + 3];
    }
    toShader(cx, cy, rips, 0);
    rips[2] = t;
    rips[3] = dir || 0;
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
        // Pluck the surface along the path (~32px, ~120ms); overlapping plucks
        // blend into one soft trail that then relaxes back very slowly.
        if (d2 > 1000 && now - lastSpawnMs >= 120) {
          // Travel direction in shader space (y is flipped) → elongates the ripple.
          var dir = have
            ? Math.atan2(-(e.clientY - lastY), e.clientX - lastX)
            : 0;
          spawn(e.clientX, e.clientY, dir);
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
        spawn(e.clientX, e.clientY, 0); // a splash on click
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
    // Prune plucks older than ~3.5s (by then they've relaxed back to rest);
    // keeps the loop cheap when the pointer is idle.
    while (ripCount > 0 && t - rips[(ripCount - 1) * 4 + 2] > 3.5) {
      ripCount--;
    }
    gl.uniform1i(uRipCount, ripCount);
    gl.uniform4fv(uRip, rips);
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
