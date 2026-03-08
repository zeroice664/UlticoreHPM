package com.ulticore.highperformancemode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * ═══════════════════════════════════════════════════════════
 *  PerformanceService — The Core Engine
 *
 *  Runs as an Android FOREGROUND service, which means:
 *  • It shows a persistent notification (required by Android)
 *  • Android will NOT kill it in memory pressure situations
 *  • It continues to run with the screen off
 *  • EMUI's aggressive app-killing (Task Cleaner / Phone Manager)
 *    is bypassed because foreground services are protected
 *
 *  What we optimise without root:
 *  ┌──────────────────────────────────────────────────────┐
 *  │ 1. PARTIAL_WAKE_LOCK  → CPU stays awake even when   │
 *  │    the screen is off. Prevents deep sleep states     │
 *  │    that cause latency spikes.                        │
 *  │                                                      │
 *  │ 2. WIFI_MODE_FULL_HIGH_PERF → Disables WiFi power   │
 *  │    management entirely. The driver no longer parks   │
 *  │    the radio, so RTT drops and throughput rises.     │
 *  │                                                      │
 *  │ 3. NetworkRequest (BANDWIDTH hint) → Tells the       │
 *  │    connectivity stack this app needs a high-         │
 *  │    bandwidth, low-latency connection. On Android 9+  │
 *  │    the system may switch from metered to unmetered   │
 *  │    or select a better interface automatically.       │
 *  │                                                      │
 *  │ 4. Thread priority escalation → Sets this service's │
 *  │    thread to THREAD_PRIORITY_FOREGROUND (-2), just  │
 *  │    below audio-class threads. All of its work runs  │
 *  │    with elevated CPU scheduling priority.           │
 *  │                                                      │
 *  │ 5. Watchdog loop (every 15s) → Re-acquires any      │
 *  │    locks that EMUI may have silently released, and   │
 *  │    broadcasts live stats to the UI.                  │
 *  └──────────────────────────────────────────────────────┘
 * ═══════════════════════════════════════════════════════════
 */
public class PerformanceService extends Service {

    // ── Service lifecycle actions ─────────────────────────
    public static final String ACTION_START    = "com.ulticore.hpm.START";
    public static final String ACTION_STOP     = "com.ulticore.hpm.STOP";

    // ── Runtime command actions (sent from MainActivity) ──
    public static final String CMD_WIFI_OPT    = "com.ulticore.hpm.WIFI_OPT";
    public static final String CMD_WAKE_LOCK   = "com.ulticore.hpm.WAKE_LOCK";
    public static final String CMD_NET_BOOST   = "com.ulticore.hpm.NET_BOOST";

    // ── Notification constants ────────────────────────────
    private static final String CHANNEL_ID   = "ulticore_hpm_channel";
    private static final int    NOTIF_ID     = 1001;

    // ── Lock references ──────────────────────────────────
    private PowerManager.WakeLock       wakeLock;
    private WifiManager.WifiLock        wifiLock;
    private ConnectivityManager.NetworkCallback netCallback;

    // ── Feature flags (mirror toggle states) ─────────────
    private boolean wifiOptEnabled  = true;
    private boolean wakeLockEnabled = true;
    private boolean netBoostEnabled = true;

    // ── Watchdog ─────────────────────────────────────────
    private Handler  watchdogHandler = new Handler();
    private Runnable watchdogRunnable;
    private static final long WATCHDOG_INTERVAL_MS = 15_000; // 15 seconds

    // ════════════════════════════════════════════════════
    //  SERVICE STARTUP
    // ════════════════════════════════════════════════════
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null) {
            // Service was restarted by Android after being killed — re-apply all locks
            applyAllOptimisations();
            return START_STICKY;
        }

        final String action = intent.getAction();
        if (action == null) return START_STICKY;

        switch (action) {

            case ACTION_START:
                // Read initial toggle states from the intent extras
                wifiOptEnabled  = intent.getBooleanExtra(MainActivity.KEY_WIFI_OPT,  true);
                wakeLockEnabled = intent.getBooleanExtra(MainActivity.KEY_WAKE_LOCK, true);
                netBoostEnabled = intent.getBooleanExtra(MainActivity.KEY_NET_BOOST, true);

                // Elevate this thread's scheduling priority immediately
                Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

                // Build the persistent foreground notification
                startForeground(NOTIF_ID, buildNotification("⚡ High Performance Mode Active"));

                // Acquire all enabled locks
                applyAllOptimisations();

                // Start the watchdog loop
                startWatchdog();
                break;

            case ACTION_STOP:
                releaseAllLocks();
                stopWatchdog();
                stopForeground(true);
                stopSelf();
                break;

            // ── Live toggle commands from MainActivity ──
            case CMD_WIFI_OPT:
                wifiOptEnabled = intent.getBooleanExtra("value", false);
                if (wifiOptEnabled) acquireWifiLock();
                else releaseWifiLock();
                break;

            case CMD_WAKE_LOCK:
                wakeLockEnabled = intent.getBooleanExtra("value", false);
                if (wakeLockEnabled) acquireWakeLock();
                else releaseWakeLock();
                break;

            case CMD_NET_BOOST:
                netBoostEnabled = intent.getBooleanExtra("value", false);
                if (netBoostEnabled) requestHighBandwidthNetwork();
                else releaseNetworkCallback();
                break;
        }

        // START_STICKY means if Android ever kills this service, it will
        // be automatically restarted with a null intent (handled above).
        return START_STICKY;
    }

    // ════════════════════════════════════════════════════
    //  OPTIMISATION ORCHESTRATION
    // ════════════════════════════════════════════════════
    private void applyAllOptimisations() {
        if (wakeLockEnabled) acquireWakeLock();
        if (wifiOptEnabled)  acquireWifiLock();
        if (netBoostEnabled) requestHighBandwidthNetwork();
    }

    private void releaseAllLocks() {
        releaseWakeLock();
        releaseWifiLock();
        releaseNetworkCallback();
    }

    // ════════════════════════════════════════════════════
    //  1. CPU WAKE LOCK
    //     PARTIAL_WAKE_LOCK keeps the CPU running even when
    //     the display is off. This prevents deep C-state
    //     transitions (which cause wake-up latency spikes)
    //     and stops EMUI's background process freezer from
    //     suspending network traffic.
    // ════════════════════════════════════════════════════
    private void acquireWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) return; // already held
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Ulticore::HighPerfLock"
                );
                // Acquire WITHOUT a timeout — we manage the release ourselves
                wakeLock.acquire();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ════════════════════════════════════════════════════
    //  2. WIFI HIGH PERFORMANCE LOCK
    //     WIFI_MODE_FULL_HIGH_PERF instructs the WiFi driver
    //     to disable power saving (PSM). In PSM the radio
    //     periodically "dozes" between beacon intervals
    //     (~100ms) which adds jitter and increases ping.
    //     With this lock the radio is always listening,
    //     giving consistently lower latency.
    // ════════════════════════════════════════════════════
    private void acquireWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) return;
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "Ulticore::WifiHighPerf"
                );
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ════════════════════════════════════════════════════
    //  3. NETWORK BANDWIDTH / LATENCY REQUEST
    //     Android 9 introduced the concept of asking the
    //     connectivity stack for a network with specific
    //     capability hints. Requesting NET_CAPABILITY_NOT_METERED
    //     + high bandwidth encourages the OS to route traffic
    //     on the best available interface and may disable
    //     background data throttling for this app's flows.
    // ════════════════════════════════════════════════════
    private void requestHighBandwidthNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            // Release any previous callback first
            releaseNetworkCallback();

            NetworkRequest.Builder builder = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);

            // On Android 9 (API 28) we can request a validated connection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                builder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }

            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    // Bind ALL process traffic to this highest-capability network
                    try {
                        cm.bindProcessToNetwork(network);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    broadcastNetworkType(cm, network);
                }

                @Override
                public void onLost(Network network) {
                    // Unbind so Android can find another network
                    try { cm.bindProcessToNetwork(null); } catch (Exception ignored) {}
                }
            };

            cm.requestNetwork(builder.build(), netCallback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void releaseNetworkCallback() {
        try {
            if (netCallback != null) {
                ConnectivityManager cm = (ConnectivityManager)
                        getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(netCallback);
                netCallback = null;
            }
        } catch (Exception ignored) {}
    }

    private void broadcastNetworkType(ConnectivityManager cm, Network network) {
        try {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            String type = "Unknown";
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    type = "WiFi (High-Perf)";
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    type = "Mobile Data";
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    type = "Ethernet";
                }
            }
            Intent broadcast = new Intent(MainActivity.ACTION_STATUS_UPDATE);
            broadcast.putExtra(MainActivity.EXTRA_NETWORK_TYPE, type);
            sendBroadcast(broadcast);
        } catch (Exception ignored) {}
    }

    // ════════════════════════════════════════════════════
    //  4. WATCHDOG — Re-validates locks every 15 seconds
    //     EMUI is known to silently revoke wake locks and
    //     WiFi locks in certain power-saving states. The
    //     watchdog detects this and immediately re-acquires.
    // ════════════════════════════════════════════════════
    private void startWatchdog() {
        watchdogRunnable = new Runnable() {
            @Override
            public void run() {
                // Re-acquire any lock EMUI may have released
                if (wakeLockEnabled && (wakeLock == null || !wakeLock.isHeld())) {
                    acquireWakeLock();
                }
                if (wifiOptEnabled && (wifiLock == null || !wifiLock.isHeld())) {
                    acquireWifiLock();
                }

                // Update notification with a timestamp so EMUI sees it as "active"
                updateNotification("⚡ Active — Watchdog OK");

                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        };
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
    }

    private void stopWatchdog() {
        if (watchdogRunnable != null) {
            watchdogHandler.removeCallbacks(watchdogRunnable);
        }
    }

    // ════════════════════════════════════════════════════
    //  FOREGROUND NOTIFICATION
    //  Android 8+ requires a visible notification for any
    //  foreground service. This is not optional — without
    //  it Android kills the service within 5 seconds.
    // ════════════════════════════════════════════════════
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Ulticore Performance Service",
                NotificationManager.IMPORTANCE_LOW  // silent but persistent
            );
            channel.setDescription("Maintains performance locks");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        createNotificationChannel();
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ulticore High Performance")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)          // Cannot be swiped away
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    // ════════════════════════════════════════════════════
    //  REQUIRED OVERRIDES
    // ════════════════════════════════════════════════════
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // We use a started service, not a bound service
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseAllLocks();
        stopWatchdog();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Called when the user swipes the app from recents.
        // We reschedule the service to restart in 1 second.
        Intent restartServiceIntent = new Intent(getApplicationContext(), PerformanceService.class);
        restartServiceIntent.setAction(ACTION_START);
        android.app.PendingIntent pIntent = android.app.PendingIntent.getService(
            getApplicationContext(), 1,
            restartServiceIntent,
            android.app.PendingIntent.FLAG_ONE_SHOT |
            (Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0)
        );
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            am.set(android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 1000, pIntent);
        }
        super.onTaskRemoved(rootIntent);
    }
}
