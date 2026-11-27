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

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

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

    // ---- Música: se conecta directamente al reproductor de Pro Radio (sin permisos especiales) ----
    private MediaController radio;
    private ListenableFuture<MediaController> radioFuture;
    private boolean visible = false;
    private final Player.Listener radioListener = new Player.Listener() {
        @Override public void onEvents(Player player, Player.Events events) { pushMedia(); }
    };

    // ---- Ubicación (para el tiempo) ----
    private LocationManager lm;
    private boolean locationStarted = false;
    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { sendLocation(l); }
        @Override public void onStatusChanged(String p, int s, Bundle e) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    // ---- Tarjeta del mapa: Pro Maps encajada (modo tarjeta) o navegador en ventana libre ----
    private String cardMode = "none";
    private int[] cardRect = null;
    private boolean resumed = false;
    private String cardSent = "";
    private long cardAskedAt = 0;
    private boolean cardAnswered = false;
    private final BroadcastReceiver cardStatus = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            cardAnswered = true;
            String st = i.getStringExtra("status");
            js("PI.onCard && PI.onCard(" + JSONObject.quote(st == null ? "" : st) + ")");
        }
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
        try {
            IntentFilter cf = new IntentFilter("es.proinicio.app.CARD_STATUS");
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(cardStatus, cf, Context.RECEIVER_EXPORTED);
            else registerReceiver(cardStatus, cf);
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
        resumed = true;
        cardSent = "";
        applyCard();
        hideSystemBars();
        // Comprueba actualizaciones al abrir y, como mucho, cada 6 horas
        long now = System.currentTimeMillis();
        if (now - lastUpdateCheck > 6L * 3600 * 1000) {
            lastUpdateCheck = now;
            handler.postDelayed(() -> checkUpdate(false), 4000);
        }
        js("PI.onResume && PI.onResume()");
        if (winWentFull) {
            winWentFull = false;
            handler.postDelayed(() -> js("PI.onCard && PI.onCard('winfull')"), 600);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        sendCard("hide");
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        connectRadio();
        startLocation();
    }

    @Override
    protected void onStop() {
        // Si justo después de abrir el navegador «en la tarjeta» Pro Inicio queda tapada entera,
        // es que la pantalla lo abrió a pantalla completa (no admite ventanas libres)
        if (System.currentTimeMillis() - winLaunchAt < 4000) winWentFull = true;
        visible = false;
        releaseRadio();
        stopLocation();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(cardStatus); } catch (Exception ignored) { }
        sendCard("hide");
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

    // ================= música (Pro Radio) =================

    /** Se conecta al reproductor de Pro Radio, como hacen los mandos del volante. */
    private void connectRadio() {
        if (radio != null || radioFuture != null || !installed(PKG_RADIO)) { pushMedia(); return; }
        try {
            SessionToken token = new SessionToken(this, new ComponentName(PKG_RADIO, PKG_RADIO + ".PlaybackService"));
            final ListenableFuture<MediaController> f = new MediaController.Builder(this, token)
                    .setListener(new MediaController.Listener() {
                        @Override public void onDisconnected(MediaController controller) {
                            radio = null;
                            radioFuture = null;
                            pushMedia();
                            if (visible) handler.postDelayed(MainActivity.this::connectRadio, 5000);
                        }
                    })
                    .buildAsync();
            radioFuture = f;
            f.addListener(() -> {
                try {
                    MediaController c = f.get();
                    if (!visible || radioFuture != f) { c.release(); return; }
                    radio = c;
                    c.addListener(radioListener);
                } catch (Exception e) {
                    radioFuture = null;
                }
                pushMedia();
            }, handler::post);
        } catch (Exception e) {
            radioFuture = null;
            pushMedia();
        }
    }

    private void releaseRadio() {
        try { if (radio != null) radio.removeListener(radioListener); } catch (Exception ignored) { }
        try { if (radioFuture != null) MediaController.releaseFuture(radioFuture); } catch (Exception ignored) { }
        radio = null;
        radioFuture = null;
    }

    private void pushMedia() {
        if (!pageReady) return;
        try {
            JSONObject o = new JSONObject();
            o.put("perm", true);
            o.put("radio", installed(PKG_RADIO));
            MediaController c = radio;
            if (c == null || !c.isConnected() || c.getMediaItemCount() == 0) {
                o.put("has", false);
            } else {
                o.put("has", true);
                o.put("pkg", PKG_RADIO);
                o.put("app", "Pro Radio");
                o.put("playing", c.getPlayWhenReady() && c.getPlaybackState() != Player.STATE_IDLE
                        && c.getPlaybackState() != Player.STATE_ENDED);
                o.put("buffering", c.getPlayWhenReady() && c.getPlaybackState() == Player.STATE_BUFFERING);
                MediaItem item = c.getCurrentMediaItem();
                String station = item != null && item.mediaMetadata.title != null ? item.mediaMetadata.title.toString().trim() : "";
                MediaMetadata live = c.getMediaMetadata();
                String song = live.title != null ? live.title.toString().trim() : "";
                if (song.equals(station)) song = "";
                o.put("title", song.isEmpty() ? (station.isEmpty() ? "Pro Radio" : station) : song);
                o.put("artist", song.isEmpty() ? "" : station);
                o.put("album", station);
                String art = "";
                Uri u = item != null && item.mediaMetadata.artworkUri != null ? item.mediaMetadata.artworkUri : live.artworkUri;
                if (u != null) art = u.toString();
                o.put("art", art);
            }
            js("PI.onMedia(" + o + ")");
        } catch (Exception ignored) { }
    }

    private void mediaCmd(String cmd) {
        MediaController c = radio;
        if (c == null || !c.isConnected()) {
            if ("play".equals(cmd) || "toggle".equals(cmd)) startRadio();
            else connectRadio();
            return;
        }
        try {
            switch (cmd) {
                case "toggle":
                    if (c.getMediaItemCount() == 0) { startRadio(); break; }
                    if (c.getPlayWhenReady()) c.pause();
                    else { if (c.getPlaybackState() == Player.STATE_IDLE) c.prepare(); c.play(); }
                    break;
                case "play":
                    if (c.getMediaItemCount() == 0) { startRadio(); break; }
                    if (c.getPlaybackState() == Player.STATE_IDLE) c.prepare();
                    c.play();
                    break;
                case "pause": c.pause(); break;
                case "next": c.seekToNext(); break;
                case "prev": c.seekToPrevious(); break;
                default: break;
            }
        } catch (Exception ignored) { }
    }

    /** Pro Radio sin emisora cargada: le pide «play» (vuelve la última emisora). Si no arranca, se abre Pro Radio. */
    private void startRadio() {
        if (!installed(PKG_RADIO)) { toast("Pro Radio no está instalada"); return; }
        MediaController c = radio;
        try { if (c != null && c.isConnected()) c.play(); } catch (Exception ignored) { }
        if (c == null) connectRadio();
        handler.postDelayed(() -> {
            MediaController r = radio;
            boolean ok = r != null && r.isConnected() && r.getMediaItemCount() > 0 && r.getPlayWhenReady();
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

    /** GPS cada segundo mientras se ve la pantalla (para el mapa y la velocidad); se para al salir. */
    private void startLocation() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        if (locationStarted) return;
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, locListener, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException ignored) { }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException ignored) { }
        locationStarted = true;
        sendLastLocation();
    }

    private void stopLocation() {
        try { lm.removeUpdates(locListener); } catch (Exception ignored) { }
        locationStarted = false;
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

    private long lastGpsFix = 0;

    private void sendLocation(Location l) {
        boolean gps = LocationManager.GPS_PROVIDER.equals(l.getProvider());
        if (gps) lastGpsFix = System.currentTimeMillis();
        // Si el GPS va bien, se ignora la posición aproximada de la red (salta mucho)
        else if (System.currentTimeMillis() - lastGpsFix < 10000) return;
        double kmh = l.hasSpeed() ? l.getSpeed() * 3.6 : -1;
        double brg = l.hasBearing() ? l.getBearing() : -1;
        js("PI.onLocation(" + l.getLatitude() + "," + l.getLongitude() + "," + kmh + "," + brg + "," + gps + ")");
    }

    // ================= tarjeta del mapa =================

    private void applyCard() {
        if (!resumed) return;
        if ("promaps".equals(cardMode) && cardRect != null) sendCard("show");
        else sendCard("hide");
    }

    /** Órdenes a Pro Maps (modo tarjeta). Solo se manda si cambia algo. */
    private void sendCard(String op) {
        String key = op + (cardRect != null && "show".equals(op) ? cardRect[0] + "," + cardRect[1] + "," + cardRect[2] + "," + cardRect[3] : "");
        if (key.equals(cardSent)) return;
        // «Quitar» solo si antes se pidió «mostrar» (así no se despierta Pro Maps sin necesidad)
        if ("hide".equals(op) && !cardSent.startsWith("show")) { cardSent = key; return; }
        cardSent = key;
        try {
            Intent i = new Intent("com.fserrano.promaps.CARD");
            i.setComponent(new ComponentName(PKG_MAPS, PKG_MAPS + ".CardReceiver"));
            i.putExtra("op", op);
            i.putExtra("reply", getPackageName());
            if (cardRect != null) {
                i.putExtra("x", cardRect[0]); i.putExtra("y", cardRect[1]);
                i.putExtra("w", cardRect[2]); i.putExtra("h", cardRect[3]); i.putExtra("r", cardRect[4]);
            }
            sendBroadcast(i);
        } catch (Exception ignored) { }
        if ("show".equals(op)) {
            cardAnswered = false;
            cardAskedAt = System.currentTimeMillis();
            final long asked = cardAskedAt;
            handler.postDelayed(() -> {
                if (!cardAnswered && asked == cardAskedAt && resumed && "promaps".equals(cardMode)) {
                    js("PI.onCard && PI.onCard(" + JSONObject.quote(installed(PKG_MAPS) ? "old" : "missing") + ")");
                }
            }, 4000);
        }
    }

    private long winLaunchAt = 0;
    private boolean winWentFull = false;

    /** Pide «ventana libre» (WINDOWING_MODE_FREEFORM = 5). Es una función oculta: se desbloquea con HiddenApiBypass. */
    private void setFreeform(android.app.ActivityOptions o) {
        try {
            if (Build.VERSION.SDK_INT >= 28) org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityOptions;");
        } catch (Throwable ignored) { }
        try {
            java.lang.reflect.Method m = android.app.ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
            m.invoke(o, 5);
            return;
        } catch (Throwable ignored) { }
        try {
            if (Build.VERSION.SDK_INT >= 28) org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(android.app.ActivityOptions.class, o, "setLaunchWindowingMode", 5);
        } catch (Throwable ignored) { }
    }

    /** ¿Permite este aparato ventanas libres (formato libre)? */
    private boolean freeformOk() {
        try {
            if (getPackageManager().hasSystemFeature("android.software.freeform_window_management")) return true;
            return Settings.Global.getInt(getContentResolver(), "enable_freeform_support", 0) != 0
                    || Settings.Global.getInt(getContentResolver(), "force_resizable_activities", 0) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Abre una app en una ventana libre del tamaño y la posición de la tarjeta. */
    private boolean openInWindow(String pkg, String cls) {
        if (cardRect == null) return false;
        try {
            Intent i;
            if (cls != null && !cls.isEmpty()) {
                i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName(pkg, cls));
            } else {
                i = getPackageManager().getLaunchIntentForPackage(pkg);
            }
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            o.setLaunchBounds(new android.graphics.Rect(cardRect[0], cardRect[1], cardRect[0] + cardRect[2], cardRect[1] + cardRect[3]));
            setFreeform(o);
            winLaunchAt = System.currentTimeMillis();
            startActivity(i, o.toBundle());
            return true;
        } catch (Exception e) {
            return false;
        }
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
        public void refreshMedia() { handler.post(() -> { if (visible) connectRadio(); pushMedia(); }); }

        @JavascriptInterface
        public void mediaPermission() { }

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

        /** Tipo de conexión: wifi, movil o nada. */
        @JavascriptInterface
        public String net() {
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) return "nada";
                android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null) return "nada";
                if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
                if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) return "movil";
                return "otra";
            } catch (Exception e) {
                return "nada";
            }
        }

        /** Teléfono: abre el marcador (en la Junsun, su app de teléfono Bluetooth). */
        @JavascriptInterface
        public void phone() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_DIAL);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    toast("No se encuentra la app de teléfono");
                }
            });
        }

        /** Icono de una app (para los favoritos). */
        @JavascriptInterface
        public String icon(String pkg) {
            try {
                return bitmapB64(drawableToBitmap(getPackageManager().getApplicationIcon(pkg), 96), 96, true);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean has(String pkg) { return installed(pkg); }

        /** Tarjeta del mapa: modo (none / promaps / window) y su posición en la página (píxeles reales). */
        @JavascriptInterface
        public void card(String mode, int x, int y, int w, int h, int r) {
            handler.post(() -> {
                int[] loc = new int[2];
                try { web.getLocationOnScreen(loc); } catch (Exception ignored) { }
                cardMode = mode == null ? "none" : mode;
                cardRect = new int[]{x + loc[0], y + loc[1], w, h, r};
                applyCard();
            });
        }

        /** Permiso «Mostrar sobre otras apps» de Pro Maps. */
        @JavascriptInterface
        public void cardPermission() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + PKG_MAPS));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    toast("Abre Ajustes > Apps > Pro Maps > Mostrar sobre otras apps");
                }
            });
        }

        @JavascriptInterface
        public void cardRetry() { handler.post(() -> { cardSent = ""; applyCard(); }); }

        @JavascriptInterface
        public boolean freeform() { return freeformOk(); }

        @JavascriptInterface
        public boolean openWindow(String pkg, String cls) {
            final boolean[] ok = {false};
            ok[0] = openInWindow(pkg, cls);
            return ok[0];
        }

        @JavascriptInterface
        public void devSettings() {
            runOnUiThread(() -> {
                // Junsun esconde el menú: se prueba a abrirlo directamente por varios caminos
                Intent[] tries = {
                    new Intent().setClassName("com.android.settings", "com.android.settings.Settings$DevelopmentSettingsDashboardActivity"),
                    new Intent().setClassName("com.android.settings", "com.android.settings.Settings$DevelopmentSettingsActivity"),
                    new Intent().setClassName("com.android.settings", "com.android.settings.DevelopmentSettings"),
                    new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                    new Intent("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
                };
                for (Intent i : tries) {
                    try {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        return;
                    } catch (Exception ignored) { }
                }
                toast("Esta pantalla no deja abrir las opciones de desarrollador");
            });
        }

        /** Datos para saber si esta pantalla admite ventanas libres. */
        @JavascriptInterface
        public String diag() {
            try {
                JSONObject o = new JSONObject();
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("release", Build.VERSION.RELEASE);
                o.put("feature", getPackageManager().hasSystemFeature("android.software.freeform_window_management"));
                o.put("freeform", Settings.Global.getInt(getContentResolver(), "enable_freeform_support", 0));
                o.put("resizable", Settings.Global.getInt(getContentResolver(), "force_resizable_activities", 0));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
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
