/* Pro Inicio — lógica de la pantalla.
   Habla con Android a través del objeto «Android» (MainActivity.java → Bridge).
   Android responde llamando a PI.onMedia, PI.onLocation, PI.onNet, PI.onApps, PI.onUpdate… */
(function () {
  'use strict';

  // En el navegador del ordenador (para probar) no existe Android: se usa uno de mentira.
  const A = window.Android || {
    version: () => 'prueba', proApps: () => '{"maps":true,"radio":true,"obd":true,"garaje":false}',
    open: () => true, openComp: () => true, allApps: () => setTimeout(() => PI.onApps([]), 200),
    media: () => {}, refreshMedia: () => {}, mediaPermission: () => {}, locationPermission: () => {},
    get: () => {}, openUrl: () => {}, homeSettings: () => {}, isHome: () => false, net: () => 'wifi',
    phone: () => {}, icon: () => '', has: () => true,
    setPref: () => {}, getPref: (k, d) => d, checkUpdate: () => {}, doUpdate: () => {}
  };

  const $ = (id) => document.getElementById(id);
  const store = {
    get(k, d) { try { const v = localStorage.getItem('pi.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem('pi.' + k, JSON.stringify(v)); } catch (e) { /* nada */ } }
  };

  // ---------- Aviso corto ----------
  let toastT = 0;
  function toast(t) {
    const el = $('toast');
    el.textContent = t;
    el.classList.remove('hide');
    clearTimeout(toastT);
    toastT = setTimeout(() => el.classList.add('hide'), 3500);
  }

  // ---------- Las 4 apps de la gama Pro ----------
  const SVG = {
    maps: '<svg viewBox="0 0 48 48"><path d="M24 5 39 41 24 33 9 41Z" fill="#fff"/></svg>',
    radio: '<svg viewBox="0 0 48 48"><rect x="6" y="16" width="36" height="24" rx="5" fill="#fff"/><circle cx="17" cy="28" r="6" fill="rgba(0,0,0,.25)"/><rect x="27" y="23" width="10" height="3" rx="1.5" fill="rgba(0,0,0,.25)"/><rect x="27" y="29" width="10" height="3" rx="1.5" fill="rgba(0,0,0,.25)"/><path d="M12 15 34 7" stroke="#fff" stroke-width="3" stroke-linecap="round"/></svg>',
    obd: '<svg viewBox="0 0 48 48"><path d="M6 32a18 18 0 0 1 36 0" fill="none" stroke="#fff" stroke-width="4" stroke-linecap="round"/><path d="M24 32 33 19" stroke="#fff" stroke-width="4" stroke-linecap="round"/><circle cx="24" cy="32" r="4" fill="#fff"/></svg>',
    garaje: '<svg viewBox="0 0 48 48"><path d="M5 20 24 8l19 12v20H5Z" fill="#fff"/><rect x="12" y="24" width="24" height="16" rx="2" fill="rgba(0,0,0,.25)"/><path d="M12 29h24M12 34h24" stroke="#fff" stroke-width="2"/></svg>',
    more: '<svg viewBox="0 0 24 24"><path d="M5 5h6v6H5zM13 5h6v6h-6zM5 13h6v6H5zM13 13h6v6h-6z" fill="#fff"/></svg>',
    add: '<svg viewBox="0 0 24 24"><path d="M11 4h2v7h7v2h-7v7h-2v-7H4v-2h7z" fill="#fff"/></svg>'
  };
  const PRO = {
    maps:   { pkg: 'com.fserrano.promaps', name: 'Pro Maps',   url: 'https://github.com/franciscoserranoadt/pro-maps/releases/tag/ultima' },
    radio:  { pkg: 'es.proradio.app',      name: 'Pro Radio',  url: 'https://github.com/franciscoserranoadt/pro-radio/releases/tag/ultima' },
    obd:    { pkg: 'es.proobd.app',        name: 'Pro OBD',    url: 'https://github.com/franciscoserranoadt/pro-obd/releases/tag/ultima' },
    garaje: { pkg: 'es.progaraje.app',     name: 'Pro Garaje', url: 'https://github.com/franciscoserranoadt/pro-garaje/releases/tag/ultima' }
  };
  let installed = {};

  function openPro(id) {
    const info = PRO[id];
    if (installed[id] && A.open(info.pkg)) return;
    toast('Abriendo la descarga de ' + info.name + '…');
    A.openUrl(info.url);
  }

  // ---------- Cuadro de apps: 4 Pro + favoritas + Más apps + Añadir ----------
  let favs = store.get('favs', []); // [{pkg, cls, label, icon}]

  function drawGrid() {
    try { installed = JSON.parse(A.proApps()); } catch (e) { installed = {}; }
    const g = $('appsGrid');
    g.innerHTML = '';
    const add = (cls, iconHtml, label, onTap, onLong) => {
      const b = document.createElement('button');
      b.className = 'ab ' + cls;
      b.innerHTML = '<div class="ai">' + iconHtml + '</div><span class="lbl"></span><b class="dl">↓</b>';
      b.querySelector('.lbl').textContent = label;
      b.addEventListener('click', onTap);
      if (onLong) longPress(b, onLong);
      g.appendChild(b);
      return b;
    };
    Object.keys(PRO).forEach((id) => {
      const b = add(installed[id] ? '' : 'off', SVG[id], PRO[id].name, () => openPro(id));
      b.querySelector('.ai').classList.add('i-' + id);
    });
    favs = favs.filter((f) => A.has(f.pkg));
    favs.forEach((f) => {
      const icon = f.icon ? '<img alt="">' : '<span>' + initials(f.label) + '</span>';
      const b = add('', icon, f.label, () => A.openComp(f.pkg, f.cls), () => askRemove(f));
      if (f.icon) b.querySelector('img').src = f.icon;
    });
    add('', SVG.more, 'Más apps', () => openDrawer(false)).querySelector('.ai').classList.add('i-more');
    add('', SVG.add, 'Añadir', () => openDrawer(true)).querySelector('.ai').classList.add('i-add');
  }

  function longPress(el, fn) {
    let t = 0, fired = false;
    el.addEventListener('touchstart', () => { fired = false; t = setTimeout(() => { fired = true; fn(); }, 650); }, { passive: true });
    el.addEventListener('touchend', (e) => { clearTimeout(t); if (fired) e.preventDefault(); });
    el.addEventListener('touchmove', () => clearTimeout(t), { passive: true });
    el.addEventListener('contextmenu', (e) => { e.preventDefault(); fn(); });
    el.addEventListener('click', (e) => { if (fired) { e.stopImmediatePropagation(); fired = false; } }, true);
  }

  let removing = null;
  function askRemove(f) {
    removing = f;
    $('cfTitle').textContent = '¿Quitar ' + f.label + '?';
    $('cfText').textContent = 'Se quita de la pantalla de inicio (la app no se desinstala).';
    $('confirm').classList.remove('hide');
  }
  $('cfNo').addEventListener('click', () => $('confirm').classList.add('hide'));
  $('cfYes').addEventListener('click', () => {
    favs = favs.filter((x) => x !== removing);
    store.set('favs', favs);
    $('confirm').classList.add('hide');
    drawGrid();
  });

  // ---------- Barra lateral ----------
  document.querySelectorAll('.rb').forEach((b) => b.addEventListener('click', () => {
    const a = b.dataset.act;
    if (a === 'home') { closeSheets(); setFollow(true); }
    else if (a === 'maps' || a === 'radio' || a === 'obd') openPro(a);
    else if (a === 'phone') A.phone();
    else if (a === 'apps') openDrawer(false);
    else if (a === 'ajustes') { refreshSettings(); $('ajustes').classList.remove('hide'); }
  }));

  // ---------- Hora y fecha ----------
  const fmtFecha = new Intl.DateTimeFormat('es-ES', { weekday: 'long', day: 'numeric', month: 'long' });
  let lastMin = -1;
  function tick() {
    const d = new Date();
    if (d.getMinutes() !== lastMin) {
      lastMin = d.getMinutes();
      $('hora').textContent = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
      const f = fmtFecha.format(d);
      $('fecha').textContent = f.charAt(0).toUpperCase() + f.slice(1);
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
    if (n !== 'nada' && !mapReady) initMap();
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
  let loc = store.get('loc', null);
  let lastWeather = 0, weatherLoc = null;

  function showWeather(w) {
    if (!w) return;
    const k = WMO[w.code] || ['', '🌡️'];
    $('wIcon').textContent = (!w.day && k[2]) ? k[2] : k[1];
    $('wTemp').textContent = Math.round(w.temp) + '°C';
    $('stWeather').title = [w.city, k[0]].filter(Boolean).join(' · ');
    if (w.city) $('cityName').textContent = w.city;
  }

  async function loadWeather(force) {
    if (!loc) return;
    if (!force && Date.now() - lastWeather < 15 * 60 * 1000) return;
    lastWeather = Date.now();
    weatherLoc = { lat: loc.lat, lon: loc.lon };
    try {
      const u = 'https://api.open-meteo.com/v1/forecast?latitude=' + loc.lat.toFixed(3) + '&longitude=' + loc.lon.toFixed(3) +
        '&current=temperature_2m,weather_code,is_day&timezone=auto';
      const j = JSON.parse(await get(u));
      const w = { temp: j.current.temperature_2m, code: j.current.weather_code, day: j.current.is_day === 1, city: store.get('city', '') };
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
  showWeather(store.get('weather', null));
  setInterval(() => loadWeather(false), 60 * 1000);

  // ---------- Mapa en directo (MapLibre + OpenFreeMap, como Pro Maps) ----------
  const STYLE = { dark: 'https://tiles.openfreemap.org/styles/dark', light: 'https://tiles.openfreemap.org/styles/liberty' };
  let map = null, meMarker = null, mapReady = false, mapLoading = false, follow = true, heading = 0, speed = -1, vecSrc = null;
  let show3d = store.get('b3d', true);

  function loadScript(src) {
    return new Promise((res, rej) => { const s = document.createElement('script'); s.src = src; s.onload = res; s.onerror = rej; document.head.appendChild(s); });
  }
  function loadCss(href) { const l = document.createElement('link'); l.rel = 'stylesheet'; l.href = href; document.head.appendChild(l); }

  async function initMap() {
    if (mapReady || mapLoading) return;
    mapLoading = true;
    if (!window.maplibregl) {
      const libs = [['https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js', 'https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css'],
        ['https://cdn.jsdelivr.net/npm/maplibre-gl@4.7.1/dist/maplibre-gl.js', 'https://cdn.jsdelivr.net/npm/maplibre-gl@4.7.1/dist/maplibre-gl.css']];
      for (const [js, css] of libs) {
        try { loadCss(css); await loadScript(js); break; } catch (e) { /* siguiente */ }
      }
    }
    if (!window.maplibregl) { $('mapOff').classList.remove('hide'); mapLoading = false; return; }
    let style = STYLE.dark;
    try { const r = await fetch(style, { cache: 'no-store' }); if (!r.ok) style = STYLE.light; } catch (e) { style = STYLE.light; }
    const start = loc ? [loc.lon, loc.lat] : [-3.8249, 40.3459]; // Alcorcón por defecto
    try {
      map = new maplibregl.Map({ container: 'map', style, center: start, zoom: 16.3, pitch: 55, maxPitch: 70,
        attributionControl: false, fadeDuration: 0, dragRotate: false, pitchWithRotate: false });
    } catch (e) { $('mapOff').classList.remove('hide'); mapLoading = false; return; }
    const el = document.createElement('div');
    el.className = 'me';
    el.innerHTML = '<svg viewBox="0 0 24 24"><path d="M12 2 20 21 12 16.5 4 21Z"/></svg>';
    meMarker = new maplibregl.Marker({ element: el, rotationAlignment: 'map', pitchAlignment: 'map' }).setLngLat(start).addTo(map);
    map.on('load', () => {
      mapReady = true; mapLoading = false;
      $('mapOff').classList.add('hide');
      add3d();
      if (loc) moveMap(true);
    });
    map.on('error', () => { /* tiles sueltos que fallan: se ignoran */ });
    map.on('dragstart', () => setFollow(false));
    map.on('zoomstart', (e) => { if (e.originalEvent) setFollow(false); });
  }

  /** Edificios en 3D con colores claros para que se vean de noche (como Pro Maps). */
  function add3d() {
    const st = map.getStyle();
    vecSrc = Object.keys(st.sources || {}).find((k) => st.sources[k].type === 'vector');
    (st.layers || []).filter((l) => l.type === 'fill-extrusion').forEach((l) => map.removeLayer(l.id));
    if (!vecSrc) return;
    const firstSymbol = ((map.getStyle().layers || []).find((l) => l.type === 'symbol') || {}).id;
    const h = ['coalesce', ['get', 'render_height'], ['get', 'height'], 6];
    map.addLayer({ id: 'b3d', type: 'fill-extrusion', source: vecSrc, 'source-layer': 'building', minzoom: 14.5,
      filter: ['!', ['to-boolean', ['get', 'hide_3d']]], layout: { visibility: show3d ? 'visible' : 'none' },
      paint: { 'fill-extrusion-color': ['interpolate', ['linear'], h, 0, '#46557a', 20, '#55668f', 60, '#6b7fab'],
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
  $('street').addEventListener('click', () => openPro('maps'));
  $('speedCard').addEventListener('click', () => openPro('maps'));

  // Calle en la que estás: la línea de calle con nombre más cercana (datos del propio mapa, sin internet extra)
  function findStreet() {
    if (!mapReady || !loc || !vecSrc) return;
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
    if (best) {
      $('streetName').textContent = best.name;
      $('streetSub').textContent = (best.ref ? best.ref + ' · ' : '') + 'Toca para navegar con Pro Maps';
    } else if (speed < 0 || !follow) {
      $('streetName').textContent = '¿A dónde vamos?';
      $('streetSub').textContent = 'Toca para abrir Pro Maps';
    }
  }
  setInterval(findStreet, 3000);

  // ---------- Lo que suena (Pro Radio) ----------
  let media = { perm: true, has: false };
  function initials(s) {
    const w = String(s || 'PR').replace(/[^\p{L}\p{N} ]/gu, ' ').trim().split(/\s+/);
    return ((w[0] || 'P')[0] + ((w[1] || w[0] || 'R')[w[1] ? 0 : 1] || '')).toUpperCase();
  }
  function renderMedia() {
    const m = media, pl = $('player');
    const playing = !!(m.has && m.playing);
    pl.classList.toggle('playing', playing);
    $('icPlay').classList.toggle('hide', playing);
    $('icPause').classList.toggle('hide', !playing);
    if (!m.has) {
      $('pTitle').textContent = m.radio === false ? 'Pro Radio no está instalada' : 'Sin sonar';
      $('pSub').textContent = m.radio === false ? 'Tócala en el cuadro de apps para descargarla' : 'Pro Radio';
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

  // ---------- Cajón de apps (todas / elegir favorita) ----------
  let apps = null, picking = false;
  function closeSheets() { document.querySelectorAll('.sheet').forEach((s) => s.classList.add('hide')); }
  document.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', () => $(b.dataset.close).classList.add('hide')));

  function openDrawer(pick) {
    picking = pick;
    $('drawerTitle').textContent = pick ? 'Añadir a inicio' : 'Todas las apps';
    $('drawer').classList.remove('hide');
    $('buscar').value = '';
    if (!apps) A.allApps(); else drawApps();
    if (pick) toast('Toca la app que quieres añadir');
  }
  $('buscar').addEventListener('input', drawApps);

  function drawApps() {
    const g = $('appGrid');
    if (!apps) { g.innerHTML = '<div class="empty">Cargando apps…</div>'; return; }
    const q = $('buscar').value.trim().toLowerCase();
    const list = apps.filter((a) => !q || a.label.toLowerCase().includes(q));
    g.innerHTML = '';
    if (!list.length) { g.innerHTML = '<div class="empty">No hay apps con ese nombre</div>'; return; }
    const frag = document.createDocumentFragment();
    list.forEach((a) => {
      const b = document.createElement('button');
      b.className = 'appbtn';
      if (a.icon) { const i = new Image(); i.src = a.icon; i.loading = 'lazy'; b.appendChild(i); }
      else { const n = document.createElement('div'); n.className = 'noic'; n.textContent = initials(a.label); b.appendChild(n); }
      const s = document.createElement('span'); s.textContent = a.label; b.appendChild(s);
      b.addEventListener('click', () => {
        if (picking) {
          if (!favs.some((f) => f.pkg === a.pkg && f.cls === a.cls)) {
            favs.push({ pkg: a.pkg, cls: a.cls, label: a.label, icon: a.icon });
            store.set('favs', favs);
          }
          closeSheets();
          drawGrid();
          toast(a.label + ' añadida. Para quitarla, mantenla pulsada.');
        } else {
          A.openComp(a.pkg, a.cls);
          setTimeout(closeSheets, 400);
        }
      });
      frag.appendChild(b);
    });
    g.appendChild(frag);
  }

  // ---------- Ajustes ----------
  function refreshSettings() {
    const home = A.isHome();
    $('homeState').textContent = home ? '✔ Pro Inicio es la pantalla de inicio' : 'Que el botón «casa» abra Pro Inicio';
    $('homeState').classList.toggle('ok', home);
    $('oHome').textContent = home ? 'Cambiar' : 'Elegir';
    $('locState').textContent = loc ? '✔ Ubicación recibida' : 'Para el mapa, la velocidad y el tiempo';
    $('locState').classList.toggle('ok', !!loc);
    $('oLoc').classList.toggle('hide', !!loc);
    $('oBoot').checked = A.getPref('boot', true);
    $('o3d').checked = show3d;
    $('verState').textContent = 'Versión ' + A.version();
  }
  $('oHome').addEventListener('click', () => { toast('Elige «Pro Inicio» como aplicación de inicio'); A.homeSettings(); });
  $('oLoc').addEventListener('click', () => A.locationPermission());
  $('oBoot').addEventListener('change', (e) => A.setPref('boot', e.target.checked));
  $('o3d').addEventListener('change', (e) => {
    show3d = e.target.checked; store.set('b3d', show3d);
    if (mapReady && map.getLayer('b3d')) map.setLayoutProperty('b3d', 'visibility', show3d ? 'visible' : 'none');
  });
  $('oUpd').addEventListener('click', () => { toast('Buscando actualización…'); A.checkUpdate(true); });

  // ---------- Actualización ----------
  $('updLater').addEventListener('click', () => $('upd').classList.add('hide'));
  $('updGo').addEventListener('click', () => {
    $('updGo').textContent = 'Descargando…';
    $('updGo').disabled = true;
    A.doUpdate();
  });

  // ---------- Lo que Android llama ----------
  window.PI = {
    toast,
    onMedia(m) { media = m || { has: false }; renderMedia(); },
    onLocation(lat, lon, kmh, brg, gps) {
      loc = { lat, lon };
      store.set('loc', loc);
      if (gps) lastGps = Date.now();
      speed = (kmh != null && kmh >= 0) ? kmh : -1;
      if (brg != null && brg >= 0 && speed > 3) heading = brg;
      $('spd').textContent = speed >= 0 ? Math.round(speed) : '--';
      $('hint').textContent = speed >= 0 ? 'Navegar con Pro Maps' : 'Navegar';
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
    onApps(list) { apps = list || []; drawApps(); },
    appsChanged() { apps = null; drawGrid(); if (!$('drawer').classList.contains('hide')) A.allApps(); },
    onUpdate(u) {
      $('updName').textContent = 'Versión ' + u.name + ' (tienes la ' + A.version() + ')';
      $('updNotes').textContent = u.notes || 'Mejoras y arreglos.';
      $('updGo').textContent = 'Actualizar';
      $('updGo').disabled = false;
      $('upd').classList.remove('hide');
    },
    updateDone() { $('updGo').textContent = 'Actualizar'; $('updGo').disabled = false; },
    onResume() { drawGrid(); A.refreshMedia(); loadWeather(false); updNet(); if (map) map.resize(); if (!$('ajustes').classList.contains('hide')) refreshSettings(); },
    back() {
      if (!$('upd').classList.contains('hide')) $('upd').classList.add('hide');
      else if (!$('confirm').classList.contains('hide')) $('confirm').classList.add('hide');
      else closeSheets();
    },
    home() { $('upd').classList.add('hide'); $('confirm').classList.add('hide'); closeSheets(); setFollow(true); }
  };

  window.addEventListener('resize', () => { if (map) map.resize(); });
  drawGrid();
  renderMedia();
  updNet();
  initMap();
  if (loc) loadWeather(true);
})();
