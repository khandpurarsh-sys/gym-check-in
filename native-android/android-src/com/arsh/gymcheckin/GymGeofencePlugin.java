package com.arsh.gymcheckin;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.Plugin;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.List;

@CapacitorPlugin(name = "GymGeofence")
public class GymGeofencePlugin extends Plugin {

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
        (prefs, key) -> {
            if (!GymGeofenceManager.KEY.equals(key)) return;
            GymGeofenceManager.rearm(getContext());
            // Fresh install: mom pastes her code after first launch, so config appears
            // after load(). Request permissions on this path too, not only at load().
            if (GymGeofenceManager.config(getContext()) != null) requestMissingPermissions();
        };

    @Override
    public void load() {
        android.content.Context ctx = getContext().getApplicationContext();
        GymGeofenceManager.prefs(ctx).registerOnSharedPreferenceChangeListener(prefsListener);
        GymGeofenceManager.rearm(ctx);
        if (GymGeofenceManager.config(ctx) != null) requestMissingPermissions();
    }

    private void requestMissingPermissions() {
        android.content.Context ctx = getContext();
        List<String> foreground = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}) {
            if (ContextCompat.checkSelfPermission(ctx, p) != PackageManager.PERMISSION_GRANTED) foreground.add(p);
        }
        if (!foreground.isEmpty()) {
            ActivityCompat.requestPermissions(getActivity(), foreground.toArray(new String[0]), 9101);
            return;
        }
        List<String> extra = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            extra.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            extra.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!extra.isEmpty()) ActivityCompat.requestPermissions(getActivity(), extra.toArray(new String[0]), 9102);
    }
}
