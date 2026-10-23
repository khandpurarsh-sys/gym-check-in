package com.arsh.gymcheckin;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Scanner;

public class GymEventWorker extends Worker {
    public GymEventWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    private static final class State {
        long serverNowMs;
        Long openVisitInAtMs;
        boolean ok;
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

        // ENTER coalescing: a later EXIT already occurred for this visit, so the visit
        // completed in the past - do not fabricate a current-time visit.
        if (checkIn) {
            SharedPreferences p = GymGeofenceManager.prefs(ctx);
            if (GymEventLogic.coalescedByExit(p.getInt("gymGeofenceLastT", -1), p.getLong("gymGeofenceLastAt", 0L), occurredAt)) {
                return Result.success();
            }
        }

        // Read-only state fetch: server clock (no client-skew) + the actually open visit.
        State st;
        try {
            st = fetchState(cfg.code);
        } catch (Exception e) {
            return retryOrDrop();
        }
        if (st == null || !st.ok) return retryOrDrop();

        if (GymEventLogic.isStale(occurredAt, st.serverNowMs, checkIn)) return Result.success();

        if (!checkIn && !GymEventLogic.exitAllowed(st.openVisitInAtMs, occurredAt)) return Result.success();

        Integer status = null;
        boolean ok = false;
        try {
            JSONObject body = new JSONObject();
            body.put("p_code", cfg.code);
            if (checkIn) { body.put("p_lat", lat); body.put("p_lon", lon); }
            String endpoint = checkIn ? "gym_check_in" : "gym_check_out";
            JSONObject resp = post(endpoint, body);
            status = 200;
            ok = resp.optBoolean("ok");
        } catch (HttpError he) {
            status = he.code;
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

    private Result retryOrDrop() {
        if (getRunAttemptCount() >= GymEventLogic.MAX_ATTEMPTS - 1) return Result.success();
        return Result.retry();
    }

    private static final class HttpError extends Exception {
        final int code;
        HttpError(int code) { this.code = code; }
    }

    private static JSONObject post(String endpoint, JSONObject body) throws Exception {
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
        int status = conn.getResponseCode();
        String text = "";
        try (Scanner s = new Scanner(status < 400 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8").useDelimiter("\\A")) {
            if (s.hasNext()) text = s.next();
        }
        conn.disconnect();
        if (status < 200 || status >= 300) throw new HttpError(status);
        return new JSONObject(text);
    }

    private static State fetchState(String code) throws Exception {
        JSONObject body = new JSONObject();
        body.put("p_code", code);
        JSONObject j = post("gym_state", body);
        State st = new State();
        st.ok = j.optBoolean("ok");
        if (!st.ok) return st;
        String now = j.optString("now", "");
        st.serverNowMs = now.isEmpty() ? System.currentTimeMillis() : OffsetDateTime.parse(now).toInstant().toEpochMilli();
        JSONArray visits = j.optJSONArray("visits");
        st.openVisitInAtMs = null;
        if (visits != null) {
            for (int i = 0; i < visits.length(); i++) {
                JSONObject v = visits.optJSONObject(i);
                if (v != null && v.isNull("out_at")) {
                    st.openVisitInAtMs = OffsetDateTime.parse(v.getString("in_at")).toInstant().toEpochMilli();
                    break;
                }
            }
        }
        return st;
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
