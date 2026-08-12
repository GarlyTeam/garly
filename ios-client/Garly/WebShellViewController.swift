import UIKit
import WebKit
import SafariServices
import UserNotifications

/// The whole app: one web view, one bridge, and the rules about what is allowed
/// to leave it.
final class WebShellViewController: UIViewController {

    /// twa-manifest.json backgroundColor. Also what the status bar and the strip
    /// under the home indicator are painted, so the app has no white edges while
    /// the page is still loading.
    private static let themeColor = UIColor(red: 0x06 / 255, green: 0x2B / 255, blue: 0x30 / 255, alpha: 1)

    /// How long the first paint may take before the app says so. A load that
    /// fails raises an error; a load that simply never finishes raises nothing,
    /// and without this the screen stays theme-coloured and empty forever —
    /// which is what "the app did not open" looks like to the person holding it.
    ///
    /// Measured to `didCommit`, so this is the budget for the page to start
    /// existing, not for it to finish loading.
    private static let firstPaintDeadline: TimeInterval = 20

    private let bridge = NativeBridge()
    private let protection = ProtectionService()
    private let journey = JourneyService()
    private var webView: WKWebView!
    private var googleSignIn: GoogleSignInCoordinator?
    private var appleSignIn: AppleSignInCoordinator?
    private var watchdog: DispatchWorkItem?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Self.themeColor

        let installId = InstallIdentity.current
        protection.delegate = self
        journey.delegate = self
        UNUserNotificationCenter.current().delegate = self
        bridge.update {
            $0.installId = installId
            // Whether the phone has the hardware, not whether we intend to use
            // it. The page stops its own web sensors on the strength of this.
            $0.sensorsAvailable = GarlyConfig.capabilities.motion && self.protection.hasSensors
        }

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(catchUpOnWalk),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        catchUpOnWalk()

        if InstallIdentity.isFreshInstall {
            // Before the first paint, so nothing of a previous install is ever
            // on screen — not even for a frame.
            InstallIdentity.clearWebData { [weak self] in
                // Building a web view is UIKit work; the store makes no promise
                // about which queue it answers on.
                DispatchQueue.main.async {
                    self?.buildWebView(installId: installId)
                    self?.loadApp()
                }
            }
        } else {
            buildWebView(installId: installId)
            loadApp()
        }
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    /// A walk deadline can pass while the app is suspended, or while it is not
    /// running at all. Coming back is the moment to work out whether it did.
    @objc private func catchUpOnWalk() {
        let recovered = protection.reconcileWalk()
        if recovered { bridge.emit(channel: "walkSilence") }
    }

    // MARK: - setup

    private func buildWebView(installId: String) {
        guard webView == nil else { return }

        let controller = WKUserContentController()
        controller.add(bridge, name: NativeBridge.handlerName)
        controller.addUserScript(bootScript(installId: installId))
        controller.addUserScript(bridgeScript())

        let configuration = WKWebViewConfiguration()
        configuration.userContentController = controller
        configuration.websiteDataStore = .default()
        configuration.allowsInlineMediaPlayback = true
        // Mirrors setMediaPlaybackRequiresUserGesture(false): the app plays its
        // own short clips as part of the interface, not as media the user
        // pressed play on.
        configuration.mediaTypesRequiringUserActionForPlayback = []

        webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = false
        webView.isOpaque = false
        webView.backgroundColor = Self.themeColor
        webView.scrollView.backgroundColor = Self.themeColor
        webView.scrollView.bounces = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        bridge.webView = webView
        bridge.host = self

        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            webView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])
    }

    /// Runs before bridge.js and tells it what this build can do.
    private func bootScript(installId: String) -> WKUserScript {
        let boot: [String: Any] = [
            "installId": installId,
            "platform": "ios",
            "capabilities": (try? JSONSerialization.jsonObject(
                with: JSONEncoder().encode(GarlyConfig.capabilities)
            )) ?? [:]
        ]
        let json = (try? JSONSerialization.data(withJSONObject: boot))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"

        // __garlyInstallId is read by the page directly (app/index.html, the
        // installId funnel) as well as through the bridge.
        let source = """
        window.__garlyNativeBoot = \(json);
        window.__garlyInstallId = \(jsString(installId));
        """
        return WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: true)
    }

    private func bridgeScript() -> WKUserScript {
        guard
            let url = Bundle.main.url(forResource: "bridge", withExtension: "js"),
            let source = try? String(contentsOf: url, encoding: .utf8)
        else {
            // The bridge missing from the bundle would give the page a browser,
            // not an app: it would offer a subscription it cannot sell.
            fatalError("bridge.js is missing from the app bundle")
        }
        return WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: true)
    }

    private func loadApp() {
        var components = URLComponents(url: GarlyConfig.appURL, resolvingAgainstBaseURL: false)
        components?.queryItems = [URLQueryItem(name: "installId", value: InstallIdentity.current)]
        guard let url = components?.url else { return }
        armWatchdog()
        webView.load(URLRequest(url: url))
    }

    private func armWatchdog() {
        watchdog?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.showLoadFailure(
                reason: NSLocalizedString("Garly is taking longer than usual to open.", comment: "")
            )
        }
        watchdog = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.firstPaintDeadline, execute: work)
    }

    private func jsString(_ value: String) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: [value])) ?? Data()
        let array = String(data: data, encoding: .utf8) ?? "[\"\"]"
        return String(array.dropFirst().dropLast())
    }

    private func isTrusted(_ url: URL?) -> Bool {
        guard let url, let host = url.host else { return false }
        return url.scheme?.lowercased() == "https" && host.caseInsensitiveCompare(GarlyConfig.appHost) == .orderedSame
    }
}

// MARK: - navigation

extension WebShellViewController: WKNavigationDelegate {

    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.cancel)
            return
        }

        if isTrusted(url) {
            decisionHandler(.allow)
            return
        }

        decisionHandler(.cancel)

        switch url.scheme?.lowercased() {
        case "sms", "tel", "mailto":
            // The SOS fallback when there is no data: the page hands the phone
            // an sms: link with the message and the location already in it.
            UIApplication.shared.open(url)
        case "http", "https":
            let safari = SFSafariViewController(url: url)
            safari.preferredControlTintColor = .white
            present(safari, animated: true)
        default:
            if UIApplication.shared.canOpenURL(url) { UIApplication.shared.open(url) }
        }
    }

    /// The document is committed and painting. That is what the watchdog is
    /// waiting for — not the last asset. A 1.3 MB page finishes when it
    /// finishes, and warning about a screen the user can already read and use
    /// is a false alarm, which is worse than no alarm.
    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        watchdog?.cancel()
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        watchdog?.cancel()
        // A new document has an empty JS state; push what the app already knows.
        bridge.republish()
        announceNativeReady()
    }








    /// Android sets this in onPageFinished unconditionally. Here it is gated on
    /// the capability, because `__garlyHasNativeSensors` is the flag the page
    /// uses to stop running its own sensors — setting it in a build with no
    /// native sensors would switch protection off and report it as on.
    private func announceNativeReady() {
        guard GarlyConfig.capabilities.motion, protection.hasSensors else { return }
        webView.evaluateJavaScript(
            "window.__garlyHasNativeSensors=true;"
            + "window.__garlyAcousticQa=false;"
            + "window.__garlyLocalPage=false;"
            + "window.dispatchEvent(new Event('garly-native-ready'));"
        )
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        report(error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        report(error)
    }

    /// The renderer dying leaves the window painted and frozen, which looks
    /// exactly like a blank coloured screen. Android handles this explicitly;
    /// so must this.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        loadApp()
    }

    private func report(_ error: Error) {
        // -999 is a navigation the app itself replaced; not a failure to report.
        if (error as NSError).code == NSURLErrorCancelled { return }
        showLoadFailure(reason: error.localizedDescription)
    }

    private func showLoadFailure(reason: String) {
        watchdog?.cancel()
        guard presentedViewController == nil else { return }

        let alert = UIAlertController(
            title: NSLocalizedString("Garly could not load", comment: ""),
            message: reason,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: NSLocalizedString("Try again", comment: ""), style: .default) { [weak self] _ in
            self?.loadApp()
        })
        present(alert, animated: true)
    }
}

// MARK: - page requests

extension WebShellViewController: WKUIDelegate {

    /// The web sound meter asks for the microphone. Granting here only lets the
    /// request reach iOS, which then asks the user with the reason from
    /// Info.plist; it does not grant anything by itself.
    func webView(_ webView: WKWebView,
                 requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                 initiatedByFrame frame: WKFrameInfo,
                 type: WKMediaCaptureType,
                 decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        let trusted = origin.host.caseInsensitiveCompare(GarlyConfig.appHost) == .orderedSame
        decisionHandler(trusted && type == .microphone ? .prompt : .deny)
    }

    /// target="_blank". Own pages continue in place, everything else leaves.
    func webView(_ webView: WKWebView,
                 createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = navigationAction.request.url {
            if isTrusted(url) {
                webView.load(URLRequest(url: url))
            } else if url.scheme?.hasPrefix("http") == true {
                present(SFSafariViewController(url: url), animated: true)
            }
        }
        return nil
    }

    func webView(_ webView: WKWebView,
                 runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping () -> Void) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: NSLocalizedString("OK", comment: ""), style: .default) { _ in completionHandler() })
        present(alert, animated: true)
    }

    func webView(_ webView: WKWebView,
                 runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (Bool) -> Void) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: NSLocalizedString("Cancel", comment: ""), style: .cancel) { _ in completionHandler(false) })
        alert.addAction(UIAlertAction(title: NSLocalizedString("OK", comment: ""), style: .default) { _ in completionHandler(true) })
        present(alert, animated: true)
    }
}

// MARK: - bridge host

extension WebShellViewController: NativeBridgeHost {

    func bridgeRequestsWebDataClear() {
        InstallIdentity.clearWebData {}
    }

    func bridgeRequestsAppleSignIn() {
        let coordinator = AppleSignInCoordinator(presenting: self)
        appleSignIn = coordinator
        coordinator.start { [weak self] result in
            guard let self else { return }
            self.appleSignIn = nil
            switch result {
            case .success(let apple):
                self.webView.evaluateJavaScript(
                    "window.__garlyNativeAppleCredential && window.__garlyNativeAppleCredential("
                    + "\(self.jsString(apple.identityToken)), \(self.jsString(apple.fullName)));"
                )
            case .failure(let error):
                self.webView.evaluateJavaScript(
                    "window.__garlyNativeAppleError && window.__garlyNativeAppleError(\(self.jsString(error.localizedDescription)));"
                )
            }
        }
    }

    func bridgeRequestsMotionSensors(start: Bool) {
        if start { protection.start() } else { protection.stop() }
    }

    func bridgeRequestsSOSState(_ state: String, eventId: String) {
        protection.notifySOS(state: state, eventId: eventId)
    }

    func bridgeArmsSosDispatch(url: String, token: String, userName: String, seconds: Double) {
        protection.armSosDispatch(url: url, token: token, userName: userName, seconds: seconds)
    }

    func bridgeClearsSosDispatch() {
        protection.clearSosDispatch()
    }

    func bridgeRequestsWalk(_ request: WalkRequest) {
        switch request {
        case .arm(let silenceMs):
            // Prima il canale, poi l'armo: un silenzio vecchio non deve poter
            // essere raccolto come se fosse quello nuovo.
            bridge.clear(channel: "walkSilence")
            protection.armWalk(silenceMs: silenceMs)
        case .stop: protection.stopWalk()
        }
    }

    func bridgeRequestsJourney(_ request: JourneyRequest) {
        switch request {
        case .homeWatch(let lat, let lon, let radius, let name, let session, let url, let token):
            journey.startHomeWatch(latitude: lat, longitude: lon, radius: radius,
                                   homeName: name, sessionId: session,
                                   uploadUrl: url, uploadToken: token)
        case .share(let url, let token, let expires):
            journey.startShare(uploadUrl: url, uploadToken: token, expiresAt: expires)
        case .complete(let name):
            journey.complete(homeName: name)
        case .stop:
            journey.stopByUser()
        }
    }

    func bridgeDidConsume(channel: String) {
        // The in-memory channel is already closed. This clears what was written
        // to disk so a relaunch cannot hand the same event over again.
        if channel == "walkSilence" { protection.acknowledgeWalkSilence() }
    }

    func bridgeRequestsGoogleSignIn() {
        let coordinator = GoogleSignInCoordinator(presenting: self)
        googleSignIn = coordinator
        coordinator.start { [weak self] result in
            guard let self else { return }
            self.googleSignIn = nil
            switch result {
            case .success(let idToken):
                self.webView.evaluateJavaScript(
                    "window.__garlyNativeGoogleCredential && window.__garlyNativeGoogleCredential(\(self.jsString(idToken)));"
                )
            case .failure(let error):
                self.webView.evaluateJavaScript(
                    "window.__garlyNativeGoogleError && window.__garlyNativeGoogleError(\(self.jsString(error.localizedDescription)));"
                )
            }
        }
    }
}

// MARK: - protection

extension WebShellViewController: ProtectionServiceDelegate {

    func protectionService(_ service: ProtectionService, didSample sample: [String: Any]) {
        bridge.deliverMotion(sample)
    }

    func protectionServiceDidChangeListening(_ service: ProtectionService) {
        bridge.update { $0.listening = service.isListening }
    }

    func protectionServiceDidObserveWalkSilence(_ service: ProtectionService) {
        bridge.emit(channel: "walkSilence")
    }

    /* Riconosciuto mentre la pagina dormiva. La notifica e' gia' partita dal
       lato nativo; questo canale serve a chi riapre Garly, che deve trovare il
       conto alla rovescia e non una schermata come se niente fosse. Consumabile
       una volta sola, come la modalita' cammino: un ricaricamento della pagina
       non deve far ricomparire un allarme di ieri. */
    func protectionService(_ service: ProtectionService,
                           notificationsAllowed: Bool,
                           timeSensitiveAllowed: Bool) {
        bridge.update {
            $0.notificationsAllowed = notificationsAllowed
            $0.timeSensitiveAllowed = timeSensitiveAllowed
        }
    }

    func protectionServiceDidDetectImpact(_ service: ProtectionService) {
        bridge.emit(channel: "nativeImpact")
    }

    func protectionServiceDidSendSOS(_ service: ProtectionService) {
        // Consumabile una volta sola: la pagina lo trova al risveglio, chiude il
        // suo conto alla rovescia e dice che l'allarme e' gia' partito.
        bridge.clear(channel: "nativeImpact")
        bridge.emit(channel: "nativeSosSent")
    }

    func protectionService(_ service: ProtectionService, didCancelSOS eventId: String, at millis: Int) {
        let payload: [String: Any] = ["eventId": eventId, "cancelledAt": millis]
        let json = (try? JSONSerialization.data(withJSONObject: payload))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "null"
        bridge.emit(channel: "sosCancellation", value: json)
    }
}

extension WebShellViewController: JourneyServiceDelegate {
    func journeyServiceDidComplete(_ service: JourneyService, payload: String) {
        bridge.emit(channel: "journeyCompletion", value: payload)
    }
}

extension WebShellViewController: UNUserNotificationCenterDelegate {

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if response.actionIdentifier == "GARLY_SOS_CANCEL" {
            let eventId = response.notification.request.content.userInfo["eventId"] as? String ?? ""
            protection.handleCancelAction(eventId: eventId)
        }
        completionHandler()
    }

    /// An SOS countdown the user is already looking at still has to be visible.
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}
