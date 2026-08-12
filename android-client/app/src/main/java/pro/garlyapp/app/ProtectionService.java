package pro.garlyapp.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import org.json.JSONObject;

/**
 * Owns motion monitoring while Protection mode is armed.
 *
 * The WebView is deliberately not the owner of the sensors: Android may pause
 * a page when it is backgrounded or the screen is locked. This service keeps
 * the accelerometer/gyroscope stream alive and broadcasts samples to the
 * visible activity. The SOS scoring engine is still in the web layer and will
 * be moved behind this boundary in the next milestone.
 */
public final class ProtectionService extends Service implements SensorEventListener {
    public static final String ACTION_START = "pro.garlyapp.app.action.START_PROTECTION";
    public static final String ACTION_STOP = "pro.garlyapp.app.action.STOP_PROTECTION";
    public static final String ACTION_WALK_START = "pro.garlyapp.app.action.START_WALK";
    public static final String ACTION_WALK_PING = "pro.garlyapp.app.action.PING_WALK";
    public static final String ACTION_WALK_STOP = "pro.garlyapp.app.action.STOP_WALK";
    public static final String ACTION_ALERT = "pro.garlyapp.app.action.PROTECTION_ALERT";
    public static final String ACTION_CANCEL_ALERT = "pro.garlyapp.app.action.CANCEL_PROTECTION_ALERT";
    public static final String ACTION_SOS_CANCELLED = "pro.garlyapp.app.action.SOS_CANCELLED";
    public static final String ACTION_MOTION = "pro.garlyapp.app.action.PROTECTION_MOTION";
    public static final String PREFS = "garly_protection_service";
    public static final String KEY_RUNNING = "running";
    public static final String KEY_ARMED = "armed";
    public static final String KEY_CANCELLED_EVENT_ID = "cancelled_event_id";
    public static final String KEY_CANCELLED_AT = "cancelled_at";
    public static final String KEY_WALK_ACTIVE = "walk_active";
    public static final String KEY_WALK_DEADLINE = "walk_deadline";
    public static final String KEY_WALK_SILENT = "walk_silent_pending";

    private static final String CHANNEL_ID = "garly_protection";
    private static final String ALERT_CHANNEL_ID = "garly_protection_alerts";
    private static final int NOTIFICATION_ID = 781;
    private static final int SAMPLE_INTERVAL_MS = 16;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private SharedPreferences prefs;
    private float lastGx;
    private float lastGy;
    private float lastGz;
    private long lastPushMs;
    private boolean registered;
    private boolean alertActive;
    private boolean countdownActive;
    private String alertEventId = "";
    private String alertTitle = "Garly SOS";
    private String alertText = "SOS countdown active";
    private final Handler alertHandler = new Handler(Looper.getMainLooper());
    private final Runnable stopAlertRunnable = () -> {
        if (!alertActive) return;
        clearAlertAndResumeProtection();
    };

    // Walk mode. The deadline is held here rather than in the page because a
    // WebView is paused when the screen locks, and a dead-man's switch that stops
    // counting the moment the phone goes in a pocket protects nobody.
    private PowerManager.WakeLock walkWakeLock;
    private final Handler walkHandler = new Handler(Looper.getMainLooper());
    private final Runnable walkSilenceRunnable = this::onWalkSilence;

    public static boolean isRunning(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_WALK_START.equals(action) || ACTION_WALK_PING.equals(action)) {
            long silenceMs = intent != null ? intent.getLongExtra("silence_ms", 300000L) : 300000L;
            armWalk(silenceMs);
            return START_STICKY;
        }
        if (ACTION_WALK_STOP.equals(action)) {
            disarmWalk();
            return prefs.getBoolean(KEY_ARMED, false) ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_CANCEL_ALERT.equals(action)) {
            cancelAlertFromNotification(intent != null ? intent.getStringExtra("event_id") : "");
            return prefs.getBoolean(KEY_ARMED, false) ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_ALERT.equals(action)) {
            String state = intent != null ? intent.getStringExtra("state") : "countdown";
            String incomingEventId = intent != null ? intent.getStringExtra("event_id") : "";
            if (incomingEventId != null && !incomingEventId.isEmpty()) alertEventId = incomingEventId;
            if ("clear".equals(state)) {
                alertHandler.removeCallbacks(stopAlertRunnable);
                clearAlertAndResumeProtection();
                return prefs.getBoolean(KEY_ARMED, false) ? START_STICKY : START_NOT_STICKY;
            }
            alertActive = true;
            countdownActive = !"sent".equals(state) && !"failed".equals(state);
            alertHandler.removeCallbacks(stopAlertRunnable);
            if ("sent".equals(state)) {
                alertTitle = "SOS alert sent";
                alertText = "Your trusted contact was notified";
                vibrate(new long[] {0, 700, 220, 700});
            } else if ("failed".equals(state)) {
                alertTitle = "SOS needs your attention";
                alertText = "Automatic delivery failed; open Garly to continue";
                vibrate(new long[] {0, 700, 220, 700});
            } else {
                alertTitle = "Garly SOS";
                alertText = "SOS countdown active — open Garly to cancel";
                vibrate(new long[] {0, 300, 160, 300});
            }
            startMonitoring();
            refreshNotification();
            if ("sent".equals(state) || "failed".equals(state)) alertHandler.postDelayed(stopAlertRunnable, 60000L);
            return START_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            if (alertActive) return START_STICKY;
            prefs.edit().putBoolean(KEY_ARMED, false).apply();
            stopMonitoring();
            stopSelf();
            return START_NOT_STICKY;
        }
        prefs.edit().putBoolean(KEY_ARMED, true).apply();
        startMonitoring();
        // Protection is explicitly armed by the user. If Android reclaims the
        // process, request a restart so the monitoring state is not silently
        // lost; a future UI milestone will also reconcile the state on resume.
        return START_STICKY;
    }

    /** Arms or re-arms the silence deadline. Called again on every sign of life. */
    private void armWalk(long silenceMs) {
        long window = Math.max(60000L, Math.min(1800000L, silenceMs));
        prefs.edit()
                .putBoolean(KEY_WALK_ACTIVE, true)
                .putLong(KEY_WALK_DEADLINE, System.currentTimeMillis() + window)
                .putBoolean(KEY_WALK_SILENT, false)
                .apply();
        acquireWalkWakeLock();
        startMonitoring();
        walkHandler.removeCallbacks(walkSilenceRunnable);
        walkHandler.postDelayed(walkSilenceRunnable, window);
    }

    private void disarmWalk() {
        walkHandler.removeCallbacks(walkSilenceRunnable);
        prefs.edit().putBoolean(KEY_WALK_ACTIVE, false).putLong(KEY_WALK_DEADLINE, 0L).apply();
        releaseWalkWakeLock();
        if (!prefs.getBoolean(KEY_ARMED, false) && !alertActive) {
            stopMonitoring();
            stopSelf();
        }
    }

    /**
     * The deadline passed with no reply. The page is told through a flag it polls
     * on resume, and the user is alerted here as well, because the whole point is
     * that this has to work when the app is not on screen.
     */
    private void onWalkSilence() {
        if (!prefs.getBoolean(KEY_WALK_ACTIVE, false)) return;
        if (System.currentTimeMillis() < prefs.getLong(KEY_WALK_DEADLINE, 0L)) {
            // A reply landed while this was queued; wait for the new deadline.
            walkHandler.postDelayed(walkSilenceRunnable,
                    Math.max(1000L, prefs.getLong(KEY_WALK_DEADLINE, 0L) - System.currentTimeMillis()));
            return;
        }
        prefs.edit().putBoolean(KEY_WALK_ACTIVE, false).putBoolean(KEY_WALK_SILENT, true).apply();
        releaseWalkWakeLock();
        alertActive = true;
        countdownActive = true;
        alertTitle = "Garly walk mode";
        alertText = "No reply — open Garly now to stop the alert";
        vibrate(new long[] {0, 700, 200, 700, 200, 700});
        startMonitoring();
        refreshNotification();
    }

    /** True once, then cleared: the page consumes this to run the SOS countdown. */
    public static boolean consumeWalkSilence(Context context) {
        SharedPreferences store = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!store.getBoolean(KEY_WALK_SILENT, false)) return false;
        store.edit().putBoolean(KEY_WALK_SILENT, false).apply();
        return true;
    }

    public static long walkDeadline(Context context) {
        SharedPreferences store = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        return store.getBoolean(KEY_WALK_ACTIVE, false) ? store.getLong(KEY_WALK_DEADLINE, 0L) : 0L;
    }

    private void acquireWalkWakeLock() {
        if (walkWakeLock != null && walkWakeLock.isHeld()) return;
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power == null) return;
        walkWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "garly:walk");
        walkWakeLock.setReferenceCounted(false);
        // Bounded so a stuck walk can never drain the battery indefinitely.
        walkWakeLock.acquire(2 * 60 * 60 * 1000L);
    }

    private void releaseWalkWakeLock() {
        if (walkWakeLock == null) return;
        if (walkWakeLock.isHeld()) walkWakeLock.release();
        walkWakeLock = null;
    }

    private void startMonitoring() {
        Notification notification = buildNotification();
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);
        prefs.edit().putBoolean(KEY_RUNNING, true).apply();
        if (registered || sensorManager == null) return;
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
        registered = accelerometer != null || gyroscope != null;
    }

    private void stopMonitoring() {
        if (sensorManager != null && registered) sensorManager.unregisterListener(this);
        registered = false;
        prefs.edit().putBoolean(KEY_RUNNING, false).apply();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, GarlyWebActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                782,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        String channelId = alertActive ? ALERT_CHANNEL_ID : CHANNEL_ID;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(alertActive ? alertTitle : "Garly Protection active")
                .setContentText(alertActive ? alertText : "Motion sensors are monitoring this device")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setCategory(alertActive ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_SERVICE)
                .setPriority(alertActive ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_LOW);
        if (alertActive && countdownActive) {
            Intent cancelIntent = new Intent(this, ProtectionService.class)
                    .setAction(ACTION_CANCEL_ALERT)
                    .putExtra("event_id", alertEventId);
            PendingIntent cancelPendingIntent = PendingIntent.getService(
                    this,
                    783,
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "I'm okay - cancel SOS", cancelPendingIntent);
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Garly Protection",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Visible status while Garly Protection monitors motion sensors");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
            NotificationChannel alertChannel = new NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "Garly SOS alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            alertChannel.setDescription("Urgent local feedback for Garly SOS countdowns and delivery");
            alertChannel.enableVibration(true);
            alertChannel.setVibrationPattern(new long[] {0, 350, 180, 350});
            alertChannel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, null);
            manager.createNotificationChannel(alertChannel);
        }
    }

    private void refreshNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void cancelAlertFromNotification(String eventId) {
        if (!alertActive) return;
        if (eventId != null && !eventId.isEmpty() && !alertEventId.isEmpty() && !eventId.equals(alertEventId)) return;
        long cancelledAt = System.currentTimeMillis();
        prefs.edit()
                .putString(KEY_CANCELLED_EVENT_ID, alertEventId)
                .putLong(KEY_CANCELLED_AT, cancelledAt)
                .apply();
        sendBroadcast(new Intent(ACTION_SOS_CANCELLED)
                .setPackage(getPackageName())
                .putExtra("event_id", alertEventId)
                .putExtra("cancelled_at", cancelledAt));
        vibrate(new long[] {0, 120});
        clearAlertAndResumeProtection();
    }

    private void clearAlertAndResumeProtection() {
        alertHandler.removeCallbacks(stopAlertRunnable);
        alertActive = false;
        countdownActive = false;
        alertEventId = "";
        if (prefs.getBoolean(KEY_ARMED, false)) {
            startMonitoring();
            refreshNotification();
        } else {
            stopMonitoring();
            stopSelf();
        }
    }

    private void vibrate(long[] pattern) {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            lastGx = event.values[0];
            lastGy = event.values[1];
            lastGz = event.values[2];
            return;
        }
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        long now = System.currentTimeMillis();
        if (now - lastPushMs < SAMPLE_INTERVAL_MS) return;
        lastPushMs = now;
        try {
            JSONObject payload = new JSONObject();
            payload.put("x", event.values[0]);
            payload.put("y", event.values[1]);
            payload.put("z", event.values[2]);
            payload.put("alpha", lastGz * (180.0 / Math.PI));
            payload.put("beta", lastGx * (180.0 / Math.PI));
            payload.put("gamma", lastGy * (180.0 / Math.PI));
            payload.put("ts", now);
            sendBroadcast(new Intent(ACTION_MOTION).setPackage(getPackageName())
                    .putExtra("payload", payload.toString()));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onDestroy() {
        walkHandler.removeCallbacks(walkSilenceRunnable);
        releaseWalkWakeLock();
        stopMonitoring();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
