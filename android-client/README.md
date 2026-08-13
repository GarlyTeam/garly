# Garly Android client

Garly's interface is the web application served from `https://garlyapp.pro`. It is
not in this directory. What is here is the Android code around it: the native host
that loads it, and the services that have to be native to work at all.

This is the reviewed development client. It may include security hardening prepared
after the most recent store testing build; published store versions are identified
separately by their version code.

## Included components

- Native WebView host restricted to the official `https://garlyapp.pro` origin
- Android Credential Manager integration for Google sign-in
- Install-scoped WebView session isolation
- Foreground service for user-started Protection Mode
- Foreground services for live location sharing and for the acoustic feature,
  each started only by the person and each stoppable from its own notification
- Accelerometer and gyroscope bridge
- Local SOS notifications and vibration feedback
- Android 16 / API level 36 target configuration
- The advertising ID permissions are removed from the merged manifest, so the
  shipped app cannot read one

The safety scoring engine and product interface currently remain in the hosted web application and are not part of this source release. Backend services, production signing configuration and private operational tooling are also excluded.

## Security properties

The client:

- blocks cleartext traffic and mixed content;
- rejects untrusted WebView origins;
- never grants the camera, and never grants a request that carries anything
  besides audio, so the camera cannot ride along inside a microphone grant;
- grants the microphone only to the official page, only for the opt-in acoustic
  feature, and only once Android's own permission dialog has been accepted;
- disables Android backup of WebView session data;
- exposes the native JavaScript bridge only to the official application page;
- keeps signing credentials outside the repository.

## Local debug build

Requirements:

- JDK 17
- Android SDK with API level 36

On Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

On macOS or Linux:

```bash
./gradlew :app:assembleDebug
```

This creates an unsigned debug build for development. It does not reproduce the production signing process.

## Limitations

Garly is in public beta and Android testing. Motion detection and SOS features are experimental and cannot guarantee that every dangerous event will be detected or delivered.

## Source use

Garly-owned files are provided for inspection and security review. No open-source license is granted. Files that contain their own third-party license header remain governed by that license.
