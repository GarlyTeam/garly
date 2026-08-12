const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const activity = fs.readFileSync(
  path.join(root, "android-client/app/src/main/java/pro/garlyapp/app/GarlyWebActivity.java"),
  "utf8"
);
const manifest = fs.readFileSync(
  path.join(root, "android-client/app/src/main/AndroidManifest.xml"),
  "utf8"
);

const requiredActivityProtections = [
  'private static final String APP_SCHEME = "https"',
  'private static final String APP_HOST = "garlyapp.pro"',
  "WebSettings.MIXED_CONTENT_NEVER_ALLOW",
  "settings.setAllowFileAccess(false)",
  "settings.setAllowContentAccess(false)",
  "setAcceptThirdPartyCookies(webView, false)",
  "APP_HOST.equalsIgnoreCase(uri.getHost())",
  "if (!hasTrustedPage() || token == null || token.isEmpty()) return"
];

/* The WebView used to deny every permission request outright, and this file
   checked for that one line. It cannot any more: the acoustic safety feature
   needs the microphone, so a blanket deny would have meant a switch that does
   nothing. What replaced it has to be checked property by property, because
   "sometimes grants the microphone" is only safe if each of these holds.

   Camera is still never granted. Neither is anything else. */
const requiredPermissionRules = [
  // 1. a request from anywhere but the official page is refused before
  //    anything else is read from it
  /if \(request == null \|\| !isTrustedAppUri\(request\.getOrigin\(\)\)\) \{\s*\n\s*if \(request != null\) request\.deny\(\);/,
  // 2. audio is the only resource that can ever be granted: a request that
  //    carries anything else is denied whole, so camera cannot ride along
  //    inside a microphone grant
  /if \(PermissionRequest\.RESOURCE_AUDIO_CAPTURE\.equals\(resource\)\)[\s\S]{0,80}?else \{[\s\S]{0,220}?request\.deny\(\);\s*\n\s*return;/,
  // 3. and the grant only ever names audio, never the resources the page asked for
  /request\.grant\(new String\[\] \{ PermissionRequest\.RESOURCE_AUDIO_CAPTURE \}\)/,
  // 4. and only once Android itself has the runtime permission, which means
  //    the person said yes in the system dialog
  /ContextCompat\.checkSelfPermission\(this, Manifest\.permission\.RECORD_AUDIO\)\s*\n?\s*== PackageManager\.PERMISSION_GRANTED/
];

for (const rule of requiredPermissionRules) {
  if (!rule.test(activity)) {
    throw new Error(`Missing WebView permission rule: ${rule}`);
  }
}

for (const protection of requiredActivityProtections) {
  if (!activity.includes(protection)) {
    throw new Error(`Missing Android WebView protection: ${protection}`);
  }
}

const forbiddenActivityPatterns = [
  /MIXED_CONTENT_COMPATIBILITY_MODE/,
  /startsWith\("http:\/\/garlyapp\.pro"\)/,
  /request\.grant\(request\.getResources\(\)\)/
];

for (const pattern of forbiddenActivityPatterns) {
  if (pattern.test(activity)) {
    throw new Error(`Unsafe Android WebView behavior is present: ${pattern}`);
  }
}

const requiredManifestProtections = [
  'android:allowBackup="false"',
  'android:fullBackupContent="false"',
  'android:usesCleartextTraffic="false"'
];

for (const protection of requiredManifestProtections) {
  if (!manifest.includes(protection)) {
    throw new Error(`Missing Android manifest protection: ${protection}`);
  }
}

console.log("OK: Android WebView is HTTPS-only, origin-restricted, backup-disabled; "
  + "camera is never granted and the microphone only after the person allows it");
