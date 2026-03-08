# ULTICORE HIGH PERFORMANCE MODE v2.0
### For Honor 8X · Android 9.x · Non-Root

---

## What This App Does (and Why It Works)

Think of your Honor 8X's performance management like traffic lights on a busy road.
Android and EMUI (Huawei's OS layer) constantly apply "red lights" to save power —
sleeping the CPU, parking the WiFi radio, and killing background processes.
This app holds as many of those traffic lights on **green** as Android's non-root
API allows.

### The 4 Optimisation Engines

**1. CPU Wake Lock (PARTIAL_WAKE_LOCK)**
When the screen turns off, Android pushes the CPU into low-power "sleep states"
(C-states). Waking up from deep sleep takes 10–50ms — invisible to most apps but
noticeable in gaming, streaming, or real-time calls. `PARTIAL_WAKE_LOCK` keeps
the CPU active, eliminating this wake-up latency spike. The service holds this
lock and the built-in 15-second watchdog re-acquires it if EMUI silently revokes it.

**2. WiFi High Performance Lock (WIFI_MODE_FULL_HIGH_PERF)**
WiFi routers send a "beacon" every ~100ms. In power-save mode (PSM), your phone's
WiFi chip "dozes" between beacons — saving battery but adding up to 100ms of random
latency. `WIFI_MODE_FULL_HIGH_PERF` disables PSM entirely: the radio stays fully
awake, giving you consistently low ping and stable throughput. This is the same
lock that Android uses internally for VoIP calls.

**3. Network Bandwidth Priority (ConnectivityManager.NetworkCallback)**
Android 9's `NetworkRequest` API lets an app declare that it needs a high-bandwidth,
validated, unrestricted network connection. The OS then binds *all* of this app's
network traffic — and via `bindProcessToNetwork`, all process traffic — to the
best available interface. If both WiFi and mobile data are active, Android picks the
faster one automatically.

**4. Foreground Service (Process Immunity)**
EMUI's "Phone Manager" and background task killer are aggressive. A normal background
service can be killed in seconds. A **foreground service** (one with a visible
notification) is in a protected process class — Android will almost never kill it,
and if it does, `START_STICKY` tells Android to restart it. The `BootReceiver` then
re-starts everything after a reboot.

---

## Honest Limitations (Without Root)

Root access allows writing directly to `/sys/devices/system/cpu/` to lock the CPU
governor (e.g., force "performance" instead of "schedutil") and set minimum clock
frequencies. **This app cannot do that**, and claiming otherwise would be dishonest.
What you get instead is everything the Android user-space API legally exposes:
sustained wake locks, WiFi radio control, and network stack priority hints — which
collectively make a real, measurable difference in responsiveness and network stability.

---

## HOW TO BUILD THE APK

### Prerequisites
- Android Studio 3.6 or newer (free from developer.android.com)
- Java 8 or newer (bundled with Android Studio)
- An internet connection the first time (to download Gradle dependencies)

### Step-by-Step

1. **Open Android Studio** and choose **"Open an existing project"**.
2. Navigate to and select the `UlticoreHPM` folder.
3. Wait for Gradle to sync (bottom status bar will say "Gradle sync finished").
4. Go to **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. When the build finishes, click **"locate"** in the popup. The APK will be at:
   `app/build/outputs/apk/debug/com.ulticore.highperformancemode-2.0.apk`
6. Transfer to your Honor 8X via USB or cloud, enable **"Install from unknown sources"**
   in Settings → Security, and install.

---

## HONOR 8X SPECIFIC SETUP (Very Important)

After installing, do these steps or EMUI will still kill the service:

1. **Go to Settings → Battery → App launch.**
2. Find **"Ulticore HPM"** in the list.
3. Turn the toggle OFF (disable automatic management).
4. In the popup that appears, enable all three: **Auto-launch, Secondary launch, Run in background.**
5. **Go to Settings → Battery → More battery settings.**
6. Tap **"Ignore battery optimisation"** → find the app → Allow.
7. Open the app, toggle Performance Mode ON.
8. In the dialog that appears asking about battery optimisation, tap **"Allow"**.

Without step 4, EMUI's task killer will terminate the service within minutes regardless
of what Android's own APIs say. This is a Huawei-specific restriction that requires
a manual user action — no app can bypass it programmatically without root.

---

## File Structure

```
UlticoreHPM/
├── build.gradle                          ← Project-level Gradle config
├── settings.gradle
└── app/
    ├── build.gradle                      ← App-level config (targetSdk 28)
    └── src/main/
        ├── AndroidManifest.xml           ← Permissions + component declarations
        ├── java/com/ulticore/highperformancemode/
        │   ├── MainActivity.java         ← UI + toggle controller
        │   ├── PerformanceService.java   ← Core engine (runs in background)
        │   └── BootReceiver.java         ← Auto-restore on reboot
        └── res/
            ├── layout/activity_main.xml  ← Dark-theme dashboard UI
            └── values/
                ├── strings.xml
                └── styles.xml
```

---

*Ulticore HPM v2.0 — Built for Honor 8X / Android 9 / Non-Root*
