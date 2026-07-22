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
  "runOnUiThread(request::deny)",
  "if (!hasTrustedPage() || token == null || token.isEmpty()) return"
];

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

console.log("OK: Android WebView is HTTPS-only, origin-restricted, backup-disabled and permission-deny-by-default");
