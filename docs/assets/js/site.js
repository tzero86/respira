/* Respira — site interactions
   Language switching, breathing demo, ambient mixer, scroll motion. */
(() => {
  "use strict";

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  const reduced = window.matchMedia("(prefers-reduced-motion: reduce)");
  const clamp = (n, min, max) => Math.min(max, Math.max(min, n));

  /* ------------------------------------------------------------------ i18n */

  const EN = {
    nav_breathe: "Breathe",
    nav_sounds: "Sounds",
    nav_screens: "Screens",
    nav_download: "Download",
    nav_get: "Download",

    hero_eyebrow: "Android · no account · no network",
    hero_h1: "Breathe <em>slowly</em>.<span>Sleep deeper.</span>",
    hero_lead:
      "Guided breathing, meditation and a nature sound board that mixes live. Everything plays offline, without accounts and without ads.",
    hero_cta1: "Download for Android",
    hero_cta2: "Try it here",
    hero_meta: "v0.1.0-alpha11 · 24 MB · Android 8+",
    hero_meta_link: "Code on GitHub",

    chip_478: "4 · 7 · 8",
    chip_routine: "Bedtime routine",
    chip_offline: "Fully offline",

    b_eyebrow: "01 — Breathe",
    b_title: "An orb that keeps the time.",
    b_lead:
      "Four patterns, a drift-free clock and a circle that swells and settles with you. Soft haptics and quiet chimes if you want them.",
    p_calm: "Calm",
    p_box: "Box",
    p_478_hint: "Deep relaxation",
    p_coherent: "Coherent",
    p_coherent_hint: "Heart rhythm",
    b_hint: "Soft and steady · tap start",
    b_start: "Start",
    b_open: "Open",

    f_eyebrow: "Everything it does",
    f_title: "A small app, carefully made.",
    f1_title: "Guided breathing",
    f1_body:
      "Calm, Box, 4-7-8 and Coherent. Sessions of 1, 3, 5 or 10 minutes, or no limit at all. The orb follows a monotonic clock, so it never drifts.",
    f2_title: "Five original pieces",
    f2_body:
      "Long pads made by hand with numpy and scipy. No third-party licences, no catalogue that vanishes. Still Water, Night Sky, Sunrise, Deep Rest and Ocean Breath.",
    f3_title: "Ten sound layers",
    f3_body:
      "Rain, downpour, thunder, wind, stream, ocean, fireplace, night forest, birds and a deep hum. Seamless loops, mixed live with volume per layer.",
    f4_title: "Your audio, four ways",
    f4_body:
      "From your device, from a direct link, from YouTube or a podcast, or by sharing a file into Respira. It all stays on your phone.",
    f5_title: "Bedtime routine",
    f5_body:
      "Three minutes of 4-7-8, then your preferred track starts on its own. With a fade-in, a sleep timer and a fade-out at the end of the track.",
    f6_title: "Private by design",
    f6_body:
      "No accounts, no analytics, no ads. The network is only used to import audio from a link or to check for app updates.",

    s_eyebrow: "02 — Sounds",
    s_title: "Mix your own night.",
    s_lead:
      "Six presets to start from and control per layer. Raise the rain, lower the wind, leave the fire underneath. You can try it here — in the app these are the original loops.",
    s_mixer: "Mixer",
    s_p1: "Rainy night",
    s_p2: "Storm",
    s_p3: "Forest stream",
    s_p4: "Calm ocean",
    s_p5: "By the fire",
    s_p6: "Deep calm",
    s_rain: "Rain",
    s_thunder: "Thunder",
    s_wind: "Wind",
    s_stream: "Stream",
    s_ocean: "Ocean",
    s_fire: "Fireplace",
    s_listen: "Listen to the mix",
    s_stop: "Mute",
    s_note: "Built in your browser, layer by layer. The app uses field recordings.",

    g_eyebrow: "03 — Screens",
    g_title: "This is how it feels.",
    g_lead:
      "Night blue, serif type and an aurora backdrop breathing behind everything. Swipe to see more.",
    g1: "Home",
    g2: "Breathe",
    g3: "Meditate",
    g4: "Sounds",
    g5: "Player",
    g6: "Sleep timer",
    g7: "Import",

    r_eyebrow: "04 — Sleep",
    r_title: "Three minutes, then your track.",
    r_lead:
      "The bedtime routine does the boring part: it walks you through 4-7-8 and, when it ends, starts the track you picked. One tap from Home or from Breathe.",
    r_s1: "Three minutes of 4-7-8",
    r_s1b: "Inhale four, hold seven, exhale eight. The orb and the haptics keep the beat.",
    r_s2: "Your preferred track",
    r_s2b: "Heart a track and pin it as preferred so it takes the “Tonight” card.",
    r_s3: "It turns itself off",
    r_s3b: "Fade-in, a sleep timer if you need one, and a soft fade-out at the end of the track.",
    r_badge: "Keep sleeping",
    r_badge_b: "Pick up where you stopped",

    i_eyebrow: "05 — Your music",
    i_title: "Bring what you already listen to.",
    i_lead:
      "Four ways to add audio. It is downloaded or copied into the app's private storage and stays there, available offline.",
    i1_title: "From your device",
    i1_body: "The system file picker, with persisted read grants.",
    i2_title: "From a link",
    i2_body: "MP3, M4A, OGG, Opus, WAV, FLAC or AAC. With progress, cancel and a 300 MB cap.",
    i3_title: "From YouTube",
    i3_body: "Video, Shorts or short links. Save space (~50 MB for 3 h) or keep maximum quality (~190 MB).",
    i4_title: "By sharing",
    i4_body: "Send an audio file to Respira from any app and it shows up in your library.",

    d_eyebrow: "06 — Download",
    d_title: "Take it to your phone.",
    d_lead:
      "A signed APK that installs like any app. New versions download from inside the app, verifying package, version and signing certificate before handing them to the installer.",
    d_cta: "Download APK · 24 MB",
    d_all: "All releases",
    d_f1: "Version",
    d_f2: "Size",
    d_f3: "Android",
    d_f4: "Languages",
    d_how: "How to install",
    d_step1: "Open the link on your phone and download the APK.",
    d_step2: "When asked, allow installing unknown apps for your browser.",
    d_step3: "Install and open. No accounts, no first-run setup.",
    d_note: "No ads, no trackers, no extra permissions.",

    q_text: "“Exhale a little longer than you inhale.”",
    q_cite: "The only advice on the home screen",

    f_releases: "Releases",
    f_readme: "Documentation",
    f_made: "A personal project, made calmly. Android · Kotlin · Jetpack Compose.",
    f_priv: "No accounts · No ads · No analytics",

    /* dynamic */
    p_calm_hint: "Soft and steady",
    p_box_hint: "Balanced rhythm",
    p_478_hint: "Deep relaxation",
    b_start: "Start",
    s_listen: "Listen to the mix",
    s_stop: "Mute",
    phase_inhale: "Inhale",
    phase_holdIn: "Hold",
    phase_exhale: "Exhale",
    phase_holdOut: "Rest",
    b_ready: "Breathe",
    b_tap_start: " · tap start",
    b_pause: "Pause",
    b_resume: "Resume",
    b_paused: "Paused",
    b_again: "Again",
    b_done: "Done",
    b_done_hint: "Session complete · well done",
    b_left: "min left",
    b_open_session: "Open session",
    lang_switch: "Switch to Spanish",
    title: "Respira — breathe slowly, sleep deeper",
    desc: "Guided breathing, meditation and a nature sound board that mixes live. Android, offline, no accounts, no ads."
  };

  const ES_DYNAMIC = {
    p_calm_hint: "Suave y constante",
    p_box_hint: "Ritmo equilibrado",
    p_478_hint: "Relajación profunda",
    b_start: "Comenzar",
    s_listen: "Escuchar la mezcla",
    s_stop: "Silenciar",
    s_note: "Creado en tu navegador, capa por capa. La app usa grabaciones reales.",
    phase_inhale: "Inhala",
    phase_holdIn: "Sostén",
    phase_exhale: "Exhala",
    phase_holdOut: "Descansa",
    b_ready: "Respira",
    b_tap_start: " · toca comenzar",
    b_pause: "Pausar",
    b_resume: "Continuar",
    b_paused: "En pausa",
    b_again: "Otra vez",
    b_done: "Listo",
    b_done_hint: "Sesión completada · bien hecho",
    b_left: "min restantes",
    b_open_session: "Sesión sin límite",
    lang_switch: "Cambiar a inglés",
    title: "Respira — respira despacio, duerme mejor",
    desc: "Respiración guiada, meditación y un tablero de sonidos de la naturaleza que se mezcla en vivo. Android, offline, sin cuentas y sin anuncios."
  };

  let lang = "es";
  const esSnapshot = new Map();

  function t(key) {
    const dict = lang === "en" ? EN : ES_DYNAMIC;
    return dict[key] != null ? dict[key] : key;
  }

  function applyLang(next, persist = true) {
    lang = next;
    document.documentElement.lang = next;
    $$("[data-i18n]").forEach((el) => {
      const key = el.dataset.i18n;
      if (!esSnapshot.has(key)) esSnapshot.set(key, el.innerHTML);
      const value = next === "en" ? EN[key] : esSnapshot.get(key);
      if (value != null) el.innerHTML = value;
    });
    document.title = t("title");
    const meta = $('meta[name="description"]');
    if (meta) meta.setAttribute("content", t("desc"));
    const langBtn = $("#lang");
    if (langBtn) {
      langBtn.setAttribute("aria-label", t("lang_switch"));
      langBtn.classList.toggle("is-en", next === "en");
    }
    $$(".layers input[data-layer]").forEach((input) => {
      const name = input.closest(".layer")?.querySelector(".layer__name")?.textContent;
      if (name) input.setAttribute("aria-label", name.trim());
    });
    if (persist) {
      try { localStorage.setItem("respira-lang", next); } catch (_) { /* private mode */ }
    }
    breathe.sync();
    mixer.syncLabels();
  }

  // Snapshot the Spanish markup before any switch happens.
  $$("[data-i18n]").forEach((el) => esSnapshot.set(el.dataset.i18n, el.innerHTML));

  /* ------------------------------------------------------------------ nav */

  const nav = $("#nav");
  const onScroll = () => nav.classList.toggle("is-stuck", window.scrollY > 24);
  onScroll();
  window.addEventListener("scroll", onScroll, { passive: true });

  /* ---------------------------------------------------------------- reveal */

  const revealEls = $$(".reveal");
  if ("IntersectionObserver" in window) {
    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-in");
            io.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.12, rootMargin: "0px 0px -40px 0px" }
    );
    revealEls.forEach((el) => io.observe(el));
  } else {
    revealEls.forEach((el) => el.classList.add("is-in"));
  }

  /* ------------------------------------------------------------- starfield */

  const canvas = $("#stars");
  if (canvas) {
    const ctx = canvas.getContext("2d");
    let stars = [];
    let w = 0;
    let h = 0;
    let frame = 0;
    let visible = true;

    const build = () => {
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const rect = canvas.getBoundingClientRect();
      w = rect.width;
      h = rect.height;
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      const count = Math.round(clamp((w * h) / 14000, 40, 130));
      stars = Array.from({ length: count }, () => ({
        x: Math.random() * w,
        y: Math.random() * h * 0.92,
        r: Math.random() * 1.1 + 0.3,
        a: Math.random() * 0.5 + 0.15,
        speed: Math.random() * 0.9 + 0.25,
        phase: Math.random() * Math.PI * 2
      }));
    };

    const draw = (time = 0) => {
      ctx.clearRect(0, 0, w, h);
      const tSec = time / 1000;
      for (const s of stars) {
        const twinkle = reduced.matches
          ? 1
          : 0.62 + 0.38 * Math.sin(tSec * s.speed + s.phase);
        ctx.beginPath();
        ctx.arc(s.x, s.y, s.r, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(214, 244, 255, ${(s.a * twinkle).toFixed(3)})`;
        ctx.fill();
      }
      if (visible && !reduced.matches) frame = requestAnimationFrame(draw);
    };

    build();
    draw();
    window.addEventListener("resize", () => {
      build();
      draw();
    });

    if ("IntersectionObserver" in window) {
      new IntersectionObserver(
        (entries) => {
          visible = entries[0].isIntersecting;
          cancelAnimationFrame(frame);
          if (visible) draw();
        },
        { threshold: 0 }
      ).observe(canvas);
    }
  }

  /* ---------------------------------------------------------- phone parallax */

  const phone = $("#phone");
  const stage = $(".hero__stage");
  if (phone && stage && !reduced.matches) {
    const reset = () => { phone.style.transform = ""; };
    stage.addEventListener("pointermove", (event) => {
      if (event.pointerType === "touch") return;
      const rect = stage.getBoundingClientRect();
      const x = (event.clientX - rect.left) / rect.width - 0.5;
      const y = (event.clientY - rect.top) / rect.height - 0.5;
      phone.style.transform = `rotateY(${(x * 11).toFixed(2)}deg) rotateX(${(-y * 8).toFixed(2)}deg) translateZ(0)`;
    });
    stage.addEventListener("pointerleave", reset);
    stage.addEventListener("pointercancel", reset);
  }

  /* --------------------------------------------------------------- breathe */

  const PATTERNS = {
    calm: {
      hint: "p_calm_hint",
      slices: [["inhale", 4], ["exhale", 6]]
    },
    box: {
      hint: "p_box_hint",
      slices: [["inhale", 4], ["holdIn", 4], ["exhale", 4], ["holdOut", 4]]
    },
    "478": {
      hint: "p_478_hint",
      slices: [["inhale", 4], ["holdIn", 7], ["exhale", 8]]
    },
    coherent: {
      hint: "p_coherent_hint",
      slices: [["inhale", 5.5], ["exhale", 5.5]]
    }
  };

  const orb = $("#orb");
  const orbWrap = $("#orbWrap");
  const phaseEl = $("#orbPhase");
  const countEl = $("#orbCount");
  const hintEl = $("#breatheHint");
  const toggleBtn = $("#breatheToggle");
  const toggleLabel = $("#breatheToggleLabel");

  const breath = {
    pattern: "calm",
    minutes: 1,
    running: false,
    paused: false,
    cycle: 0,
    elapsed: 0,
    slice: -1,
    raf: 0,
    last: 0,
    finished: false
  };

  const cycleSeconds = (name) =>
    PATTERNS[name].slices.reduce((sum, slice) => sum + slice[1], 0);

  const scaleFor = (phaseKey) => (phaseKey === "exhale" ? 0.7 : 1);

  function setOrbScale(scale, seconds) {
    if (!orb) return;
    orb.style.transitionDuration = `${Math.max(0.05, seconds)}s`;
    orb.style.transform = `scale(${scale.toFixed(4)})`;
  }

  function freezeOrb() {
    if (!orb) return;
    let current = 0.7;
    try {
      const matrix = new DOMMatrixReadOnly(getComputedStyle(orb).transform);
      current = matrix.a || 0.7;
    } catch (_) {
      current = 0.7;
    }
    setOrbScale(current, 0.2);
  }

  function setIdle() {
    breath.running = false;
    breath.paused = false;
    breath.elapsed = 0;
    breath.cycle = 0;
    breath.slice = -1;
    breath.finished = false;
    cancelAnimationFrame(breath.raf);
    if (orbWrap) orbWrap.classList.add("is-idle");
    setOrbScale(0.72, 0.9);
    if (phaseEl) phaseEl.textContent = t("b_ready");
    if (countEl) countEl.textContent = "";
    if (toggleLabel) toggleLabel.textContent = t("b_start");
    if (hintEl) hintEl.textContent = t(PATTERNS[breath.pattern].hint) + t("b_tap_start");
  }

  function tick(now) {
    if (!breath.running) return;
    const dt = clamp((now - breath.last) / 1000, 0, 2);
    breath.last = now;
    breath.elapsed += dt;
    breath.cycle = (breath.cycle + dt) % cycleSeconds(breath.pattern);

    const slices = PATTERNS[breath.pattern].slices;
    let acc = 0;
    let index = 0;
    for (let i = 0; i < slices.length; i += 1) {
      if (breath.cycle < acc + slices[i][1]) { index = i; break; }
      acc += slices[i][1];
    }
    const [phaseKey, duration] = slices[index];
    const remaining = Math.max(0, acc + duration - breath.cycle);

    if (index !== breath.slice) {
      breath.slice = index;
      setOrbScale(scaleFor(phaseKey), remaining);
      if (phaseEl) phaseEl.textContent = t(`phase_${phaseKey}`);
    }
    if (countEl) countEl.textContent = String(Math.ceil(remaining));

    if (hintEl) {
      if (breath.minutes > 0) {
        const left = Math.max(0, Math.ceil((breath.minutes * 60 - breath.elapsed) / 60));
        hintEl.textContent = breath.elapsed < breath.minutes * 60
          ? `${left} ${t("b_left")}`
          : t("b_done_hint");
      } else {
        hintEl.textContent = t("b_open_session");
      }
    }

    if (breath.minutes > 0 && breath.elapsed >= breath.minutes * 60) {
      finish();
      return;
    }
    breath.raf = requestAnimationFrame(tick);
  }

  function start() {
    breath.running = true;
    breath.paused = false;
    breath.finished = false;
    breath.last = performance.now();
    if (orbWrap) orbWrap.classList.remove("is-idle");
    if (toggleLabel) toggleLabel.textContent = t("b_pause");
    toggleBtn.querySelector("use").setAttribute("href", "#i-pause");
    breath.slice = -1;
    cancelAnimationFrame(breath.raf);
    breath.raf = requestAnimationFrame(tick);
  }

  function pause() {
    breath.running = false;
    breath.paused = true;
    cancelAnimationFrame(breath.raf);
    freezeOrb();
    if (toggleLabel) toggleLabel.textContent = t("b_resume");
    toggleBtn.querySelector("use").setAttribute("href", "#i-play");
    if (hintEl) hintEl.textContent = t("b_paused");
  }

  function finish() {
    breath.running = false;
    breath.paused = false;
    breath.finished = true;
    cancelAnimationFrame(breath.raf);
    if (orbWrap) orbWrap.classList.add("is-idle");
    setOrbScale(0.72, 1.6);
    if (phaseEl) phaseEl.textContent = t("b_done");
    if (countEl) countEl.textContent = "";
    if (hintEl) hintEl.textContent = t("b_done_hint");
    if (toggleLabel) toggleLabel.textContent = t("b_again");
    toggleBtn.querySelector("use").setAttribute("href", "#i-play");
  }

  if (orb) {
    $$(".pattern").forEach((btn) => {
      btn.addEventListener("click", () => {
        $$(".pattern").forEach((b) => b.classList.toggle("is-active", b === btn));
        breath.pattern = btn.dataset.pattern;
        setIdle();
      });
    });

    $$(".breathe-lengths .chip-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        $$(".breathe-lengths .chip-btn").forEach((b) => b.classList.toggle("is-active", b === btn));
        breath.minutes = Number(btn.dataset.min);
        setIdle();
      });
    });

    toggleBtn.addEventListener("click", () => {
      if (breath.running) {
        pause();
        return;
      }
      if (breath.finished || (!breath.paused && breath.elapsed > 0)) {
        breath.elapsed = 0;
        breath.cycle = 0;
      }
      start();
    });

    setIdle();

    document.addEventListener("visibilitychange", () => {
      if (document.hidden && breath.running) pause();
    });
  }

  const breathe = {
    sync() {
      if (!orb) return;
      if (breath.running || breath.paused) {
        if (breath.running) {
          if (toggleLabel) toggleLabel.textContent = t("b_pause");
          const index = Math.max(0, breath.slice);
          const key = PATTERNS[breath.pattern].slices[index][0];
          if (phaseEl) phaseEl.textContent = t(`phase_${key}`);
        } else {
          if (toggleLabel) toggleLabel.textContent = t("b_resume");
          if (hintEl) hintEl.textContent = t("b_paused");
        }
      } else if (breath.finished) {
        if (phaseEl) phaseEl.textContent = t("b_done");
        if (hintEl) hintEl.textContent = t("b_done_hint");
        if (toggleLabel) toggleLabel.textContent = t("b_again");
      } else {
        setIdle();
      }
    }
  };

  /* ------------------------------------------------------------------ mixer */

  const PRESETS = {
    rainy: { rain: 70, thunder: 25, wind: 40, stream: 0, ocean: 0, fire: 0 },
    storm: { rain: 80, thunder: 85, wind: 55, stream: 0, ocean: 0, fire: 0 },
    forest: { rain: 0, thunder: 0, wind: 25, stream: 70, ocean: 0, fire: 0 },
    ocean: { rain: 0, thunder: 0, wind: 25, stream: 0, ocean: 80, fire: 0 },
    fire: { rain: 0, thunder: 0, wind: 20, stream: 0, ocean: 0, fire: 85 },
    deep: { rain: 35, thunder: 0, wind: 0, stream: 0, ocean: 0, fire: 0 }
  };

  /**
   * The demo's layers are built from the mechanism that makes each sound,
   * the same way the app's own generator works.  A band-passed noise bed
   * sounds like static no matter how it is filtered, so each layer here
   * has a body, and its events are irregular and varied.
   *
   * rain   - Poisson droplet impacts over a low-mid sheet
   * thunder- a rumble bed with a few widely spaced rolls
   * wind   - a wandering band under a slow gust
   * stream - bubble resonance: short chirps whose pitch rises
   * ocean  - a slow swell with foam on the wave peaks
   * fire   - crackle trains over a continuous roar and sizzle
   */
  const SOUNDS = {
    rain: { build: "rain", level: 0.62 },
    thunder: { build: "thunder", level: 1.05 },
    wind: { build: "wind", level: 0.72 },
    stream: { build: "stream", level: 0.66 },
    ocean: { build: "ocean", level: 0.95 },
    fire: { build: "fire", level: 0.78 }
  };

  const sliders = {};
  $$(".layers input[data-layer]").forEach((input) => {
    sliders[input.dataset.layer] = input;
  });

  const mixerEl = $(".mixer");
  const mixerToggle = $("#mixerToggle");
  const mixerLabel = $("#mixerToggleLabel");
  const mixerCount = $("#mixerCount");

  const mixer = {
    playing: false,
    ctx: null,
    master: null,
    nodes: {},
    buffers: null,
    labelSpin: false
  };

  const valueOf = (id) => Number(sliders[id].value);

  function paintSlider(input) {
    input.style.setProperty("--fill", `${input.value}%`);
    const row = input.closest(".layer");
    if (row) {
      row.classList.toggle("is-on", Number(input.value) > 0);
      const val = row.querySelector(".layer__val");
      if (val) val.textContent = input.value;
    }
  }

  function paintAll() {
    let active = 0;
    Object.keys(sliders).forEach((id) => {
      const input = sliders[id];
      paintSlider(input);
      if (Number(input.value) > 0) active += 1;
    });
    if (mixerCount) mixerCount.textContent = String(active);
  }

  function applyPreset(name) {
    const preset = PRESETS[name];
    if (!preset) return;
    Object.keys(preset).forEach((id) => {
      if (sliders[id]) sliders[id].value = preset[id];
    });
    $$(".preset").forEach((btn) => btn.classList.toggle("is-active", btn.dataset.preset === name));
    paintAll();
    syncAudioLevels();
  }

  function syncAudioLevels() {
    if (!mixer.ctx) return;
    const now = mixer.ctx.currentTime;
    Object.keys(SOUNDS).forEach((id) => {
      const node = mixer.nodes[id];
      if (!node) return;
      const sound = SOUNDS[id];
      const amount = Math.pow(valueOf(id) / 100, 1.6) * sound.level * 0.5;
      node.gain.gain.cancelScheduledValues(now);
      node.gain.gain.setTargetAtTime(amount, now, 0.2);
    });
  }

  /**
   * Build a layer from the mechanism that makes the sound.
   *
   * Everything is generated into a four second buffer and looped.  The
   * buffer is long enough that the loop point is not obvious, and each
   * layer is built so that its events are irregular: a Poisson arrival
   * process, not a metronome.
   */
  function buildLayer(ctx, kind) {
    const sr = ctx.sampleRate;
    const length = Math.floor(sr * 4);
    const buffer = ctx.createBuffer(1, length, sr);
    const data = buffer.getChannelData(0);

    // a small seeded generator so the demo is reproducible
    let seed = 20261024;
    const random = () => {
      seed = (seed * 1664525 + 1013904223) >>> 0;
      return seed / 4294967296;
    };
    const noise = () => random() * 2 - 1;

    /** One-pole low-pass, used to shape the noise beds. */
    const lowpass = (cutoff) => {
      const a = 1 - Math.exp((-2 * Math.PI * cutoff) / sr);
      let last = 0;
      return (x) => {
        last += a * (x - last);
        return last;
      };
    };

    /**
     * Run a filter over the whole buffer and throw away the start-up
     * transient.
     *
     * A one-pole filter starts with its state at zero, so its first samples
     * are wrong.  On a looping buffer that puts a step at the loop point,
     * which is audible as a tick every few seconds, and it also raises the
     * peak of the layer so the normalisation pulls everything else down.
     */
    const filtered = (cutoff, mix) => {
      const tone = lowpass(cutoff);
      const warm = Math.floor(sr * 0.25);
      let last = 0;
      for (let i = 0; i < warm; i += 1) last = tone(noise());
      for (let i = 0; i < length; i += 1) {
        last = tone(noise());
        data[i] += last * mix;
      }
    };

    /**
     * Warm a set of filters up together, so none of them carries a
     * start-up transient into the loop.
     */
    const warmUp = (tones) => {
      for (let i = 0; i < Math.floor(sr * 0.25); i += 1) {
        tones.forEach((tone) => tone(noise()));
      }
    };

    /** Add a short burst at `at`, shaped by an envelope. */
    const addBurst = (at, lengthSamples, cutoff, decay, amp) => {
      const start = Math.floor(at * sr);
      const tone = lowpass(cutoff);
      // a burst that starts at the loop point would carry the filter's
      // start-up transient with it
      for (let i = 0; i < 64; i += 1) tone(noise());
      for (let i = 0; i < lengthSamples; i += 1) {
        const t = i / sr;
        const env = Math.exp(-t / decay);
        const index = (start + i) % length;
        data[index] += tone(noise()) * env * amp;
      }
    };

    if (kind === "rain") {
      // a low-mid sheet so the rain has a body, not just a hiss
      filtered(900, 0.30);
      // droplet impacts, arriving at random.  Each drop is darker than the
      // sheet, and they are loud enough to stand above it, but they must
      // not be so bright that the layer turns into a hiss.
      const bright = lowpass(2600);
      let t = 0;
      while (t < 4) {
        t += -Math.log(1 - random()) / 42;
        const amp = 0.30 + random() * 0.75;
        const decay = 0.004 + random() * 0.012;
        const start = Math.floor(t * sr);
        const n = Math.floor(decay * 4 * sr);
        for (let i = 0; i < n; i += 1) {
          const env = Math.exp(-(i / sr) / decay);
          data[(start + i) % length] += bright(noise()) * env * amp;
        }
      }
    } else if (kind === "fire") {
      // the roar of the gas and the hiss of the flame: the bed that the
      // crackles ride on, which is what stops it sounding like a click train.
      // The roar carries most of the energy, so the layer sits low, the way
      // a fire does.
      const roar = lowpass(320);
      const hiss = lowpass(2600);
      // warm the filters up first, so their start-up transient does not
      // land on the loop point
      warmUp([roar, hiss]);
      for (let i = 0; i < length; i += 1) {
        const seconds = i / sr;
        const swell = 0.72 + 0.28 * Math.sin(seconds * 2 * Math.PI * 0.23);
        data[i] += roar(noise()) * 1.15 * swell;
        data[i] += hiss(noise()) * 0.10 * (0.8 + 0.2 * Math.sin(seconds * 2 * Math.PI * 0.41));
      }
      // crackles in short irregular bursts, each with its own decay
      let cursor = 0;
      while (cursor < 4) {
        const burst = 0.06 + random() * 0.3;
        const rate = 1.5 + random() * 4;
        const count = Math.max(1, Math.round(burst * rate));
        for (let i = 0; i < count; i += 1) {
          const at = cursor + random() * burst;
          // the crackle has to stand clearly above the roar, or the layer is
          // just a rumble with something buried in it
          const amp = 0.9 + random() * 1.6 * random();
          // each crackle has its own brightness, which is what stops the
          // layer sounding like one click repeated
          const cutoff = 1800 + random() * 3600;
          addBurst(at, Math.floor(0.03 * sr), cutoff, 0.004 + random() * 0.012, amp);
        }
        cursor += burst + 0.2 + random() * 1.4;
      }
    } else if (kind === "stream") {
      // bubbles: a short chirp whose pitch rises, over a body of water.
      // The body is kept low and smooth, so the bubbles stand above it
      // rather than competing with the bed's own random peaks.
      const body = lowpass(900);
      warmUp([body]);
      let smooth = 0;
      for (let i = 0; i < length; i += 1) {
        smooth = smooth * 0.995 + body(noise()) * 0.005;
        data[i] += smooth * 4.0;
      }
      let t = 0;
      while (t < 4) {
        t += -Math.log(1 - random()) / 26;
        const f0 = 500 + random() * 900;
        const f1 = f0 * (1.2 + random() * 0.9);
        const dur = 0.03 + random() * 0.05;
        const start = Math.floor(t * sr);
        const n = Math.floor(dur * sr);
        let phase = 0;
        const amp = 0.7 + random() * 0.5;
        for (let i = 0; i < n; i += 1) {
          const f = f0 + (f1 - f0) * (i / n);
          phase += (2 * Math.PI * f) / sr;
          const env = Math.sin((Math.PI * i) / n);
          data[(start + i) % length] += Math.sin(phase) * env * amp;
        }
      }
    } else if (kind === "ocean") {
      // the swell, and foam on the peaks of it
      const body = lowpass(420);
      const foam = lowpass(2600);
      warmUp([body, foam]);
      const period = 4 / 1.6;
      for (let i = 0; i < length; i += 1) {
        const seconds = i / sr;
        const swell = 0.5 + 0.5 * Math.sin((2 * Math.PI * seconds) / period);
        const shaped = swell * swell;
        data[i] += body(noise()) * (0.3 + 0.7 * shaped) * 1.05;
        data[i] += foam(noise()) * (0.05 + 0.22 * shaped);
      }
      // the break of each wave: a burst of foam on the way up
      const breaks = 1.6;
      for (let k = 0; k < breaks; k += 1) {
        const at = (k / breaks) * 4 + period * 0.25;
        addBurst(at, Math.floor(0.12 * sr), 3200, 0.035, 0.9 + random() * 0.5);
      }
    } else if (kind === "thunder") {
      // a rumble bed with a few widely spaced rolls
      const bed = lowpass(110);
      warmUp([bed]);
      for (let i = 0; i < length; i += 1) {
        const seconds = i / sr;
        data[i] += bed(noise()) * (0.4 + 0.3 * Math.sin(seconds * 2 * Math.PI * 0.035));
      }
      let t = 0.4;
      while (t < 3.6) {
        const dur = 0.8 + random() * 1.4;
        const start = Math.floor(t * sr);
        const n = Math.floor(dur * sr);
        // the roll is a low rumble, not a hiss: it is filtered down before
        // it is added, otherwise it is broadband and the whole layer sounds
        // like static
        const roll = lowpass(400);
        for (let i = 0; i < n; i += 1) {
          const seconds = i / sr;
          const env = (1 - Math.exp(-seconds / 0.12)) * Math.exp(-seconds / 0.5);
          data[(start + i) % length] += roll(noise()) * env * (0.5 + random() * 0.5);
        }
        t += 1.1 + random() * 1.8;
      }
    } else {
      // wind: a wandering band under a slow gust, with the gusts arriving
      // as distinct swells rather than an even wash
      const band = lowpass(700);
      warmUp([band]);
      for (let i = 0; i < length; i += 1) {
        const seconds = i / sr;
        const gust = 0.55 + 0.45 * Math.sin(seconds * 2 * Math.PI * 0.06);
        data[i] += band(noise()) * gust * 0.55;
      }
      // a few stronger gusts, so the layer has events above its bed
      let t = 0.3;
      while (t < 3.7) {
        const dur = 0.4 + random() * 0.7;
        const start = Math.floor(t * sr);
        const n = Math.floor(dur * sr);
        const gust = lowpass(1100);
        for (let i = 0; i < n; i += 1) {
          const seconds = i / sr;
          const env = Math.sin((Math.PI * i) / n);
          data[(start + i) % length] += gust(noise()) * env * (0.7 + random() * 0.7);
        }
        t += 0.5 + random() * 0.9;
      }
    }

    // normalise so every layer starts at the same level
    let peak = 0;
    for (let i = 0; i < length; i += 1) peak = Math.max(peak, Math.abs(data[i]));
    if (peak > 0) {
      const gain = 0.5 / peak;
      for (let i = 0; i < length; i += 1) data[i] *= gain;
    }
    return buffer;
  }

  async function startMixer() {
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (!AudioCtx) return false;
    const ctx = new AudioCtx();
    mixer.ctx = ctx;
    mixer.buffers = {};

    const master = ctx.createGain();
    master.gain.value = 0;
    const shelf = ctx.createDynamicsCompressor();
    shelf.threshold.value = -18;
    shelf.knee.value = 24;
    shelf.ratio.value = 6;
    master.connect(shelf).connect(ctx.destination);
    mixer.master = master;

    Object.keys(SOUNDS).forEach((id) => {
      const sound = SOUNDS[id];
      const buffer = buildLayer(ctx, sound.build);
      mixer.buffers[id] = buffer;

      const source = ctx.createBufferSource();
      source.buffer = buffer;
      source.loop = true;

      // each layer sits on its own place in the stereo field, the same way
      // the app places them on a stage
      const pan = ctx.createStereoPanner ? ctx.createStereoPanner() : null;
      const panValue = { rain: -0.35, thunder: 0, wind: -0.2, stream: -0.5, ocean: 0.4, fire: 0.55 }[id] || 0;
      if (pan) pan.pan.value = panValue;

      const gain = ctx.createGain();
      const amount = Math.pow(valueOf(id) / 100, 1.6) * sound.level * 0.5;
      gain.gain.value = amount;

      if (pan) {
        source.connect(gain).connect(pan).connect(master);
      } else {
        source.connect(gain).connect(master);
      }
      // start at a random point so the layers are not all in step
      source.start(0, Math.random() * 3);

      mixer.nodes[id] = { source, pan, gain };
    });

    await ctx.resume();
    master.gain.linearRampToValueAtTime(0.85, ctx.currentTime + 1.8);
    return true;
  }

  function stopMixer() {
    if (!mixer.ctx) return;
    const ctx = mixer.ctx;
    const master = mixer.master;
    try {
      master.gain.cancelScheduledValues(ctx.currentTime);
      master.gain.setTargetAtTime(0, ctx.currentTime, 0.15);
    } catch (_) { /* already closed */ }
    Object.values(mixer.nodes).forEach((node) => {
      try { node.source.stop(ctx.currentTime + 0.6); } catch (_) { /* stopped */ }
    });
    mixer.nodes = {};
    mixer.ctx = null;
    mixer.master = null;
    setTimeout(() => { try { ctx.close(); } catch (_) { /* closed */ } }, 700);
  }

  async function toggleMixer() {
    if (mixer.playing) {
      mixer.playing = false;
      stopMixer();
      if (mixerEl) mixerEl.classList.remove("is-playing");
      if (mixerLabel) mixerLabel.textContent = t("s_listen");
      mixerToggle.querySelector("use").setAttribute("href", "#i-volume");
      return;
    }
    mixerToggle.disabled = true;
    const ok = await startMixer().catch(() => false);
    mixerToggle.disabled = false;
    if (!ok) {
      if (mixerLabel) mixerLabel.textContent = t("s_listen");
      return;
    }
    mixer.playing = true;
    if (mixerEl) mixerEl.classList.add("is-playing");
    if (mixerLabel) mixerLabel.textContent = t("s_stop");
    mixerToggle.querySelector("use").setAttribute("href", "#i-pause");
  }

  mixer.syncLabels = function syncLabels() {
    if (!mixerLabel) return;
    mixerLabel.textContent = mixer.playing ? t("s_stop") : t("s_listen");
  };

  if (mixerToggle) {
    Object.values(sliders).forEach((input) => {
      input.addEventListener("input", () => {
        paintSlider(input);
        $$(".preset").forEach((btn) => btn.classList.remove("is-active"));
        let active = 0;
        Object.values(sliders).forEach((s) => { if (Number(s.value) > 0) active += 1; });
        if (mixerCount) mixerCount.textContent = String(active);
        syncAudioLevels();
      });
    });

    $$(".preset").forEach((btn) => {
      btn.addEventListener("click", () => applyPreset(btn.dataset.preset));
    });

    mixerToggle.addEventListener("click", toggleMixer);
    paintAll();

    document.addEventListener("visibilitychange", () => {
      if (document.hidden && mixer.playing) toggleMixer();
    });
  }

  /* ---------------------------------------------------------------- language */

  const langBtn = $("#lang");
  if (langBtn) {
    let stored = null;
    try { stored = localStorage.getItem("respira-lang"); } catch (_) { /* private mode */ }
    const initial = stored === "es" || stored === "en"
      ? stored
      : /^en/i.test(navigator.language || "") ? "en" : "es";
    applyLang(initial, false);
    langBtn.addEventListener("click", () => applyLang(lang === "es" ? "en" : "es"));
  }

  /* ------------------------------------------------------------------- misc */

  const yearEl = $("#year");
  if (yearEl) yearEl.textContent = String(new Date().getFullYear());
})();
