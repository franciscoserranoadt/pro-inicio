package es.proinicio.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Abre Pro Inicio al encender el coche (si está activado en Ajustes). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (!c.getSharedPreferences("proinicio", Context.MODE_PRIVATE).getBoolean("boot", true)) return;
        try {
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
