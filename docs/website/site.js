/* =====================================================================
   MCA website prototype — opening timeline + page interactions
   No dependencies. Every opening property is a pure function of time,
   so ?t=<ms> can freeze any frame for review.
   ===================================================================== */
(function () {
  'use strict';

  const root = document.documentElement;
  const page = document.getElementById('page');
  const opening = document.getElementById('opening');
  const reducedMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  root.classList.add('js');
  window.__mcaSite = true;

  /* ------------------------------------------------------------------
     Opening geometry — same coordinate space as the launcher vector
     (viewBox 20 20 68 68). The four icon dots are the four sources.
     ------------------------------------------------------------------ */
  const ICON = { cx: 54, cy: 50 };
  const SOURCES = [
    { x: 36, y: 38, r: 3.5, mr: 2.4 },
    { x: 72, y: 62, r: 3.5, mr: 2.4 },
    { x: 42, y: 68, r: 2.5, mr: 1.8 },
    { x: 66, y: 36, r: 2.0, mr: 1.5 }
  ];
  const START_RADIUS = 56;
  const DURATION = 2660;
  const RELEASE_AT = 2380;

  const clamp01 = (x) => x < 0 ? 0 : x > 1 ? 1 : x;
  const lerp = (a, b, t) => a + (b - a) * t;
  const easeOutCubic = (t) => 1 - Math.pow(1 - t, 3);
  const easeInCubic = (t) => t * t * t;
  const easeInOutCubic = (t) => t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
  const easeOutBack = (t) => {
    const c1 = 1.35, c3 = c1 + 1;
    return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
  };
  const pulse = (t, start, dur) => {
    const p = clamp01((t - start) / dur);
    return Math.sin(Math.PI * p);
  };

  const sources = SOURCES.map((s) => {
    const dx = s.x - ICON.cx;
    const dy = s.y - ICON.cy;
    const len = Math.hypot(dx, dy);
    const ux = dx / len;
    const uy = dy / len;
    return Object.assign({}, s, {
      ux, uy,
      sx: ICON.cx + ux * START_RADIUS,
      sy: ICON.cy + uy * START_RADIUS
    });
  });

  const el = {};
  const get = (id) => document.getElementById(id);
  el.veil = get('openingVeil');
  el.stage = get('openingStage');
  el.svg = get('openingSvg');
  el.glow = get('coreGlow');
  el.rings = get('openingRings');
  el.trails = get('openingTrails');
  el.motes = get('openingMotes');
  el.core = get('openingCore');
  el.shock = get('shockRing');
  el.bubble = get('openingBubble');
  el.star = get('openingStar');
  el.wordmark = get('openingWordmark');
  el.name = get('openingName');
  el.flash = get('openingFlash');
  el.meta = get('openingMeta');
  el.skip = get('openingSkip');
  el.motesEls = [get('mote0'), get('mote1'), get('mote2'), get('mote3')];
  el.trailEls = [get('trail0'), get('trail1'), get('trail2'), get('trail3')];
  el.dotEls = [get('openDot0'), get('openDot1'), get('openDot2'), get('openDot3')];
  el.cornerTL = document.querySelector('.opening-corner--tl');
  el.cornerBL = document.querySelector('.opening-corner--bl');
  el.brand = document.querySelector('.nav .brand');
  el.brandMark = document.querySelector('.brand-mark');
  el.heroReveals = Array.prototype.slice.call(document.querySelectorAll('[data-open-reveal]'));

  const openingActive = root.classList.contains('has-opening');
  const freezeRaw = root.dataset.openingT;
  const freezeAt = freezeRaw !== undefined && freezeRaw !== '' ? Number(freezeRaw) : null;
  let navTarget = { cx: 0, cy: 0, size: 30 };
  let baseOrigin = { x: 0, y: 0 };
  let iconSize = 260;
  let irisMax = 1000;
  let gooAttached = true;
  let raf = 0;
  let startTime = 0;
  let released = false;
  let finished = false;

  function measure() {
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    if (el.svg) iconSize = el.svg.offsetWidth || 260;
    irisMax = Math.hypot(vw, vh) * 0.68 + 320;
    baseOrigin = { x: vw / 2, y: vh / 2 - iconSize * (4 / 68) };
    if (el.veil) {
      el.veil.style.setProperty('--ox', baseOrigin.x.toFixed(1) + 'px');
      el.veil.style.setProperty('--oy', baseOrigin.y.toFixed(1) + 'px');
    }
    if (el.brandMark) {
      const r = el.brandMark.getBoundingClientRect();
      navTarget = { cx: r.left + r.width / 2, cy: r.top + r.height / 2, size: r.width || 30 };
    }
  }

  function render(t) {
    if (!openingActive || !el.svg) return;

    /* — core ignition — */
    const coreP = clamp01((t - 80) / 340);
    const coreR = 0.85 * easeOutCubic(coreP);
    if (el.core) {
      el.core.setAttribute('r', coreR.toFixed(3));
      el.core.setAttribute('opacity', String(clamp01((t - 80) / 160)));
    }

    /* — rings: a slow gravitational lens — */
    const ringP = clamp01((t - 180) / 860);
    if (el.rings) {
      const k = 0.82 + 0.34 * easeOutCubic(ringP);
      el.rings.setAttribute('transform', 'translate(54 50) scale(' + k.toFixed(4) + ') translate(-54 -50)');
      el.rings.style.opacity = (Math.sin(Math.PI * ringP) * 0.6).toFixed(3);
    }

    /* — ambient core glow — */
    if (el.glow) {
      const halo = clamp01((t - 1440) / 280) * (1 - clamp01((t - 1960) / 200));
      const glow = Math.max(
        0.55 * easeOutCubic(clamp01((t - 60) / 500)) * (1 - clamp01((t - 1120) / 220)),
        0.82 * pulse(t, 1130, 320),
        0.18 * halo
      );
      el.glow.setAttribute('r', (16 + 22 * halo).toFixed(2));
      el.glow.setAttribute('opacity', glow.toFixed(3));
    }

    /* — detach SVG goo filter after fusion completes to save compositing — */
    if (el.motes) {
      const needGoo = t <= 1360;
      if (needGoo !== gooAttached) {
        if (needGoo) el.motes.setAttribute('filter', 'url(#goo)');
        else el.motes.removeAttribute('filter');
        gooAttached = needGoo;
      }
    }

    /* — four sources travel inward — */
    const delays = [0, 28, 56, 80];
    sources.forEach((s, i) => {
      const mote = el.motesEls[i];
      const trail = el.trailEls[i];
      if (!mote) return;

      const appear = clamp01((t - 340 - i * 22) / 220);
      const travel = clamp01((t - 500 - delays[i]) / 650);
      const e = easeInCubic(travel);
      const x = lerp(s.sx, ICON.cx, e);
      const y = lerp(s.sy, ICON.cy, e);
      const radius = s.mr * easeOutCubic(appear) * (1 - 0.34 * e);
      const visible = appear * (1 - clamp01((t - 1180 - i * 10) / 120));

      mote.setAttribute('cx', x.toFixed(3));
      mote.setAttribute('cy', y.toFixed(3));
      mote.setAttribute('r', Math.max(0, radius).toFixed(3));
      mote.setAttribute('opacity', visible.toFixed(3));

      if (trail) {
        const trailLen = 14 * (1 - 0.45 * e) * appear;
        trail.setAttribute('x1', x.toFixed(3));
        trail.setAttribute('y1', y.toFixed(3));
        trail.setAttribute('x2', (x + s.ux * trailLen).toFixed(3));
        trail.setAttribute('y2', (y + s.uy * trailLen).toFixed(3));
        trail.setAttribute('opacity', (visible * (0.15 + 0.45 * (1 - e))).toFixed(3));
      }

      /* the four dots re-emerge from the core after the icon has formed */
      const dot = el.dotEls[i];
      if (dot) {
        const dp = clamp01((t - 1470 - i * 36) / 250);
        const de = easeOutBack(dp);
        const px = ICON.cx + (s.x - ICON.cx) * de;
        const py = ICON.cy + (s.y - ICON.cy) * de;
        dot.setAttribute('cx', px.toFixed(3));
        dot.setAttribute('cy', py.toFixed(3));
        dot.setAttribute('r', Math.max(0, s.r * clamp01(0.25 + dp)).toFixed(3));
        dot.setAttribute('opacity', clamp01(dp * 2.2).toFixed(3));
      }
    });
    if (el.trails) {
      el.trails.setAttribute('opacity', String(clamp01((t - 340) / 240) * (1 - clamp01((t - 1140) / 180))));
    }

    /* — fusion impact — */
    if (el.core) {
      const impact = clamp01((t - 1130) / 160);
      if (impact > 0) {
        el.core.setAttribute('r', (0.85 + 1.9 * pulse(t, 1130, 270)).toFixed(3));
        el.core.setAttribute('opacity', (1 - clamp01((t - 1220) / 140)).toFixed(3));
      }
    }
    if (el.shock) {
      const sp = clamp01((t - 1190) / 400);
      el.shock.setAttribute('r', (1 + 46 * easeOutCubic(sp)).toFixed(3));
      el.shock.setAttribute('opacity', ((1 - sp) * 0.6 * (t > 1190 ? 1 : 0)).toFixed(3));
    }

    /* — the star blooms — */
    if (el.star) {
      const sp = clamp01((t - 1240) / 230);
      const s = easeOutBack(sp);
      const rot = -52 * (1 - easeOutCubic(clamp01((t - 1240) / 290)));
      el.star.setAttribute('transform',
        'translate(54 50) rotate(' + rot.toFixed(2) + ') scale(' + Math.max(0, s).toFixed(4) + ') translate(-54 -50)');
      el.star.setAttribute('opacity', clamp01(sp * 2.4).toFixed(3));
    }

    /* — the speech bubble draws itself, then fills — */
    if (el.bubble) {
      const draw = easeInOutCubic(clamp01((t - 1330) / 270));
      const fill = easeOutCubic(clamp01((t - 1450) / 200));
      el.bubble.style.strokeDashoffset = (1 - draw).toFixed(4);
      el.bubble.style.fillOpacity = (fill * 0.98).toFixed(3);
      el.bubble.style.strokeOpacity = (1 - clamp01((t - 1600) / 160)).toFixed(3);
    }

    /* — wordmark — */
    if (el.wordmark) {
      const wp = clamp01((t - 1600) / 250);
      const out = clamp01((t - 1960) / 140);
      const we = easeOutCubic(wp);
      el.wordmark.style.opacity = (we * (1 - out)).toFixed(3);
      el.wordmark.style.transform = 'translateX(-50%) translateY(' + ((1 - we) * 9 - out * 6).toFixed(2) + 'px)';
      if (el.name) {
        const tracking = lerp(0.42, 0.12, we);
        el.name.style.letterSpacing = tracking.toFixed(3) + 'em';
        el.name.style.paddingLeft = tracking.toFixed(3) + 'em';
      }
    }

    /* — frame furniture — */
    const fadeIn = (start, dur) => easeOutCubic(clamp01((t - start) / dur));
    const furnitureOut = 1 - easeOutCubic(clamp01((t - 1560) / 240));
    if (el.cornerTL) el.cornerTL.style.opacity = (fadeIn(200, 420) * furnitureOut * 0.9).toFixed(3);
    if (el.cornerBL) el.cornerBL.style.opacity = (fadeIn(320, 420) * furnitureOut * 0.9).toFixed(3);
    if (el.meta) el.meta.style.opacity = (fadeIn(400, 420) * furnitureOut).toFixed(3);
    if (el.skip) el.skip.style.opacity = (fadeIn(520, 320) * (1 - clamp01((t - 2040) / 160))).toFixed(3);

    /* — light pulses: fusion and the iris opening — */
    if (el.flash) {
      const f = Math.max(
        0.15 * pulse(t, 1140, 260),
        0.08 * pulse(t, 1980, 220)
      );
      el.flash.style.opacity = f.toFixed(3);
    }

    /* — symmetric soft iris reveal + icon dock to navbar — */
    const fp = easeInOutCubic(clamp01((t - 1960) / 360));
    if (el.svg) {
      if (fp > 0) {
        const vw = window.innerWidth;
        const vh = window.innerHeight;
        const dx = navTarget.cx - vw / 2;
        const dy = navTarget.cy - vh / 2;
        const shrink = easeOutCubic(fp);
        const scale = lerp(1, navTarget.size / iconSize, shrink);
        el.svg.style.transformOrigin = 'center';
        el.svg.style.transform = 'translate(' + (dx * fp).toFixed(2) + 'px,' + (dy * fp).toFixed(2) + 'px) scale(' + scale.toFixed(4) + ')';
      } else {
        el.svg.style.transform = '';
      }
    }

    if (el.veil) {
      const ip = easeInOutCubic(clamp01((t - 1940) / 420));
      const veilFade = 1 - easeOutCubic(clamp01((ip - 0.30) / 0.70));
      el.veil.style.setProperty('--ox', baseOrigin.x.toFixed(1) + 'px');
      el.veil.style.setProperty('--oy', baseOrigin.y.toFixed(1) + 'px');
      el.veil.style.setProperty('--iris', (-260 + ip * (irisMax + 260)).toFixed(1) + 'px');
      el.veil.style.opacity = veilFade.toFixed(3);
    }

    /* — the navbar brand fades in smoothly as the flying mark lands — */
    if (el.brand) {
      const bp = easeOutCubic(clamp01((t - 2260) / 110));
      el.brand.style.opacity = bp.toFixed(3);
    }

    /* — hero copy rises behind the opening iris — */
    el.heroReveals.forEach((node) => {
      const delay = Number(node.dataset.delay || 0);
      const hp = clamp01((t - 1900 - delay) / 460);
      const he = easeOutCubic(hp);
      node.style.opacity = '1';
      node.style.transform = 'translateY(' + ((1 - he) * 16).toFixed(2) + 'px)';
    });

    if (!freezeAt && !released && t >= RELEASE_AT) release(false);
    if (!freezeAt && t >= DURATION) finish();
  }

  function release(fromSkip) {
    if (released) return;
    released = true;
    root.classList.remove('has-opening');
    if (page) page.removeAttribute('inert');
    if (opening) opening.style.display = 'none';
    try { sessionStorage.setItem('mca.opening', '1'); } catch (e) {}
    if (fromSkip) {
      el.heroReveals.forEach((node) => { node.style.opacity = ''; node.style.transform = ''; });
      finish();
    }
  }

  function finish() {
    if (finished) return;
    finished = true;
    if (raf) cancelAnimationFrame(raf);
    if (opening && opening.parentNode) opening.remove();
    if (el.brand) el.brand.style.opacity = '';
    if (el.brandMark) el.brandMark.style.opacity = '';
    el.heroReveals.forEach((node) => { node.style.opacity = ''; node.style.transform = ''; });
    document.removeEventListener('keydown', onKey);
    window.removeEventListener('wheel', onWheel);
    window.removeEventListener('touchmove', onTouchMove);
  }

  function skipOpening() {
    if (!openingActive || finished) return;
    release(true);
  }

  function onKey(e) {
    if (e.key === 'Escape' || e.key === ' ' || e.key === 'ArrowDown' || e.key === 'PageDown') {
      skipOpening();
    }
  }

  function onWheel(e) {
    if (!startTime || performance.now() - startTime < 520) return;
    if (Math.abs(e.deltaY) > 16) skipOpening();
  }

  function onTouchMove() {
    if (!startTime || performance.now() - startTime < 520) return;
    skipOpening();
  }

  function boot() {
    if (!openingActive || !opening) {
      if (document.documentElement.dataset.static === '1') {
        document.querySelectorAll('.reveal').forEach((n) => n.classList.add('is-in'));
      }
      return;
    }

    window.scrollTo(0, 0);
    if (page) page.setAttribute('inert', '');
    measure();

    if (freezeAt !== null && !Number.isNaN(freezeAt)) {
      render(freezeAt);
      window.__mcaOpening = { seek: render, skip: skipOpening, release: release };
      return;
    }

    opening.addEventListener('click', skipOpening);
    if (el.skip) {
      el.skip.addEventListener('click', (e) => { e.stopPropagation(); skipOpening(); });
    }
    document.addEventListener('keydown', onKey);
    window.addEventListener('wheel', onWheel, { passive: true });
    window.addEventListener('touchmove', onTouchMove, { passive: true });
    window.addEventListener('resize', () => {
      measure();
      if (released) return;
      render(Math.max(0, performance.now() - startTime));
    }, { passive: true });

    startTime = performance.now();
    const loop = (now) => {
      const t = now - startTime;
      render(t);
      if (!finished) raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    window.__mcaOpening = { seek: render, skip: skipOpening, release: release };
  }

  /* ------------------------------------------------------------------
     Page interactions
     ------------------------------------------------------------------ */
  function initNav() {
    const nav = document.getElementById('nav');
    const bar = document.getElementById('navProgress');
    if (!nav) return;
    let ticking = false;
    const update = () => {
      ticking = false;
      const y = window.scrollY || document.documentElement.scrollTop;
      nav.classList.toggle('is-scrolled', y > 8);
      if (bar) {
        const max = document.documentElement.scrollHeight - window.innerHeight;
        bar.style.width = (max > 0 ? Math.min(1, y / max) * 100 : 0) + '%';
      }
    };
    window.addEventListener('scroll', () => {
      if (!ticking) { ticking = true; requestAnimationFrame(update); }
    }, { passive: true });
    update();
  }

  function initMobileNav() {
    const toggle = document.getElementById('navToggle');
    const panel = document.getElementById('navMobile');
    if (!toggle || !panel) return;
    const close = () => {
      panel.hidden = true;
      toggle.setAttribute('aria-expanded', 'false');
    };
    toggle.addEventListener('click', () => {
      const next = panel.hidden;
      panel.hidden = !next;
      toggle.setAttribute('aria-expanded', String(next));
    });
    panel.querySelectorAll('a').forEach((a) => a.addEventListener('click', close));
    window.addEventListener('resize', () => { if (window.innerWidth > 900) close(); });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !panel.hidden) { close(); toggle.focus(); } });
  }

  function initWorkspaces() {
    const stage = document.getElementById('wsPanel');
    const tabs = Array.prototype.slice.call(document.querySelectorAll('.ws-tab'));
    if (!stage || !tabs.length) return;
    const panes = Array.prototype.slice.call(stage.querySelectorAll('.ws-pane'));
    const label = (id) => {
      const map = { dsh: 'wsTabDsh', image: 'wsTabImage', video: 'wsTabVideo' };
      return map[id] || 'wsTabDsh';
    };

    const activate = (key, focus) => {
      stage.dataset.ws = key;
      stage.setAttribute('aria-labelledby', label(key));
      tabs.forEach((tab) => {
        const on = tab.dataset.ws === key;
        tab.classList.toggle('is-active', on);
        tab.setAttribute('aria-selected', String(on));
        tab.tabIndex = on ? 0 : -1;
        if (on && focus) tab.focus();
      });
      panes.forEach((pane) => pane.classList.toggle('is-active', pane.dataset.ws === key));
    };

    tabs.forEach((tab, i) => {
      tab.addEventListener('click', () => activate(tab.dataset.ws, false));
      tab.addEventListener('keydown', (e) => {
        let next = -1;
        if (e.key === 'ArrowDown' || e.key === 'ArrowRight') next = (i + 1) % tabs.length;
        if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') next = (i - 1 + tabs.length) % tabs.length;
        if (e.key === 'Home') next = 0;
        if (e.key === 'End') next = tabs.length - 1;
        if (next !== -1) {
          e.preventDefault();
          activate(tabs[next].dataset.ws, true);
        }
      });
    });
    activate('dsh', false);
  }

  function initRouting() {
    const panel = document.getElementById('tracePanel');
    const seg = document.querySelector('.seg');
    const tabs = Array.prototype.slice.call(document.querySelectorAll('.seg-btn'));
    const mode = document.getElementById('traceMode');
    const badge = document.getElementById('traceBadge');
    if (!panel || !seg || !tabs.length) return;

    const activate = (path, focus) => {
      panel.dataset.path = path;
      seg.dataset.active = path;
      tabs.forEach((tab) => {
        const on = tab.dataset.path === path;
        tab.classList.toggle('is-active', on);
        tab.setAttribute('aria-selected', String(on));
        tab.tabIndex = on ? 0 : -1;
        if (on && focus) tab.focus();
      });
      if (mode) mode.textContent = path === 'cloud' ? 'CLOUD' : 'LOCAL';
      if (badge) badge.textContent = path === 'cloud' ? 'ON NETWORK' : 'ON DEVICE';
    };

    tabs.forEach((tab, i) => {
      tab.addEventListener('click', () => activate(tab.dataset.path, false));
      tab.addEventListener('keydown', (e) => {
        let next = -1;
        if (e.key === 'ArrowRight') next = (i + 1) % tabs.length;
        if (e.key === 'ArrowLeft') next = (i - 1 + tabs.length) % tabs.length;
        if (e.key === 'Home') next = 0;
        if (e.key === 'End') next = tabs.length - 1;
        if (next !== -1) {
          e.preventDefault();
          activate(tabs[next].dataset.path, true);
        }
      });
    });
    activate('local', false);
  }

  function initCopy() {
    const buttons = Array.prototype.slice.call(document.querySelectorAll('[data-copy-target]'));
    buttons.forEach((btn) => {
      const targetId = btn.getAttribute('data-copy-target');
      const targetEl = targetId ? document.getElementById(targetId) : null;
      if (!targetEl) return;
      const defaultLabel = btn.textContent;
      let timer = 0;

      btn.addEventListener('click', async () => {
        const text = (targetEl.innerText || targetEl.textContent || '').replace(/\u00a0/g, ' ').trim();
        let ok = false;
        try {
          await navigator.clipboard.writeText(text);
          ok = true;
        } catch (e) {
          const area = document.createElement('textarea');
          area.value = text;
          area.setAttribute('readonly', '');
          area.style.cssText = 'position:fixed;left:-9999px;top:0';
          document.body.appendChild(area);
          area.select();
          try { ok = document.execCommand('copy'); } catch (err) { ok = false; }
          area.remove();
        }
        btn.textContent = ok ? '已复制' : '复制失败';
        btn.classList.toggle('is-copied', ok);
        window.clearTimeout(timer);
        timer = window.setTimeout(() => {
          btn.textContent = defaultLabel;
          btn.classList.remove('is-copied');
        }, 1600);
      });
    });
  }

  function revealInstant(node) {
    if (!node) return;
    node.style.transition = 'none';
    node.classList.add('is-in');
    requestAnimationFrame(() => { node.style.transition = ''; });
  }

  function initReveal() {
    const nodes = Array.prototype.slice.call(document.querySelectorAll('.reveal'));
    if (!nodes.length) return;
    if (root.dataset.static === '1' || reducedMotion || !('IntersectionObserver' in window)) {
      nodes.forEach((n) => n.classList.add('is-in'));
      return;
    }
    const vh = window.innerHeight || 900;
    const io = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-in');
          io.unobserve(entry.target);
        }
      });
    }, { rootMargin: '0px 0px -4% 0px', threshold: 0.05 });
    nodes.forEach((n) => {
      if (n.classList.contains('is-in')) return;
      const r = n.getBoundingClientRect();
      if (r.top < vh * 1.02 && r.bottom > -40) {
        revealInstant(n);
      } else {
        io.observe(n);
      }
    });
  }

  function initFaq() {
    const items = Array.prototype.slice.call(document.querySelectorAll('.faq-list details'));
    const canAnimate = !reducedMotion && root.dataset.static !== '1' && 'animate' in HTMLElement.prototype;

    items.forEach((details) => {
      const summary = details.querySelector('summary');
      const body = details.querySelector('.faq-a');
      if (!summary || !body || !canAnimate) return;
      let anim = null;

      summary.addEventListener('click', (e) => {
        e.preventDefault();
        if (anim) anim.cancel();

        if (details.open && !details.classList.contains('is-closing')) {
          const startH = details.offsetHeight;
          const endH = summary.offsetHeight;
          details.classList.add('is-closing');
          anim = details.animate(
            [{ height: startH + 'px' }, { height: endH + 'px' }],
            { duration: 240, easing: 'cubic-bezier(0.2, 0, 0, 1)' }
          );
          anim.onfinish = () => {
            details.open = false;
            details.classList.remove('is-closing');
            details.style.height = '';
            anim = null;
          };
          anim.oncancel = () => {
            details.classList.remove('is-closing');
            details.style.height = '';
          };
        } else {
          details.classList.remove('is-closing');
          const startH = summary.offsetHeight;
          details.style.height = startH + 'px';
          details.open = true;
          const endH = summary.offsetHeight + body.offsetHeight;
          anim = details.animate(
            [{ height: startH + 'px' }, { height: endH + 'px' }],
            { duration: 260, easing: 'cubic-bezier(0.2, 0, 0, 1)' }
          );
          anim.onfinish = () => {
            details.style.height = '';
            anim = null;
          };
          anim.oncancel = () => {
            details.style.height = '';
          };
        }
      });
    });

    const openTarget = (isInitial) => {
      const id = window.location.hash.slice(1);
      if (!id) return;
      const node = document.getElementById(id);
      if (!node) return;
      if (node.tagName === 'DETAILS') {
        node.classList.remove('is-closing');
        node.open = true;
      }
      const container = node.closest('section') || node;
      if (container.classList && container.classList.contains('reveal')) revealInstant(container);
      container.querySelectorAll('.reveal').forEach(revealInstant);
      if (isInitial && !openingActive) {
        try { node.scrollIntoView({ block: 'start', behavior: 'instant' }); }
        catch (e) { node.scrollIntoView(true); }
      }
    };
    openTarget(true);
    window.addEventListener('hashchange', () => openTarget(false));

    document.querySelectorAll('a[href^="#faq-"]').forEach((link) => {
      link.addEventListener('click', () => {
        const id = link.getAttribute('href').slice(1);
        const target = document.getElementById(id);
        if (target && target.tagName === 'DETAILS') target.open = true;
      });
    });
  }

  function initReplay() {
    const btn = document.getElementById('replayOpening');
    if (!btn) return;
    btn.addEventListener('click', () => {
      try { sessionStorage.removeItem('mca.opening'); } catch (e) {}
      const url = new URL(window.location.href);
      url.hash = '';
      url.searchParams.delete('skipOpening');
      url.searchParams.delete('static');
      url.searchParams.delete('t');
      url.searchParams.set('replay', '1');
      window.scrollTo(0, 0);
      window.location.href = url.toString();
    });
  }

  /* Theme switching. The initial value is resolved by the inline head script so the
     first paint is already correct; this only handles the button and cross-tab sync. */
  /* Theme switching. Three explicit buttons (role="group"), one per theme — the
     same shape the reference implementation uses. A cycling button would hide two
     of three states behind repeat clicks and make aria-pressed ambiguous. */
  const THEME_ORDER = ['glass', 'light', 'dark'];
  const THEME_COLOR = { light: '#F8F9FA', dark: '#0B0D10', glass: '#EDF1F9' };

  function initTheme() {
    const buttons = Array.from(document.querySelectorAll('[data-theme-set]'));
    const meta = document.querySelector('meta[name="theme-color"]');

    const paint = () => {
      const current = root.dataset.theme;
      buttons.forEach((b) => {
        b.setAttribute('aria-pressed', String(b.dataset.themeSet === current));
      });
      if (meta) meta.setAttribute('content', THEME_COLOR[current] || THEME_COLOR.light);
    };

    const setTheme = (next, persist) => {
      if (THEME_ORDER.indexOf(next) === -1) next = 'light';
      root.dataset.theme = next;
      document.body.classList.toggle('is-glass', next === 'glass');
      if (persist) {
        try { localStorage.setItem('mca.theme', next); } catch (e) {}
      }
      paint();
    };

    buttons.forEach((btn) => {
      btn.addEventListener('click', () => setTheme(btn.dataset.themeSet, true));
    });

    // Follow the OS only while the visitor has never chosen explicitly.
    const mq = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)');
    if (mq && mq.addEventListener) {
      mq.addEventListener('change', (e) => {
        let saved = null;
        try { saved = localStorage.getItem('mca.theme'); } catch (err) {}
        if (!saved || THEME_ORDER.indexOf(saved) === -1) {
          setTheme(e.matches ? 'dark' : 'light', false);
        }
      });
    }

    // Keep multiple open tabs in agreement.
    window.addEventListener('storage', (e) => {
      if (e.key === 'mca.theme' && THEME_ORDER.indexOf(e.newValue) !== -1) {
        setTheme(e.newValue, false);
      }
    });

    document.body.classList.toggle('is-glass', root.dataset.theme === 'glass');
    paint();
  }

  /* Glass specular highlight.
     Each glass pane gets one injected `.g-gloss` child; pointer movement writes
     --gx/--gy so the radial highlight follows the cursor. Following the reference
     implementation, the gloss is a real element rather than a pseudo-element, so it
     can be added lazily and disposed of when the theme is not glass. */
  /* Panels that receive a specular gloss layer.
     Deliberately excluded:
       .ws-tabs / .ws-stage  they are the two halves of the .ws-grid pane; a gloss on
                             each half would draw a highlight seam down the middle.
       .engine-table         it is a <table>. A table is not a containing block for an
                             absolutely positioned child, and an injected <span> inside
                             one becomes an anonymous table cell — so the gloss goes on
                             the .table-wrap container instead. */
  const GLASS_SELECTORS = [
    '.source', '.shot-box', '.trace', '.chip', '.seg', '.datasheet',
    '.prompt-card', '.console', '.graph', '.tpl', '.rail-frame--tablet',
    '.limit', '.shot-frame', '.ws-grid', '.table-wrap', '.delivery-box'
  ];

  function initGlass() {
    const panels = document.querySelectorAll(GLASS_SELECTORS.join(','));
    const enabled = () => root.dataset.theme === 'glass';

    const ensureGloss = (node) => {
      let gloss = node.querySelector(':scope > .g-gloss');
      if (!gloss) {
        gloss = document.createElement('span');
        gloss.className = 'g-gloss';
        gloss.setAttribute('aria-hidden', 'true');
        node.appendChild(gloss);
      }
      return gloss;
    };

    const track = (e) => {
      const node = e.currentTarget;
      if (!enabled()) return;
      const r = node.getBoundingClientRect();
      if (!r.width || !r.height) return;
      node.style.setProperty('--gx', (((e.clientX - r.left) / r.width) * 100).toFixed(2) + '%');
      node.style.setProperty('--gy', (((e.clientY - r.top) / r.height) * 100).toFixed(2) + '%');
    };
    const reset = (e) => {
      e.currentTarget.style.removeProperty('--gx');
      e.currentTarget.style.removeProperty('--gy');
    };
    const press = (e) => e.currentTarget.classList.add('is-dragging');
    const release = (e) => e.currentTarget.classList.remove('is-dragging');

    panels.forEach((node) => {
      node.classList.add('g-surface');
      ensureGloss(node);
      node.addEventListener('pointermove', track, { passive: true });
      node.addEventListener('pointerleave', reset, { passive: true });
      node.addEventListener('pointerdown', press, { passive: true });
      node.addEventListener('pointerup', release, { passive: true });
      node.addEventListener('pointercancel', release, { passive: true });
    });

    // Respect the OS "reduce transparency" request: no gloss, no tracking cost.
    const rt = window.matchMedia && window.matchMedia('(prefers-reduced-transparency: reduce)');
    if (rt && rt.matches) {
      panels.forEach((n) => n.classList.remove('g-surface'));
    }
  }

  function init() {
    initFaq();
    initTheme();
    initGlass();
    initNav();
    initMobileNav();
    initWorkspaces();
    initRouting();
    initCopy();
    initReveal();
    initReplay();
    boot();
    requestAnimationFrame(() => {
      requestAnimationFrame(() => { root.classList.add('smooth-scroll'); });
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
