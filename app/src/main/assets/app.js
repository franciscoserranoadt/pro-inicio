/* Pro Inicio — lógica de la pantalla.
   Habla con Android a través del objeto «Android» (MainActivity.java → Bridge).
   Android responde llamando a PI.onMedia, PI.onLocation, PI.onNet, PI.onApps, PI.onUpdate… */
(function () {
  'use strict';

  // En el navegador del ordenador (para probar) no existe Android: se usa uno de mentira.
  const A = window.Android || {
    version: () => 'prueba', proApps: () => '{"maps":true,"radio":true,"obd":false,"garaje":false}',
    open: () => true, openComp: () => true, allApps: () => setTimeout(() => PI.onApps([]), 200),
    media: () => {}, refreshMedia: () => {}, mediaPermission: () => {}, locationPermission: () => {},
    get: () => {}, openUrl: () => {}, homeSettings: () => {}, isHome: () => false,
    setPref: () => {}, getPref: (k, d) => d, checkUpdate: () => {}, doUpdate: () => {}
  };

  const $ = (id) => document.getElementById(id);
  const store = {
    get(k, d) { try { const v = localStorage.getItem('pi.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem('pi.' + k, JSON.stringify(v)); } catch (e) { /* nada */ } }
  };

  // ---------- Las 4 apps de la gama Pro ----------
  const PRO = {
    maps:   { pkg: 'com.fserrano.promaps', name: 'Pro Maps',   url: 'https://github.com/franciscoserranoadt/pro-maps/releases/tag/ultima' },
    radio:  { pkg: 'es.proradio.app',      name: 'Pro Radio',  url: 'https://github.com/franciscoserranoadt/pro-radio/releases/tag/ultima' },
    obd:    { pkg: 'es.proobd.app',        name: 'Pro OBD',    url: null },
    garaje: { pkg: 'es.progaraje.app',     name: 'Pro Garaje', url: null }
  };
  let installed = {};

  function refreshTiles() {
    try { installed = JSON.parse(A.proApps()); } catch (e) { installed = {}; }
    document.querySelectorAll('.tile').forEach((t) => {
      const id = t.dataset.app, info = PRO[id], ok = !!installed[id];
      t.classList.toggle('off', !ok);
      t.querySelector('.badge').textContent = ok ? '' : (info.url ? 'Descargar' : 'Próximamente');
    });
  }

  document.querySelectorAll('.tile').forEach((t) => {
    t.addEventListener('click', () => {
      const id = t.dataset.app, info = PRO[id];
      if (installed[id] && A.open(info.pkg)) return;
      if (info.url) { toast('Abriendo la descarga de ' + info.name + '…'); A.openUrl(info.url); }
      else toast(info.name + ' todavía no está hecha. ¡Próximamente!');
    });
  });

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
  let lastWeather = 0;

  function showWeather(w) {
    if (!w) return;
    const k = WMO[w.code] || ['', '🌡️'];
    $('wIcon').textContent = (!w.day && k[2]) ? k[2] : k[1];
    $('wTemp').textContent = Math.round(w.temp) + '°';
    const parts = [];
    if (w.city) parts.push(w.city);
    if (k[0]) parts.push(k[0]);
    if (w.max != null) parts.push(Math.round(w.max) + '° / ' + Math.round(w.min) + '°');
    $('wDesc').textContent = parts.join(' · ');
  }

  async function loadWeather(force) {
    if (!loc) return;
    if (!force && Date.now() - lastWeather < 15 * 60 * 1000) return;
    lastWeather = Date.now();
    try {
      const u = 'https://api.open-meteo.com/v1/forecast?latitude=' + loc.lat.toFixed(3) + '&longitude=' + loc.lon.toFixed(3) +
        '&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto';
      const j = JSON.parse(await get(u));
      const w = { temp: j.current.temperature_2m, code: j.current.weather_code, day: j.current.is_day === 1,
        max: j.daily.temperature_2m_max[0], min: j.daily.temperature_2m_min[0], city: store.get('city', '') };
      // Nombre del sitio (solo si nos hemos movido)
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
      if (!store.get('weather', null)) $('wDesc').textContent = 'Sin datos del tiempo';
    }
  }
  showWeather(store.get('weather', null));
  if (!loc) $('wDesc').textContent = 'Esperando ubicación…';
  setInterval(() => loadWeather(false), 60 * 1000);

  // ---------- Lo que suena (Pro Radio) ----------
  let media = { perm: true, has: false };
  function initials(s) {
    const w = String(s || 'PR').replace(/[^\p{L}\p{N} ]/gu, ' ').trim().split(/\s+/);
    return ((w[0] || 'P')[0] + ((w[1] || w[0] || 'R')[w[1] ? 0 : 1] || '')).toUpperCase();
  }
  function renderMedia() {
    const m = media, pl = $('player');
    pl.classList.toggle('noperm', !m.perm);
    $('pPerm').classList.toggle('hide', !!m.perm);
    pl.classList.toggle('playing', !!(m.has && m.playing));
    $('icPlay').classList.toggle('hide', !!(m.has && m.playing));
    $('icPause').classList.toggle('hide', !(m.has && m.playing));
    if (!m.perm) {
      $('pApp').textContent = 'Pro Radio';
      $('pTitle').textContent = 'Lo que suena';
      $('pSub').textContent = 'Hace falta un permiso (solo una vez)';
      setArt('', 'PR');
      return;
    }
    if (!m.has) {
      $('pApp').textContent = 'Pro Radio';
      $('pTitle').textContent = m.radio === false ? 'Pro Radio no está instalada' : 'Sin sonar';
      $('pSub').textContent = m.radio === false ? 'Toca el botón Pro Radio para descargarla' : 'Toca ▶ para la última emisora';
      setArt('', 'PR');
      return;
    }
    $('pApp').textContent = m.app || 'Música';
    const title = m.title || m.album || 'Sonando';
    let sub = m.artist || '';
    if (m.album && m.album !== title && m.album !== sub) sub = sub ? sub + ' · ' + m.album : m.album;
    if (m.buffering) sub = 'Conectando…';
    else if (!m.playing) sub = (sub ? sub + ' · ' : '') + 'En pausa';
    $('pTitle').textContent = title;
    $('pSub').textContent = sub || ' ';
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
    if (!media.perm) { A.mediaPermission(); return; }
    A.media('toggle');
    if (media.has) { media.playing = !media.playing; renderMedia(); } // respuesta inmediata
    else toast('Poniendo la última emisora de Pro Radio…');
  });
  $('bPrev').addEventListener('click', () => A.media('prev'));
  $('bNext').addEventListener('click', () => A.media('next'));
  $('pPerm').addEventListener('click', () => { toast('Busca «Pro Inicio» en la lista y actívalo'); A.mediaPermission(); });
  $('pTap').addEventListener('click', () => {
    if (!media.perm) { A.mediaPermission(); return; }
    const pkg = media.has ? media.pkg : PRO.radio.pkg;
    if (!A.open(pkg)) { toast('Pro Radio no está instalada'); }
  });

  // ---------- Cajón de apps ----------
  let apps = null;
  function openSheet(id) { $(id).classList.remove('hide'); }
  function closeSheets() { document.querySelectorAll('.sheet').forEach((s) => s.classList.add('hide')); }
  document.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', () => $(b.dataset.close).classList.add('hide')));

  $('btnApps').addEventListener('click', () => {
    openSheet('drawer');
    $('buscar').value = '';
    if (!apps) A.allApps(); else drawApps();
  });
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
      b.addEventListener('click', () => { A.openComp(a.pkg, a.cls); setTimeout(closeSheets, 400); });
      frag.appendChild(b);
    });
    g.appendChild(frag);
  }

  // ---------- Ajustes ----------
  $('btnAjustes').addEventListener('click', () => { refreshSettings(); openSheet('ajustes'); });
  function refreshSettings() {
    const home = A.isHome();
    $('homeState').textContent = home ? '✔ Pro Inicio es la pantalla de inicio' : 'Que el botón «casa» abra Pro Inicio';
    $('homeState').classList.toggle('ok', home);
    $('oHome').textContent = home ? 'Cambiar' : 'Elegir';
    $('locState').textContent = loc ? '✔ Ubicación recibida' : 'Usa el GPS para saber el tiempo';
    $('locState').classList.toggle('ok', !!loc);
    $('oLoc').classList.toggle('hide', !!loc);
    $('oBoot').checked = A.getPref('boot', true);
    $('verState').textContent = 'Versión ' + A.version();
  }
  $('oHome').addEventListener('click', () => { toast('Elige «Pro Inicio» como aplicación de inicio'); A.homeSettings(); });
  $('oLoc').addEventListener('click', () => A.locationPermission());
  $('oBoot').addEventListener('change', (e) => A.setPref('boot', e.target.checked));
  $('oUpd').addEventListener('click', () => { toast('Buscando actualización…'); A.checkUpdate(true); });

  // ---------- Actualización ----------
  $('updLater').addEventListener('click', () => $('upd').classList.add('hide'));
  $('updGo').addEventListener('click', () => {
    $('updGo').textContent = 'Descargando…';
    $('updGo').disabled = true;
    A.doUpdate();
  });

  // ---------- Aviso corto ----------
  let toastT = 0;
  function toast(t) {
    const el = $('toast');
    el.textContent = t;
    el.classList.remove('hide');
    clearTimeout(toastT);
    toastT = setTimeout(() => el.classList.add('hide'), 3500);
  }

  // ---------- Lo que Android llama ----------
  window.PI = {
    toast,
    onMedia(m) { media = m || { has: false }; if (media.perm === undefined) media.perm = true; renderMedia(); },
    onLocation(lat, lon) {
      const moved = !loc || Math.abs(loc.lat - lat) > 0.02 || Math.abs(loc.lon - lon) > 0.02;
      loc = { lat, lon };
      store.set('loc', loc);
      loadWeather(moved);
    },
    onNet(id, ok, body) {
      const w = netWait[id];
      if (!w) return;
      delete netWait[id];
      ok ? w.res(body) : w.rej(new Error(body));
    },
    onApps(list) { apps = list || []; drawApps(); },
    appsChanged() { apps = null; refreshTiles(); if (!$('drawer').classList.contains('hide')) A.allApps(); },
    onUpdate(u) {
      $('updName').textContent = 'Versión ' + u.name + ' (tienes la ' + A.version() + ')';
      $('updNotes').textContent = u.notes || 'Mejoras y arreglos.';
      $('updGo').textContent = 'Actualizar';
      $('updGo').disabled = false;
      $('upd').classList.remove('hide');
    },
    updateDone() { $('updGo').textContent = 'Actualizar'; $('updGo').disabled = false; },
    onResume() { refreshTiles(); A.refreshMedia(); loadWeather(false); if (!$('ajustes').classList.contains('hide')) refreshSettings(); },
    back() { if (!$('upd').classList.contains('hide')) $('upd').classList.add('hide'); else closeSheets(); },
    home() { $('upd').classList.add('hide'); closeSheets(); }
  };

  refreshTiles();
  renderMedia();
  if (loc) loadWeather(true);
})();
