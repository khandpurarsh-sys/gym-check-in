package com.arsh.gymcheckin;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class GymEventWorker extends Worker {
    public GymEventWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        Data d = getInputData();
        int transition = d.getInt("transition", -1);
        double lat = d.getDouble("lat", Double.NaN);
        double lon = d.getDouble("lon", Double.NaN);
        long occurredAt = d.getLong("occurredAt", 0L);
        String code = d.getString("code");
        boolean checkIn = transition == com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_ENTER;

        // Logout / code rotation since the event: never post.
        GymGeofenceManager.Cfg cfg = GymGeofenceManager.config(ctx);
        if (cfg == null || !GymEventLogic.configMatches(cfg.code, code)) return Result.success();

        // Stale replay would create false current attendance (backend timestamps server-side).
        if (GymEventLogic.isStale(occurredAt, System.currentTimeMillis(), checkIn)) return Result.success();

        Integer status = null;
        boolean ok = false;
        try {
            JSONObject body = new JSONObject();
            body.put("p_code", cfg.code);
            if (checkIn) { body.put("p_lat", lat); body.put("p_lon", lon); }
            String endpoint = checkIn ? "gym_check_in" : "gym_check_out";
            HttpURLConnection conn = (HttpURLConnection) new URL(GymGeofenceManager.RPC_BASE + endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("apikey", GymGeofenceManager.RPC_KEY);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            status = conn.getResponseCode();
            String text = "";
            try (Scanner s = new Scanner(status < 400 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8").useDelimiter("\\A")) {
                if (s.hasNext()) text = s.next();
            }
            conn.disconnect();
            try { ok = new JSONObject(text).optBoolean("ok"); } catch (Exception ignored) {}
        } catch (Exception e) {
            status = null;
        }

        int outcome = GymEventLogic.postOutcome(status, ok, getRunAttemptCount(), GymEventLogic.MAX_ATTEMPTS);
        if (outcome == GymEventLogic.RETRY) return Result.retry();
        if (status != null && status >= 200 && status < 300 && ok) {
            showNotification(ctx, checkIn ? "Checked in at the gym" : "Checked out - well done today!");
        }
        return Result.success();
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
