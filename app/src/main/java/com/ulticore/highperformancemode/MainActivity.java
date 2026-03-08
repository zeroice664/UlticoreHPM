package com.ulticore.highperformancemode;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * ═══════════════════════════════════════════════════════
 *  ULTICORE HIGH PERFORMANCE MODE v2.0
 *  Main Activity - Dashboard & Toggle Controller
 *
 *  Designed for Honor 8X (Android 9 / EMUI 8.2+)
 *  No root required — maximizes every available API lever
 * ═══════════════════════════════════════════════════════
 */
public class MainActivity extends AppCompatActivity {

    // ── Preference keys ──────────────────────────────────
    public static final String PREFS_NAME       = "UlticorePrefs";
    public static final String KEY_PERF_ENABLED = "performanceEnabled";
    public static final String KEY_WIFI_OPT     = "wifiOptEnabled";
    public static final String KEY_WAKE_LOCK    = "wakeLockEnabled";
    public static final String KEY_NET_BOOST    = "networkBoostEnabled";
    public static final String KEY_SCREEN_ON    = "keepScreenOn";

    // ── Intent actions sent between Activity ↔ Service ──
    public static final String ACTION_STATUS_UPDATE = "com.ulticore.hpm.STATUS_UPDATE";
    public static final String EXTRA_CPU_FREQ       = "cpu_freq";
    public static final String EXTRA_TEMP           = "temperature";
    public static final String EXTRA_BATTERY_LEVEL  = "battery_level";
    public static final String EXTRA_NETWORK_TYPE   = "network_type";

    // ── UI widgets ───────────────────────────────────────
    private Switch   switchMasterPerf;
    private Switch   switchWifiOpt;
    private Switch   switchWakeLock;
    private Switch   switchNetBoost;
    private Switch   switchScreenOn;
    private TextView tvStatusLabel;
    private TextView tvBatteryLevel;
    private TextView tvNetworkType;
    private TextView tvCpuFreq;
    private TextView tvTemperature;
    private TextView tvModelInfo;
    private ImageView ivSpeedometer;
    private LinearLayout layoutStats;

    private SharedPreferences prefs;
    private Handler           uiHandler = new Handler();
    private Runnable          statsRefresher;

    // ── Battery receiver ─────────────────────────────────
    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            float pct = level * 100f / (float) scale;
            tvBatteryLevel.setText(String.format("Battery: %.0f%%", pct));

            // Temperature comes in tenths of °C
            float temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
            tvTemperature.setText(String.format("Temp: %.1f°C", temp));
        }
    };

    // ── Service status receiver ───────────────────────────
    private BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String netType = intent.getStringExtra(EXTRA_NETWORK_TYPE);
            if (netType != null) tvNetworkType.setText("Network: " + netType);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen bright while app is open
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        bindViews();
        displayDeviceInfo();
        loadAndApplySwitchStates();
        setupSwitchListeners();
        requestBatteryOptimizationExemption();
        startPeriodicStatsRefresh();
    }

    // ════════════════════════════════════════════════════
    //  VIEW BINDING
    // ════════════════════════════════════════════════════
    private void bindViews() {
        switchMasterPerf = findViewById(R.id.switchMasterPerf);
        switchWifiOpt    = findViewById(R.id.switchWifiOpt);
        switchWakeLock   = findViewById(R.id.switchWakeLock);
        switchNetBoost   = findViewById(R.id.switchNetBoost);
        switchScreenOn   = findViewById(R.id.switchScreenOn);
        tvStatusLabel    = findViewById(R.id.tvStatusLabel);
        tvBatteryLevel   = findViewById(R.id.tvBatteryLevel);
        tvNetworkType    = findViewById(R.id.tvNetworkType);
        tvCpuFreq        = findViewById(R.id.tvCpuFreq);
        tvTemperature    = findViewById(R.id.tvTemperature);
        tvModelInfo      = findViewById(R.id.tvModelInfo);
        ivSpeedometer    = findViewById(R.id.ivSpeedometer);
        layoutStats      = findViewById(R.id.layoutStats);
    }

    // ════════════════════════════════════════════════════
    //  DEVICE INFO BANNER
    // ════════════════════════════════════════════════════
    private void displayDeviceInfo() {
        String model = Build.MODEL + " | Android " + Build.VERSION.RELEASE
                + " | EMUI " + getEmuiVersion();
        tvModelInfo.setText(model);
    }

    private String getEmuiVersion() {
        try {
            // Huawei/Honor store EMUI version in a system property
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = systemProperties.getMethod("get", String.class);
            String emui = (String) get.invoke(null, "ro.build.version.emui");
            return (emui != null && !emui.isEmpty()) ? emui : "Unknown";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    // ════════════════════════════════════════════════════
    //  SWITCH STATE LOAD / RESTORE
    // ════════════════════════════════════════════════════
    private void loadAndApplySwitchStates() {
        // Read persisted preferences — silently restore toggles
        switchMasterPerf.setChecked(prefs.getBoolean(KEY_PERF_ENABLED, false));
        switchWifiOpt.setChecked(prefs.getBoolean(KEY_WIFI_OPT, false));
        switchWakeLock.setChecked(prefs.getBoolean(KEY_WAKE_LOCK, false));
        switchNetBoost.setChecked(prefs.getBoolean(KEY_NET_BOOST, false));
        switchScreenOn.setChecked(prefs.getBoolean(KEY_SCREEN_ON, false));

        // If performance was ON when the app was last closed, resume service
        if (prefs.getBoolean(KEY_PERF_ENABLED, false)) {
            updateStatusLabel(true);
            startPerformanceService();
        } else {
            updateStatusLabel(false);
        }
    }

    // ════════════════════════════════════════════════════
    //  SWITCH LISTENERS
    // ════════════════════════════════════════════════════
    private void setupSwitchListeners() {

        // ── Master toggle — the big one ──────────────────
        switchMasterPerf.setOnCheckedChangeListener((btn, isOn) -> {
            prefs.edit().putBoolean(KEY_PERF_ENABLED, isOn).apply();
            updateStatusLabel(isOn);
            if (isOn) {
                startPerformanceService();
                // Cascade: enable all sub-options automatically
                switchWifiOpt.setChecked(true);
                switchWakeLock.setChecked(true);
                switchNetBoost.setChecked(true);
                Toast.makeText(this, "⚡ HIGH PERFORMANCE MODE ACTIVE", Toast.LENGTH_SHORT).show();
            } else {
                stopPerformanceService();
                Toast.makeText(this, "Performance mode OFF", Toast.LENGTH_SHORT).show();
            }
        });

        // ── WiFi High Performance Lock ───────────────────
        switchWifiOpt.setOnCheckedChangeListener((btn, isOn) -> {
            prefs.edit().putBoolean(KEY_WIFI_OPT, isOn).apply();
            sendServiceCommand(PerformanceService.CMD_WIFI_OPT, isOn);
        });

        // ── CPU Wake Lock (prevent deep CPU sleep) ───────
        switchWakeLock.setOnCheckedChangeListener((btn, isOn) -> {
            prefs.edit().putBoolean(KEY_WAKE_LOCK, isOn).apply();
            sendServiceCommand(PerformanceService.CMD_WAKE_LOCK, isOn);
        });

        // ── Network Bandwidth Boost ───────────────────────
        switchNetBoost.setOnCheckedChangeListener((btn, isOn) -> {
            prefs.edit().putBoolean(KEY_NET_BOOST, isOn).apply();
            sendServiceCommand(PerformanceService.CMD_NET_BOOST, isOn);
        });

        // ── Keep Screen On (display never sleeps) ────────
        switchScreenOn.setOnCheckedChangeListener((btn, isOn) -> {
            prefs.edit().putBoolean(KEY_SCREEN_ON, isOn).apply();
            if (isOn) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    // ════════════════════════════════════════════════════
    //  SERVICE CONTROL
    // ════════════════════════════════════════════════════
    private void startPerformanceService() {
        Intent svc = new Intent(this, PerformanceService.class);
        svc.setAction(PerformanceService.ACTION_START);
        // Bundle all current sub-option states into the start intent
        svc.putExtra(KEY_WIFI_OPT,  switchWifiOpt.isChecked());
        svc.putExtra(KEY_WAKE_LOCK, switchWakeLock.isChecked());
        svc.putExtra(KEY_NET_BOOST, switchNetBoost.isChecked());
        ContextCompat.startForegroundService(this, svc);
    }

    private void stopPerformanceService() {
        Intent svc = new Intent(this, PerformanceService.class);
        svc.setAction(PerformanceService.ACTION_STOP);
        startService(svc);
    }

    /** Send a targeted command to the already-running service */
    private void sendServiceCommand(String command, boolean value) {
        Intent cmd = new Intent(this, PerformanceService.class);
        cmd.setAction(command);
        cmd.putExtra("value", value);
        startService(cmd);
    }

    // ════════════════════════════════════════════════════
    //  BATTERY OPTIMISATION EXEMPTION
    //  This is the closest non-root equivalent to
    //  "unrestricted battery access". It asks Android
    //  to whitelist this app so it is NEVER throttled
    //  or killed by DOZE / STAMINA modes.
    // ════════════════════════════════════════════════════
    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    // Some EMUI versions block this intent — fall back to battery settings
                    Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    try { startActivity(fallback); } catch (Exception ignored) {}
                }
            }
        }
    }

    // ════════════════════════════════════════════════════
    //  PERIODIC STATS REFRESH (every 2s)
    // ════════════════════════════════════════════════════
    private void startPeriodicStatsRefresh() {
        statsRefresher = new Runnable() {
            @Override
            public void run() {
                refreshStats();
                uiHandler.postDelayed(this, 2000);
            }
        };
        uiHandler.post(statsRefresher);
    }

    private void refreshStats() {
        // ── CPU frequency from /proc/cpuinfo (user-readable) ──
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"));
            String line = reader.readLine();
            reader.close();
            if (line != null) {
                long freqKHz = Long.parseLong(line.trim());
                tvCpuFreq.setText(String.format("CPU0: %.0f MHz", freqKHz / 1000f));
            }
        } catch (Exception e) {
            tvCpuFreq.setText("CPU: N/A");
        }

        // ── RAM usage ────────────────────────────────────
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long usedMB = (mi.totalMem - mi.availMem) / (1024 * 1024);
            long totalMB = mi.totalMem / (1024 * 1024);
            // Append to CPU line for compactness
            tvCpuFreq.setText(tvCpuFreq.getText() + "  |  RAM: " + usedMB + "/" + totalMB + " MB");
        }
    }

    // ════════════════════════════════════════════════════
    //  UI HELPERS
    // ════════════════════════════════════════════════════
    private void updateStatusLabel(boolean active) {
        if (active) {
            tvStatusLabel.setText("● PERFORMANCE MODE: ON");
            tvStatusLabel.setTextColor(0xFF00E676);   // green
            ivSpeedometer.setImageResource(R.drawable.speedometer_high);
        } else {
            tvStatusLabel.setText("○ PERFORMANCE MODE: OFF");
            tvStatusLabel.setTextColor(0xFFAAAAAA);   // grey
            ivSpeedometer.setImageResource(R.drawable.speedometer_default);
        }
    }

    // ════════════════════════════════════════════════════
    //  LIFECYCLE
    // ════════════════════════════════════════════════════
    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        IntentFilter statusFilter = new IntentFilter(ACTION_STATUS_UPDATE);
        registerReceiver(statusReceiver, statusFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(statusReceiver);  } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(statsRefresher);
    }
}
