package com.arsh.gymcheckin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;

import java.util.concurrent.TimeUnit;

/**
 * Enqueue-only: durable, network-constrained, serialized delivery lives in
 * GymEventWorker via a unique WorkManager chain. No network or goAsync here.
 */
public class GeofenceReceiver extends BroadcastReceiver {
    public static final String CHAIN = "gymEvents";

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
        int lastT = p.getInt("gymGeofenceLastT", -1);
        long lastAt = p.getLong("gymGeofenceLastAt", 0L);
        if (GymEventLogic.isDuplicate(lastT, lastAt, transition, now)) return;
        p.edit().putInt("gymGeofenceLastT", transition).putLong("gymGeofenceLastAt", now).apply();

        double lat = cfg.lat, lon = cfg.lon, acc = 0.0;
        if (event.getTriggeringLocation() != null) {
            lat = event.getTriggeringLocation().getLatitude();
            lon = event.getTriggeringLocation().getLongitude();
            if (event.getTriggeringLocation().hasAccuracy()) acc = event.getTriggeringLocation().getAccuracy();
        }
        Data data = new Data.Builder()
            .putInt("transition", transition)
            .putDouble("lat", lat)
            .putDouble("lon", lon)
            .putDouble("acc", acc)
            .putLong("occurredAt", now)
            .putString("code", cfg.code)
            .build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(GymEventWorker.class)
            .setInputData(data)
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build();
        WorkManager.getInstance(context.getApplicationContext())
            .beginUniqueWork(CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
            .enqueue();
    }
}
