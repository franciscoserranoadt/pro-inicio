/* Pro Inicio — lógica de la pantalla.
   Habla con Android a través del objeto «Android» (MainActivity.java → Bridge).
   Android responde llamando a PI.onMedia, PI.onLocation, PI.onNet, PI.onApps, PI.onUpdate…
   Todo lo que se ve (botones, tarjetas, mapa, colores) sale de la configuración «cfg», guardada en el aparato. */
(function () {
  'use strict';

  // En el navegador del ordenador (para probar) no existe Android: se usa uno de mentira.
  const A = window.Android || {
    version: () => 'prueba', proApps: () => '{"maps":true,"radio":true,"obd":true,"garaje":false}',
    open: () => true, openComp: () => true, allApps: () => setTimeout(() => PI.onApps([]), 200),
    media: () => {}, refreshMedia: () => {}, mediaPermission: () => {}, locationPermission: () => {},
    get: () => {}, openUrl: () => {}, homeSettings: () => {}, isHome: () => false, net: () => 'wifi',
    phone: () => {}, icon: () => '', has: () => true, card: () => {}, cardPermission: () => {}, cardRetry: () => {},
    freeform: () => false, openWindow: () => false, devSettings: () => {},
    setPref: () => {}, getPref: (k, d) => d, checkUpdate: () => {}, doUpdate: () => {}
  };

  const $ = (id) => document.getElementById(id);
  const store = {
    get(k, d) { try { const v = localStorage.getItem('pi.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem('pi.' + k, JSON.stringify(v)); } catch (e) { /* nada */ } }
  };
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // ---------- Aviso corto ----------
  let toastT = 0;
  function toast(t) {
    const el = $('toast');
    el.textContent = t;
    el.classList.remove('hide');
    clearTimeout(toastT);
    toastT = setTimeout(() => el.classList.add('hide'), 3500);
  }

  // =====================================================================
  //  TEMAS DE COLORES
  // =====================================================================
  function hexA(h, a) {
    const n = parseInt(h.slice(1).length === 3 ? h.slice(1).split('').map((c) => c + c).join('') : h.slice(1), 16);
    return 'rgba(' + (n >> 16 & 255) + ',' + (n >> 8 & 255) + ',' + (n & 255) + ',' + a + ')';
  }
  // [nombre, fondo, tarjeta, tarjeta2, acento, acento2, música(3 colores), apps, tarjeta calle (2), claro?, texto sobre acento, fichas]
  const THEMES = {
    medianoche: ['Medianoche', '#05070d', '#0d1426', '#131d36', '#2f7bff', '#33ccff', ['#3a1f7a', '#19214a', '#0d1430'], '#1a2150', ['#13853f', '#0b6630']],
    carbono:    ['Carbono', '#000000', '#0b0b0d', '#17171b', '#e8e8e8', '#ffffff', ['#2a2a2e', '#141416', '#0b0b0d'], '#18181c', ['#2f2f34', '#18181b'], false, '#111111'],
    grafito:    ['Grafito', '#0c0d10', '#17191e', '#22252c', '#64748b', '#cbd5e1', ['#3b4252', '#22262e', '#14161a'], '#262a33', ['#475569', '#334155']],
    oceano:     ['Océano', '#031018', '#062231', '#0b3245', '#00a6d6', '#4fe3ff', ['#005f7a', '#063246', '#04202e'], '#0a3b52', ['#0b8f8a', '#066a66']],
    hielo:      ['Hielo', '#061018', '#0e1c29', '#16293b', '#7dd3fc', '#e0f2fe', ['#2b5c7d', '#14304a', '#0b1a28'], '#183450', ['#0284c7', '#075985'], false, '#062033'],
    turquesa:   ['Turquesa', '#031212', '#072323', '#0c3333', '#14b8a6', '#5eead4', ['#0f766e', '#0b3535', '#061c1c'], '#0d3a3a', ['#0d9488', '#0f766e']],
    esmeralda:  ['Esmeralda', '#03100a', '#0a2117', '#113223', '#10b981', '#6ee7b7', ['#0f5c3e', '#0c2e21', '#08190f'], '#0f3a28', ['#13853f', '#0b6630']],
    bosque:     ['Bosque', '#070d06', '#121c10', '#1c2a18', '#65a30d', '#bef264', ['#3f5f1a', '#1c2a14', '#10180c'], '#22341a', ['#4d7c0f', '#365314']],
    oro:        ['Oro', '#0d0a03', '#1c1607', '#2a210c', '#eab308', '#fde047', ['#7a5c06', '#3a2c08', '#1a1405'], '#352a0c', ['#ca8a04', '#a16207'], false, '#1a1400'],
    cobre:      ['Cobre', '#0f0805', '#1e120b', '#2d1b10', '#c2703d', '#f0a875', ['#7c3f1d', '#3b1f10', '#1a0e07'], '#3a2214', ['#b45309', '#92400e']],
    atardecer:  ['Atardecer', '#120609', '#22101a', '#321826', '#ff7a45', '#ffc15e', ['#a33b2a', '#4a1830', '#200c16'], '#3b1426', ['#e8590c', '#b3420a']],
    lava:       ['Lava', '#100503', '#1f0c07', '#2e140b', '#ff4500', '#ffa500', ['#8a2a07', '#3d1306', '#1c0904'], '#3a1608', ['#ff4500', '#c2410c']],
    rubi:       ['Rubí', '#12040a', '#220913', '#33101d', '#e11d48', '#fb7185', ['#7f1d3b', '#3a0d1f', '#1e0710'], '#3a0f1f', ['#be123c', '#881337']],
    nissan:     ['Nissan', '#0a0a0a', '#151515', '#222222', '#c3002f', '#ff3355', ['#5c0016', '#2a0a10', '#141414'], '#2a1014', ['#c3002f', '#8a0021']],
    rosa:       ['Rosa', '#12070e', '#22101b', '#321828', '#ec4899', '#f9a8d4', ['#9d174d', '#45122e', '#1f0a16'], '#3f1430', ['#db2777', '#9d174d']],
    violeta:    ['Violeta', '#0a0614', '#160e2a', '#21153d', '#8b5cf6', '#c4b5fd', ['#4c1d95', '#24124a', '#130a28'], '#2a1858', ['#7c3aed', '#5b21b6']],
    neon:       ['Neón', '#05020d', '#0e0820', '#170e33', '#ff2bd6', '#00f0ff', ['#6a00ff', '#25074d', '#0e0820'], '#1f0b45', ['#ff2bd6', '#9d00ff']],
    retro:      ['Retro 80', '#120a1f', '#1f1233', '#2b1a47', '#ff6ec7', '#ffd166', ['#ff6ec7', '#7b2ff7', '#1f1233'], '#3a1d63', ['#f72585', '#7209b7']],
    artico:     ['Ártico (claro)', '#eef2f8', '#ffffff', '#e2e8f2', '#2563eb', '#0ea5e9', ['#dbe6ff', '#eef3ff', '#ffffff'], '#e8eefb', ['#16a34a', '#15803d'], true, '#ffffff', '#8a96b4'],
    arena:      ['Arena (claro)', '#f4efe6', '#fffaf2', '#ece3d4', '#c2410c', '#ea580c', ['#ffe2c6', '#fff3e4', '#fffaf2'], '#f6ead8', ['#15803d', '#166534'], true, '#ffffff', '#a8977c'],
    menta:      ['Menta (claro)', '#ecf7f2', '#ffffff', '#dcefe6', '#059669', '#10b981', ['#c9f0de', '#eafaf2', '#ffffff'], '#e3f5ec', ['#047857', '#065f46'], true, '#ffffff', '#86a99a'],
    lavanda:    ['Lavanda (claro)', '#f2effa', '#ffffff', '#e5dff5', '#7c3aed', '#a78bfa', ['#e4d9ff', '#f4efff', '#ffffff'], '#ece6fb', ['#16a34a', '#15803d'], true, '#ffffff', '#9b91bd']
  };

  function themeVars(key) {
    const t = THEMES[key] || THEMES.medianoche;
    const [, bg, card, card2, acc, acc2, p, apps, nav, light, accTxt, tile] = t;
    return {
      '--bg': bg, '--card': card, '--card2': card2,
      '--line': light ? 'rgba(0,0,0,.10)' : hexA(acc, 0.18),
      '--txt': light ? '#141a2b' : '#f2f5ff', '--muted': light ? '#5a6580' : '#95a3c4', '--sub': light ? '#33405e' : '#c9d2ee',
      '--acc': acc, '--acc2': acc2, '--accTxt': accTxt || '#ffffff',
      '--rail': 'linear-gradient(180deg, ' + card + ', ' + bg + ')',
      '--railOn': 'linear-gradient(160deg, ' + acc + ', ' + (light ? acc2 : p[2]) + ')', '--glow': hexA(acc, 0.55),
      '--player': 'radial-gradient(130% 120% at 100% 0%, ' + p[0] + ' 0%, ' + p[1] + ' 45%, ' + p[2] + ' 100%)',
      '--apps': 'linear-gradient(160deg, ' + apps + ', ' + p[2] + ' 70%)',
      '--nav': 'linear-gradient(160deg, ' + nav[0] + ', ' + nav[1] + ')',
      '--tile': tile || (light ? '#8a96b4' : '#3a4258'),
      '--overlay': light ? 'rgba(255,255,255,.93)' : hexA(card, 0.92),
      '--speed': light ? '#0f9d58' : '#4ade80', '--live': light ? '#dc2626' : '#ff6b6b'
    };
  }
  function applyTheme() {
    const v = themeVars(cfg.theme), r = document.documentElement.style;
    Object.keys(v).forEach((k) => r.setProperty(k, v[k]));
  }
  const isLightTheme = () => !!(THEMES[cfg.theme] || [])[9];

  // =====================================================================
  //  APPS PRO, ACCIONES Y CONFIGURACIÓN
  // =====================================================================
  const SVG = {
    maps: '<svg viewBox="0 0 48 48"><path d="M24 5 39 41 24 33 9 41Z" fill="#fff"/></svg>',
    radio: '<svg viewBox="0 0 48 48"><rect x="6" y="16" width="36" height="24" rx="5" fill="#fff"/><circle cx="17" cy="28" r="6" fill="rgba(0,0,0,.25)"/><rect x="27" y="23" width="10" height="3" rx="1.5" fill="rgba(0,0,0,.25)"/><rect x="27" y="29" width="10" height="3" rx="1.5" fill="rgba(0,0,0,.25)"/><path d="M12 15 34 7" stroke="#fff" stroke-width="3" stroke-linecap="round"/></svg>',
    obd: '<svg viewBox="0 0 48 48"><path d="M6 32a18 18 0 0 1 36 0" fill="none" stroke="#fff" stroke-width="4" stroke-linecap="round"/><path d="M24 32 33 19" stroke="#fff" stroke-width="4" stroke-linecap="round"/><circle cx="24" cy="32" r="4" fill="#fff"/></svg>',
    garaje: '<svg viewBox="0 0 48 48"><path d="M5 20 24 8l19 12v20H5Z" fill="#fff"/><rect x="12" y="24" width="24" height="16" rx="2" fill="rgba(0,0,0,.25)"/><path d="M12 29h24M12 34h24" stroke="#fff" stroke-width="2"/></svg>'
  };
  // Iconos de una sola tinta (barra lateral)
  const MONO = {
    home: 'M12 3 2.5 11h2.8v9.5h5.2v-6h3v6h5.2V11h2.8z',
    nav: 'M21 3 3 10.5l7.6 2.9L13.5 21zm-4.4 4.4-4.2 9.2-1.5-4-4-1.5z',
    radio: 'M9 4v10.6A3.5 3.5 0 1 0 11 18V8h8v6.6a3.5 3.5 0 1 0 2 3.4V4z',
    phone: 'M6.6 10.8a15.2 15.2 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11.4 11.4 0 0 0 3.6.57 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.25.2 2.45.57 3.57a1 1 0 0 1-.25 1z',
    apps: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z',
    settings: 'M18.4 13a7.5 7.5 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7.6 7.6 0 0 0-1.7-1L14 3.2h-4l-.3 2.7a7.6 7.6 0 0 0-1.7 1l-2.5-1-2 3.5L5.6 11a7.5 7.5 0 0 0 0 2l-2.1 1.6 2 3.5 2.5-1c.5.4 1.1.7 1.7 1l.3 2.7h4l.3-2.7c.6-.3 1.2-.6 1.7-1l2.5 1 2-3.5zM12 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z',
    obd: 'M18.9 6a1.5 1.5 0 0 0-1.4-1h-11a1.5 1.5 0 0 0-1.4 1L3 12v8a1 1 0 0 0 1 1h1a1 1 0 0 0 1-1v-1h12v1a1 1 0 0 0 1 1h1a1 1 0 0 0 1-1v-8zM6.5 16a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm11 0a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zM5 11l1.5-4.5h11L19 11z',
    garaje: 'M12 3 2 9v12h3v-9h14v9h3V9zM7 14h10v2H7zm0 3h10v2H7z',
    maps: 'M12 2 20 21 12 16.5 4 21Z',
    add: 'M11 4h2v7h7v2h-7v7h-2v-7H4v-2h7z',
    none: 'M5 11h14v2H5z'
  };
  const mono = (k) => '<svg viewBox="0 0 24 24"><path d="' + (MONO[k] || MONO.apps) + '"/></svg>';

  const PRO = {
    maps:   { pkg: 'com.fserrano.promaps', name: 'Pro Maps',   url: 'https://github.com/franciscoserranoadt/pro-maps/releases/tag/ultima' },
    radio:  { pkg: 'es.proradio.app',      name: 'Pro Radio',  url: 'https://github.com/franciscoserranoadt/pro-radio/releases/tag/ultima' },
    obd:    { pkg: 'es.proobd.app',        name: 'Pro OBD',    url: 'https://github.com/franciscoserranoadt/pro-obd/releases/tag/ultima' },
    garaje: { pkg: 'es.progaraje.app',     name: 'Pro Garaje', url: 'https://github.com/franciscoserranoadt/pro-garaje/releases/tag/ultima' }
  };
  const ACTS = {
    home: 'Inicio', nav: 'Navegar', apps: 'Todas las apps', more: 'Más apps', add: 'Añadir',
    settings: 'Personalizar', phone: 'Teléfono', none: 'Vacío'
  };
  const CARDS = { music: 'Música', apps: 'Apps', weather: 'Tiempo', clock: 'Reloj', speed: 'Velocidad', none: 'Nada' };

  const DEFAULT_CFG = () => ({
    theme: 'medianoche',
    nav: { t: 'pro', id: 'maps' },
    map: true, mapMode: 'nav', mapStyle: 'auto', b3d: true, street: true, speed: true, mapBtns: true,
    side: 'right', top: 'music', bottom: 'apps',
    rail: true,
    railItems: [{ t: 'act', id: 'home' }, { t: 'act', id: 'nav' }, { t: 'pro', id: 'radio' }, { t: 'act', id: 'phone' },
      { t: 'act', id: 'apps' }, { t: 'act', id: 'settings' }, { t: 'pro', id: 'obd' }],
    grid: [{ t: 'pro', id: 'maps' }, { t: 'pro', id: 'radio' }, { t: 'pro', id: 'obd' }, { t: 'pro', id: 'garaje' },
      { t: 'act', id: 'more' }, { t: 'act', id: 'add' }],
    st: { date: true, gps: true, net: true, wx: true }
  });

  let cfg = store.get('cfg', null);
  if (!cfg) {
    cfg = DEFAULT_CFG();
    // Versión anterior: favoritas sueltas y edificios 3D
    const oldFavs = store.get('favs', []);
    if (oldFavs.length) cfg.grid.splice(4, 0, ...oldFavs.map((f) => ({ t: 'app', pkg: f.pkg, cls: f.cls, label: f.label, icon: f.icon })));
    cfg.b3d = store.get('b3d', true);
  } else {
    const d = DEFAULT_CFG();
    Object.keys(d).forEach((k) => { if (cfg[k] === undefined) cfg[k] = d[k]; });
    cfg.st = Object.assign(d.st, cfg.st);
  }
  function save() { store.set('cfg', cfg); }
  save();

  let installed = {};
  function refreshInstalled() { try { installed = JSON.parse(A.proApps()); } catch (e) { installed = {}; } }

  const labelOf = (it) => !it ? '' : it.t === 'pro' ? PRO[it.id].name : it.t === 'app' ? it.label : (ACTS[it.id] || '');
  const navLabel = () => labelOf(cfg.nav) || 'Pro Maps';

  function initials(s) {
    const w = String(s || 'PR').replace(/[^\p{L}\p{N} ]/gu, ' ').trim().split(/\s+/);
    return ((w[0] || 'P')[0] + ((w[1] || w[0] || 'R')[w[1] ? 0 : 1] || '')).toUpperCase();
  }
  /** Icono en ficha de color (cuadro de apps, elegir). */
  function tileHtml(it) {
    if (it.t === 'pro') return '<div class="ai i-' + it.id + '">' + SVG[it.id] + '</div>';
    if (it.t === 'app') return '<div class="ai i-app">' + (it.icon ? '<img alt="" src="' + esc(it.icon) + '">' : '<span>' + esc(initials(it.label)) + '</span>') + '</div>';
    const k = it.id === 'more' ? 'apps' : it.id === 'nav' ? 'nav' : it.id;
    return '<div class="ai i-act">' + mono(k) + '</div>';
  }
  /** Icono de una tinta (barra lateral). */
  function railHtml(it) {
    if (it.t === 'app') return it.icon ? '<img alt="" src="' + esc(it.icon) + '">' : '<b>' + esc(initials(it.label)) + '</b>';
    if (it.t === 'pro') return mono(it.id);
    return mono(it.id === 'more' ? 'apps' : it.id);
  }

  function openPro(id) {
    const info = PRO[id];
    if (installed[id] && A.open(info.pkg)) return;
    toast('Abriendo la descarga de ' + info.name + '…');
    A.openUrl(info.url);
  }
  function runItem(it) {
    if (!it) return;
    if (it.t === 'pro') return openPro(it.id);
    if (it.t === 'app') { if (!A.openComp(it.pkg, it.cls)) toast(it.label + ' no está instalada'); return; }
    switch (it.id) {
      case 'home': closeSheets(); setFollow(true); break;
      case 'nav': runItem(cfg.nav); break;
      case 'apps': case 'more': pick({ title: 'Todas las apps', acts: false, pros: false }, (x) => runItem(x)); break;
      case 'add': pick({ title: 'Añadir al cuadro de apps', acts: true }, (x) => { addToGrid(x); }); break;
      case 'settings': openSettings(); break;
      case 'phone': A.phone(); break;
      default: break;
    }
  }
  function addToGrid(x) {
    const i = cfg.grid.findIndex((g) => g.t === 'act' && g.id === 'add');
    cfg.grid.splice(i < 0 ? cfg.grid.length : i, 0, x);
    save(); drawGrid();
    toast(labelOf(x) + ' añadida. Mantén pulsado cualquier botón para cambiarlo.');
  }

  // ---------- Mantener pulsado ----------
  function longPress(el, fn) {
    let t = 0, fired = false;
    el.addEventListener('touchstart', () => { fired = false; t = setTimeout(() => { fired = true; fn(); }, 600); }, { passive: true });
    el.addEventListener('touchend', (e) => { clearTimeout(t); if (fired) e.preventDefault(); });
    el.addEventListener('touchmove', () => clearTimeout(t), { passive: true });
    el.addEventListener('contextmenu', (e) => { e.preventDefault(); fired = true; fn(); });
    el.addEventListener('click', (e) => { if (fired) { e.stopImmediatePropagation(); fired = false; } }, true);
  }

  // ---------- Menú de un botón ----------
  function menu(title, options) {
    $('menuTitle').textContent = title;
    const box = $('menuBtns');
    box.innerHTML = '';
    options.concat([['Cancelar', null, 'ghost']]).forEach(([txt, fn, cls]) => {
      const b = document.createElement('button');
      b.className = 'obtn ' + (cls || '');
      b.textContent = txt;
      b.addEventListener('click', () => { $('menu').classList.add('hide'); if (fn) fn(); });
      box.appendChild(b);
    });
    $('menu').classList.remove('hide');
  }
  function listMenu(listName, i, redraw) {
    const list = cfg[listName], it = list[i];
    const where = listName === 'grid' ? 'el cuadro de apps' : 'la barra lateral';
    menu(labelOf(it) || 'Botón', [
      ['🔁  Cambiar este botón', () => pick({ title: 'Cambiar «' + labelOf(it) + '»', acts: true }, (x) => { list[i] = x; save(); redraw(); })],
      ['⬅️  Mover antes', () => { if (i > 0) { [list[i - 1], list[i]] = [list[i], list[i - 1]]; save(); redraw(); } }],
      ['➡️  Mover después', () => { if (i < list.length - 1) { [list[i + 1], list[i]] = [list[i], list[i + 1]]; save(); redraw(); } }],
      ['🗑️  Quitar de ' + where, () => { list.splice(i, 1); save(); redraw(); }, 'red'],
      ['🎨  Personalizar pantalla', openSettings]
    ]);
  }

  // =====================================================================
  //  DIBUJAR LA PANTALLA SEGÚN LA CONFIGURACIÓN
  // =====================================================================
  function drawRail() {
    const r = $('rail');
    r.innerHTML = '';
    cfg.railItems.forEach((it, i) => {
      const b = document.createElement('button');
      b.className = 'rb' + (it.t === 'act' && it.id === 'home' ? ' on' : '');
      b.setAttribute('aria-label', labelOf(it));
      b.innerHTML = railHtml(it);
      b.addEventListener('click', () => runItem(it));
      longPress(b, () => listMenu('railItems', i, drawAll));
      r.appendChild(b);
    });
  }

  function drawGrid() {
    refreshInstalled();
    const g = $('appsGrid');
    g.innerHTML = '';
    cfg.grid.forEach((it, i) => {
      if (it.t === 'app' && !A.has(it.pkg)) return; // desinstalada: no se enseña
      const b = document.createElement('button');
      const off = it.t === 'pro' && !installed[it.id];
      b.className = 'ab' + (off ? ' off' : '');
      b.innerHTML = tileHtml(it) + '<span class="lbl"></span><b class="dl">↓</b>';
      b.querySelector('.lbl').textContent = labelOf(it);
      b.addEventListener('click', () => runItem(it));
      longPress(b, () => listMenu('grid', i, drawGrid));
      g.appendChild(b);
    });
  }

  const CARD_EL = { music: 'player', apps: 'appsCard', weather: 'wxCard', clock: 'clockCard', speed: 'spdBig' };
  function drawLayout() {
    const app = $('app'), side = $('side');
    const cards = [cfg.top, cfg.bottom].filter((c) => c && c !== 'none');
    Object.values(CARD_EL).forEach((id) => $(id).classList.add('hide'));
    cards.forEach((c) => { const el = $(CARD_EL[c]); el.classList.remove('hide'); side.appendChild(el); });
    side.classList.toggle('one', cards.length === 1);
    app.classList.toggle('noSide', cards.length === 0);
    app.classList.toggle('sideLeft', cfg.side === 'left');
    app.classList.toggle('noRail', !cfg.rail);
    app.classList.toggle('noMap', !cfg.map);
    $('rail').classList.toggle('hide', !cfg.rail);
    $('mapCard').classList.toggle('hide', !cfg.map);
    const own = cardKind() === 'own';
    $('street').classList.toggle('hide', !cfg.street || !own);
    $('speedCard').classList.toggle('hide', !cfg.speed || !own);
    document.querySelector('.mapbtns').classList.toggle('hide', !cfg.mapBtns || !own);
    drawCardMsg();
    $('fecha').classList.toggle('hide', !cfg.st.date);
    $('stGps').classList.toggle('hide', !cfg.st.gps);
    $('stNet').classList.toggle('hide', !cfg.st.net);
    $('stWeather').classList.toggle('hide', !cfg.st.wx);
    $('cityName').textContent = store.get('city', '') || navLabel();
    $('hint').textContent = 'Navegar con ' + navLabel();
    if (!streetKnown) $('streetSub').textContent = 'Toca para navegar con ' + navLabel();
    if (cfg.map && own) { initMap(); setTimeout(() => { if (map) map.resize(); }, 60); }
    setTimeout(syncCard, 80);
    if (cards.includes('weather')) loadWeather(false);
  }

  function drawAll() { applyTheme(); drawRail(); drawGrid(); drawLayout(); applyMapStyle(); }

  // Ajustes siempre a mano: mantener pulsada la hora
  longPress($('status'), openSettings);

  // ---------- Hora y fecha ----------
  const fmtFecha = new Intl.DateTimeFormat('es-ES', { weekday: 'long', day: 'numeric', month: 'long' });
  let lastMin = -1;
  function tick() {
    const d = new Date();
    if (d.getMinutes() !== lastMin) {
      lastMin = d.getMinutes();
      const hh = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
      const f = fmtFecha.format(d), ff = f.charAt(0).toUpperCase() + f.slice(1);
      $('hora').textContent = hh; $('fecha').textContent = ff;
      $('bigHora').textContent = hh; $('bigFecha').textContent = ff;
    }
  }
  tick();
  setInterval(tick, 1000);

  // ---------- Conexión y GPS (barra de estado) ----------
  const ICON_WIFI = '<svg viewBox="0 0 24 24"><path d="M12 18.5a1.8 1.8 0 1 1 0 3.6 1.8 1.8 0 0 1 0-3.6zM12 13c2 0 3.8.8 5.1 2.1l-1.6 1.6A5 5 0 0 0 12 15.3a5 5 0 0 0-3.5 1.4l-1.6-1.6A7.2 7.2 0 0 1 12 13zm0-4.5c3.2 0 6.2 1.3 8.3 3.4l-1.6 1.6A9.5 9.5 0 0 0 12 10.7c-2.6 0-5 1-6.7 2.8L3.7 12A11.7 11.7 0 0 1 12 8.5zM12 4c4.5 0 8.6 1.8 11.5 4.8l-1.6 1.6A14 14 0 0 0 12 6.3c-3.9 0-7.4 1.6-9.9 4.1L.5 8.8A16.3 16.3 0 0 1 12 4z"/></svg>';
  const ICON_BARS = '<svg viewBox="0 0 24 24"><path d="M3 18h3v4H3zM8 14h3v8H8zM13 10h3v12h-3zM18 5h3v17h-3z"/></svg>';
  const ICON_NONE = '<svg viewBox="0 0 24 24" style="opacity:.35"><path d="M3 18h3v4H3zM8 14h3v8H8zM13 10h3v12h-3zM18 5h3v17h-3z"/></svg>';
  let lastNet = '';
  function updNet() {
    let n = 'nada';
    try { n = A.net(); } catch (e) { /* nada */ }
    if (n === lastNet) return;
    lastNet = n;
    $('stNet').innerHTML = n === 'wifi' ? ICON_WIFI : n === 'movil' ? '<b>4G</b>' + ICON_BARS : n === 'otra' ? ICON_BARS : ICON_NONE;
    if (n !== 'nada' && cfg.map && !mapReady) initMap();
  }
  setInterval(updNet, 5000);
  let lastGps = 0;
  setInterval(() => $('stGps').classList.toggle('off', Date.now() - lastGps > 15000), 3000);

  // ---------- Peticiones web por Android ----------
  let netId = 0;
  const netWait = {};
  function get(url) {
    return new Promise((res, rej) => {
      const id = ++netId;
      netWait[id] = { res, rej };
      if (window.Android) A.get(id, url);
      else fetch(url).then((r) => r.text()).then((t) => PI.onNet(id, true, t)).catch((e) => PI.onNet(id, false, String(e)));
      setTimeout(() => { if (netWait[id]) { delete netWait[id]; rej(new Error('tiempo')); } }, 25000);
    });
  }

  // ---------- Tiempo (Open-Meteo, gratis) ----------
  const WMO = {
    0: ['Despejado', '☀️', '🌙'], 1: ['Casi despejado', '🌤️', '🌙'], 2: ['Algo nuboso', '⛅', '☁️'], 3: ['Nublado', '☁️', '☁️'],
    45: ['Niebla', '🌫️'], 48: ['Niebla', '🌫️'], 51: ['Llovizna', '🌦️'], 53: ['Llovizna', '🌦️'], 55: ['Llovizna', '🌧️'],
    56: ['Llovizna helada', '🌧️'], 57: ['Llovizna helada', '🌧️'], 61: ['Lluvia débil', '🌦️'], 63: ['Lluvia', '🌧️'], 65: ['Lluvia fuerte', '🌧️'],
    66: ['Lluvia helada', '🌧️'], 67: ['Lluvia helada', '🌧️'], 71: ['Nieve débil', '🌨️'], 73: ['Nieve', '🌨️'], 75: ['Nieve fuerte', '❄️'],
    77: ['Granizo fino', '🌨️'], 80: ['Chubascos', '🌦️'], 81: ['Chubascos', '🌧️'], 82: ['Chubascos fuertes', '⛈️'],
    85: ['Chubascos de nieve', '🌨️'], 86: ['Chubascos de nieve', '❄️'], 95: ['Tormenta', '⛈️'], 96: ['Tormenta con granizo', '⛈️'], 99: ['Tormenta con granizo', '⛈️']
  };
  const wIcon = (code, day) => { const k = WMO[code] || ['', '🌡️']; return (!day && k[2]) ? k[2] : k[1]; };
  let loc = store.get('loc', null);
  let lastWeather = 0, weatherLoc = null, isDay = true;

  function showWeather(w) {
    if (!w) return;
    isDay = w.day !== false;
    const k = WMO[w.code] || ['', '🌡️'];
    $('wIcon').textContent = wIcon(w.code, w.day);
    $('wTemp').textContent = Math.round(w.temp) + '°C';
    $('stWeather').title = [w.city, k[0]].filter(Boolean).join(' · ');
    if (w.city) $('cityName').textContent = w.city;
    $('wxIcon').textContent = wIcon(w.code, w.day);
    $('wxTemp').textContent = Math.round(w.temp) + '°';
    $('wxDesc').textContent = [w.city, k[0], w.max != null ? Math.round(w.max) + '° / ' + Math.round(w.min) + '°' : ''].filter(Boolean).join(' · ');
    $('wxHours').innerHTML = (w.hours || []).map((h) => '<div><span>' + h.h + '</span><em>' + wIcon(h.code, h.day) + '</em><b>' + Math.round(h.t) + '°</b></div>').join('');
    applyMapStyle();
  }

  async function loadWeather(force) {
    if (!loc) return;
    if (!force && Date.now() - lastWeather < 15 * 60 * 1000) return;
    lastWeather = Date.now();
    weatherLoc = { lat: loc.lat, lon: loc.lon };
    try {
      const u = 'https://api.open-meteo.com/v1/forecast?latitude=' + loc.lat.toFixed(3) + '&longitude=' + loc.lon.toFixed(3) +
        '&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=2&timezone=auto';
      const j = JSON.parse(await get(u));
      const w = { temp: j.current.temperature_2m, code: j.current.weather_code, day: j.current.is_day === 1, city: store.get('city', ''),
        max: j.daily ? j.daily.temperature_2m_max[0] : null, min: j.daily ? j.daily.temperature_2m_min[0] : null, hours: [] };
      if (j.hourly && j.hourly.time) {
        let i0 = j.hourly.time.findIndex((t) => t > j.current.time);
        if (i0 < 0) i0 = 0;
        for (let i = i0; i < i0 + 5 && i < j.hourly.time.length; i++) {
          w.hours.push({ h: j.hourly.time[i].slice(11, 13) + 'h', t: j.hourly.temperature_2m[i], code: j.hourly.weather_code[i], day: j.hourly.is_day[i] === 1 });
        }
      }
      const cityKey = loc.lat.toFixed(2) + ',' + loc.lon.toFixed(2);
      if (store.get('cityKey', '') !== cityKey) {
        try {
          const c = JSON.parse(await get('https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=' + loc.lat + '&longitude=' + loc.lon + '&localityLanguage=es'));
          w.city = c.city || c.locality || c.principalSubdivision || '';
          store.set('city', w.city); store.set('cityKey', cityKey);
        } catch (e) { /* sin nombre */ }
      }
      store.set('weather', w);
      showWeather(w);
    } catch (e) {
      lastWeather = Date.now() - 13 * 60 * 1000; // reintenta en 2 min
    }
  }
  setInterval(() => loadWeather(false), 60 * 1000);

  // =====================================================================
  //  MAPA EN DIRECTO (MapLibre + OpenFreeMap, los mismos estilos que Pro Maps)
  // =====================================================================
  const STYLE = { dark: 'https://tiles.openfreemap.org/styles/dark', light: 'https://tiles.openfreemap.org/styles/liberty', soft: 'https://tiles.openfreemap.org/styles/positron' };
  let map = null, meMarker = null, mapReady = false, mapLoading = false, follow = true, heading = 0, speed = -1, vecSrc = null;
  let curStyle = '', darkOk = true, streetKnown = false;

  function wantedStyle() {
    if (cfg.mapStyle === 'dark') return darkOk ? 'dark' : 'light';
    if (cfg.mapStyle === 'light' || cfg.mapStyle === 'soft') return cfg.mapStyle;
    // Automático: oscuro de noche o con un tema oscuro; claro de día con un tema claro
    return (!isDay || !isLightTheme()) && darkOk ? 'dark' : 'light';
  }
  function applyMapStyle() {
    if (!map || !mapReady) return;
    const k = wantedStyle();
    if (k === curStyle) return;
    curStyle = k;
    map.setStyle(STYLE[k], { diff: false });
  }

  function loadScript(src) {
    return new Promise((res, rej) => { const s = document.createElement('script'); s.src = src; s.onload = res; s.onerror = rej; document.head.appendChild(s); });
  }
  function loadCss(href) { const l = document.createElement('link'); l.rel = 'stylesheet'; l.href = href; document.head.appendChild(l); }

  async function initMap() {
    if (mapReady || mapLoading || !cfg.map) return;
    mapLoading = true;
    if (!window.maplibregl) {
      const libs = [['https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js', 'https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css'],
        ['https://cdn.jsdelivr.net/npm/maplibre-gl@4.7.1/dist/maplibre-gl.js', 'https://cdn.jsdelivr.net/npm/maplibre-gl@4.7.1/dist/maplibre-gl.css']];
      for (const [js, css] of libs) {
        try { loadCss(css); await loadScript(js); break; } catch (e) { /* siguiente */ }
      }
    }
    if (!window.maplibregl) { $('mapOff').classList.remove('hide'); mapLoading = false; return; }
    try { const r = await fetch(STYLE.dark, { cache: 'no-store' }); darkOk = r.ok; } catch (e) { darkOk = false; }
    curStyle = wantedStyle();
    const start = loc ? [loc.lon, loc.lat] : [-3.8249, 40.3459]; // Alcorcón por defecto
    try {
      map = new maplibregl.Map({ container: 'map', style: STYLE[curStyle], center: start, zoom: 16.3, pitch: 55, maxPitch: 70,
        attributionControl: false, fadeDuration: 0, dragRotate: false, pitchWithRotate: false });
    } catch (e) { $('mapOff').classList.remove('hide'); mapLoading = false; return; }
    const el = document.createElement('div');
    el.className = 'me';
    el.innerHTML = '<svg viewBox="0 0 24 24"><path d="M12 2 20 21 12 16.5 4 21Z"/></svg>';
    meMarker = new maplibregl.Marker({ element: el, rotationAlignment: 'map', pitchAlignment: 'map' }).setLngLat(start).addTo(map);
    map.on('style.load', add3d);
    map.on('load', () => {
      mapReady = true; mapLoading = false;
      $('mapOff').classList.add('hide');
      if (loc) moveMap(true);
      applyMapStyle();
    });
    map.on('error', () => { /* trozos del mapa que fallan: se ignoran */ });
    map.on('dragstart', () => setFollow(false));
    map.on('zoomstart', (e) => { if (e.originalEvent) setFollow(false); });
  }

  /** Edificios en 3D (como Pro Maps): colores según el estilo, por debajo de los nombres. */
  function add3d() {
    const st = map.getStyle();
    vecSrc = Object.keys(st.sources || {}).find((k) => st.sources[k].type === 'vector');
    (st.layers || []).filter((l) => l.type === 'fill-extrusion').forEach((l) => map.removeLayer(l.id));
    if (!vecSrc) return;
    const dark = curStyle === 'dark';
    const firstSymbol = ((map.getStyle().layers || []).find((l) => l.type === 'symbol') || {}).id;
    const h = ['coalesce', ['get', 'render_height'], ['get', 'height'], 6];
    map.addLayer({ id: 'b3d', type: 'fill-extrusion', source: vecSrc, 'source-layer': 'building', minzoom: 14.5,
      filter: ['!', ['to-boolean', ['get', 'hide_3d']]], layout: { visibility: cfg.b3d ? 'visible' : 'none' },
      paint: { 'fill-extrusion-color': ['interpolate', ['linear'], h, 0, dark ? '#46557a' : '#e4ddd0', 20, dark ? '#55668f' : '#d8cfbf', 60, dark ? '#6b7fab' : '#c9bea9'],
        'fill-extrusion-height': ['interpolate', ['linear'], ['zoom'], 14.5, 0, 15.2, h],
        'fill-extrusion-base': ['coalesce', ['get', 'render_min_height'], 0], 'fill-extrusion-opacity': 0.92 } }, firstSymbol);
  }

  function setFollow(on) {
    follow = on;
    $('locate').classList.toggle('on', on);
    if (on && map && loc) moveMap(true);
  }
  function moveMap(jump) {
    if (!map || !loc) return;
    const h = map.getContainer().clientHeight;
    const bearing = speed > 8 ? heading : map.getBearing();
    map.easeTo({ center: [loc.lon, loc.lat], bearing, pitch: 55, padding: { top: h * 0.32, bottom: 0, left: 0, right: 0 },
      duration: jump ? 600 : 950, easing: (t) => t });
  }

  $('zIn').addEventListener('click', () => { if (map) map.zoomIn(); });
  $('zOut').addEventListener('click', () => { if (map) map.zoomOut(); });
  $('locate').addEventListener('click', () => { setFollow(true); if (map) map.easeTo({ zoom: 16.3 }); });
  $('street').addEventListener('click', () => runItem(cfg.nav));
  $('speedCard').addEventListener('click', () => runItem(cfg.nav));
  longPress($('mapCard'), () => menu('Mapa', [
    ['🧭  Elegir navegador (ahora: ' + navLabel() + ')', chooseNav],
    [cfg.mapMode === 'own' ? '🗺️  Ver el navegador de verdad en la tarjeta' : '🗺️  Usar el mapa de Pro Inicio',
      () => { cfg.mapMode = cfg.mapMode === 'own' ? 'nav' : 'own'; save(); cardState = ''; drawLayout(); }],
    ['🎨  Personalizar pantalla', openSettings]
  ]));
  function chooseNav() {
    pick({ title: 'Navegador de la tarjeta del mapa', acts: false }, (x) => {
      cfg.nav = x; save(); cardState = ''; winOpenedOnce = false; drawLayout();
      if (!$('ajustes').classList.contains('hide')) renderOpts();
      toast('Navegador: ' + labelOf(x));
    });
  }

  // Calle en la que estás: la calle con nombre más cercana (datos del propio mapa, sin internet extra)
  function findStreet() {
    if (!mapReady || !loc || !vecSrc || !cfg.map) return;
    let feats;
    try { feats = map.querySourceFeatures(vecSrc, { sourceLayer: 'transportation_name' }); } catch (e) { return; }
    const kx = 111320 * Math.cos(loc.lat * Math.PI / 180), ky = 110540;
    let best = null, bestD = 35; // metros
    const segDist = (a, b) => {
      const ax = (a[0] - loc.lon) * kx, ay = (a[1] - loc.lat) * ky, bx = (b[0] - loc.lon) * kx, by = (b[1] - loc.lat) * ky;
      const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
      let t = l2 ? -(ax * dx + ay * dy) / l2 : 0;
      t = Math.max(0, Math.min(1, t));
      return Math.hypot(ax + t * dx, ay + t * dy);
    };
    for (const f of feats) {
      const name = f.properties['name:es'] || f.properties.name;
      if (!name) continue;
      const g = f.geometry, lines = g.type === 'LineString' ? [g.coordinates] : g.type === 'MultiLineString' ? g.coordinates : [];
      for (const ln of lines) for (let i = 1; i < ln.length; i++) {
        const d = segDist(ln[i - 1], ln[i]);
        if (d < bestD) { bestD = d; best = { name, ref: f.properties.ref || '' }; }
      }
    }
    streetKnown = !!best;
    if (best) {
      $('streetName').textContent = best.name;
      $('streetSub').textContent = (best.ref ? best.ref + ' · ' : '') + 'Toca para navegar con ' + navLabel();
      $('spdBigSt').textContent = best.name;
    } else {
      $('streetName').textContent = '¿A dónde vamos?';
      $('streetSub').textContent = 'Toca para navegar con ' + navLabel();
    }
  }
  setInterval(findStreet, 3000);

  // =====================================================================
  //  TARJETA DEL MAPA CON EL NAVEGADOR DE VERDAD
  //  · Pro Maps: se dibuja encajada en la tarjeta (modo tarjeta de Pro Maps 3.11+)
  //  · Otro navegador: se abre en una «ventana libre» del tamaño de la tarjeta (si la pantalla lo permite)
  // =====================================================================
  function cardKind() {
    if (!cfg.map || cfg.mapMode === 'own') return 'own';
    return cfg.nav && cfg.nav.t === 'pro' && cfg.nav.id === 'maps' ? 'promaps' : 'window';
  }
  const navPkg = () => cfg.nav.t === 'pro' ? PRO[cfg.nav.id].pkg : cfg.nav.pkg;
  const navCls = () => cfg.nav.t === 'app' ? (cfg.nav.cls || '') : '';
  let cardState = '';
  let winOpenedOnce = false;

  function cardButtons(list) {
    const box = $('cmBtns');
    box.innerHTML = '';
    list.forEach(([txt, fn, cls]) => {
      const b = document.createElement('button');
      b.className = 'obtn ' + (cls || '');
      b.textContent = txt;
      b.addEventListener('click', fn);
      box.appendChild(b);
    });
  }
  function cardMsg(title, text, buttons) {
    $('cmIcon').innerHTML = tileHtml(cfg.nav);
    $('cmTitle').textContent = title;
    $('cmText').textContent = text || '';
    cardButtons(buttons || []);
  }
  function drawCardMsg() {
    const k = cardKind();
    $('cardMsg').classList.toggle('hide', k === 'own');
    if (k === 'own') return;
    const ownMap = ['Usar el mapa de Pro Inicio', () => { cfg.mapMode = 'own'; save(); drawLayout(); }, 'ghost'];
    if (k === 'promaps') {
      if (cardState === 'noperm') {
        cardMsg('Falta un permiso de Pro Maps',
          'Para verse dentro de esta tarjeta, Pro Maps necesita «Mostrar sobre otras apps».\n1) Toca «Dar permiso».  2) Busca Pro Maps y activa el interruptor.  3) Vuelve atrás.',
          [['Dar permiso', () => A.cardPermission()], ownMap]);
      } else if (cardState === 'old') {
        cardMsg('Actualiza Pro Maps', 'Hace falta Pro Maps 3.11 o más nueva. Ábrela: te avisará de la versión nueva. Luego vuelve aquí.',
          [['Abrir Pro Maps', () => openPro('maps')], ['Reintentar', () => { cardState = ''; drawCardMsg(); A.cardRetry(); }, 'ghost']]);
      } else if (cardState === 'missing') {
        cardMsg('Pro Maps no está instalada', 'Descárgala e instálala; después aparecerá aquí.', [['Descargar Pro Maps', () => openPro('maps')], ownMap]);
      } else if (cardState === 'error') {
        cardMsg('No se pudo abrir Pro Maps', '', [['Reintentar', () => { cardState = ''; drawCardMsg(); A.cardRetry(); }], ownMap]);
      } else {
        cardMsg('Abriendo Pro Maps…', 'Pro Maps aparecerá aquí en unos segundos.', []);
      }
      return;
    }
    // Otro navegador: ventana libre
    const name = navLabel();
    if (!A.freeform()) {
      cardMsg(name + ' en la tarjeta',
        'Tu pantalla tiene que permitir «ventanas libres» (solo una vez):\n1) Ajustes > Información > toca 7 veces «Número de compilación».\n2) Ajustes > Sistema > Opciones de desarrollador > activa «Habilitar ventanas de formato libre».\n3) Reinicia la pantalla.',
        [['Abrir opciones de desarrollador', () => A.devSettings()], ['Abrir ' + name, () => runItem(cfg.nav), 'ghost'], ownMap]);
      return;
    }
    cardMsg(name, 'Se abre en una ventana encima de esta tarjeta. Puedes usarla con normalidad.',
      [['Abrir ' + name + ' aquí', openNavWindow], ownMap]);
    if (!winOpenedOnce) { winOpenedOnce = true; setTimeout(openNavWindow, 1500); }
  }
  function openNavWindow() {
    syncCard(true);
    setTimeout(() => {
      if (!A.has(navPkg())) { toast(navLabel() + ' no está instalada'); return; }
      if (!A.openWindow(navPkg(), navCls())) toast('No se pudo abrir ' + navLabel() + ' en la tarjeta');
    }, 150);
  }

  const overlayOpen = () => !!document.querySelector('.sheet:not(.hide), .modal:not(.hide)');
  let lastCardKey = '';
  /** Le dice a Android dónde está la tarjeta y qué debe ir dentro. */
  function syncCard(force) {
    const k = cardKind();
    let mode = k === 'promaps' ? (overlayOpen() || document.hidden ? 'none' : 'promaps') : k === 'window' ? 'window' : 'none';
    const el = $('mapCard'), r = el.getBoundingClientRect(), d = window.devicePixelRatio || 1;
    if (r.width < 40 || r.height < 40 || el.classList.contains('hide')) mode = 'none';
    const rad = Math.round((parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0) * d);
    const v = [Math.round(r.left * d), Math.round(r.top * d), Math.round(r.width * d), Math.round(r.height * d), rad];
    const key = mode + v.join(',');
    if (!force && key === lastCardKey) return;
    lastCardKey = key;
    try { A.card(mode, v[0], v[1], v[2], v[3], v[4]); } catch (e) { /* versión sin tarjeta */ }
  }
  setInterval(syncCard, 700);
  window.addEventListener('resize', () => setTimeout(syncCard, 120));

  // =====================================================================
  //  MÚSICA (Pro Radio)
  // =====================================================================
  let media = { perm: true, has: false };
  function renderMedia() {
    const m = media, pl = $('player');
    const playing = !!(m.has && m.playing);
    pl.classList.toggle('playing', playing);
    $('icPlay').classList.toggle('hide', playing);
    $('icPause').classList.toggle('hide', !playing);
    if (!m.has) {
      $('pTitle').textContent = m.radio === false ? 'Pro Radio no está instalada' : 'Sin sonar';
      $('pSub').textContent = m.radio === false ? 'Toca aquí para descargarla' : 'Pro Radio';
      $('lLeft').textContent = 'PRO RADIO';
      $('lRight').textContent = m.radio === false ? '' : 'Toca ▶ para la última emisora';
      setArt('', 'PR');
      return;
    }
    const title = m.title || m.album || 'Pro Radio';
    let sub = m.artist || '';
    if (m.album && m.album !== title && m.album !== sub) sub = sub ? sub + ' · ' + m.album : m.album;
    $('pTitle').textContent = title;
    $('pSub').textContent = sub || 'Pro Radio';
    $('lLeft').textContent = playing ? 'EN DIRECTO' : 'PRO RADIO';
    $('lRight').textContent = m.buffering ? 'Conectando…' : playing ? (m.album || '') : 'En pausa';
    setArt(m.art, initials(m.album || m.title || m.app));
  }
  let artNow = null;
  function setArt(src, txt) {
    const key = src || ('t:' + txt);
    if (key === artNow) return;
    artNow = key;
    const box = $('pArt');
    box.innerHTML = '';
    if (src) { const i = new Image(); i.onerror = () => { artNow = null; setArt('', txt); }; i.src = src; box.appendChild(i); }
    else { const s = document.createElement('span'); s.textContent = txt; box.appendChild(s); }
  }
  $('bPlay').addEventListener('click', () => {
    A.media('toggle');
    if (media.has) { media.playing = !media.playing; renderMedia(); }
    else toast('Poniendo la última emisora de Pro Radio…');
  });
  $('bPrev').addEventListener('click', () => A.media('prev'));
  $('bNext').addEventListener('click', () => A.media('next'));
  $('pTap').addEventListener('click', () => openPro('radio'));
  $('wxCard').addEventListener('click', () => loadWeather(true));
  $('spdBig').addEventListener('click', () => runItem(cfg.nav));

  // Mantener pulsada una tarjeta: cambiarla por otra
  ['player', 'appsCard', 'wxCard', 'clockCard', 'spdBig'].forEach((id) => {
    const type = Object.keys(CARD_EL).find((k) => CARD_EL[k] === id);
    const handler = () => {
      const slot = cfg.top === type ? 'top' : 'bottom';
      menu('Tarjeta: ' + CARDS[type], Object.keys(CARDS).filter((c) => c !== type).map((c) => [
        'Cambiar por: ' + CARDS[c], () => { cfg[slot] = c; save(); drawLayout(); }
      ]).concat([['🎨  Personalizar pantalla', openSettings]]));
    };
    if (id === 'appsCard') {
      // En el cuadro de apps, mantener pulsado un botón cambia ese botón; el hueco vacío cambia la tarjeta
      $(id).addEventListener('contextmenu', (e) => { if (e.target === $(id) || e.target === $('appsGrid')) { e.preventDefault(); handler(); } });
    } else {
      longPress($(id), handler);
    }
  });

  // =====================================================================
  //  ELEGIR (acciones, apps Pro, todas las apps)
  // =====================================================================
  let apps = null, pickCb = null, pickOpts = {};
  function closeSheets() { document.querySelectorAll('.sheet').forEach((s) => s.classList.add('hide')); }
  document.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', () => $(b.dataset.close).classList.add('hide')));

  function pick(opts, cb) {
    pickOpts = Object.assign({ acts: true, pros: true }, opts);
    pickCb = cb;
    $('pickTitle').textContent = opts.title || 'Elegir';
    $('buscar').value = '';
    $('picker').classList.remove('hide');
    if (!apps) A.allApps();
    drawPick();
  }
  $('buscar').addEventListener('input', drawPick);

  function pickBtn(it, label) {
    const b = document.createElement('button');
    b.className = 'appbtn';
    b.innerHTML = tileHtml(it) + '<span class="l"></span>';
    b.querySelector('.l').textContent = label || labelOf(it);
    b.addEventListener('click', () => {
      $('picker').classList.add('hide');
      const cb = pickCb; pickCb = null;
      if (cb) cb(it);
    });
    return b;
  }
  function drawPick() {
    const box = $('pickList'), q = $('buscar').value.trim().toLowerCase();
    box.innerHTML = '';
    const section = (title, items) => {
      if (!items.length) return;
      const h = document.createElement('h3'); h.textContent = title; box.appendChild(h);
      const g = document.createElement('div'); g.className = 'appgrid';
      items.forEach((x) => g.appendChild(pickBtn(x)));
      box.appendChild(g);
    };
    const match = (it) => !q || labelOf(it).toLowerCase().includes(q);
    if (pickOpts.acts) section('Botones especiales', Object.keys(ACTS).filter((k) => k !== 'more').map((id) => ({ t: 'act', id })).filter(match));
    if (pickOpts.pros) section('Apps Pro', Object.keys(PRO).map((id) => ({ t: 'pro', id })).filter(match));
    const h = document.createElement('h3'); h.textContent = 'Todas las apps'; box.appendChild(h);
    if (!apps) { const e = document.createElement('div'); e.className = 'empty'; e.textContent = 'Cargando apps…'; box.appendChild(e); return; }
    const list = apps.filter((a) => !q || a.label.toLowerCase().includes(q));
    const g = document.createElement('div'); g.className = 'appgrid';
    list.forEach((a) => g.appendChild(pickBtn({ t: 'app', pkg: a.pkg, cls: a.cls, label: a.label, icon: a.icon })));
    if (!list.length) g.innerHTML = '<div class="empty">No hay apps con ese nombre</div>';
    box.appendChild(g);
  }

  // =====================================================================
  //  PERSONALIZAR
  // =====================================================================
  function openSettings() { closeSheets(); renderOpts(); $('ajustes').classList.remove('hide'); $('opts').scrollTop = 0; }

  function renderOpts() {
    const o = $('opts');
    const keep = o.scrollTop;
    o.innerHTML = '';
    const h3 = (t) => { const h = document.createElement('h3'); h.textContent = t; o.appendChild(h); };
    const row = (title, sub, right) => {
      const d = document.createElement('div'); d.className = 'opt';
      d.innerHTML = '<div><b></b><small></small></div>';
      d.querySelector('b').textContent = title;
      d.querySelector('small').textContent = sub || '';
      if (right) d.appendChild(right);
      o.appendChild(d);
      return d;
    };
    const sw = (on, fn) => {
      const l = document.createElement('label'); l.className = 'sw';
      l.innerHTML = '<input type="checkbox"><span></span>';
      const i = l.querySelector('input'); i.checked = !!on;
      i.addEventListener('change', () => fn(i.checked));
      return l;
    };
    const seg = (opts, val, fn) => {
      const d = document.createElement('div'); d.className = 'seg';
      Object.keys(opts).forEach((k) => {
        const b = document.createElement('button'); b.textContent = opts[k];
        b.className = k === val ? 'on' : '';
        b.addEventListener('click', () => fn(k));
        d.appendChild(b);
      });
      return d;
    };
    const btn = (txt, fn, cls) => { const b = document.createElement('button'); b.className = 'obtn ' + (cls || ''); b.textContent = txt; b.addEventListener('click', fn); return b; };
    const set = (k, v) => { cfg[k] = v; save(); drawAll(); renderOpts(); };
    const slots = (listName, redraw, max) => {
      const d = document.createElement('div'); d.className = 'slotrow';
      cfg[listName].forEach((it, i) => {
        const s = document.createElement('button'); s.className = 'slot';
        s.innerHTML = tileHtml(it) + '<span class="sl"></span>';
        s.querySelector('.sl').textContent = labelOf(it);
        s.addEventListener('click', () => listMenu(listName, i, () => { redraw(); renderOpts(); }));
        d.appendChild(s);
      });
      if (cfg[listName].length < max) {
        const a = document.createElement('button'); a.className = 'slot addslot';
        a.innerHTML = '<div class="ai i-act">' + mono('add') + '</div><span class="sl">Añadir</span>';
        a.addEventListener('click', () => pick({ title: 'Añadir botón', acts: true }, (x) => { cfg[listName].push(x); save(); redraw(); renderOpts(); }));
        d.appendChild(a);
      }
      o.appendChild(d);
    };

    // --- Tema
    h3('Tema de colores');
    const th = document.createElement('div'); th.className = 'themes';
    Object.keys(THEMES).forEach((k) => {
      const t = THEMES[k], b = document.createElement('button');
      b.className = 'th' + (cfg.theme === k ? ' on' : '');
      b.style.background = t[2];
      b.style.color = t[9] ? '#141a2b' : '#f2f5ff';
      b.innerHTML = '<div class="sw4"><i style="background:linear-gradient(160deg,' + t[6][0] + ',' + t[6][2] + ')"></i><i style="background:' + t[4] + '"></i><i style="background:' + t[5] + '"></i></div><span></span>';
      b.querySelector('span').textContent = t[0];
      b.addEventListener('click', () => set('theme', k));
      th.appendChild(b);
    });
    o.appendChild(th);

    // --- Mapa
    h3('Mapa');
    row('Mostrar el mapa', 'Si lo quitas, las tarjetas ocupan su sitio', sw(cfg.map, (v) => set('map', v)));
    row('Navegador', 'El que sale en la tarjeta y abre el botón «Navegar»: ' + navLabel(), btn('Cambiar', chooseNav));
    row('Qué se ve en la tarjeta', cfg.mapMode === 'own' ? 'Mapa propio de Pro Inicio (toca para abrir el navegador)' :
      (cardKind() === 'promaps' ? 'Pro Maps de verdad, encajada en la tarjeta' : navLabel() + ' de verdad, en una ventana encima de la tarjeta'),
      seg({ nav: 'El navegador', own: 'Mapa de Pro Inicio' }, cfg.mapMode, (v) => set('mapMode', v)));
    row('Estilo del mapa', 'Automático: oscuro de noche', seg({ auto: 'Auto', dark: 'Oscuro', light: 'Claro', soft: 'Suave' }, cfg.mapStyle, (v) => set('mapStyle', v)));
    row('Edificios en 3D', '', sw(cfg.b3d, (v) => { cfg.b3d = v; save(); if (mapReady && map.getLayer('b3d')) map.setLayoutProperty('b3d', 'visibility', v ? 'visible' : 'none'); }));
    row('Tarjeta verde de la calle', '', sw(cfg.street, (v) => set('street', v)));
    row('Tarjeta de velocidad', '', sw(cfg.speed, (v) => set('speed', v)));
    row('Botones + − y centrar', '', sw(cfg.mapBtns, (v) => set('mapBtns', v)));

    // --- Tarjetas
    h3('Tarjetas');
    row('Tarjeta de arriba', '', seg(CARDS, cfg.top, (v) => set('top', v)));
    row('Tarjeta de abajo', '', seg(CARDS, cfg.bottom, (v) => set('bottom', v)));
    row('Lado de las tarjetas', 'En horizontal', seg({ right: 'Derecha', left: 'Izquierda' }, cfg.side, (v) => set('side', v)));

    // --- Barra lateral
    h3('Barra lateral');
    row('Mostrar la barra lateral', 'Toca un botón para cambiarlo, moverlo o quitarlo', sw(cfg.rail, (v) => set('rail', v)));
    slots('railItems', drawAll, 8);

    // --- Cuadro de apps
    h3('Cuadro de apps');
    slots('grid', drawGrid, 40);

    // --- Barra de arriba
    h3('Barra de arriba');
    row('Fecha', '', sw(cfg.st.date, (v) => { cfg.st.date = v; save(); drawLayout(); }));
    row('Icono del GPS', '', sw(cfg.st.gps, (v) => { cfg.st.gps = v; save(); drawLayout(); }));
    row('Icono de la conexión', '', sw(cfg.st.net, (v) => { cfg.st.net = v; save(); drawLayout(); }));
    row('Tiempo', '', sw(cfg.st.wx, (v) => { cfg.st.wx = v; save(); drawLayout(); }));

    // --- Sistema
    h3('Sistema');
    const home = A.isHome();
    const hr = row('Pantalla de inicio', home ? '✔ Pro Inicio es la pantalla de inicio' : 'Que el botón «casa» abra Pro Inicio',
      btn(home ? 'Cambiar' : 'Elegir', () => { toast('Elige «Pro Inicio» como aplicación de inicio'); A.homeSettings(); }));
    if (home) hr.querySelector('small').classList.add('ok');
    row('Abrir al encender el coche', 'Pro Inicio aparece sola al arrancar', sw(A.getPref('boot', true), (v) => A.setPref('boot', v)));
    const lr = row('Ubicación', loc ? '✔ Ubicación recibida' : 'Para el mapa, la velocidad y el tiempo', loc ? null : btn('Permitir', () => A.locationPermission()));
    if (loc) lr.querySelector('small').classList.add('ok');
    row('Actualizaciones', 'Versión ' + A.version(), btn('🔄 Buscar', () => { toast('Buscando actualización…'); A.checkUpdate(true); }));
    row('Volver al diseño original', 'Deja botones, tarjetas y tema como al principio', btn('Restablecer', () => menu('¿Restablecer el diseño?', [
      ['Sí, restablecer', () => { const t = cfg.theme; cfg = DEFAULT_CFG(); cfg.theme = t; save(); drawAll(); renderOpts(); toast('Diseño restablecido'); }, 'red']
    ]), 'ghost'));
    const tip = document.createElement('div'); tip.className = 'muted'; tip.style.padding = '8px 4px';
    tip.textContent = 'Truco: mantén pulsado cualquier botón, tarjeta o el mapa para cambiarlo directamente. Mantén pulsada la hora para volver aquí.';
    o.appendChild(tip);
    o.scrollTop = keep;
  }

  // ---------- Actualización ----------
  $('updLater').addEventListener('click', () => $('upd').classList.add('hide'));
  $('updGo').addEventListener('click', () => {
    $('updGo').textContent = 'Descargando…';
    $('updGo').disabled = true;
    A.doUpdate();
  });

  // =====================================================================
  //  LO QUE ANDROID LLAMA
  // =====================================================================
  window.PI = {
    toast,
    onMedia(m) { media = m || { has: false }; renderMedia(); },
    onCard(st) {
      cardState = st === 'ok' ? '' : st;
      drawCardMsg();
      if (st === 'ok') $('cmText').textContent = 'Si Pro Maps no aparece, toca la hora y vuelve.';
    },
    onLocation(lat, lon, kmh, brg, gps) {
      loc = { lat, lon };
      store.set('loc', loc);
      if (gps) lastGps = Date.now();
      speed = (kmh != null && kmh >= 0) ? kmh : -1;
      if (brg != null && brg >= 0 && speed > 3) heading = brg;
      const sp = speed >= 0 ? Math.round(speed) : '--';
      $('spd').textContent = sp; $('spdBigV').textContent = sp;
      if (meMarker) { meMarker.setLngLat([lon, lat]); meMarker.setRotation(heading); }
      if (follow) moveMap(false);
      const moved = !weatherLoc || Math.abs(weatherLoc.lat - lat) > 0.05 || Math.abs(weatherLoc.lon - lon) > 0.05;
      loadWeather(moved);
    },
    onNet(id, ok, body) {
      const w = netWait[id];
      if (!w) return;
      delete netWait[id];
      ok ? w.res(body) : w.rej(new Error(body));
    },
    onApps(list) { apps = list || []; if (!$('picker').classList.contains('hide')) drawPick(); },
    appsChanged() { apps = null; drawGrid(); if (!$('picker').classList.contains('hide')) A.allApps(); },
    onUpdate(u) {
      $('updName').textContent = 'Versión ' + u.name + ' (tienes la ' + A.version() + ')';
      $('updNotes').textContent = u.notes || 'Mejoras y arreglos.';
      $('updGo').textContent = 'Actualizar';
      $('updGo').disabled = false;
      $('upd').classList.remove('hide');
    },
    updateDone() { $('updGo').textContent = 'Actualizar'; $('updGo').disabled = false; },
    onResume() {
      drawGrid(); A.refreshMedia(); loadWeather(false); updNet(); if (map) map.resize();
      if (cardState === 'noperm' || cardState === 'old') { cardState = ''; drawCardMsg(); }
      syncCard(true);
    },
    back() {
      if (!$('upd').classList.contains('hide')) $('upd').classList.add('hide');
      else if (!$('menu').classList.contains('hide')) $('menu').classList.add('hide');
      else if (!$('picker').classList.contains('hide')) $('picker').classList.add('hide');
      else closeSheets();
    },
    home() { ['upd', 'menu'].forEach((id) => $(id).classList.add('hide')); closeSheets(); setFollow(true); }
  };

  window.addEventListener('resize', () => { if (map) map.resize(); });
  showWeather(store.get('weather', null));
  drawAll();
  renderMedia();
  updNet();
  if (loc) loadWeather(true);
})();
