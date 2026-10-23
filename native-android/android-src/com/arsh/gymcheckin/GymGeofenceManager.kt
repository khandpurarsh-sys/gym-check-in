package com.arsh.gymcheckin

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONObject

object GymGeofenceManager {
    const val PREFS = "CapacitorStorage"
    const val KEY = "gymGeofence"
    const val GEOFENCE_ID = "com.arsh.gymcheckin.gym"
    const val RPC_BASE = "https://vlbzvwmbcjxihlcgkped.supabase.co/rest/v1/rpc/"
    const val RPC_KEY = "sb_publishable_WTYpx355WH8AjHrSwgTFOg_ZW0nXele"

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Cfg(val code: String, val lat: Double, val lon: Double, val radius: Double)

    fun config(ctx: Context): Cfg? {
        val raw = prefs(ctx).getString(KEY, null) ?: return null
        return try {
            val j = JSONObject(raw)
            Cfg(j.getString("code"), j.getDouble("lat"), j.getDouble("lon"), j.getDouble("radius"))
        } catch (e: Exception) {
            null
        }
    }

    fun rearm(ctx: Context) {
        val appCtx = ctx.applicationContext
        val client = LocationServices.getGeofencingClient(appCtx)
        val pi = geofenceIntent(appCtx)
        client.removeGeofences(pi)
        val c = config(appCtx) ?: return
        if (ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val radius = c.radius.coerceIn(100.0, 2000.0).toFloat()
        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(c.lat, c.lon, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()
        try {
            client.addGeofences(request, pi)
        } catch (e: SecurityException) {
            // permission revoked between check and registration; next app open retries
        }
    }

    fun geofenceIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, GeofenceReceiver::class.java)
        return PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
