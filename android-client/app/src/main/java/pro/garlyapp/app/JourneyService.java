package pro.garlyapp.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * One-shot, user-started return-home follow-up for a Live Map journey.
 * It waits for a confirmed departure before accepting a confirmed re-entry,
 * so opening the map while already at home never creates a false notification.
 */
public final class JourneyService extends Service implements LocationListener {
    public static final String ACTION_START = "pro.garlyapp.app.action.START_JOURNEY";
    public static final String ACTION_STOP = "pro.garlyapp.app.action.STOP_JOURNEY";
    public static final String ACTION_COMPLETE = "pro.garlyapp.app.action.COMPLETE_JOURNEY";

    private static final String PREFS = "garly_journey_service";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_PENDING = "completion_pending";
    private static final String CHANNEL_ID = "garly_journey_tracking";
    private static final String RETURN_CHANNEL_ID = "garly_journey_return";
    private static final int FOREGROUND_ID = 791;
    private static final int RETURN_ID = 792;

    private SharedPreferences prefs;
    private LocationManager locationManager;
    private boolean listening;
    private double homeLatitude;
    private double homeLongitude;
    private float homeRadius;
    private String homeName;
    private String language;
    private String sessionId;
    private String uploadUrl;
    private String uploadToken;
    private String liveJourneyId;
    private boolean uploadOnly;
    private long shareExpiresAt;
    private long startedAt;
    private long departedAt;
    private boolean departed;
    private int outsideSamples;
    private int insideSamples;
    private final Object uploadLock = new Object();
    private String pendingUploadUrl;
    private byte[] pendingUploadBody;
    private boolean uploadWorkerRunning;

    public static boolean isRunning(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ACTIVE, false);
    }

    public static String consumeCompletion(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!preferences.getBoolean(KEY_PENDING, false)) return "null";
        try {
            JSONObject result = new JSONObject();
            result.put("completed", true);
            result.put("homeName", preferences.getString("home_name", "Home"));
            result.put("sessionId", preferences.getString("session_id", ""));
            preferences.edit().putBoolean(KEY_PENDING, false).apply();
            return result.toString();
        } catch (Exception ignored) {
            preferences.edit().putBoolean(KEY_PENDING, false).apply();
            return "null";
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopJourney();
            return START_NOT_STICKY;
        }
        if (ACTION_COMPLETE.equals(action)) {
            loadOrApply(intent, true);
            startForegroundTracking();
            finishJourney(false);
            return START_NOT_STICKY;
        }
        loadOrApply(intent, false);
        startForegroundTracking();
        requestLocationUpdates();
        return START_STICKY;
    }

    private void loadOrApply(Intent intent, boolean completionOnly) {
        String incomingSession = intent == null ? "" : safe(intent.getStringExtra("session_id"));
        String savedSession = prefs.getString("session_id", "");
        boolean newSession = !incomingSession.isEmpty() && !incomingSession.equals(savedSession);

        homeLatitude = intent != null && intent.hasExtra("home_latitude") ? intent.getDoubleExtra("home_latitude", 0) : Double.longBitsToDouble(prefs.getLong("home_latitude", Double.doubleToLongBits(0)));
        homeLongitude = intent != null && intent.hasExtra("home_longitude") ? intent.getDoubleExtra("home_longitude", 0) : Double.longBitsToDouble(prefs.getLong("home_longitude", Double.doubleToLongBits(0)));
        homeRadius = (float) (intent != null && intent.hasExtra("home_radius") ? intent.getDoubleExtra("home_radius", 90) : prefs.getFloat("home_radius", 90));
        homeRadius = Math.max(50f, Math.min(120f, homeRadius));
        homeName = intent != null && intent.hasExtra("home_name") ? safe(intent.getStringExtra("home_name")) : prefs.getString("home_name", "Home");
        language = intent != null && intent.hasExtra("language") ? safe(intent.getStringExtra("language")) : prefs.getString("language", "en");
        sessionId = incomingSession.isEmpty() ? savedSession : incomingSession;
        uploadUrl = intent != null && intent.hasExtra("upload_url") ? safe(intent.getStringExtra("upload_url")) : prefs.getString("upload_url", "");
        uploadToken = intent != null && intent.hasExtra("upload_token") ? safe(intent.getStringExtra("upload_token")) : prefs.getString("upload_token", "");
        liveJourneyId = intent != null && intent.hasExtra("live_journey_id") ? safe(intent.getStringExtra("live_journey_id")) : prefs.getString("live_journey_id", "");
        uploadOnly = intent != null && intent.hasExtra("upload_only") ? intent.getBooleanExtra("upload_only", false) : prefs.getBoolean("upload_only", false);
        shareExpiresAt = intent != null && intent.hasExtra("share_expires_at") ? intent.getLongExtra("share_expires_at", 0L) : prefs.getLong("share_expires_at", 0L);
        startedAt = newSession ? System.currentTimeMillis() : prefs.getLong("started_at", System.currentTimeMillis());
        departed = !newSession && prefs.getBoolean("departed", false);
        departedAt = !newSession ? prefs.getLong("departed_at", 0L) : 0L;
        outsideSamples = !newSession ? prefs.getInt("outside_samples", 0) : 0;
        insideSamples = !newSession ? prefs.getInt("inside_samples", 0) : 0;

        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(KEY_ACTIVE, !completionOnly)
                .putString("session_id", sessionId)
                .putLong("home_latitude", Double.doubleToLongBits(homeLatitude))
                .putLong("home_longitude", Double.doubleToLongBits(homeLongitude))
                .putFloat("home_radius", homeRadius)
                .putString("home_name", homeName.isEmpty() ? "Home" : homeName)
                .putString("language", language.isEmpty() ? "en" : language)
                .putString("upload_url", uploadUrl)
                .putString("upload_token", uploadToken)
                .putString("live_journey_id", liveJourneyId)
                .putBoolean("upload_only", uploadOnly)
                .putLong("share_expires_at", shareExpiresAt)
                .putLong("started_at", startedAt)
                .putBoolean("departed", departed)
                .putLong("departed_at", departedAt)
                .putInt("outside_samples", outsideSamples)
                .putInt("inside_samples", insideSamples);
        if (newSession) editor.putBoolean(KEY_PENDING, false);
        editor.apply();
    }

    private void startForegroundTracking() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0;
        ServiceCompat.startForeground(this, FOREGROUND_ID, trackingNotification(), type);
    }

    private void requestLocationUpdates() {
        if (listening || locationManager == null) return;
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            stopJourney();
            return;
        }
        try {
            if (fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 15000L, 20f, this);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 20000L, 30f, this);
            }
            listening = true;
        } catch (SecurityException ignored) {
            stopJourney();
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!prefs.getBoolean(KEY_ACTIVE, false) || location == null) return;
        uploadCurrentLocation(location);
        if (uploadOnly) {
            if (shareExpiresAt > 0 && System.currentTimeMillis() >= shareExpiresAt) {
                endRemoteJourney("expired");
                stopJourney();
            }
            return;
        }
        float[] result = new float[1];
        Location.distanceBetween(location.getLatitude(), location.getLongitude(), homeLatitude, homeLongitude, result);
        float distance = result[0];
        float departureRadius = Math.max(homeRadius + 80f, homeRadius * 1.65f);
        if (!departed) {
            outsideSamples = distance >= departureRadius ? outsideSamples + 1 : 0;
            if (outsideSamples >= 2) {
                departed = true;
                departedAt = System.currentTimeMillis();
                insideSamples = 0;
            }
        } else {
            insideSamples = distance <= homeRadius ? insideSamples + 1 : 0;
            if (insideSamples >= 2 && System.currentTimeMillis() - Math.max(departedAt, startedAt) >= 30000L) {
                finishJourney(true);
                return;
            }
        }
        prefs.edit()
                .putBoolean("departed", departed)
                .putLong("departed_at", departedAt)
                .putInt("outside_samples", outsideSamples)
                .putInt("inside_samples", insideSamples)
                .apply();
    }

    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    private void finishJourney(boolean pendingForWeb) {
        endRemoteJourney("home");
        prefs.edit().putBoolean(KEY_ACTIVE, false).putBoolean(KEY_PENDING, pendingForWeb).apply();
        removeLocationUpdates();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(RETURN_ID, returnNotification());
        stopForegroundCompat();
        stopSelf();
    }

    private void stopJourney() {
        /* Fermare il servizio non basta a fermare la condivisione. Il percorso
           resta aperto sul server, e chi ha il link continua a vedere l'ultima
           posizione fino alla scadenza: dal telefono sembra finita, dall'altra
           parte no. Chiudere anche la' e' l'unica parte che conta davvero, e il
           servizio sa gia' come si fa. */
        if (uploadOnly) endRemoteJourney("stopped");
        prefs.edit().putBoolean(KEY_ACTIVE, false).apply();
        removeLocationUpdates();
        stopForegroundCompat();
        stopSelf();
    }

    private void removeLocationUpdates() {
        if (locationManager != null && listening) {
            try { locationManager.removeUpdates(this); } catch (SecurityException ignored) { }
        }
        listening = false;
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(this, GarlyWebActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 793, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /* Una via d'uscita che non passa dalla pagina.
       Finora l'unico modo per smettere di condividere era un pulsante dentro
       l'app, e quindi dipendeva dal fatto che la pagina sapesse di stare
       condividendo. Quando non lo sapeva, o quando il blocco privacy si metteva
       davanti, non restava niente da premere mentre la posizione continuava ad
       andare. La notifica invece c'e' sempre, per forza: e' quella che tiene
       vivo il servizio. Il tasto sta li'. */
    private PendingIntent stopSharingIntent() {
        Intent intent = new Intent(this, JourneyService.class).setAction(ACTION_STOP);
        return PendingIntent.getService(this, 794, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification trackingNotification() {
        boolean italian = language != null && language.toLowerCase().startsWith("it");
        if (uploadOnly) {
            return new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setContentTitle(italian ? "Live Journey attivo" : "Live Journey active")
                    .setContentText(italian ? "Stai condividendo temporaneamente la posizione scelta" : "Your chosen contacts can temporarily see your latest position")
                    .setContentIntent(openAppIntent())
                    .addAction(R.drawable.ic_notification_icon,
                            italian ? "Interrompi la condivisione" : "Stop sharing",
                            stopSharingIntent())
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(italian ? "Controllo rientro attivo" : "Return-home follow-up active")
                .setContentText(italian ? "Garly ti chiederà com'è andata quando rientri a casa" : "Garly will ask how it went when you return home")
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private Notification returnNotification() {
        boolean italian = language != null && language.toLowerCase().startsWith("it");
        return new NotificationCompat.Builder(this, RETURN_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(italian ? "Rientro a casa" : "Back home")
                .setContentText(italian ? "Sei di nuovo a casa. Com'è andata?" : "You're home. How did it go?")
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel tracking = new NotificationChannel(CHANNEL_ID, "Garly journey follow-up", NotificationManager.IMPORTANCE_LOW);
        tracking.setDescription("Visible while Garly waits for a return to the saved Home area");
        manager.createNotificationChannel(tracking);
        NotificationChannel returned = new NotificationChannel(RETURN_CHANNEL_ID, "Garly return-home check-ins", NotificationManager.IMPORTANCE_HIGH);
        returned.setDescription("One-time check-in after returning home from a Live Map journey");
        manager.createNotificationChannel(returned);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private void uploadCurrentLocation(Location location) {
        if (uploadUrl == null || uploadUrl.isEmpty() || uploadToken == null || uploadToken.isEmpty()) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("latitude", location.getLatitude());
            payload.put("longitude", location.getLongitude());
            payload.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
            postJourneyPayload(uploadUrl, payload);
        } catch (Exception ignored) { }
    }

    private void endRemoteJourney(String reason) {
        if (uploadUrl == null || uploadUrl.isEmpty() || uploadToken == null || uploadToken.isEmpty()) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("reason", reason);
            postJourneyPayload(uploadUrl.replaceFirst("/upload$", "/upload/end"), payload);
        } catch (Exception ignored) { }
    }

    private void postJourneyPayload(String endpoint, JSONObject payload) {
        synchronized (uploadLock) {
            // Keep only the latest known point while offline. This prevents a
            // queue from growing and preserves Garly's latest-position-only model.
            pendingUploadUrl = endpoint;
            pendingUploadBody = payload.toString().getBytes(StandardCharsets.UTF_8);
            if (uploadWorkerRunning) return;
            uploadWorkerRunning = true;
        }
        new Thread(this::drainJourneyUploads, "garly-journey-upload").start();
    }

    private void drainJourneyUploads() {
        while (true) {
            final String endpoint;
            final byte[] body;
            synchronized (uploadLock) {
                endpoint = pendingUploadUrl;
                body = pendingUploadBody;
                pendingUploadUrl = null;
                pendingUploadBody = null;
                if (endpoint == null || body == null) {
                    uploadWorkerRunning = false;
                    return;
                }
            }
            boolean delivered = postJourneyPayloadWithRetry(endpoint, body);
            prefs.edit()
                    .putLong(delivered ? "last_upload_at" : "last_upload_failed_at", System.currentTimeMillis())
                    .apply();
        }
    }

    private boolean postJourneyPayloadWithRetry(String endpoint, byte[] body) {
        final long[] retryDelays = new long[] {0L, 2000L, 5000L, 12000L};
        for (int attempt = 0; attempt < retryDelays.length; attempt++) {
            if (retryDelays[attempt] > 0L) {
                try {
                    Thread.sleep(retryDelays[attempt]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            int status = postJourneyPayloadOnce(endpoint, body);
            if (status >= 200 && status < 300) return true;
            // Invalid or revoked journey credentials will not recover by retrying.
            if (status == 401 || status == 403 || status == 404) return false;
        }
        return false;
    }

    private int postJourneyPayloadOnce(String endpoint, byte[] body) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Journey " + uploadToken);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            return connection.getResponseCode();
        } catch (Exception ignored) {
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override
    public void onDestroy() {
        removeLocationUpdates();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
