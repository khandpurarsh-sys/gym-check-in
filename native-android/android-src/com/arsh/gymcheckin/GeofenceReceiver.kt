package com.arsh.gymcheckin

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val transition = event.geofenceTransition
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER && transition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        val cfg = GymGeofenceManager.config(context) ?: return
        val now = System.currentTimeMillis()
        val p = GymGeofenceManager.prefs(context)
        val last = p.getString("gymGeofenceLast", null)
        if (last != null) {
            val parts = last.split(":")
            if (parts.size == 2 && parts[0] == transition.toString() &&
                now - (parts[1].toLongOrNull() ?: 0L) < 180_000L) return
        }
        p.edit().putString("gymGeofenceLast", "$transition:$now").apply()
        var lat = cfg.lat
        var lon = cfg.lon
        event.triggeringLocation?.let {
            lat = it.latitude
            lon = it.longitude
        }
        val pending = goAsync()
        Thread {
            try {
                postEvent(context, cfg, transition == Geofence.GEOFENCE_TRANSITION_ENTER, lat, lon)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun postEvent(context: Context, cfg: GymGeofenceManager.Cfg, checkIn: Boolean, lat: Double, lon: Double) {
        val endpoint = if (checkIn) "gym_check_in" else "gym_check_out"
        val body = JSONObject()
        body.put("p_code", cfg.code)
        if (checkIn) {
            body.put("p_lat", lat)
            body.put("p_lon", lon)
        }
        try {
            val conn = (URL(GymGeofenceManager.RPC_BASE + endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("apikey", GymGeofenceManager.RPC_KEY)
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val ok = try { JSONObject(text).optBoolean("ok") } catch (e: Exception) { false }
            if (ok) showNotification(context, if (checkIn) "Checked in at the gym" else "Checked out - well done today!")
            // A rejected event is dropped: the next boundary crossing, app open, or manual tap covers it (iOS parity).
        } catch (e: Exception) {
            // no network in background; same drop policy as iOS
        }
    }

    private fun showNotification(context: Context, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("gym_auto", "Gym check-in", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = NotificationCompat.Builder(context, "gym_auto")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Gym check-in")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) GymGeofenceManager.rearm(context)
    }
}
