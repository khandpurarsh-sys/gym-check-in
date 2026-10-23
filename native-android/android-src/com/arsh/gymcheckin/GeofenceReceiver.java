package com.arsh.gymcheckin;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class GeofenceReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null || event.hasError()) return;
        int transition = event.getGeofenceTransition();
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER && transition != Geofence.GEOFENCE_TRANSITION_EXIT) return;
        GymGeofenceManager.Cfg cfg = GymGeofenceManager.config(context);
        if (cfg == null) return;
        long now = System.currentTimeMillis();
        SharedPreferences p = GymGeofenceManager.prefs(context);
        String last = p.getString("gymGeofenceLast", null);
        if (last != null) {
            String[] parts = last.split(":");
            if (parts.length == 2 && parts[0].equals(String.valueOf(transition))) {
                try {
                    if (now - Long.parseLong(parts[1]) < 180_000L) return;
                } catch (NumberFormatException ignored) {}
            }
        }
        p.edit().putString("gymGeofenceLast", transition + ":" + now).apply();
        double lat = cfg.lat;
        double lon = cfg.lon;
        if (event.getTriggeringLocation() != null) {
            lat = event.getTriggeringLocation().getLatitude();
            lon = event.getTriggeringLocation().getLongitude();
        }
        final boolean checkIn = transition == Geofence.GEOFENCE_TRANSITION_ENTER;
        final double fLat = lat, fLon = lon;
        final PendingResult pending = goAsync();
        final Context appCtx = context.getApplicationContext();
        new Thread(() -> {
            try {
                postEvent(appCtx, cfg, checkIn, fLat, fLon);
            } finally {
                pending.finish();
            }
        }).start();
    }

    private void postEvent(Context context, GymGeofenceManager.Cfg cfg, boolean checkIn, double lat, double lon) {
        String endpoint = checkIn ? "gym_check_in" : "gym_check_out";
        try {
            JSONObject body = new JSONObject();
            body.put("p_code", cfg.code);
            if (checkIn) {
                body.put("p_lat", lat);
                body.put("p_lon", lon);
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(GymGeofenceManager.RPC_BASE + endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("apikey", GymGeofenceManager.RPC_KEY);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            String text;
            try (Scanner s = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A")) {
                text = s.hasNext() ? s.next() : "";
            }
            conn.disconnect();
            boolean ok = false;
            try { ok = new JSONObject(text).optBoolean("ok"); } catch (Exception ignored) {}
            if (ok) showNotification(context, checkIn ? "Checked in at the gym" : "Checked out - well done today!");
            // A rejected event is dropped: next boundary crossing, app open, or manual tap covers it (iOS parity).
        } catch (Exception e) {
            // no network in background; same drop policy as iOS
        }
    }

    private void showNotification(Context context, String text) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("gym_auto", "Gym check-in", NotificationManager.IMPORTANCE_DEFAULT));
        }
        android.app.Notification n = new NotificationCompat.Builder(context, "gym_auto")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Gym check-in")
            .setContentText(text)
            .setAutoCancel(true)
            .build();
        nm.notify((int) (System.currentTimeMillis() % 100000), n);
    }
}
