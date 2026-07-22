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
import android.os.VibrationEffect;
import android.os.Vibrator;

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
    public static final String ACTION_ALERT = "pro.garlyapp.app.action.PROTECTION_ALERT";
    public static final String ACTION_MOTION = "pro.garlyapp.app.action.PROTECTION_MOTION";
    public static final String PREFS = "garly_protection_service";
    public static final String KEY_RUNNING = "running";

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
    private String alertTitle = "Garly SOS";
    private String alertText = "SOS countdown active";
    private final Handler alertHandler = new Handler(Looper.getMainLooper());
    private final Runnable stopAlertRunnable = () -> {
        if (!alertActive) return;
        alertActive = false;
        stopMonitoring();
        stopSelf();
    };

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
        if (ACTION_ALERT.equals(action)) {
            String state = intent != null ? intent.getStringExtra("state") : "countdown";
            if ("clear".equals(state)) {
                alertHandler.removeCallbacks(stopAlertRunnable);
                alertActive = false;
                stopMonitoring();
                stopSelf();
                return START_NOT_STICKY;
            }
            alertActive = true;
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
            if ("sent".equals(state)) alertHandler.postDelayed(stopAlertRunnable, 60000L);
            return START_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            if (alertActive) return START_STICKY;
            stopMonitoring();
            stopSelf();
            return START_NOT_STICKY;
        }
        startMonitoring();
        // Protection is explicitly armed by the user. If Android reclaims the
        // process, request a restart so the monitoring state is not silently
        // lost; a future UI milestone will also reconcile the state on resume.
        return START_STICKY;
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
        return new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(alertActive ? alertTitle : "Garly Protection active")
                .setContentText(alertActive ? alertText : "Motion sensors are monitoring this device")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setCategory(alertActive ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_SERVICE)
                .setPriority(alertActive ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_LOW)
                .build();
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
            manager.createNotificationChannel(alertChannel);
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
        stopMonitoring();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
