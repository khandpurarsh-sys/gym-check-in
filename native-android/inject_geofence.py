import shutil, os, re

SRC = 'native-android/android-src/com/arsh/gymcheckin'
DST = 'android/app/src/main/java/com/arsh/gymcheckin'
for f in os.listdir(SRC):
    shutil.copy(os.path.join(SRC, f), os.path.join(DST, f))
print('kotlin sources copied:', sorted(os.listdir(SRC)))

# MainActivity registers the plugin
open(os.path.join(DST, 'MainActivity.java'), 'w').write('''package com.arsh.gymcheckin;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(GymGeofencePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
''')
print('MainActivity patched')

# Manifest: permissions + receivers
mp = 'android/app/src/main/AndroidManifest.xml'
m = open(mp).read()
perms = (
    '    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />\n'
    '    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />\n'
    '    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />\n'
    '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n'
    '    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n'
)
anchor = '    <uses-permission android:name="android.permission.INTERNET" />'
assert anchor in m, 'internet permission anchor missing'
m = m.replace(anchor, anchor + '\n' + perms.rstrip('\n'), 1)
receivers = (
    '        <receiver android:name=".GeofenceReceiver" android:exported="false" />\n'
    '        <receiver android:name=".BootReceiver" android:enabled="true" android:exported="true">\n'
    '            <intent-filter>\n'
    '                <action android:name="android.intent.action.BOOT_COMPLETED" />\n'
    '            </intent-filter>\n'
        '        </receiver>\n'
    )
app_end = '    </application>'
assert app_end in m, 'application end anchor missing'
m = m.replace(app_end, receivers + app_end, 1)
open(mp, 'w').write(m)
print('manifest patched')

# play-services-location dependency
gp = 'android/app/build.gradle'
g = open(gp).read()
dep_anchor = '    implementation "androidx.appcompat:appcompat:$androidxAppCompatVersion"'
assert dep_anchor in g, 'dependency anchor missing'
g = g.replace(dep_anchor, dep_anchor + '\n    implementation "com.google.android.gms:play-services-location:21.3.0"', 1)
open(gp, 'w').write(g)
print('dependency added')
