/*
 * Garly iOS — the shim that makes `window.GarlyAndroid` exist inside WKWebView.
 *
 * The web app talks to the phone through one object, `window.GarlyAndroid`,
 * from 76 places in app/index.html. Renaming it on iOS would mean editing a
 * 1.3 MB single-file front end that is also being edited on Windows, so the
 * name stays and only what is behind it changes. There is a second reason:
 * `coreDemoPreview()` decides it is running in a browser by asking whether
 * `window.GarlyAndroid` is missing, and a shipped iOS app that believes it is
 * a browser would show the subscription as a preview nobody can buy.
 *
 * The hard part is that Android's bridge is synchronous — `hasNativeSensors()`
 * returns a boolean there and then — while WKWebView only offers
 * `postMessage`, which returns nothing. So:
 *
 *   - methods that return something read a copy of the native state that Swift
 *     pushes in through `__garlyNativeApply`;
 *   - methods that only do something post a message and return.
 *
 * A method is defined here only when iOS actually implements it. Every call
 * site in the web app is already guarded by `typeof … === "function"`, so an
 * absent method makes the app fall back to its own web implementation — the
 * one that mobile Safari has been running all along. A method that existed but
 * did nothing would instead make the app believe the phone was watching when
 * it was not, which is the one thing this codebase does not allow.
 */
(function () {
  "use strict";

  if (window.GarlyAndroid) return;

  var boot = window.__garlyNativeBoot || {};
  var caps = boot.capabilities || {};

  /* Everything a synchronous getter has to be able to answer without asking. */
  var state = {
    installId: String(boot.installId || ""),
    sensorsAvailable: false,
    listening: false,
    /* Finche' iOS non ha risposto, "non lo so" vale come permesso: dichiarare
       spento un avviso prima di aver chiesto farebbe comparire un allarme che
       non esiste. */
    notificationsAllowed: true,
    timeSensitiveAllowed: true,
    billing: false,
    acoustic: { available: false, running: false, status: "stopped" }
  };

  /*
   * Consume-once channels.
   *
   * Each carries a counter that only ever grows. A state push that arrives
   * twice therefore cannot deliver the same event twice — and for a walk-mode
   * silence, delivering it twice means a second SOS nobody asked for.
   */
  var channels = { walkSilence: 0, sosCancellation: 0, journeyCompletion: 0, purchaseResult: 0, nativeImpact: 0, nativeSosSent: 0 };
  var payloads = { sosCancellation: "null", journeyCompletion: "null", purchaseResult: null };
  var seen = { walkSilence: 0, sosCancellation: 0, journeyCompletion: 0, purchaseResult: 0, nativeImpact: 0, nativeSosSent: 0 };

  function send(method, args) {
    try {
      window.webkit.messageHandlers.garly.postMessage({ method: method, args: args || {} });
      return true;
    } catch (error) {
      return false;
    }
  }

  /* Swift calls this. It is the only way anything in `state` ever changes. */
  window.__garlyNativeApply = function (next) {
    if (!next || typeof next !== "object") return;
    if (typeof next.installId === "string") state.installId = next.installId;
    if (typeof next.sensorsAvailable === "boolean") state.sensorsAvailable = next.sensorsAvailable;
    if (typeof next.listening === "boolean") state.listening = next.listening;
    if (typeof next.notificationsAllowed === "boolean") state.notificationsAllowed = next.notificationsAllowed;
    if (typeof next.timeSensitiveAllowed === "boolean") state.timeSensitiveAllowed = next.timeSensitiveAllowed;
    if (typeof next.billing === "boolean") state.billing = next.billing;
    if (next.acoustic && typeof next.acoustic === "object") state.acoustic = next.acoustic;
    if (next.channels && typeof next.channels === "object") {
      for (var key in channels) {
        if (!Object.prototype.hasOwnProperty.call(channels, key)) continue;
        var entry = next.channels[key];
        if (!entry || typeof entry.id !== "number" || entry.id <= channels[key]) continue;
        channels[key] = entry.id;
        if (Object.prototype.hasOwnProperty.call(payloads, key)) payloads[key] = entry.value;
      }
    }
  };

  function consumeFlag(key) {
    if (channels[key] <= seen[key]) return false;
    seen[key] = channels[key];
    send("consumed", { channel: key });
    return true;
  }

  function consumeValue(key, empty) {
    if (channels[key] <= seen[key]) return empty;
    seen[key] = channels[key];
    send("consumed", { channel: key });
    var value = payloads[key];
    return value === undefined || value === null ? empty : value;
  }

  /* ---------------- always present ---------------- */

  var bridge = {
    getInstallId: function () {
      return state.installId;
    },

    /* Android consumes the flag before the first paint and answers false from
       then on. The page relies on the URL parameter and the install id. */
    isFreshInstall: function () {
      return false;
    },

    clearWebData: function () {
      send("clearWebData");
    },

    hasNativeSensors: function () {
      return state.sensorsAvailable === true;
    },

    sensorStatus: function () {
      return JSON.stringify({
        listening: state.listening === true,
        accelerometer: state.sensorsAvailable === true,
        /* Due campi in piu' rispetto ad Android, dove non esistono: la pagina li
           legge con "se ci sono", quindi di la' non cambia niente. Un avviso che
           non puo' arrivare non e' un avviso, e questo e' l'unico posto da cui
           la pagina puo' saperlo. */
        notificationsAllowed: state.notificationsAllowed !== false,
        timeSensitiveAllowed: state.timeSensitiveAllowed !== false,
        installId: state.installId
      });
    },

    hasWalkMode: function () {
      return caps.walkMode === true;
    },

    /* The Sound Lab is an internal build on Android and has no iOS counterpart
       at all. Answering false is what a production Android build answers. */
    hasAcousticDiagnostics: function () {
      return caps.acousticDiagnostics === true;
    },

    hasAcousticProtection: function () {
      return caps.acousticProtection === true;
    },

    hasAcousticClipAccess: function () {
      return caps.acousticProtection === true;
    },

    acousticDiagnosticStatus: function () {
      return JSON.stringify({
        available: caps.acousticDiagnostics === true,
        running: state.acoustic.running === true,
        status: String(state.acoustic.status || "stopped")
      });
    },

    hasBilling: function () {
      return caps.billing === true && state.billing === true;
    }
  };

  /* ---------------- present once iOS implements them ---------------- */

  if (caps.motion === true) {
    bridge.startMotionSensors = function () { send("startMotionSensors"); };
    bridge.stopMotionSensors = function () { send("stopMotionSensors"); };
    bridge.notifySosState = function (sosState) {
      send("notifySosState", { state: String(sosState == null ? "countdown" : sosState), eventId: "" });
    };
    bridge.notifySosStateWithEvent = function (sosState, eventId) {
      send("notifySosState", {
        state: String(sosState == null ? "countdown" : sosState),
        eventId: String(eventId == null ? "" : eventId)
      });
    };
    bridge.consumeSosCancellation = function () { return consumeValue("sosCancellation", "null"); };
    /* Quello che serve all'app per mandare un allarme mentre questa pagina e'
       congelata: dove chiamare, con quale gettone, a nome di chi. Lo stesso
       schema gia' usato dalla Live Journey. Senza, il rilevatore nativo avvisa
       il telefono e si ferma li'. */
    bridge.armSosDispatch = function (url, token, userName, seconds) {
      send("armSosDispatch", {
        url: String(url == null ? "" : url),
        token: String(token == null ? "" : token),
        userName: String(userName == null ? "" : userName),
        seconds: Number(seconds) || 20
      });
    };
    bridge.clearSosDispatch = function () { send("clearSosDispatch"); };
    /* Uno scuotimento riconosciuto dal lato nativo mentre questa pagina era
       congelata dal sistema. La notifica e' gia' arrivata al telefono; qui la
       pagina lo scopre quando si risveglia, e mostra il conto alla rovescia
       invece di far finta di niente. Una volta sola: un ricaricamento non deve
       ripescare l'allarme di ieri. */
    bridge.consumeNativeImpact = function () { return consumeFlag("nativeImpact"); };
    /* L'app ha gia' mandato l'allarme mentre la pagina dormiva. Chi si sveglia
       deve chiudere il proprio conto alla rovescia e dire che e' partito, non
       mandarne un secondo allo stesso contatto. */
    bridge.consumeNativeSosSent = function () { return consumeFlag("nativeSosSent"); };
  }

  if (caps.walkMode === true) {
    bridge.startWalkMode = function (silenceMs) { send("startWalkMode", { silenceMs: Number(silenceMs) || 0 }); };
    bridge.noteWalkReply = function (silenceMs) { send("noteWalkReply", { silenceMs: Number(silenceMs) || 0 }); };
    bridge.stopWalkMode = function () { send("stopWalkMode"); };
    bridge.consumeWalkSilence = function () { return consumeFlag("walkSilence"); };
  }

  if (caps.journey === true) {
    bridge.startJourneyHomeWatch = function (latitude, longitude, radius, homeName, language, sessionId, uploadUrl, uploadToken, liveJourneyId) {
      send("startJourneyHomeWatch", {
        latitude: Number(latitude) || 0,
        longitude: Number(longitude) || 0,
        radius: Number(radius) || 0,
        homeName: String(homeName == null ? "Home" : homeName),
        language: String(language == null ? "en" : language),
        sessionId: String(sessionId == null ? "" : sessionId),
        uploadUrl: String(uploadUrl == null ? "" : uploadUrl),
        uploadToken: String(uploadToken == null ? "" : uploadToken),
        liveJourneyId: String(liveJourneyId == null ? "" : liveJourneyId)
      });
    };
    bridge.startLiveJourneyShare = function (uploadUrl, uploadToken, liveJourneyId, language, expiresAt) {
      send("startLiveJourneyShare", {
        uploadUrl: String(uploadUrl == null ? "" : uploadUrl),
        uploadToken: String(uploadToken == null ? "" : uploadToken),
        liveJourneyId: String(liveJourneyId == null ? "" : liveJourneyId),
        language: String(language == null ? "en" : language),
        expiresAt: Number(expiresAt) || 0
      });
    };
    bridge.completeJourneyHomeWatch = function (homeName, language, sessionId) {
      send("completeJourneyHomeWatch", {
        homeName: String(homeName == null ? "Home" : homeName),
        language: String(language == null ? "en" : language),
        sessionId: String(sessionId == null ? "" : sessionId)
      });
    };
    bridge.consumeJourneyCompletion = function () { return consumeValue("journeyCompletion", "null"); };
    bridge.stopJourneyHomeWatch = function () { send("stopJourneyHomeWatch"); };
  }

  if (caps.acousticProtection === true) {
    bridge.startAcousticProtection = function (contextJson, recordingConsent) {
      send("startAcousticProtection", {
        context: String(contextJson == null ? "{}" : contextJson),
        recordingConsent: recordingConsent === true
      });
      return true;
    };
    bridge.stopAcousticDiagnostics = function () { send("stopAcousticDiagnostics"); return true; };
    bridge.playAcousticQaClip = function () { send("playAcousticClip"); return true; };
    bridge.stopAcousticQaPlayback = function () { send("stopAcousticPlayback"); return true; };
    bridge.deleteAcousticQaClip = function () { send("deleteAcousticClip"); return true; };
    bridge.exportAcousticQaClip = function () { send("exportAcousticClip"); return true; };
  }

  if (caps.billing === true) {
    bridge.startCorePurchase = function () { return send("startCorePurchase"); };
    bridge.consumePurchaseResult = function () { return consumeValue("purchaseResult", null); };
    bridge.restorePurchases = function () { send("restorePurchases"); };
  }

  /*
   * Only when an iOS OAuth client is configured. Without it the page falls back
   * to Google's own web button — which Google refuses to serve inside an
   * embedded web view — so a button that is present but cannot work is worse
   * than no button beside the e-mail form that does work.
   */
  if (caps.googleSignIn === true) {
    bridge.startGoogleSignIn = function () { send("startGoogleSignIn"); };
  }

  /* "Accedi con Apple" non ha niente da configurare: il sistema parla con
     Apple e il server verifica il token. Esiste su ogni iPhone, quindi non c'e'
     una capacita' da accendere - c'e' solo iOS. */
  if (boot.platform === "ios") {
    bridge.startAppleSignIn = function () { send("startAppleSignIn"); };
  }

  Object.defineProperty(window, "GarlyAndroid", {
    value: bridge,
    writable: false,
    configurable: false,
    enumerable: true
  });
})();
