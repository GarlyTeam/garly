import Foundation
import CoreMotion
import CoreLocation
import UIKit
import UserNotifications

protocol ProtectionServiceDelegate: AnyObject {
    /// One accelerometer sample, already in the shape `__garlyNativeMotion` wants.
    func protectionService(_ service: ProtectionService, didSample sample: [String: Any])
    func protectionServiceDidChangeListening(_ service: ProtectionService)
    func protectionService(_ service: ProtectionService, didCancelSOS eventId: String, at millis: Int)
    /// The walk deadline passed with no sign of life.
    func protectionServiceDidObserveWalkSilence(_ service: ProtectionService)
    /// Uno scuotimento o un urto riconosciuti mentre la pagina dormiva.
    func protectionServiceDidDetectImpact(_ service: ProtectionService)
    /// L'allarme e' gia' partito dal lato nativo: la pagina non deve mandarne un altro.
    func protectionServiceDidSendSOS(_ service: ProtectionService)
    /// Se gli avvisi possono arrivare, e se possono farlo a telefono silenzioso.
    func protectionService(_ service: ProtectionService,
                           notificationsAllowed: Bool,
                           timeSensitiveAllowed: Bool)
}

/**
 The iOS counterpart of ProtectionService.java.

 It decides nothing. Android's service is explicit about this — *the SOS scoring
 engine is still in the web layer* — and it streams raw samples to the page,
 which scores them. This does the same, so both platforms run one detector and a
 threshold tuned on Android means the same thing here.

 The hard part is not the accelerometer. It is staying alive.

 Android keeps a foreground service; iOS has no such thing. An app that is
 merely backgrounded is suspended within seconds and CoreMotion stops with it,
 so "Protection is on" would become false the moment the phone went into a
 pocket — which is the exact situation the feature exists for. The only
 sanctioned way to keep running is background location updates, which is why
 this class holds a CLLocationManager it barely reads: the location is genuinely
 used (an SOS carries it), and the run loop it keeps alive is what lets the
 sensors keep sampling.
 */
final class ProtectionService: NSObject {

    weak var delegate: ProtectionServiceDelegate?

    /// 16 ms, the same throttle as SAMPLE_INTERVAL_MS on Android, so the page
    /// sees the same sample rate and its jerk maths means the same thing.
    private static let sampleInterval: TimeInterval = 0.016

    private static let cancelActionId = "GARLY_SOS_CANCEL"
    private static let sosCategoryId = "GARLY_SOS"
    private static let sosNotificationId = "garly.sos"
    private static let walkNotificationId = "garly.walk.silence"

    /// The same clamp as armWalk() on Android: never shorter than a minute,
    /// never longer than half an hour.
    private static let walkWindow: ClosedRange<TimeInterval> = 60...1800

    private enum WalkKey {
        static let active = "garly.walk.active"
        static let deadline = "garly.walk.deadline"
        static let silent = "garly.walk.silent_pending"
    }

    private let motion = CMMotionManager()
    private let location = CLLocationManager()
    private let queue = OperationQueue()

    /**
     Whether the accelerometer is actually running.

     The keep-alive and the page are driven from here rather than from the call
     sites, because the call sites got it wrong: `start()` used to ask for the
     keep-alive while this was still false, and `updateKeepAlive()` reads it to
     decide whether the app needs to stay awake — so arming Protection on its
     own switched the location off instead of on, and the sensors would have
     died at the next screen lock. Nothing on screen would have said so.
     */
    private(set) var isListening = false {
        didSet {
            guard isListening != oldValue else { return }
            updateKeepAlive()
            delegate?.protectionServiceDidChangeListening(self)
        }
    }
    private var lastPush = Date.distantPast

    /* Il rilevatore che copre il tempo in cui la pagina non puo' decidere. Vedi
       BackgroundImpactDetector: con l'app davanti resta zitto, perche' li'
       decide la pagina e due padroni sullo stesso gesto sono peggio di uno. */
    private let backgroundDetector = BackgroundImpactDetector()
    private var isForeground = true
    private var alertEventId = ""
    private var lastKnownLocation: CLLocation?

    /* Cosa serve per mandare un allarme senza la pagina: dove, con quale
       gettone, a nome di chi. Lo consegna la pagina quando si arma la
       Protezione, come gia' fa per la Live Journey. Senza questi, il rilevatore
       nativo avvisa il telefono e basta - che e' esattamente come si comportava
       prima che questa parte esistesse. */
    private var sosURL: URL?
    private var sosToken = ""
    private var sosUserName = ""
    private var sosCountdown: TimeInterval = 20
    private var pendingDispatch: DispatchWorkItem?
    private var walkTimer: DispatchWorkItem?
    private let defaults = UserDefaults.standard

    /// Whether this device can do the job at all. The page asks before it
    /// decides to stop running its own web sensors.
    var hasSensors: Bool { motion.isAccelerometerAvailable }

    override init() {
        super.init()
        queue.qualityOfService = .userInitiated
        // Serial: push() throttles on lastPush, and two samples racing on it
        // would let the page see a rate it was never tuned for.
        queue.maxConcurrentOperationCount = 1
        location.delegate = self
        location.desiredAccuracy = kCLLocationAccuracyHundredMeters
        location.activityType = .otherNavigation
        // A protection session that pauses itself is a protection session that
        // stopped, and nothing on screen would say so.
        location.pausesLocationUpdatesAutomatically = false
        registerNotificationCategory()
        observeAppState()
    }

    /* Chi decide cambia con lo stato dell'app, quindi va saputo in tempo reale.
       Tornando davanti il rilevatore nativo si zittisce subito; andando dietro
       riparte pulito, e la sua finestra di assestamento copre il gesto di
       infilare il telefono in tasca, che e' un colpo forte quanto una caduta. */
    private func observeAppState() {
        let center = NotificationCenter.default
        /* Due famiglie di notifiche, e servono tutte e due.

           Questa app adotta le scene (UIApplicationSceneManifest nell'Info.plist,
           e c'e' un SceneDelegate). In un'app a scene iOS **non** manda
           UIApplication.didEnterBackgroundNotification: manda quelle di UIScene.
           Ascoltando solo le prime, isForeground restava vero per sempre, il
           rilevatore nativo non prendeva mai il turno, e scuotere il telefono a
           schermo spento non produceva niente. Provato su un telefono vero: la
           notifica non arrivava.

           Le due famiglie non sono alternative da scegliere in base a com'e'
           fatta l'app oggi: sono due, e domani qualcuno potrebbe togliere le
           scene. Ascoltarle entrambe costa nulla e non puo' sbagliare. */
        let vaDietro = [UIScene.didEnterBackgroundNotification,
                        UIApplication.didEnterBackgroundNotification]
        let tornaDavanti = [UIScene.willEnterForegroundNotification,
                            UIApplication.willEnterForegroundNotification]

        for nome in vaDietro {
            center.addObserver(forName: nome, object: nil, queue: .main) { [weak self] _ in
                guard let self, self.isForeground else { return }
                self.isForeground = false
                self.backgroundDetector.begin()
            }
        }
        for nome in tornaDavanti {
            center.addObserver(forName: nome, object: nil, queue: .main) { [weak self] _ in
                self?.isForeground = true
            }
        }
    }

    // MARK: - lifecycle

    /// Quando e' arrivato l'ultimo campione davvero consegnato alla pagina.
    /// Serve a distinguere "sto ascoltando" da "sto ricevendo": sono due cose
    /// diverse, e la seconda e' l'unica che protegge qualcuno.
    private(set) var lastSampleAt = Date.distantPast

    func start() {
        guard motion.isAccelerometerAvailable else { return }

        /* Qui c'era `guard !isListening else { return }`, e quel guard era un
           blocco senza uscita. isListening dice che a suo tempo abbiamo chiesto
           i campioni, non che stiano arrivando: se il sistema sospende l'app e
           CoreMotion si ferma senza avvisare, la bandiera resta alzata, ogni
           richiesta successiva di ripartire non fa niente, e lo scuotimento non
           viene piu' visto. Il cane da guardia della pagina chiamava start()
           ogni due secondi e otteneva un no.

           Ora, se dice di ascoltare ma da tre secondi non arriva un campione,
           si ferma e si riparte davvero. Tre secondi: i campioni sono ogni 16
           ms, quindi un vuoto cosi' lungo non e' un ritardo, e' uno stop. */
        if isListening {
            guard Date().timeIntervalSince(lastSampleAt) > 3 else { return }
            motion.stopAccelerometerUpdates()
            motion.stopGyroUpdates()
        }

        requestNotificationPermission()

        motion.accelerometerUpdateInterval = Self.sampleInterval
        motion.gyroUpdateInterval = Self.sampleInterval
        if motion.isGyroAvailable { motion.startGyroUpdates() }

        motion.startAccelerometerUpdates(to: queue) { [weak self] data, _ in
            guard let self, let data else { return }
            self.push(data)
        }

        // Last, and it does the rest: the keep-alive and the page both follow
        // from this being true.
        isListening = true
    }

    func stop() {
        // Spegnere la Protezione ferma anche un allarme gia' in conto alla
        // rovescia: chi la spegne sta dicendo che non serve piu'.
        cancelPendingDispatch()
        guard isListening else { return }
        motion.stopAccelerometerUpdates()
        motion.stopGyroUpdates()
        // A walk still running needs the process alive even with the sensors
        // off; updateKeepAlive, reached from the observer, is what knows that.
        isListening = false
    }

    /// The one place that decides whether the app has to stay awake.
    private func updateKeepAlive() {
        guard isListening || isWalkActive else {
            location.stopUpdatingLocation()
            location.allowsBackgroundLocationUpdates = false
            return
        }
        switch location.authorizationStatus {
        case .notDetermined:
            location.requestAlwaysAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            break
        default:
            // Denied or restricted. Sampling still runs while the app is on
            // screen; it will stop when it is not, and sensorStatus() keeps
            // saying what is true rather than what was intended.
            return
        }
        // Setting this without the `location` background mode raises an
        // exception, so Info.plist and this line travel together.
        location.allowsBackgroundLocationUpdates = true
        location.startUpdatingLocation()
    }

    // MARK: - samples

    private func push(_ data: CMAccelerometerData) {
        let now = Date()
        // Prima del throttle: un campione arrivato e scartato perche' troppo
        // ravvicinato dimostra comunque che i sensori stanno girando, ed e'
        // esattamente cio' che start() deve sapere per non ripartire a vuoto.
        lastSampleAt = now
        guard now.timeIntervalSince(lastPush) >= Self.sampleInterval else { return }
        lastPush = now

        // CoreMotion reports g, and reports gravity with the opposite sign to
        // Android. The page scores on magnitude, so the sign changes nothing
        // there — it is flipped anyway so the components mean on both platforms
        // what the Android thresholds were tuned against.
        let g = -9.81
        let radiansToDegrees = 180.0 / Double.pi
        let rotation = motion.gyroData?.rotationRate

        /* Il turno del rilevatore nativo: solo con l'app dietro, solo mentre la
           Protezione e' armata. Prima del delegato, perche' la pagina in secondo
           piano non lo leggera' comunque, e questo non deve dipendere da lei. */
        if !isForeground, isListening {
            let fired = backgroundDetector.consider(
                x: data.acceleration.x * g,
                y: data.acceleration.y * g,
                z: data.acceleration.z * g,
                alpha: (rotation?.z ?? 0) * radiansToDegrees,
                beta: (rotation?.x ?? 0) * radiansToDegrees,
                gamma: (rotation?.y ?? 0) * radiansToDegrees,
                now: now
            )
            if fired {
                DispatchQueue.main.async { [weak self] in self?.raiseBackgroundImpact() }
            }
        }

        delegate?.protectionService(self, didSample: [
            "x": data.acceleration.x * g,
            "y": data.acceleration.y * g,
            "z": data.acceleration.z * g,
            // Android's mapping, kept exactly: alpha from z, beta from x,
            // gamma from y, all rad/s turned into deg/s.
            "alpha": (rotation?.z ?? 0) * radiansToDegrees,
            "beta": (rotation?.x ?? 0) * radiansToDegrees,
            "gamma": (rotation?.y ?? 0) * radiansToDegrees,
            "ts": Int(now.timeIntervalSince1970 * 1000)
        ])
    }

    // MARK: - the SOS, as the phone shows it

    /// Mirrors ACTION_ALERT. States: countdown, sent, failed, clear.
    /* Riconosciuto con la pagina addormentata. Qui non parte nessun allarme
       verso nessuno: parte la notifica, subito, con il tasto per dire che stai
       bene. Mandare il messaggio ai contatti senza che una persona abbia visto
       niente e' un'altra decisione, piu' grande, e non si prende di straforo
       dentro una correzione. Finche' non c'e', questa notifica e' il modo
       onesto di dire "ho visto qualcosa, apri". */
    private func raiseBackgroundImpact() {
        let eventId = "native-\(Int(Date().timeIntervalSince1970 * 1000))"
        alertEventId = eventId

        let content = UNMutableNotificationContent()
        content.title = NSLocalizedString("Garly noticed something", comment: "")
        content.body = NSLocalizedString(
            "A hard shake or an impact. Open Garly to send an alert, or tap below if you are okay.",
            comment: ""
        )
        content.sound = .default
        content.categoryIdentifier = Self.sosCategoryId
        content.userInfo = ["eventId": eventId]
        // Lo stesso motivo del conto alla rovescia: se il telefono e' stato
        // messo a tacere, questo e' il momento in cui deve arrivare comunque.
        content.interruptionLevel = .timeSensitive
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: Self.sosNotificationId, content: content, trigger: nil)
        )

        // Il conto alla rovescia vero, che parte solo ora che la notifica e'
        // gia' sul telefono. Chiunque si faccia vivo prima lo ferma.
        scheduleDispatch()

        // E la pagina lo trovera' quando si sveglia, cosi' chi riapre Garly
        // trova il conto alla rovescia invece di niente.
        delegate?.protectionServiceDidDetectImpact(self)
    }

    /// La pagina consegna quello che serve per mandare un allarme senza di lei.
    func armSosDispatch(url: String, token: String, userName: String, seconds: Double) {
        sosURL = URL(string: url)
        sosToken = token
        sosUserName = userName
        /* Mai meno di venti secondi. Il conto alla rovescia scelto nelle
           impostazioni vale quando la persona sta guardando lo schermo e vede
           il cerchio scendere. Qui non sta guardando niente: ha in mano una
           notifica arrivata all'improvviso, e le serve il tempo di leggerla,
           capirla e decidere. Dieci secondi in tasca non sono un'occasione di
           fermarlo, sono una formalita'. */
        sosCountdown = max(20, seconds)
    }

    func clearSosDispatch() {
        sosURL = nil
        sosToken = ""
        cancelPendingDispatch()
    }

    private func cancelPendingDispatch() {
        pendingDispatch?.cancel()
        pendingDispatch = nil
    }

    /* L'allarme vero, senza nessuno davanti allo schermo.

       Parte solo dopo che la notifica e' gia' arrivata e il conto alla rovescia
       e' passato senza che la persona abbia detto niente. Chiunque si faccia
       vivo prima - il tasto "sto bene", oppure la pagina che si sveglia e
       prende in mano la situazione - lo ferma. */
    private func scheduleDispatch() {
        guard let url = sosURL, !sosToken.isEmpty else {
            /* Senza indirizzo e gettone non si puo' chiamare nessuno, e tacere
               qui e' quello che e' successo su un telefono vero: la notifica di
               rilevamento arrivava, l'allarme no, e non c'era modo di capirlo.
               Se non possiamo chiamare aiuto lo diciamo, cosi' chi ha il
               telefono in mano sa che tocca a lei. */
            let content = UNMutableNotificationContent()
            content.title = NSLocalizedString("Garly cannot send the alert", comment: "")
            content.body = NSLocalizedString(
                "Something was detected, but Garly is not signed in on this phone. Open Garly to send it yourself.",
                comment: ""
            )
            content.sound = .default
            content.interruptionLevel = .timeSensitive
            UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: Self.sosNotificationId + ".nosend", content: content, trigger: nil)
            )
            return
        }
        cancelPendingDispatch()
        let work = DispatchWorkItem { [weak self] in self?.dispatchAlert(url: url) }
        pendingDispatch = work
        DispatchQueue.main.asyncAfter(deadline: .now() + sosCountdown, execute: work)
    }

    private func dispatchAlert(url: URL) {
        pendingDispatch = nil
        var body: [String: Any] = ["userName": sosUserName, "source": "sensor"]
        if let fix = lastKnownLocation {
            body["location"] = [
                "latitude": fix.coordinate.latitude,
                "longitude": fix.coordinate.longitude
            ]
        }
        guard let payload = try? JSONSerialization.data(withJSONObject: body) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(sosToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = payload
        request.timeoutInterval = 20

        URLSession.shared.dataTask(with: request) { [weak self] _, response, error in
            let codice = (response as? HTTPURLResponse)?.statusCode ?? -1
            let ok = error == nil && (200..<300).contains(codice)
            DispatchQueue.main.async {
                self?.reportDispatch(ok: ok)
                // E la pagina lo sapra' al risveglio, cosi' non manda il suo.
                if ok, let self { self.delegate?.protectionServiceDidSendSOS(self) }
            }
        }.resume()
    }

    /* Detto in ogni caso. Un allarme partito e uno non partito portano la
       persona a fare due cose diverse, e la seconda e' la piu' urgente: se non
       e' arrivato deve saperlo subito, non scoprirlo domani. */
    private func reportDispatch(ok: Bool) {
        let content = UNMutableNotificationContent()
        if ok {
            content.title = NSLocalizedString("SOS alert sent", comment: "")
            content.body = NSLocalizedString("Your trusted contact was notified, with your location.", comment: "")
        } else {
            content.title = NSLocalizedString("SOS could not be sent", comment: "")
            content.body = NSLocalizedString("Garly could not reach the network. Open Garly and try again.", comment: "")
        }
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: Self.sosNotificationId, content: content, trigger: nil)
        )
    }

    func notifySOS(state: String, eventId: String) {
        /* Chi comanda su questo allarme.

           "clear" e "sent" sono conclusioni: la persona lo ha annullato, oppure
           la pagina lo ha gia' mandato. In tutti e due i casi l'invio nativo si
           ferma, altrimenti partirebbe un secondo allarme allo stesso contatto,
           o peggio uno gia' annullato.

           "countdown" invece dice solo che la pagina ha aperto il suo cerchio, e
           qui va guardato lo stato dell'app. Con Garly davanti la pagina il
           conto alla rovescia lo porta a termine davvero, quindi comanda lei.
           In secondo piano no: iOS le congela i timer, il cerchio resta fermo e
           non arriva mai in fondo. Cedere il turno li' e' esattamente cosa e'
           successo su un telefono vero - l'invio nativo veniva annullato un
           secondo dopo essere stato programmato, la pagina non poteva
           concluderlo, e al contatto non arrivava niente. */
        if state == "clear" || state == "sent" || isForeground {
            cancelPendingDispatch()
        }
        if !eventId.isEmpty { alertEventId = eventId }

        guard state != "clear" else {
            UNUserNotificationCenter.current()
                .removeDeliveredNotifications(withIdentifiers: [Self.sosNotificationId])
            alertEventId = ""
            return
        }

        let content = UNMutableNotificationContent()
        switch state {
        case "sent":
            content.title = NSLocalizedString("SOS alert sent", comment: "")
            content.body = NSLocalizedString("Your trusted contact was notified", comment: "")
        case "failed":
            content.title = NSLocalizedString("SOS needs your attention", comment: "")
            content.body = NSLocalizedString("Automatic delivery failed; open Garly to continue", comment: "")
        default:
            content.title = NSLocalizedString("Garly SOS", comment: "")
            content.body = NSLocalizedString("SOS countdown active — open Garly to cancel", comment: "")
            // Only a countdown can still be called off, so only a countdown
            // offers the button.
            content.categoryIdentifier = Self.sosCategoryId
            content.userInfo = ["eventId": alertEventId]
        }
        content.sound = .default
        // Buca il Focus e il Non disturbare. E' il senso di questo avviso: se
        // il telefono e' stato messo a tacere, e' proprio quello il momento in
        // cui deve arrivare comunque. Regge solo con la capability
        // corrispondente, che sta in Garly.entitlements accanto a questa riga.
        content.interruptionLevel = .timeSensitive

        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: Self.sosNotificationId, content: content, trigger: nil)
        )
    }

    private func registerNotificationCategory() {
        let cancel = UNNotificationAction(
            identifier: Self.cancelActionId,
            title: NSLocalizedString("I'm okay — cancel SOS", comment: ""),
            options: [.foreground]
        )
        UNUserNotificationCenter.current().setNotificationCategories([
            UNNotificationCategory(identifier: Self.sosCategoryId, actions: [cancel], intentIdentifiers: [])
        ])
    }

    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] _, _ in
            self?.refreshNotificationSettings()
        }
    }

    /* Avere il diritto di bucare il Focus non basta: la persona puo' togliere
       le "notifiche urgenti" a questa app, e nel Focus notturno succede spesso
       senza saperlo. E' successo su un telefono vero: il rilevatore aveva visto
       lo scuotimento, la notifica era partita, e il Non disturbare l'ha
       trattenuta.

       L'app promette che un SOS arriva anche a telefono silenzioso - e' scritto
       pure nelle note per il revisore. Una promessa che puo' essere spenta da un
       interruttore va letta e detta, non data per buona: e' lo stesso difetto di
       "Protezione attiva" mentre i sensori erano fermi, in un altro punto. */
    private func refreshNotificationSettings() {
        UNUserNotificationCenter.current().getNotificationSettings { [weak self] settings in
            let consentite = settings.authorizationStatus == .authorized
                || settings.authorizationStatus == .provisional
            // .enabled e' l'unico caso in cui l'avviso passa davvero il Focus.
            let urgentiPermesse = settings.timeSensitiveSetting == .enabled
            DispatchQueue.main.async {
                self?.delegate?.protectionService(self!,
                                                  notificationsAllowed: consentite,
                                                  timeSensitiveAllowed: urgentiPermesse)
            }
        }
    }

    // MARK: - walk mode

    /*
     The dead man's switch.

     Android moved this out of the page for one reason, stated in its own
     comment: a WebView is paused when the screen locks, and a deadline that
     stops counting the moment the phone goes in a pocket protects nobody. iOS
     is worse — the whole app is suspended — so the deadline is kept three ways,
     and the weakest of them is the timer:

       1. a timer, which is what fires while the app is alive;
       2. a local notification scheduled for the deadline, which fires even if
          the app has been suspended or killed outright;
       3. the deadline on disk, so a relaunch can work out that it passed while
          nothing was running.

     Turning `hasWalkMode()` on also removes the page's own "the deadline can
     slip" warning. That warning is a promise in reverse: with the capability on,
     it must not slip.
     */

    var isWalkActive: Bool { defaults.bool(forKey: WalkKey.active) }
    var hasPendingWalkSilence: Bool { defaults.bool(forKey: WalkKey.silent) }

    /// Arms or re-arms the deadline. Every sign of life calls this again.
    func armWalk(silenceMs: Double) {
        let window = min(max(silenceMs / 1000, Self.walkWindow.lowerBound), Self.walkWindow.upperBound)
        let deadline = Date().addingTimeInterval(window)

        defaults.set(true, forKey: WalkKey.active)
        defaults.set(deadline.timeIntervalSince1970, forKey: WalkKey.deadline)
        defaults.set(false, forKey: WalkKey.silent)

        requestNotificationPermission()
        updateKeepAlive()

        walkTimer?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.onWalkSilence() }
        walkTimer = work
        DispatchQueue.main.asyncAfter(deadline: .now() + window, execute: work)

        scheduleWalkNotification(after: window)
    }

    func stopWalk() {
        walkTimer?.cancel()
        walkTimer = nil
        defaults.set(false, forKey: WalkKey.active)
        defaults.set(0.0, forKey: WalkKey.deadline)
        cancelWalkNotification()
        updateKeepAlive()
    }

    /**
     Works out what happened while nothing was running.

     Called on launch and on every return to the foreground. If the walk was
     armed and the deadline is behind us, the silence happened — whether or not
     any timer of ours survived to notice.
     */
    @discardableResult
    func reconcileWalk() -> Bool {
        guard isWalkActive else { return hasPendingWalkSilence }
        let deadline = defaults.double(forKey: WalkKey.deadline)
        guard Date().timeIntervalSince1970 >= deadline else { return hasPendingWalkSilence }
        markWalkSilence()
        return true
    }

    /// The page has taken the silence. Clearing the stored flag is what stops
    /// the next launch handing it over a second time.
    func acknowledgeWalkSilence() {
        defaults.set(false, forKey: WalkKey.silent)
    }

    private func onWalkSilence() {
        guard isWalkActive else { return }
        let deadline = defaults.double(forKey: WalkKey.deadline)
        let remaining = deadline - Date().timeIntervalSince1970
        guard remaining <= 0 else {
            // A sign of life landed while this was queued. Wait for the new
            // deadline rather than raising an alarm that is no longer true.
            let work = DispatchWorkItem { [weak self] in self?.onWalkSilence() }
            walkTimer = work
            DispatchQueue.main.asyncAfter(deadline: .now() + max(1, remaining), execute: work)
            return
        }
        markWalkSilence()
    }

    private func markWalkSilence() {
        defaults.set(false, forKey: WalkKey.active)
        defaults.set(true, forKey: WalkKey.silent)
        walkTimer?.cancel()
        walkTimer = nil
        // The scheduled one is either about to fire or has fired; either way the
        // app is awake now and posts its own.
        cancelWalkNotification()
        postWalkSilenceNotification()
        updateKeepAlive()
        delegate?.protectionServiceDidObserveWalkSilence(self)
    }

    private func walkContent() -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = NSLocalizedString("Garly walk mode", comment: "")
        content.body = NSLocalizedString("No reply — open Garly now to stop the alert", comment: "")
        content.sound = .default
        // Un silenzio in walk mode e' la stessa urgenza di un SOS: la persona
        // non risponde da cinque minuti.
        content.interruptionLevel = .timeSensitive
        // Same as Android, which puts the cancel action on the walk alert too.
        content.categoryIdentifier = Self.sosCategoryId
        content.userInfo = ["eventId": ""]
        return content
    }

    private func scheduleWalkNotification(after seconds: TimeInterval) {
        cancelWalkNotification()
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(
                identifier: Self.walkNotificationId,
                content: walkContent(),
                trigger: UNTimeIntervalNotificationTrigger(timeInterval: max(1, seconds), repeats: false)
            )
        )
    }

    private func postWalkSilenceNotification() {
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: Self.walkNotificationId, content: walkContent(), trigger: nil)
        )
    }

    private func cancelWalkNotification() {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [Self.walkNotificationId])
        center.removeDeliveredNotifications(withIdentifiers: [Self.walkNotificationId])
    }

    /// Called by the notification delegate when the button is tapped.
    func handleCancelAction(eventId: String) {
        cancelPendingDispatch()
        let cancelledAt = Int(Date().timeIntervalSince1970 * 1000)
        let identifier = eventId.isEmpty ? alertEventId : eventId
        UNUserNotificationCenter.current()
            .removeDeliveredNotifications(withIdentifiers: [Self.sosNotificationId])
        alertEventId = ""
        delegate?.protectionService(self, didCancelSOS: identifier, at: cancelledAt)
    }
}

extension ProtectionService: CLLocationManagerDelegate {

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard isListening || isWalkActive else { return }
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            manager.allowsBackgroundLocationUpdates = true
            manager.startUpdatingLocation()
        default:
            manager.stopUpdatingLocation()
        }
        // Permission changing changes what the app can honestly claim.
        delegate?.protectionServiceDidChangeListening(self)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        /* Servivano solo a tenere vivo il processo, e la posizione la chiedeva
           la pagina per conto suo. Ma quando l'allarme parte con l'app chiusa la
           pagina non c'e', e un SOS senza posizione dice a chi lo riceve che sei
           nei guai e non dove. Quindi l'ultima buona si tiene. */
        guard let fix = locations.last else { return }
        guard fix.horizontalAccuracy >= 0 else { return }
        lastKnownLocation = fix
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // A failed fix is not a failed protection session; the sensors are
        // unaffected and the page is told nothing it would have to act on.
    }
}
