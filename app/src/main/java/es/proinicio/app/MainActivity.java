package es.proinicio.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
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
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // Paquetes de las apps de la gama Pro. Pro OBD y Pro Garaje deberán usar estos nombres cuando se hagan.
    static final String PKG_MAPS = "com.fserrano.promaps";
    static final String PKG_RADIO = "es.proradio.app";
    static final String PKG_OBD = "es.proobd.app";
    static final String PKG_GARAJE = "es.progaraje.app";

    private WebView web;
    private FrameLayout root;

    // ---- Widgets de Android en las tarjetas (como cualquier pantalla de inicio) ----
    static final int HOST_ID = 0x5049;
    static final int REQ_BIND = 71, REQ_CONF = 72;
    private AppWidgetManager awm;
    private AppWidgetHost host;
    private final Map<String, WidgetFrame> wgViews = new HashMap<>();
    private final Map<String, String> wgState = new HashMap<>();
    private String pendSlot = null;
    private int pendId = -1;

    // ---- Rutinas al arrancar: encender el coche (o despertar la pantalla tras un rato apagada) ----
    static final String EXTRA_BOOT = "pi_boot";
    static final long WAKE_MIN_OFF = 10 * 60 * 1000L;   // apagada al menos 10 min = arranque nuevo
    private String pendingStart = null;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            long now = android.os.SystemClock.elapsedRealtime();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                prefs.edit().putLong("screenOff", System.currentTimeMillis()).apply();
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                long off = prefs.getLong("screenOff", 0);
                if (off > 0 && System.currentTimeMillis() - off >= WAKE_MIN_OFF) {
                    prefs.edit().putLong("screenOff", 0).apply();
                    handler.postDelayed(() -> startEvent("wake"), 2500);
                }
            }
        }
    };

    /** Avisa a la página de que el coche acaba de arrancar (la página decide qué rutina toca). */
    private void startEvent(String kind) {
        long last = prefs.getLong("startAt", 0);
        if (System.currentTimeMillis() - last < 2 * 60 * 1000L) return; // ya avisado (encendido + despertar a la vez)
        prefs.edit().putLong("startAt", System.currentTimeMillis()).apply();
        if (!pageReady) { pendingStart = kind; return; }
        js("PI.onStart && PI.onStart(" + JSONObject.quote(kind) + ")");
    }

    // ---- Música de otras apps (Deezer, YouTube…): teclas multimedia, sin permisos ----
    private AudioManager audio;
    private boolean extMusic = false;
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
                if (pendingStart != null) {
                    final String k = pendingStart;
                    pendingStart = null;
                    handler.postDelayed(() -> js("PI.onStart && PI.onStart(" + JSONObject.quote(k) + ")"), 1500);
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.addJavascriptInterface(new ProSync.Js(this, web), "ProSync");
        root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try {
            awm = AppWidgetManager.getInstance(this);
            host = new AppWidgetHost(getApplicationContext(), HOST_ID);
        } catch (Exception ignored) { }
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

        try {
            IntentFilter sf = new IntentFilter();
            sf.addAction(Intent.ACTION_SCREEN_ON);
            sf.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenReceiver, sf);
        } catch (Exception ignored) { }
        // Arranque: abierta por BootReceiver, o la pantalla se acaba de encender (menos de 3 min)
        Intent st = getIntent();
        if ((st != null && st.getBooleanExtra(EXTRA_BOOT, false))
                || android.os.SystemClock.elapsedRealtime() < 3 * 60 * 1000L) {
            startEvent("boot");
        }

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
        try { if (host != null) host.startListening(); } catch (Exception ignored) { }
        connectRadio();
        handler.removeCallbacks(musicWatch);
        handler.postDelayed(musicWatch, 1500);
        startLocation();
    }

    @Override
    protected void onStop() {
        // Si justo después de abrir el navegador «en la tarjeta» Pro Inicio queda tapada entera,
        // es que la pantalla lo abrió a pantalla completa (no admite ventanas libres)
        if (System.currentTimeMillis() - winLaunchAt < 4000) winWentFull = true;
        visible = false;
        handler.removeCallbacks(musicWatch);
        try { if (host != null) host.stopListening(); } catch (Exception ignored) { }
        releaseRadio();
        stopLocation();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(cardStatus); } catch (Exception ignored) { }
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
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
        if (intent.getBooleanExtra(EXTRA_BOOT, false)) { startEvent("boot"); return; }
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

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_BIND && req != REQ_CONF) return;
        if (pendSlot == null || pendId < 0) return;
        if (res != RESULT_OK) {
            try { host.deleteAppWidgetId(pendId); } catch (Exception ignored) { }
            widgetResult(pendSlot, null, req == REQ_BIND ? "nobind" : "cancel");
            pendSlot = null; pendId = -1;
            return;
        }
        if (req == REQ_BIND) configureWidget(pendSlot, pendId);
        else finishWidget(pendSlot, pendId);
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
            o.put("ext", extMusic);
            o.put("extPlaying", extMusic && musicActive() && !radioPlaying());
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

    private boolean radioPlaying() {
        MediaController c = radio;
        try { return c != null && c.isConnected() && c.getMediaItemCount() > 0 && c.getPlayWhenReady(); } catch (Exception e) { return false; }
    }

    private boolean musicActive() {
        try { return audio != null && audio.isMusicActive(); } catch (Exception e) { return false; }
    }

    /** Cada 3 s mira si suena música de otra app (Deezer, YouTube…). Así los botones ⏮ ⏯ ⏭ van a esa app. */
    private boolean lastExtPlaying = false;
    private final Runnable musicWatch = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            boolean rp = radioPlaying(), active = musicActive();
            if (rp) extMusic = false;
            else if (active) extMusic = true;
            boolean ep = extMusic && active && !rp;
            if (ep != lastExtPlaying) { lastExtPlaying = ep; pushMedia(); }
            handler.postDelayed(this, 3000);
        }
    };

    /** Tecla multimedia (como los botones de unos auriculares): la recibe la app que está sonando o la última que sonó. */
    private void mediaKey(int code) {
        try {
            long t = android.os.SystemClock.uptimeMillis();
            audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
        } catch (Exception ignored) { }
        handler.postDelayed(this::pushMedia, 900);
    }

    private void mediaCmd(String cmd) {
        // Si suena (o sonó la última) otra app, los botones van a esa app
        if (extMusic && !radioPlaying()) {
            switch (cmd) {
                case "toggle": mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return;
                case "play": mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); return;
                case "pause": mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); return;
                case "next": mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); return;
                case "prev": mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return;
                default: break;
            }
        }
        if ("radio".equals(cmd)) { extMusic = false; if (musicActive()) mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); handler.postDelayed(() -> mediaCmd("play"), 600); return; }
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

    /** Pone una memoria concreta de Pro Radio (por su id). Si Pro Radio está cerrada, la abre por dentro primero. */
    private void playStation(String mediaId, int tries) {
        extMusic = false;
        if (!installed(PKG_RADIO)) { toast("Pro Radio no está instalada"); return; }
        MediaController c = radio;
        if (c == null || !c.isConnected()) {
            connectRadio();
            if (tries < 5) handler.postDelayed(() -> playStation(mediaId, tries + 1), 1500);
            else startRadio();
            return;
        }
        try {
            if (musicActive() && !radioPlaying()) mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
            if (c.getMediaItemCount() == 0) {
                c.play(); // Pro Radio carga sus memorias (como al pulsar play en el volante)
                if (tries < 5) handler.postDelayed(() -> playStation(mediaId, tries + 1), 1500);
                return;
            }
            int idx = -1;
            for (int i = 0; i < c.getMediaItemCount(); i++) {
                if (c.getMediaItemAt(i).mediaId.equals(mediaId)) { idx = i; break; }
            }
            if (idx >= 0 && idx != c.getCurrentMediaItemIndex()) c.seekToDefaultPosition(idx);
            if (c.getPlaybackState() == Player.STATE_IDLE) c.prepare();
            c.play();
        } catch (Exception ignored) { }
        handler.postDelayed(this::pushMedia, 800);
    }

    /** Memorias de Pro Radio (para elegir en las rutinas). Llega a PI.onStations(lista). */
    private void sendStations(int tries) {
        MediaController c = radio;
        if ((c == null || !c.isConnected()) && tries < 4) {
            connectRadio();
            handler.postDelayed(() -> sendStations(tries + 1), 1200);
            return;
        }
        JSONArray arr = new JSONArray();
        try {
            if (c != null && c.isConnected()) {
                for (int i = 0; i < c.getMediaItemCount(); i++) {
                    MediaItem it = c.getMediaItemAt(i);
                    JSONObject o = new JSONObject();
                    o.put("id", it.mediaId);
                    o.put("name", it.mediaMetadata.title != null ? it.mediaMetadata.title.toString() : ("Memoria " + (i + 1)));
                    arr.put(o);
                }
            }
        } catch (Exception ignored) { }
        js("PI.onStations && PI.onStations(" + arr + ")");
    }

    /** WhatsApp con el mensaje ya escrito para un número (queda tocar Enviar). */
    private boolean openWhatsApp(String phone, String text) {
        String num = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        Uri u = Uri.parse("https://api.whatsapp.com/send?phone=" + num + "&text=" + Uri.encode(text == null ? "" : text));
        for (String pkg : new String[]{"com.whatsapp", "com.whatsapp.w4b", null}) {
            try {
                if (pkg != null && !installed(pkg)) continue;
                Intent i = new Intent(Intent.ACTION_VIEW, u);
                if (pkg != null) i.setPackage(pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    /** Abre el navegador con la ruta a un sitio (Pro Maps acepta «geo:»; Waze y Google Maps también). */
    private boolean openNav(String pkg, double lat, double lon, String name) {
        String q = lat + "," + lon + (name == null || name.isEmpty() ? "" : "(" + name.replace("(", "").replace(")", "") + ")");
        Uri u = Uri.parse("geo:" + lat + "," + lon + "?q=" + Uri.encode(q));
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            if (pkg != null && !pkg.isEmpty() && installed(pkg)) i.setPackage(pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
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

    // ================= widgets de Android =================

    /** Marco del widget: deja usar el widget con normalidad y, al mantenerlo pulsado, abre el menú de la tarjeta. */
    private class WidgetFrame extends FrameLayout {
        String slot;
        int id;
        AppWidgetHostView hv;
        int radius = 0, lw = -1, lh = -1;
        private float dx, dy;
        private boolean fired = false;
        private final int slop;
        private final Runnable lp = () -> {
            fired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            js("PI.widgetMenu && PI.widgetMenu(" + JSONObject.quote(slot) + ")");
        };

        WidgetFrame(Context c, String slot) {
            super(c);
            this.slot = slot;
            slop = ViewConfiguration.get(c).getScaledTouchSlop() * 2;
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
            });
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    fired = false; dx = e.getX(); dy = e.getY();
                    removeCallbacks(lp); postDelayed(lp, 650);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getX() - dx) > slop || Math.abs(e.getY() - dy) > slop) removeCallbacks(lp);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(lp);
                    break;
                default: break;
            }
            return fired;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { removeCallbacks(lp); fired = false; }
            return true;
        }
    }

    private void widgetResult(String slot, JSONObject ok, String err) {
        try {
            JSONObject o = ok != null ? ok : new JSONObject().put("error", err);
            js("PI.onWidget && PI.onWidget(" + JSONObject.quote(slot) + "," + o + ")");
        } catch (Exception ignored) { }
    }

    private AppWidgetProviderInfo findProvider(ComponentName cn) {
        try {
            for (AppWidgetProviderInfo p : awm.getInstalledProviders()) if (p.provider.equals(cn)) return p;
        } catch (Exception ignored) { }
        return null;
    }

    /** Paso 1: pedir el widget. Android pregunta una sola vez «¿Permitir a Pro Inicio crear widgets?». */
    private void addWidget(String slot, String prov) {
        if (awm == null || host == null) { widgetResult(slot, null, "nohost"); return; }
        ComponentName cn = ComponentName.unflattenFromString(prov);
        if (cn == null || findProvider(cn) == null) { widgetResult(slot, null, "missing"); return; }
        int id;
        try { id = host.allocateAppWidgetId(); } catch (Exception e) { widgetResult(slot, null, "nohost"); return; }
        pendSlot = slot; pendId = id;
        boolean ok = false;
        try { ok = awm.bindAppWidgetIdIfAllowed(id, cn); } catch (Exception ignored) { }
        if (ok) { configureWidget(slot, id); return; }
        try {
            Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, cn);
            startActivityForResult(i, REQ_BIND);
        } catch (Exception e) {
            try { host.deleteAppWidgetId(id); } catch (Exception ignored) { }
            pendSlot = null; pendId = -1;
            widgetResult(slot, null, "nobind");
        }
    }

    /** Paso 2: algunos widgets tienen su propia pantalla de ajustes (elegir calendario, ciudad…). */
    private void configureWidget(String slot, int id) {
        AppWidgetProviderInfo info = null;
        try { info = awm.getAppWidgetInfo(id); } catch (Exception ignored) { }
        if (info == null) { widgetResult(slot, null, "error"); pendSlot = null; pendId = -1; return; }
        if (info.configure == null) { finishWidget(slot, id); return; }
        pendSlot = slot; pendId = id;
        try {
            host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONF, null);
        } catch (Exception e) {
            try {
                Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
                i.setComponent(info.configure);
                i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                startActivityForResult(i, REQ_CONF);
            } catch (Exception e2) {
                // No deja abrir sus ajustes: se pone tal cual
                finishWidget(slot, id);
            }
        }
    }

    /** Paso 3: guardado. Si en esa tarjeta había otro widget, se borra. */
    private void finishWidget(String slot, int id) {
        pendSlot = null; pendId = -1;
        int old = prefs.getInt("wg_" + slot, -1);
        if (old > 0 && old != id) {
            try { host.deleteAppWidgetId(old); } catch (Exception ignored) { }
            removeWidgetView(slot);
        }
        prefs.edit().putInt("wg_" + slot, id).apply();
        try {
            AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("label", info != null ? String.valueOf(info.loadLabel(getPackageManager())) : "Widget");
            o.put("pkg", info != null ? info.provider.getPackageName() : "");
            widgetResult(slot, o, null);
        } catch (Exception e) {
            widgetResult(slot, null, "error");
        }
    }

    private void removeWidgetView(String slot) {
        WidgetFrame f = wgViews.remove(slot);
        wgState.remove(slot);
        if (f != null) try { root.removeView(f); } catch (Exception ignored) { }
    }

    /** Coloca (o esconde) el widget encima del hueco de su tarjeta. Medidas en píxeles reales de la página. */
    private void showWidget(String slot, int id, int x, int y, int w, int h, int r, boolean vis) {
        String key = id + ":" + x + "," + y + "," + w + "," + h + "," + r + "," + vis;
        if (key.equals(wgState.get(slot))) return;
        wgState.put(slot, key);
        WidgetFrame f = wgViews.get(slot);
        if (id <= 0 || prefs.getInt("wg_" + slot, -1) != id) {
            if (f != null) f.setVisibility(View.GONE);
            return;
        }
        AppWidgetProviderInfo info = null;
        try { info = awm.getAppWidgetInfo(id); } catch (Exception ignored) { }
        if (info == null) {
            // La app del widget se ha desinstalado
            removeWidgetView(slot);
            prefs.edit().remove("wg_" + slot).apply();
            js("PI.widgetGone && PI.widgetGone(" + JSONObject.quote(slot) + ")");
            return;
        }
        if (f == null || f.id != id) {
            if (f != null) removeWidgetView(slot);
            wgState.put(slot, key);
            f = new WidgetFrame(this, slot);
            f.id = id;
            try {
                f.hv = host.createView(getApplicationContext(), id, info);
                f.hv.setPadding(0, 0, 0, 0);
                f.addView(f.hv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            } catch (Exception e) {
                return;
            }
            root.addView(f, new FrameLayout.LayoutParams(Math.max(1, w), Math.max(1, h)));
            wgViews.put(slot, f);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) f.getLayoutParams();
        if (lp.width != w || lp.height != h) { lp.width = Math.max(1, w); lp.height = Math.max(1, h); f.setLayoutParams(lp); }
        f.setX(x); f.setY(y);
        if (f.radius != r) { f.radius = r; f.invalidateOutline(); }
        if (f.lw != w || f.lh != h) {
            f.lw = w; f.lh = h;
            float d = getResources().getDisplayMetrics().density;
            int wd = Math.round(w / d), hd = Math.round(h / d);
            try { f.hv.updateAppWidgetSize(null, wd, hd, wd, hd); } catch (Exception ignored) { }
        }
        f.setVisibility(vis ? View.VISIBLE : View.GONE);
    }

    private static Bitmap drawableToBitmapFit(Drawable d, int max) {
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) { w = max; h = max; }
        float k = Math.min(1f, (float) max / Math.max(w, h));
        int bw = Math.max(1, Math.round(w * k)), bh = Math.max(1, Math.round(h * k));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, bw, bh);
        d.draw(c);
        return b;
    }

    // ================= teléfono =================

    private static final String PHONE_RX = "(?i).*(tel[eé]fono|phone|marcador|llamada|dialer|bt ?phone|bluetooth|manos libres).*";

    /** Abre la app del teléfono. Primero la elegida; si no, la que se llame «Teléfono», «Bluetooth»…; si no, el marcador. */
    private String openPhone() {
        String pkg = prefsStr("phonePkg"), cls = prefsStr("phoneCls");
        if (!pkg.isEmpty()) {
            if (openComponent(pkg, cls)) return pkg;
        }
        PackageManager pm = getPackageManager();
        try {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            ResolveInfo best = null;
            int bestScore = 0;
            for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
                String p = r.activityInfo.packageName;
                if (p.equals(getPackageName()) || p.startsWith("com.android.settings")) continue;
                if (p.contains("projection.gearhead") || p.contains("androidauto")) continue; // Android Auto no es el teléfono
                String label = String.valueOf(r.loadLabel(pm));
                if (!label.matches(PHONE_RX) && !p.matches("(?i).*(dialer|\\.bt\\b|bluetooth|btphone|\\.phone).*")) continue;
                int score = label.matches("(?i).*(tel[eé]fono|phone|marcador|dialer).*") ? 3 : 1;
                if (p.matches("(?i).*(dialer|contacts|phone).*")) score++;
                if (score > bestScore) { bestScore = score; best = r; }
            }
            if (best != null && openComponent(best.activityInfo.packageName, best.activityInfo.name)) return best.activityInfo.packageName;
        } catch (Exception ignored) { }
        // No se usa el marcador «por defecto» de Android: en la Junsun abre los ajustes de Android Auto.
        // Si no se encuentra la app, Pro Inicio pregunta cuál es (y la recuerda).
        return "";
    }

    private String prefsStr(String k) { String v = prefs.getString(k, ""); return v == null ? "" : v; }

    private boolean openComponent(String pkg, String cls) {
        if (cls == null || cls.isEmpty()) return openApp(pkg);
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

        /** Teléfono: abre la app del teléfono (en la Junsun, su app de teléfono Bluetooth). Devuelve "" si no encuentra ninguna. */
        @JavascriptInterface
        public String phone() { return openPhone(); }

        /** Guarda la app que abre el botón Teléfono (pkg vacío = buscarla sola). */
        @JavascriptInterface
        public void setPhoneApp(String pkg, String cls) {
            prefs.edit().putString("phonePkg", pkg == null ? "" : pkg).putString("phoneCls", cls == null ? "" : cls).apply();
        }

        @JavascriptInterface
        public String phoneApp() { return prefsStr("phonePkg"); }

        /** Lista de widgets instalados. La respuesta llega a PI.onWidgets(lista). */
        @JavascriptInterface
        public void widgetList() {
            pool.execute(() -> {
                JSONArray arr = new JSONArray();
                try {
                    PackageManager pm = getPackageManager();
                    int dens = getResources().getDisplayMetrics().densityDpi;
                    float d = getResources().getDisplayMetrics().density;
                    List<JSONObject> out = new ArrayList<>();
                    for (AppWidgetProviderInfo p : awm.getInstalledProviders()) {
                        String pkg = p.provider.getPackageName();
                        if (pkg.equals(getPackageName())) continue;
                        JSONObject o = new JSONObject();
                        o.put("prov", p.provider.flattenToString());
                        o.put("pkg", pkg);
                        o.put("label", String.valueOf(p.loadLabel(pm)));
                        String app = pkg;
                        try { app = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); } catch (Exception ignored) { }
                        o.put("app", app);
                        o.put("w", Math.round(p.minWidth / d));
                        o.put("h", Math.round(p.minHeight / d));
                        String img = "";
                        try {
                            Drawable pv = p.loadPreviewImage(MainActivity.this, dens);
                            if (pv != null) img = bitmapB64(drawableToBitmapFit(pv, 220), 220, true);
                        } catch (Throwable ignored) { }
                        o.put("preview", img);
                        String icon = "";
                        try { icon = bitmapB64(drawableToBitmap(pm.getApplicationIcon(pkg), 72), 72, true); } catch (Exception ignored) { }
                        o.put("icon", icon);
                        out.add(o);
                    }
                    Collections.sort(out, (a, b) -> {
                        int c = a.optString("app").compareToIgnoreCase(b.optString("app"));
                        return c != 0 ? c : a.optString("label").compareToIgnoreCase(b.optString("label"));
                    });
                    for (JSONObject o : out) arr.put(o);
                } catch (Exception ignored) { }
                js("PI.onWidgets(" + arr + ")");
            });
        }

        @JavascriptInterface
        public void widgetAdd(String slot, String prov) { handler.post(() -> addWidget(slot, prov)); }

        @JavascriptInterface
        public void widgetRemove(String slot) {
            handler.post(() -> {
                int id = prefs.getInt("wg_" + slot, -1);
                if (id > 0) try { host.deleteAppWidgetId(id); } catch (Exception ignored) { }
                prefs.edit().remove("wg_" + slot).apply();
                removeWidgetView(slot);
            });
        }

        /** Dónde va el widget de una tarjeta (píxeles de la página) y si se ve. */
        @JavascriptInterface
        public void widgetShow(String slot, int id, int x, int y, int w, int h, int r, boolean vis) {
            handler.post(() -> showWidget(slot, id, x, y, w, h, r, vis));
        }

        /** Una memoria concreta de Pro Radio. */
        @JavascriptInterface
        public void radioStation(String mediaId) { handler.post(() -> playStation(mediaId == null ? "" : mediaId, 0)); }

        @JavascriptInterface
        public void radioStations() { handler.post(() -> sendStations(0)); }

        /** Seguir con la última música de otra app (Deezer, YouTube…). */
        @JavascriptInterface
        public void resumeOther() { handler.post(() -> { extMusic = true; mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); }); }

        @JavascriptInterface
        public boolean whatsapp(String phone, String text) { return openWhatsApp(phone, text); }

        @JavascriptInterface
        public boolean hasWhatsApp() { return installed("com.whatsapp") || installed("com.whatsapp.w4b"); }

        @JavascriptInterface
        public boolean navTo(String pkg, double lat, double lon, String name) { return openNav(pkg, lat, lon, name); }

        /** Pone Pro Radio (y pausa la música de otra app). */
        @JavascriptInterface
        public void playRadio() { handler.post(() -> mediaCmd("radio")); }

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
