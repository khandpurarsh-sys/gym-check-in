package com.arsh.gymcheckin

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.Plugin
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "GymGeofence")
class GymGeofencePlugin : Plugin() {

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == GymGeofenceManager.KEY) GymGeofenceManager.rearm(context)
    }

    override fun load() {
        val ctx = context.applicationContext
        GymGeofenceManager.prefs(ctx).registerOnSharedPreferenceChangeListener(prefsListener)
        GymGeofenceManager.rearm(ctx)
        if (GymGeofenceManager.config(ctx) != null) requestMissingPermissions()
    }

    private fun requestMissingPermissions() {
        val ctx = context
        val foreground = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (foreground.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, foreground.toTypedArray(), 9101)
            return
        }
        val extra = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            extra.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            extra.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (extra.isNotEmpty()) ActivityCompat.requestPermissions(activity, extra.toTypedArray(), 9102)
    }
}
