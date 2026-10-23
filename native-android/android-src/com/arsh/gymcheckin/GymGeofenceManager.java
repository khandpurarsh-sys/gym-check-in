package com.arsh.gymcheckin;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;

import org.json.JSONObject;

public final class GymGeofenceManager {
    public static final String PREFS = "CapacitorStorage";
    public static final String KEY = "gymGeofence";
    public static final String GEOFENCE_ID = "com.arsh.gymcheckin.gym";
    public static final String RPC_BASE = "https://vlbzvwmbcjxihlcgkped.supabase.co/rest/v1/rpc/";
    public static final String RPC_KEY = "sb_publishable_WTYpx355WH8AjHrSwgTFOg_ZW0nXele";

    private GymGeofenceManager() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static final class Cfg {
        public final String code;
        public final double lat;
        public final double lon;
        public final double radius;
        Cfg(String code, double lat, double lon, double radius) {
            this.code = code; this.lat = lat; this.lon = lon; this.radius = radius;
        }
    }

    public static Cfg config(Context ctx) {
        String raw = prefs(ctx).getString(KEY, null);
        if (raw == null) return null;
        try {
            JSONObject j = new JSONObject(raw);
            return new Cfg(j.getString("code"), j.getDouble("lat"), j.getDouble("lon"), j.getDouble("radius"));
        } catch (Exception e) {
            return null;
        }
    }

    public static void rearm(Context ctx) {
        Context appCtx = ctx.getApplicationContext();
        GeofencingClient client = LocationServices.getGeofencingClient(appCtx);
        PendingIntent pi = geofenceIntent(appCtx);
        client.removeGeofences(pi);
        Cfg c = config(appCtx);
        if (c == null) return;
        if (ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        float radius = (float) Math.min(Math.max(c.radius, 100.0), 2000.0);
        Geofence geofence = new Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(c.lat, c.lon, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER | Geofence.GEOFENCE_TRANSITION_EXIT)
            .build();
        GeofencingRequest request = new GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build();
        try {
            client.addGeofences(request, pi);
        } catch (SecurityException e) {
            // permission revoked between check and registration; next app open retries
        }
    }

    public static PendingIntent geofenceIntent(Context ctx) {
        Intent intent = new Intent(ctx, GeofenceReceiver.class);
        return PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }
}
