package es.proinicio.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // Paquetes de las apps de la gama Pro. Pro OBD y Pro Garaje deberán usar estos nombres cuando se hagan.
    static final String PKG_MAPS = "com.fserrano.promaps";
    static final String PKG_RADIO = "es.proradio.app";
    static final String PKG_OBD = "es.proobd.app";
    static final String PKG_GARAJE = "es.progaraje.app";

    private WebView web;
    private boolean pageReady = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private SharedPreferences prefs;
    private long lastUpdateCheck = 0;

    // ---- Música (lo que suena en Pro Radio) ----
    private MediaSessionManager msm;
    private MediaController mediaCtl;
    private boolean mediaStarted = false;
    private String lastArtKey = "";
    private String lastArt = "";
    private final MediaController.Callback mediaCb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { pushMedia(); }
        @Override public void onPlaybackStateChanged(PlaybackState st) { pickController(); pushMedia(); }
        @Override public void onSessionDestroyed() { pickController(); pushMedia(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsCb =
            list -> { pickController(); pushMedia(); };

    // ---- Ubicación (para el tiempo) ----
    private LocationManager lm;
    private boolean locationStarted = false;
    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { sendLocation(l); }
        @Override public void onStatusChanged(String p, int s, Bundle e) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    // ---- Apps instaladas o quitadas ----
    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { js("PI.appsChanged && PI.appsChanged()"); }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("proinicio", MODE_PRIVATE);
        msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B1220);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                pageReady = true;
                pushMedia();
                sendLastLocation();
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        hideSystemBars();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(pkgReceiver, f);
        } catch (Exception ignored) { }

        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, 5);
        } else {
            startLocation();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        startMedia();
        if (!locationStarted) startLocation();
        // Comprueba actualizaciones al abrir y, como mucho, cada 6 horas
        long now = System.currentTimeMillis();
        if (now - lastUpdateCheck > 6L * 3600 * 1000) {
            lastUpdateCheck = now;
            handler.postDelayed(() -> checkUpdate(false), 4000);
        }
        js("PI.onResume && PI.onResume()");
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsCb); } catch (Exception ignored) { }
        try { if (mediaCtl != null) mediaCtl.unregisterCallback(mediaCb); } catch (Exception ignored) { }
        try { lm.removeUpdates(locListener); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        if (Updater.ACTION_STATUS.equals(intent.getAction())) {
            int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(confirm); } catch (Exception ignored) { }
                }
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                toast("No se pudo instalar" + (m != null ? ": " + m : ""));
            }
            return;
        }
        // Botón «casa»: cerrar paneles y volver a la pantalla principal
        js("PI.home && PI.home()");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // Es la pantalla de inicio: «atrás» solo cierra paneles, nunca sale
        js("PI.back && PI.back()");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 5) startLocation();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    // ================= utilidades =================

    private void js(String code) {
        if (!pageReady || web == null) return;
        runOnUiThread(() -> { try { web.evaluateJavascript(code, null); } catch (Exception ignored) { } });
    }

    private void toast(String t) {
        runOnUiThread(() -> Toast.makeText(this, t, Toast.LENGTH_LONG).show());
    }

    private boolean installed(String pkg) {
        try { return getPackageManager().getLaunchIntentForPackage(pkg) != null; } catch (Exception e) { return false; }
    }

    private static String bitmapB64(Bitmap b, int max, boolean png) {
        try {
            int w = b.getWidth(), h = b.getHeight();
            float k = Math.min(1f, (float) max / Math.max(w, h));
            Bitmap sc = k < 1 ? Bitmap.createScaledBitmap(b, Math.max(1, (int) (w * k)), Math.max(1, (int) (h * k)), true) : b;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            sc.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 82, o);
            return (png ? "data:image/png;base64," : "data:image/jpeg;base64,")
                    + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private static Bitmap drawableToBitmap(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    // ================= música =================

    private ComponentName listenerName() { return new ComponentName(this, MediaListener.class); }

    private boolean hasMediaPerm() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void startMedia() {
        if (mediaStarted || msm == null || !hasMediaPerm()) { pushMedia(); return; }
        try {
            msm.addOnActiveSessionsChangedListener(sessionsCb, listenerName(), handler);
            mediaStarted = true;
        } catch (Exception ignored) { }
        pickController();
        pushMedia();
    }

    /** Elige qué reproductor mostrar: primero Pro Radio; si no, el que esté sonando. */
    private void pickController() {
        if (msm == null || !hasMediaPerm()) return;
        try {
            List<MediaController> l = msm.getActiveSessions(listenerName());
            MediaController best = null, playing = null, radio = null;
            for (MediaController c : l) {
                if (c.getPackageName().equals(getPackageName())) continue;
                if (PKG_RADIO.equals(c.getPackageName()) && radio == null) radio = c;
                PlaybackState st = c.getPlaybackState();
                if (playing == null && st != null && st.getState() == PlaybackState.STATE_PLAYING) playing = c;
                if (best == null) best = c;
            }
            if (radio != null) best = radio;
            else if (playing != null) best = playing;
            boolean same = mediaCtl != null && best != null && mediaCtl.getSessionToken().equals(best.getSessionToken());
            if (!same) {
                if (mediaCtl != null) try { mediaCtl.unregisterCallback(mediaCb); } catch (Exception ignored) { }
                if (best != null) best.registerCallback(mediaCb, handler);
                mediaCtl = best;
            }
        } catch (Exception ignored) { }
    }

    private void pushMedia() {
        if (!pageReady) return;
        try {
            JSONObject o = new JSONObject();
            o.put("perm", hasMediaPerm());
            o.put("radio", installed(PKG_RADIO));
            MediaController c = mediaCtl;
            if (c == null) {
                o.put("has", false);
            } else {
                o.put("has", true);
                o.put("pkg", c.getPackageName());
                String app = c.getPackageName();
                try {
                    app = getPackageManager().getApplicationLabel(
                            getPackageManager().getApplicationInfo(c.getPackageName(), 0)).toString();
                } catch (Exception ignored) { }
                o.put("app", app);
                PlaybackState st = c.getPlaybackState();
                int state = st == null ? 0 : st.getState();
                o.put("playing", state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING);
                o.put("buffering", state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING);
                MediaMetadata m = c.getMetadata();
                String title = "", artist = "", album = "", art = "";
                if (m != null) {
                    title = nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
                    if (title.isEmpty()) title = nz(m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                    artist = nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
                    if (artist.isEmpty()) artist = nz(m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
                    album = nz(m.getString(MediaMetadata.METADATA_KEY_ALBUM));
                    String key = c.getPackageName() + "|" + title + "|" + album;
                    if (key.equals(lastArtKey)) {
                        art = lastArt;
                    } else {
                        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                        art = b != null ? bitmapB64(b, 240, false) : "";
                        if (b != null || m.getString(MediaMetadata.METADATA_KEY_TITLE) != null) {
                            lastArtKey = key;
                            lastArt = art;
                        }
                    }
                }
                o.put("title", title);
                o.put("artist", artist);
                o.put("album", album);
                o.put("art", art);
            }
            js("PI.onMedia(" + o + ")");
        } catch (Exception ignored) { }
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    private void mediaCmd(String cmd) {
        MediaController c = mediaCtl;
        if (c == null) {
            if ("play".equals(cmd) || "toggle".equals(cmd)) startRadio();
            return;
        }
        try {
            MediaController.TransportControls t = c.getTransportControls();
            PlaybackState st = c.getPlaybackState();
            boolean playing = st != null && (st.getState() == PlaybackState.STATE_PLAYING
                    || st.getState() == PlaybackState.STATE_BUFFERING);
            switch (cmd) {
                case "toggle": if (playing) t.pause(); else t.play(); break;
                case "play": t.play(); break;
                case "pause": t.pause(); break;
                case "next": t.skipToNext(); break;
                case "prev": t.skipToPrevious(); break;
                default: break;
            }
        } catch (Exception ignored) { }
    }

    /** Pro Radio cerrada: le manda «play» (vuelve a la última emisora). Si no arranca, se abre Pro Radio. */
    private void startRadio() {
        if (!installed(PKG_RADIO)) { toast("Pro Radio no está instalada"); return; }
        try {
            for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON);
                i.setComponent(new ComponentName(PKG_RADIO, "androidx.media3.session.MediaButtonReceiver"));
                i.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY));
                sendBroadcast(i);
            }
        } catch (Exception ignored) { }
        handler.postDelayed(() -> {
            pickController();
            MediaController c = mediaCtl;
            boolean ok = c != null && PKG_RADIO.equals(c.getPackageName());
            if (!ok) openApp(PKG_RADIO);
            pushMedia();
        }, 3000);
    }

    private boolean openApp(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ================= ubicación =================

    private void startLocation() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 10 * 60 * 1000L, 1000f, locListener, Looper.getMainLooper());
            }
            locationStarted = true;
        } catch (SecurityException | IllegalArgumentException ignored) { }
        sendLastLocation();
    }

    private void sendLastLocation() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) { }
        if (best != null) sendLocation(best);
    }

    private void sendLocation(Location l) {
        js("PI.onLocation(" + l.getLatitude() + "," + l.getLongitude() + ")");
    }

    // ================= actualizaciones =================

    private void checkUpdate(boolean manual) {
        pool.execute(() -> {
            Updater.Info inf = Updater.latest();
            if (inf == null) {
                if (manual) js("PI.toast('No se pudo comprobar. ¿Hay internet?')");
                return;
            }
            if (inf.code > Updater.installedCode(this)) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("code", inf.code);
                    o.put("name", inf.name);
                    o.put("notes", inf.notes);
                    js("PI.onUpdate(" + o + ")");
                } catch (Exception ignored) { }
            } else if (manual) {
                js("PI.toast(" + JSONObject.quote("Ya tienes la última versión (" + Updater.installedName(this) + ")") + ")");
            }
        });
    }

    private void doUpdate() {
        runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                toast("Activa «Permitir de esta fuente» y vuelve a pulsar Actualizar");
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) { }
                js("PI.updateDone && PI.updateDone()");
                return;
            }
            pool.execute(() -> {
                String err = Updater.downloadAndInstall(this);
                if (err != null) toast(err);
                js("PI.updateDone && PI.updateDone()");
            });
        });
    }

    // ================= puente con la página =================

    private class Bridge {

        @JavascriptInterface
        public String version() { return Updater.installedName(MainActivity.this); }

        /** Estado de las 4 apps Pro: instalada o no. */
        @JavascriptInterface
        public String proApps() {
            try {
                JSONObject o = new JSONObject();
                o.put("maps", installed(PKG_MAPS));
                o.put("radio", installed(PKG_RADIO));
                o.put("obd", installed(PKG_OBD));
                o.put("garaje", installed(PKG_GARAJE));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public boolean open(String pkg) { return openApp(pkg); }

        @JavascriptInterface
        public boolean openComp(String pkg, String cls) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                i.setComponent(new ComponentName(pkg, cls));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(i);
                return true;
            } catch (Exception e) {
                return openApp(pkg);
            }
        }

        /** Lista de todas las apps (con icono). La respuesta llega a PI.onApps(lista). */
        @JavascriptInterface
        public void allApps() {
            pool.execute(() -> {
                try {
                    PackageManager pm = getPackageManager();
                    Intent main = new Intent(Intent.ACTION_MAIN);
                    main.addCategory(Intent.CATEGORY_LAUNCHER);
                    List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
                    List<JSONObject> out = new ArrayList<>();
                    for (ResolveInfo r : list) {
                        String pkg = r.activityInfo.packageName;
                        if (pkg.equals(getPackageName())) continue;
                        JSONObject o = new JSONObject();
                        o.put("pkg", pkg);
                        o.put("cls", r.activityInfo.name);
                        o.put("label", String.valueOf(r.loadLabel(pm)));
                        String icon = "";
                        try { icon = bitmapB64(drawableToBitmap(r.loadIcon(pm), 96), 96, true); } catch (Exception ignored) { }
                        o.put("icon", icon);
                        out.add(o);
                    }
                    Collections.sort(out, (a, b) -> a.optString("label").compareToIgnoreCase(b.optString("label")));
                    JSONArray arr = new JSONArray();
                    for (JSONObject o : out) arr.put(o);
                    js("PI.onApps(" + arr + ")");
                } catch (Exception e) {
                    js("PI.onApps([])");
                }
            });
        }

        @JavascriptInterface
        public void media(String cmd) { handler.post(() -> mediaCmd(cmd)); }

        @JavascriptInterface
        public void refreshMedia() { handler.post(() -> { startMedia(); pickController(); pushMedia(); }); }

        @JavascriptInterface
        public void mediaPermission() {
            try {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        }

        @JavascriptInterface
        public void locationPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 23) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION}, 5);
                }
            });
        }

        /** Petición web (para el tiempo). La respuesta llega a PI.onNet(id, ok, texto). */
        @JavascriptInterface
        public void get(int id, String url) {
            pool.execute(() -> {
                HttpURLConnection con = null;
                boolean ok = false;
                String body = "";
                try {
                    con = (HttpURLConnection) new URL(url).openConnection();
                    con.setConnectTimeout(10000);
                    con.setReadTimeout(15000);
                    con.setRequestProperty("User-Agent", "ProInicio/1.0");
                    int code = con.getResponseCode();
                    InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
                    if (in != null) {
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                        in.close();
                        body = buf.toString("UTF-8");
                    }
                    ok = code >= 200 && code < 300;
                } catch (Exception e) {
                    body = String.valueOf(e.getMessage());
                } finally {
                    if (con != null) con.disconnect();
                }
                js("PI.onNet(" + id + "," + ok + "," + JSONObject.quote(body) + ")");
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                toast("No hay navegador para abrir el enlace");
            }
        }

        @JavascriptInterface
        public void homeSettings() {
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
            }
        }

        /** ¿Es Pro Inicio la pantalla de inicio elegida? */
        @JavascriptInterface
        public boolean isHome() {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_HOME);
                ResolveInfo r = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
                return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void setPref(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }

        @JavascriptInterface
        public boolean getPref(String key, boolean def) { return prefs.getBoolean(key, def); }

        @JavascriptInterface
        public void checkUpdate(boolean manual) { MainActivity.this.checkUpdate(manual); }

        @JavascriptInterface
        public void doUpdate() { MainActivity.this.doUpdate(); }
    }
}
