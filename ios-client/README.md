# Garly for iOS

The iOS shell. One `WKWebView` on `https://garlyapp.pro/app/index.html`, the
same start URL as the Android TWA, plus the native bridge the web app expects.

Bundle id `pro.garlyapp.app` · iPhone only · portrait · minimum iOS 16.

## The one decision everything else follows from

The web app talks to the phone through **`window.GarlyAndroid`**, from 76 places
inside `app/index.html`. The name stays on iOS. Two reasons, and the second is
the one that matters:

1. Renaming it means editing a 1.3 MB single-file front end that is also being
   edited on Windows. That is an afternoon of merge conflicts for nothing.
2. `coreDemoPreview()` decides it is running in a browser by asking whether
   `window.GarlyAndroid` is **missing**. An iOS app without the object would
   believe it was a web page and show the subscription as a preview that cannot
   be bought — a dead end inside a shipped app.

So `Garly/Resources/bridge.js` defines `window.GarlyAndroid` at document start,
and Swift sits behind it.

## Sync versus async, and why there is a state snapshot

Android's bridge is synchronous: `hasNativeSensors()` returns a boolean there
and then. WKWebView only offers `postMessage`, which returns nothing. So:

| direction | how |
|---|---|
| page → app | `webkit.messageHandlers.garly.postMessage({method, args})` |
| app → page | Swift pushes a snapshot into `window.__garlyNativeApply(…)` |

Getters read the snapshot. Actions post a message. The consume-once channels
(`walkSilence`, `sosCancellation`, `journeyCompletion`, `purchaseResult`) each
carry a counter that only ever grows, so a snapshot that arrives twice cannot
deliver the same event twice. For a walk-mode silence, delivering it twice means
a second SOS nobody asked for.

## What this build claims, and what it does not

`GarlyConfig.capabilities` is the whole answer. Every flag is `false` today, and
`bridge.js` **defines a method only where the flag is true**.

That is not a placeholder. Every call site in `app/index.html` is guarded by
`typeof … === "function"`, so an absent method makes the app fall back to its own
web implementation — the one mobile Safari has been running all along, already
including the iOS motion-permission handling at `openGuard()`. A method that
existed but did nothing would instead make the app believe the phone was
watching when nothing was, which is the one thing this codebase forbids.

| capability | today | what turning it on needs |
|---|---|---|
| `motion` | **on** — see below | done |
| `walkMode` | **on** — see below | done |
| `journey` | false | CoreLocation background updates |
| `acousticProtection` | false | AVAudioEngine + TFLite, `audio` background mode |
| `acousticDiagnostics` | false | nothing: the Sound Lab is Android-internal |
| `billing` | false, no paywall shown | StoreKit 2 + server verification |
| `googleSignIn` | **on** — needs the server deployed | see below |

A build with everything false is a complete, honest app: sign-in by e-mail, the
chat, the diary, Protection with web sensors, an SOS with location. It is worth
putting on TestFlight before any native work starts, because it proves the
shell, the bridge and the signing pipeline in one go.

## Motion: what the native sensors do, and what keeps them alive

`ProtectionService.swift` is the counterpart of `ProtectionService.java`, and it
copies its most important property: **it decides nothing.** Android is explicit
that the scoring engine lives in the web layer, and streams raw samples to the
page. This does the same, so one detector runs on both platforms and a threshold
tuned on Android means the same thing here.

Which makes the units the whole game:

| | Android | iOS |
|---|---|---|
| acceleration | `TYPE_ACCELEROMETER`, m/s², gravity included | CoreMotion reports **g**, and flips gravity's sign → `× -9.81` |
| rotation | rad/s → deg/s | rad/s → deg/s |
| rate | 16 ms | 16 ms |

The page scores on magnitude, so the sign flip changes nothing by itself; it is
there so the components mean the same on both platforms. `bridge-contract.test.mjs`
fails if either side drifts — a sample in g rather than m/s² is well-formed and
reads as a world where nothing ever happens.

**Staying alive is the hard part, not the accelerometer.** iOS has no foreground
service. A backgrounded app is suspended within seconds and CoreMotion stops
with it, so "Protection is on" would quietly become false the moment the phone
went into a pocket — the exact situation the feature exists for. The only
sanctioned way to keep running is background location updates, so the service
holds a `CLLocationManager` it barely reads. The location is genuinely used (an
SOS carries one), and the run loop it keeps alive is what lets the sensors keep
sampling. Hence `UIBackgroundModes: location` and the Always usage string —
setting `allowsBackgroundLocationUpdates` without the background mode raises an
exception, so those two travel together.

If the user grants only When In Use, or denies location outright, sampling still
runs while the app is on screen and stops when it is not. Nothing pretends
otherwise: `sensorStatus().listening` reports what is true.

### Two things to know before submitting

- **Time Sensitive notifications.** An SOS countdown should break through a
  Focus, which is what the interruption level is for, and it needs the matching
  capability on the App ID. Until that is added the notification stays at
  `.active` rather than claiming an urgency iOS will not honour.
- **Always location will be questioned in review.** The reviewer notes have to
  say plainly why a safety app needs it: without it the app stops watching when
  the screen locks. Expect this to be the single most scrutinised part.

## Walk mode: a deadline kept three ways

Android moved this out of the page for a reason it states itself — a WebView is
paused when the screen locks, and *a dead-man's switch that stops counting the
moment the phone goes in a pocket protects nobody*. iOS is worse: the whole app
is suspended. So the deadline is kept three ways, and the timer is the weakest
of them.

| | fires when |
|---|---|
| a timer | the app is alive |
| a scheduled local notification | the app is suspended, or has been killed outright |
| the deadline written to disk | nothing was running at all; a relaunch reconciles it |

`hasWalkMode()` returning true also removes the page's own *"the deadline can
slip"* warning. That warning is a promise in reverse: with the capability on, it
must not slip. The three mechanisms are what makes that true, and
`bridge-contract.test.mjs` fails if any of them is removed.

The walk window is clamped to 60–1800 seconds, the same as Android's `armWalk`,
and a ping that lands while the timer is already queued reschedules instead of
raising an alarm that is no longer true — also copied from Android.

### The bug this uncovered

The consume-once channels had a hole, introduced when the bridge was written and
harmless until walk mode made it live.

Each channel carries a counter, and the page ignores any id it has already seen.
That works inside one document. **It does nothing across a reload**, because a
fresh document starts counting from zero — so an event the previous document had
already taken would be handed to the new one and fire again. For a walk-mode
silence that is a second SOS nobody asked for.

The counter cannot see this; only the app declining to republish can. So a
channel now also carries `delivered`, set when the page acknowledges, and the
snapshot skips delivered channels. Anything written to disk to survive a kill is
cleared at the same moment, through `bridgeDidConsume`, or the next launch
replays it. Both halves are covered by tests.

### Two differences from Android that cannot be closed

**Reopening works; being killed does not.** Android arms `START_STICKY`, so a
service the system reclaims comes back on its own — the rejection audit verified
that with Protection armed and the process force-stopped. iOS has no equivalent:
an app the user swipes away, or that jetsam kills, stays dead. What does work is
the same as Android on reopen — the page restores `guardOn`, `garly-native-ready`
fires, and sampling restarts for real rather than merely being claimed.

**A scheduled notification is not a wake-up.** Android's timer runs inside a
live process, so on silence the service can act. On iOS, if the app has been
killed, the notification fires and tells the user — but nothing runs until they
open the app, at which point `reconcileWalk` catches up and the SOS countdown
starts. The alarm reaches them either way; what differs is that on iOS the app
cannot act on it by itself while it is dead.

### Known limit, not papered over

Android puts the *walk-mode deadline* in the service precisely because a paused
WebView cannot be trusted to count. On iOS the detection engine is still in the
page, and background JS is throttled. Motion samples keep arriving, but the
page's own timers run slower in the background than on screen. Moving the
scoring behind the native boundary is the fix, and it is the same milestone
Android has open (`ProtectionService.java`: *"will be moved behind this boundary
in the next milestone"*).

## Google sign-in

Google refuses to serve its own sign-in inside an embedded web view. Observed,
not predicted: before the native flow existed, the login screen fell back to
Google's web button and printed *"Google sign-in couldn't load. Check your
connection, or use email below."* Nothing was wrong with the connection. A
reviewer opening the app would have read that as a broken app, which is a 2.1
rejection before anything else gets looked at.

So iOS runs the native flow. `GoogleSignInCoordinator.swift`:
ASWebAuthenticationSession — Safari's own view, address bar visible, the app
unable to read what is typed into it — authorization code with PKCE, no client
secret, handing the page the same id_token the web button would have produced
through `window.__garlyNativeGoogleCredential`.

**No change to `index.html` was needed.** The page already has
`renderNativeGoogleButton`, and it switches to it the moment the bridge exposes
`startGoogleSignIn`, which happens as soon as `googleIOSClientID` is non-empty.

It took two changes, and the second is easy to miss:

1. An OAuth client of type **iOS** in Google Cloud Console — now in
   `GarlyConfig.googleIOSClientID`, same project (815085989019) as the web one.
2. `garly-api/server.js` accepting more than one audience. It used to check
   `info.aud === googleClientId`, a single web client id. A native flow issues
   an id_token whose audience is the *iOS* client, so it was rejected **after
   the person had already typed their password**. Now `googleClientIds` is a
   list and the check is a membership test — still over our own ids only, which
   is the part that matters: a token minted for somebody else's client
   authenticates somebody else's user. Covered by
   `garly-api/test/google-auth.test.mjs`.

**The server change has to be deployed before iOS sign-in works.** The app on a
phone talks to production, not to this checkout.

## Where an install says it came from

`analyticsSource()` in `index.html` used to return `android_app` whenever
`window.GarlyAndroid` existed — which on iOS it does, deliberately. It now reads
the platform out of the boot payload instead.

The half of that change worth remembering is the server half. `ALLOWED_SOURCES`
in `analytics-service.js` did not contain `ios_app`, and an unrecognised source
is not rejected there — it is quietly recorded as `direct`. Fixing only the page
would have turned every iOS install into web traffic, which is worse than
mislabelling it as Android, because nobody would ever go looking for it. Both
lists are now checked against each other by `bridge-contract.test.mjs`.

## The blank screen the shell refuses to show

A load that **fails** raises an error. A load that simply never finishes raises
nothing, and the shell would sit on its theme colour forever — which is exactly
the blank coloured screen the Android handover describes, and it is
indistinguishable from a crash to the person holding the phone.

So `loadApp()` arms a watchdog, cancelled at `didCommit`. It measures the page
starting to exist, not finishing: a 1.3 MB page finishes when it finishes, and
warning about a screen the user can already read is a false alarm, which is
worse than no alarm. `webViewWebContentProcessDidTerminate` reloads, the way
Android handles `onRenderProcessGone`.

## Known gap, one line, not fixed here

`app/index.html` line 10019:

```js
return window.GarlyAndroid ? "android_app" : "direct";
```

An iOS install reports itself as `android_app` to analytics. The fix is one
line — the shell already sets `window.__garlyNativeBoot.platform = "ios"`:

```js
return window.GarlyAndroid
  ? (window.__garlyNativeBoot?.platform === "ios" ? "ios_app" : "android_app")
  : "direct";
```

Left undone on purpose: `index.html` is being edited on Windows on branch
`frontend-deinline`, and this checkout is not a git repository. Make the change
there, not here.

## Building

```bash
xcodebuild -project Garly.xcodeproj -scheme Garly -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO
```

Signing is unset (`DEVELOPMENT_TEAM = ""`). Open the project in Xcode, pick the
team under Signing & Capabilities once, and it is remembered.

## Tests

```bash
node --test garly-ios/test/*.test.mjs
```

Run from the repository root. Like the rest of the suite these read the source
that ships rather than a copy of it, so they catch drift:

- **`bridge-contract.test.mjs`** reads `app/index.html`, `bridge.js`,
  `Config.swift` and `GarlyNativeBridge.java` and checks the four agree. The
  check that matters: nothing the page calls **without a guard** may be missing
  from this build, because on iOS that is a `TypeError` thrown inside whatever
  was running, and what is running is an SOS or a walk home.
- **`bridge-runtime.test.mjs`** runs `bridge.js` in a fake page. Mostly it
  proves that a consume-once event fires once — the snapshot carrying it is
  pushed on every state change and again after every navigation, and a walk-mode
  silence delivered twice is a second SOS nobody asked for.

Turning a capability on in `Config.swift` without the native service behind it
makes the contract test fail. That is the intended order of work.

## Files

| file | what |
|---|---|
| `Garly/Config.swift` | the start URL, the capability flags, the Google client id |
| `Garly/Resources/bridge.js` | `window.GarlyAndroid`, injected at document start |
| `Garly/NativeBridge.swift` | the Swift half: message handler and state snapshot |
| `Garly/ProtectionService.swift` | CoreMotion sampling, the location keep-alive, the SOS notification |
| `Garly/WebShellViewController.swift` | the web view, what may leave it, and the first-paint watchdog |
| `Garly/InstallIdentity.swift` | install-scoped id, `UserDefaults` not keychain |
| `Garly/GoogleSignInCoordinator.swift` | ready, switched off |
| `Garly/Resources/Info.plist` | permission wording, portrait, export compliance |
| `Garly/Resources/PrivacyInfo.xcprivacy` | privacy manifest — **check against the App Privacy answers before submitting** |
| `test/*.test.mjs` | the bridge contract, checked against index.html itself |

`InstallIdentity` uses `UserDefaults` and not the keychain on purpose. Keychain
items outlive deleting the app, which is the opposite of the intent: on Android
a new install means a new id, so a reinstall or a phone handed on cannot land
inside the previous account's session.
