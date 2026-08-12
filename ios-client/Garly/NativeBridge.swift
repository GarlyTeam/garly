import Foundation
import WebKit

protocol NativeBridgeHost: AnyObject {
    func bridgeRequestsWebDataClear()
    func bridgeRequestsGoogleSignIn()
    func bridgeRequestsAppleSignIn()
    func bridgeRequestsMotionSensors(start: Bool)
    func bridgeRequestsSOSState(_ state: String, eventId: String)
    /// Cosa serve per mandare un allarme quando la pagina dorme.
    func bridgeArmsSosDispatch(url: String, token: String, userName: String, seconds: Double)
    func bridgeClearsSosDispatch()
    func bridgeRequestsWalk(_ request: WalkRequest)
    func bridgeRequestsJourney(_ request: JourneyRequest)
    func bridgeDidConsume(channel: String)
}

enum JourneyRequest {
    case homeWatch(latitude: Double, longitude: Double, radius: Double,
                   homeName: String, sessionId: String, uploadUrl: String, uploadToken: String)
    case share(uploadUrl: String, uploadToken: String, expiresAt: Double)
    case complete(homeName: String)
    case stop
}

enum WalkRequest {
    /// Both arm the same deadline; Android treats a ping as a re-arm too.
    case arm(silenceMs: Double)
    case stop
}

/// The Swift half of `window.GarlyAndroid`.
///
/// Two directions, deliberately different:
///
///   - page → app: `postMessage`, one handler, a method name and a dictionary.
///   - app → page: a snapshot pushed into `__garlyNativeApply`. Synchronous
///     getters on the JS side read that snapshot, because WKWebView has no way
///     to answer a question the moment it is asked.
final class NativeBridge: NSObject, WKScriptMessageHandler {

    static let handlerName = "garly"

    weak var webView: WKWebView?
    weak var host: NativeBridgeHost?

    /// Mirrors the JS `state` object in bridge.js.
    struct State {
        var installId: String = ""
        var sensorsAvailable = false
        var listening = false
        /* Un avviso che non puo' arrivare non e' un avviso. Vedi
           refreshNotificationSettings: il diritto di bucare il Focus ce l'ha,
           ma la persona puo' averlo tolto senza saperlo. */
        var notificationsAllowed = true
        var timeSensitiveAllowed = true
        var billing = false
        var acousticRunning = false
        var acousticStatus = "stopped"
    }

    /// A consume-once event.
    ///
    /// Two guards, because one is not enough. `id` only ever grows, so a
    /// snapshot delivered twice inside one document cannot fire twice — that is
    /// the page's guard. `delivered` stops it being published at all once taken,
    /// which is the guard that matters across a reload: a fresh document starts
    /// counting from zero, so anything still in the snapshot would fire again.
    /// For a walk-mode silence that is a second SOS nobody asked for.
    struct Channel {
        var id = 0
        var value: String?
        var delivered = false
    }

    private(set) var state = State()
    private var channels: [String: Channel] = [
        "walkSilence": Channel(),
        "sosCancellation": Channel(),
        "journeyCompletion": Channel(),
        "purchaseResult": Channel()
    ]

    // MARK: - app → page

    func update(_ change: (inout State) -> Void) {
        change(&state)
        push()
    }

    /// Raises an event the page will pick up on its next poll. Phase 3 calls
    /// this from the motion, journey and StoreKit code.
    func emit(channel: String, value: String? = nil) {
        guard var existing = channels[channel] else { return }
        existing.id += 1
        existing.value = value
        existing.delivered = false
        channels[channel] = existing
        push()
    }

    private func push() {
        // Phase 3 raises events from CoreMotion and CoreLocation callbacks,
        // which are not the main queue, and evaluateJavaScript on any other
        // thread is undefined behaviour rather than an error you would see.
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in self?.push() }
            return
        }
        guard let webView else { return }
        var snapshot: [String: Any] = [
            "installId": state.installId,
            "sensorsAvailable": state.sensorsAvailable,
            "listening": state.listening,
            "notificationsAllowed": state.notificationsAllowed,
            "timeSensitiveAllowed": state.timeSensitiveAllowed,
            "billing": state.billing,
            "acoustic": [
                "running": state.acousticRunning,
                "status": state.acousticStatus
            ]
        ]
        var encodedChannels: [String: Any] = [:]
        for (name, channel) in channels where channel.id > 0 && !channel.delivered {
            encodedChannels[name] = ["id": channel.id, "value": channel.value as Any]
        }
        if !encodedChannels.isEmpty { snapshot["channels"] = encodedChannels }

        guard
            let data = try? JSONSerialization.data(withJSONObject: snapshot),
            let json = String(data: data, encoding: .utf8)
        else { return }

        webView.evaluateJavaScript("window.__garlyNativeApply && window.__garlyNativeApply(\(json));")
    }

    /// Chiude un canale senza consegnarlo.
    ///
    /// Serve quando l'evento non e' piu' vero: armare un nuovo walk cancella la
    /// scadenza vecchia dal disco, ma il canale resterebbe carico e la pagina
    /// raccoglierebbe un silenzio scaduto - cioe' un SOS che nessuno ha vissuto.
    func clear(channel: String) {
        guard var existing = channels[channel] else { return }
        existing.value = nil
        existing.delivered = true
        channels[channel] = existing
        push()
    }

    /// Called after every navigation: a fresh document has a fresh, empty state.
    func republish() {
        push()
    }

    /// One accelerometer sample, straight to the page.
    ///
    /// Deliberately not routed through the state snapshot: this arrives about
    /// 62 times a second, and the page scores each sample against the one
    /// before it. Coalescing them would change the jerk it measures, and the
    /// jerk it measures is what decides whether somebody fell.
    func deliverMotion(_ sample: [String: Any]) {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in self?.deliverMotion(sample) }
            return
        }
        guard let webView,
              let data = try? JSONSerialization.data(withJSONObject: sample),
              let json = String(data: data, encoding: .utf8)
        else { return }
        webView.evaluateJavaScript("window.__garlyNativeMotion && window.__garlyNativeMotion(\(json));")
    }

    // MARK: - page → app

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        // The shim is injected into the main frame of our own origin, but the
        // handler itself is reachable from any frame on the page. An advert or
        // an embedded third party must not be able to drive the phone.
        guard message.frameInfo.isMainFrame,
              message.frameInfo.securityOrigin.host == GarlyConfig.appHost else { return }

        guard let body = message.body as? [String: Any],
              let method = body["method"] as? String else { return }
        let args = body["args"] as? [String: Any] ?? [:]

        switch method {
        case "clearWebData":
            host?.bridgeRequestsWebDataClear()

        case "startGoogleSignIn":
            host?.bridgeRequestsGoogleSignIn()

        case "startAppleSignIn":
            host?.bridgeRequestsAppleSignIn()

        case "armSosDispatch":
            host?.bridgeArmsSosDispatch(
                url: args["url"] as? String ?? "",
                token: args["token"] as? String ?? "",
                userName: args["userName"] as? String ?? "",
                seconds: args["seconds"] as? Double ?? 20
            )

        case "clearSosDispatch":
            host?.bridgeClearsSosDispatch()

        case "startMotionSensors":
            host?.bridgeRequestsMotionSensors(start: true)

        case "stopMotionSensors":
            host?.bridgeRequestsMotionSensors(start: false)

        case "startWalkMode", "noteWalkReply":
            host?.bridgeRequestsWalk(.arm(silenceMs: args["silenceMs"] as? Double ?? 0))

        case "stopWalkMode":
            host?.bridgeRequestsWalk(.stop)

        case "startJourneyHomeWatch":
            host?.bridgeRequestsJourney(.homeWatch(
                latitude: args["latitude"] as? Double ?? 0,
                longitude: args["longitude"] as? Double ?? 0,
                radius: args["radius"] as? Double ?? 0,
                homeName: args["homeName"] as? String ?? "Home",
                sessionId: args["sessionId"] as? String ?? "",
                uploadUrl: args["uploadUrl"] as? String ?? "",
                uploadToken: args["uploadToken"] as? String ?? ""
            ))

        case "startLiveJourneyShare":
            host?.bridgeRequestsJourney(.share(
                uploadUrl: args["uploadUrl"] as? String ?? "",
                uploadToken: args["uploadToken"] as? String ?? "",
                expiresAt: args["expiresAt"] as? Double ?? 0
            ))

        case "completeJourneyHomeWatch":
            host?.bridgeRequestsJourney(.complete(homeName: args["homeName"] as? String ?? "Home"))

        case "stopJourneyHomeWatch":
            host?.bridgeRequestsJourney(.stop)

        case "notifySosState":
            host?.bridgeRequestsSOSState(
                args["state"] as? String ?? "countdown",
                eventId: args["eventId"] as? String ?? ""
            )

        case "consumed":
            // The page has taken the event. It must never be published again —
            // not in the next snapshot, and above all not to the next document.
            if let channel = args["channel"] as? String, var existing = channels[channel] {
                existing.value = nil
                existing.delivered = true
                channels[channel] = existing
                // Anything the app wrote to disk to survive being killed has to
                // be cleared too, or the next launch replays it.
                host?.bridgeDidConsume(channel: channel)
            }

        default:
            // Unreachable while the capability that defines the method is off.
            // Loud in a debug build rather than a silent no-op, because a
            // silent no-op is how the page comes to believe the phone is
            // watching when nothing is.
            assertionFailure("Unhandled bridge method: \(method)")
        }
    }
}
