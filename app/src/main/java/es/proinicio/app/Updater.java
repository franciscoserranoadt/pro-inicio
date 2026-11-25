package es.proinicio.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Busca versiones nuevas en GitHub (release «ultima») y las instala, igual que Pro Maps y Pro Radio. */
public final class Updater {

    public static final String ACTION_STATUS = "es.proinicio.app.INSTALL_STATUS";
    private static final String BASE = "https://github.com/franciscoserranoadt/pro-inicio/releases/download/ultima/";

    public static final class Info {
        public final int code;
        public final String name;
        public final String notes;
        Info(int code, String name, String notes) { this.code = code; this.name = name; this.notes = notes; }
    }

    private Updater() { }

    @SuppressWarnings("deprecation")
    public static int installedCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String installedName(Context c) {
        try {
            String n = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return n == null ? "?" : n;
        } catch (Exception e) {
            return "?";
        }
    }

    private static HttpURLConnection open(String url, int readTimeout) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setInstanceFollowRedirects(true);
        con.setConnectTimeout(10000);
        con.setReadTimeout(readTimeout);
        con.setUseCaches(false);
        con.setRequestProperty("Cache-Control", "no-cache");
        con.setRequestProperty("User-Agent", "ProInicio-Updater");
        return con;
    }

    /** Versión publicada o null si no se puede consultar. Llamar desde un hilo secundario. */
    public static Info latest() {
        HttpURLConnection con = null;
        try {
            con = open(BASE + "version.txt?t=" + System.currentTimeMillis(), 15000);
            if (con.getResponseCode() != 200) return null;
            InputStream in = con.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
            String txt = buf.toString("UTF-8").trim();
            int nl = txt.indexOf('\n');
            String first = nl >= 0 ? txt.substring(0, nl) : txt;
            String notes = nl >= 0 ? txt.substring(nl + 1).trim() : "";
            String[] parts = first.trim().split("\\s+");
            int code = Integer.parseInt(parts[0]);
            return new Info(code, parts.length > 1 ? parts[1] : String.valueOf(code), notes);
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Descarga la APK y abre la pantalla de instalar. Hilo secundario. Devuelve un error o null. */
    public static String downloadAndInstall(Activity a) {
        HttpURLConnection con = null;
        try {
            con = open(BASE + "ProInicio.apk", 60000);
            if (con.getResponseCode() != 200) return "No se pudo descargar (error " + con.getResponseCode() + ")";
            PackageInstaller installer = a.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(a.getPackageName());
            int id = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(id);
            try {
                long len = con.getContentLength();
                InputStream in = con.getInputStream();
                OutputStream out = session.openWrite("ProInicio.apk", 0, len > 0 ? len : -1);
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                session.fsync(out);
                out.close();
                in.close();
                Intent i = new Intent(a, MainActivity.class)
                        .setAction(ACTION_STATUS)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent pi = PendingIntent.getActivity(a, 77, i, flags);
                session.commit(pi.getIntentSender());
            } finally {
                session.close();
            }
            return null;
        } catch (Exception e) {
            return "Error al actualizar: " + e.getMessage();
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
