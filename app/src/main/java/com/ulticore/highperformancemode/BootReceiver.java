package com.ulticore.highperformancemode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.core.content.ContextCompat;

/**
 * BootReceiver — Auto-restore on Reboot
 *
 * This receiver fires immediately after Android (and EMUI)
 * finishes booting. If the user had performance mode enabled
 * before the reboot, we silently restart the service.
 *
 * Registered in AndroidManifest.xml with android:priority="999"
 * so it runs before most other boot receivers.
 *
 * Honor / Huawei note:
 *   EMUI 8.x registers its own "HiPower" boot cleaner that
 *   runs at similar priority. By starting as a foreground
 *   service (rather than a background one) we survive that
 *   cleanup sweep.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null) return;

        // Respond to both standard Android and Huawei-specific boot actions
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {

            SharedPreferences prefs = context.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE);

            // Only restart if the user had it enabled before reboot
            boolean wasEnabled = prefs.getBoolean(MainActivity.KEY_PERF_ENABLED, false);
            if (wasEnabled) {
                Intent serviceIntent = new Intent(context, PerformanceService.class);
                serviceIntent.setAction(PerformanceService.ACTION_START);
                // Pass persisted sub-options so the service restores correctly
                serviceIntent.putExtra(MainActivity.KEY_WIFI_OPT,
                        prefs.getBoolean(MainActivity.KEY_WIFI_OPT, true));
                serviceIntent.putExtra(MainActivity.KEY_WAKE_LOCK,
                        prefs.getBoolean(MainActivity.KEY_WAKE_LOCK, true));
                serviceIntent.putExtra(MainActivity.KEY_NET_BOOST,
                        prefs.getBoolean(MainActivity.KEY_NET_BOOST, true));

                // On Android 8+ we MUST start as a foreground service from a receiver
                ContextCompat.startForegroundService(context, serviceIntent);
            }
        }
    }
}
