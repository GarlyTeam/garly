/*
 * bridge.js run for real, in a fake page.
 *
 * The contract test checks that the names line up. This one checks that the
 * thing behaves, and mostly it checks one behaviour: a consume-once event must
 * fire once. The snapshot that carries it is pushed on every state change and
 * again after every navigation, so "the page saw it already" cannot be a matter
 * of timing. For a walk-mode silence, firing twice means a second SOS nobody
 * asked for.
 */
import test from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const source = readFileSync(join(here, "..", "Garly", "Resources", "bridge.js"), "utf8");

const ALL_OFF = {
  motion: false, walkMode: false, journey: false,
  acousticProtection: false, acousticDiagnostics: false,
  billing: false, googleSignIn: false
};

/**
 * Objects built inside the vm have that realm's prototype, so a strict deep
 * compare against a literal here fails on identity alone. Round-tripping is
 * also closer to the truth: what reaches Swift is the serialised form.
 */
const plain = value => JSON.parse(JSON.stringify(value));

/** A page with the shim in it, and a note of everything it posted to Swift. */
function page(capabilities = ALL_OFF, installId = "install-1") {
  const posted = [];
  const window = {
    __garlyNativeBoot: { installId, platform: "ios", capabilities },
    webkit: { messageHandlers: { garly: { postMessage: message => posted.push(message) } } }
  };
  vm.runInContext(source, vm.createContext({ window }));
  return { window, bridge: window.GarlyAndroid, posted };
}

test("a shell build answers every capability question with no", () => {
  const { bridge } = page();
  assert.equal(bridge.hasNativeSensors(), false);
  assert.equal(bridge.hasWalkMode(), false);
  assert.equal(bridge.hasBilling(), false);
  assert.equal(bridge.hasAcousticProtection(), false);
  assert.equal(bridge.hasAcousticDiagnostics(), false);
  assert.equal(bridge.hasAcousticClipAccess(), false);
});

test("a shell build defines no method that would claim work it does not do", () => {
  const { bridge } = page();
  for (const absent of [
    "startMotionSensors", "stopMotionSensors", "notifySosState", "notifySosStateWithEvent",
    "startWalkMode", "noteWalkReply", "stopWalkMode", "consumeWalkSilence",
    "startJourneyHomeWatch", "stopJourneyHomeWatch", "consumeJourneyCompletion",
    "startCorePurchase", "consumePurchaseResult", "restorePurchases", "startGoogleSignIn"
  ]) {
    assert.equal(typeof bridge[absent], "undefined", `${absent} must not exist while its capability is off`);
  }
});

test("the install id reaches the page, and sensorStatus is readable JSON", () => {
  const { bridge } = page(ALL_OFF, "install-abc");
  assert.equal(bridge.getInstallId(), "install-abc");
  assert.equal(bridge.isFreshInstall(), false);

  const status = JSON.parse(bridge.sensorStatus());
  assert.deepEqual(status, {
    listening: false,
    accelerometer: false,
    /* Assenti nello stato di partenza significa "non lo so ancora", e va letto
       come permesso: un avviso dichiarato spento prima di aver chiesto al
       sistema farebbe comparire un allarme che non esiste. Diventano falsi solo
       quando iOS risponde davvero. */
    notificationsAllowed: true,
    timeSensitiveAllowed: true,
    installId: "install-abc"
  });
});

test("gli avvisi spenti arrivano fino alla pagina", () => {
  /* Garly promette che un SOS passa il Focus, e ha l'autorizzazione per farlo -
     ma la persona puo' averla tolta a quest'app, e allora la promessa non vale.
     Su un telefono vero il Focus notturno ha trattenuto la notifica e sembrava
     che non funzionasse niente. */
  const { window, bridge } = page(ALL_OFF, "install-abc");
  window.__garlyNativeApply({ timeSensitiveAllowed: false });
  const status = JSON.parse(bridge.sensorStatus());
  assert.equal(status.timeSensitiveAllowed, false, "la pagina non puo' sapere che l'avviso e' silenziato");
});

test("the page cannot be talked out of the bridge", () => {
  const { window } = page();
  const original = window.GarlyAndroid;
  try { window.GarlyAndroid = { hasBilling: () => true }; } catch { /* strict mode throws, fine */ }
  assert.equal(window.GarlyAndroid, original, "GarlyAndroid must not be replaceable");
});

test("a walk silence fires once, however often the snapshot arrives", () => {
  const { window, bridge, posted } = page({ ...ALL_OFF, walkMode: true });

  assert.equal(bridge.consumeWalkSilence(), false, "nothing has happened yet");

  window.__garlyNativeApply({ channels: { walkSilence: { id: 1 } } });
  assert.equal(bridge.consumeWalkSilence(), true, "the deadline passed, the page must run the SOS");

  // Swift pushes the snapshot again — a new state change, a fresh document,
  // anything. The same id must not produce a second alert.
  window.__garlyNativeApply({ channels: { walkSilence: { id: 1 } } });
  assert.equal(bridge.consumeWalkSilence(), false, "the same silence must never fire twice");

  window.__garlyNativeApply({ channels: { walkSilence: { id: 2 } } });
  assert.equal(bridge.consumeWalkSilence(), true, "a genuinely new silence must fire");

  const acks = posted.filter(m => m.method === "consumed" && m.args.channel === "walkSilence");
  assert.equal(acks.length, 2, "each event taken has to be acknowledged, so Swift can drop it");
});

/*
 * The reload case, which the counter alone does not cover.
 *
 * A fresh document starts counting from zero. If the native side kept
 * publishing an event the previous document already took, the new one would
 * take it again — and a walk-mode silence taken twice is a second SOS. The
 * counter cannot see this; only the native side going quiet can. So the page's
 * job is to acknowledge, every time, and that is what is checked here.
 */
test("taking an event always acknowledges it, so the native side can stop publishing", () => {
  const first = page({ ...ALL_OFF, walkMode: true });
  first.window.__garlyNativeApply({ channels: { walkSilence: { id: 1 } } });
  assert.equal(first.bridge.consumeWalkSilence(), true);
  assert.deepEqual(
    plain(first.posted.at(-1)),
    { method: "consumed", args: { channel: "walkSilence" } },
    "without this the app never learns the event was taken"
  );

  // The new document after a reload. Native has stopped publishing the channel,
  // so there is nothing to take.
  const second = page({ ...ALL_OFF, walkMode: true });
  second.window.__garlyNativeApply({ channels: {} });
  assert.equal(second.bridge.consumeWalkSilence(), false, "a reload must not replay a taken event");
});

test("an out-of-order snapshot cannot resurrect an event", () => {
  const { window, bridge } = page({ ...ALL_OFF, walkMode: true });
  window.__garlyNativeApply({ channels: { walkSilence: { id: 5 } } });
  assert.equal(bridge.consumeWalkSilence(), true);
  window.__garlyNativeApply({ channels: { walkSilence: { id: 3 } } });
  assert.equal(bridge.consumeWalkSilence(), false, "an older id must be ignored, not replayed");
});

test("a purchase result is handed over once, then reads null", () => {
  const { window, bridge } = page({ ...ALL_OFF, billing: true });
  assert.equal(bridge.consumePurchaseResult(), null);

  window.__garlyNativeApply({ channels: { purchaseResult: { id: 1, value: '{"state":"purchased"}' } } });
  assert.equal(bridge.consumePurchaseResult(), '{"state":"purchased"}');
  assert.equal(bridge.consumePurchaseResult(), null, "a payment must not be applied twice");
});

test("billing needs both the capability and a live connection", () => {
  const { window, bridge } = page({ ...ALL_OFF, billing: true });
  assert.equal(bridge.hasBilling(), false, "capability on, StoreKit not ready yet: still no paywall");
  window.__garlyNativeApply({ billing: true });
  assert.equal(bridge.hasBilling(), true);
});

test("turning motion on gives the page the methods and the right messages", () => {
  const { bridge, posted } = page({ ...ALL_OFF, motion: true });
  assert.equal(typeof bridge.startMotionSensors, "function");

  bridge.notifySosStateWithEvent("countdown", "event-9");
  assert.deepEqual(plain(posted.at(-1)), {
    method: "notifySosState",
    args: { state: "countdown", eventId: "event-9" }
  });

  // The one-argument form must not lose the state on the way.
  bridge.notifySosState("sent");
  assert.deepEqual(plain(posted.at(-1)), { method: "notifySosState", args: { state: "sent", eventId: "" } });
});

test("a missing native side never throws into the page", () => {
  // WKWebView tears the message handler down on navigation; a call landing in
  // that window must fail quietly, not inside an SOS.
  const posted = [];
  const window = {
    __garlyNativeBoot: { installId: "x", capabilities: { ...ALL_OFF, motion: true } },
    webkit: { messageHandlers: {} }
  };
  vm.runInContext(source, vm.createContext({ window }));
  assert.doesNotThrow(() => window.GarlyAndroid.notifySosState("countdown"));
  assert.equal(posted.length, 0);
});
