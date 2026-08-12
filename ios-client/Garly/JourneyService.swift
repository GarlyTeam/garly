import Foundation
import CoreLocation
import UserNotifications

protocol JourneyServiceDelegate: AnyObject {
    /// La persona e' rientrata: la pagina lo raccoglie e chiude il viaggio.
    func journeyServiceDidComplete(_ service: JourneyService, payload: String)
}

/**
 La controparte di JourneyService.java.

 Due modi, come su Android:

 - **rientro a casa**: guarda la posizione e aspetta una partenza confermata
   prima di accettare un rientro confermato. Il perche' e' nel commento del
   servizio Android: aprire la mappa mentre si e' gia' a casa non deve creare
   una notifica di rientro che non e' mai avvenuto.
 - **solo condivisione**: manda la posizione al link che la persona ha dato a
   chi si fida, finche' non scade.

 Le soglie sono quelle di Android, numero per numero: due campioni fuori per
 dire "partita", due dentro piu' trenta secondi per dire "tornata". Cambiarle
 qui e non li' vorrebbe dire che lo stesso tragitto finisce su un telefono e
 non sull'altro.
 */
final class JourneyService: NSObject {

    weak var delegate: JourneyServiceDelegate?

    private enum Key {
        static let active = "garly.journey.active"
        static let pending = "garly.journey.pending"
        static let homeName = "garly.journey.home_name"
        static let sessionId = "garly.journey.session_id"
        static let departed = "garly.journey.departed"
        static let departedAt = "garly.journey.departed_at"
        static let startedAt = "garly.journey.started_at"
    }

    private let location = CLLocationManager()
    private let defaults = UserDefaults.standard

    private var homeCoordinate: CLLocationCoordinate2D?
    private var homeRadius: CLLocationDistance = 0
    private var uploadURL: URL?
    private var uploadToken = ""
    private var shareExpiresAt: TimeInterval = 0
    private var uploadOnly = false

    private var outsideSamples = 0
    private var insideSamples = 0

    /// Solo l'ultimo punto conosciuto, mai una coda: e' il modello di Garly, e
    /// una coda che cresce mentre si e' senza rete manderebbe piu' tardi una
    /// posizione che non e' piu' vera.
    private var pending: (url: URL, body: Data)?
    private var uploading = false

    var isActive: Bool { defaults.bool(forKey: Key.active) }

    override init() {
        super.init()
        location.delegate = self
        location.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        location.distanceFilter = 20   // come i 20 metri del provider GPS su Android
        location.pausesLocationUpdatesAutomatically = false
        location.activityType = .otherNavigation
    }

    // MARK: - avvio

    func startHomeWatch(latitude: Double, longitude: Double, radius: Double,
                        homeName: String, sessionId: String,
                        uploadUrl: String, uploadToken: String) {
        homeCoordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        homeRadius = radius
        uploadOnly = false
        shareExpiresAt = 0
        apply(uploadUrl: uploadUrl, token: uploadToken)

        defaults.set(true, forKey: Key.active)
        defaults.set(false, forKey: Key.pending)
        defaults.set(homeName, forKey: Key.homeName)
        defaults.set(sessionId, forKey: Key.sessionId)
        defaults.set(false, forKey: Key.departed)
        defaults.set(0.0, forKey: Key.departedAt)
        defaults.set(Date().timeIntervalSince1970, forKey: Key.startedAt)
        outsideSamples = 0
        insideSamples = 0

        beginUpdates()
    }

    func startShare(uploadUrl: String, uploadToken: String, expiresAt: Double) {
        uploadOnly = true
        shareExpiresAt = expiresAt / 1000
        apply(uploadUrl: uploadUrl, token: uploadToken)
        defaults.set(true, forKey: Key.active)
        beginUpdates()
    }

    /// La pagina dichiara il viaggio concluso da se'. Su Android e' ACTION_COMPLETE:
    /// chiude il viaggio remoto e avvisa, ma non lascia niente da raccogliere,
    /// perche' chi lo ha chiuso sta gia' guardando lo schermo.
    func complete(homeName: String) {
        defaults.set(homeName, forKey: Key.homeName)
        finish(pendingForPage: false)
    }

    func stop() {
        defaults.set(false, forKey: Key.active)
        location.stopUpdatingLocation()
        location.allowsBackgroundLocationUpdates = false
    }

    /* Non possiamo piu' seguire: niente permesso, niente posizioni. Una
       condivisione che non si aggiorna piu' e' peggio di una chiusa, perche'
       mostra un punto vecchio come se fosse dove sei adesso. */
    func stopUnableToFollow() {
        endRemote(reason: "permission")
        stop()
    }

    /// Qualcuno ha premuto Ferma condivisione. Smettere di mandare posizioni non
    /// basta: finche' il viaggio resta aperto sul server il link continua a
    /// mostrare l'ultimo punto a chi ce l'ha. La pagina lo chiude gia' per conto
    /// suo, ma con la sua sessione: se quella chiamata non passa, questa qui ha
    /// il token del viaggio ed e' una seconda strada indipendente. Chiudere due
    /// volte non fa danni - il server avvisa i contatti solo la prima.
    func stopByUser() {
        endRemote(reason: "user")
        stop()
    }

    /// Il rientro, una volta sola. Stessa forma del JSON di Android, perche' la
    /// pagina lo legge con lo stesso codice.
    func consumeCompletion() -> String {
        guard defaults.bool(forKey: Key.pending) else { return "null" }
        defaults.set(false, forKey: Key.pending)
        let payload: [String: Any] = [
            "completed": true,
            "homeName": defaults.string(forKey: Key.homeName) ?? "Home",
            "sessionId": defaults.string(forKey: Key.sessionId) ?? ""
        ]
        return (try? JSONSerialization.data(withJSONObject: payload))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "null"
    }

    private func apply(uploadUrl: String, token: String) {
        uploadURL = URL(string: uploadUrl)
        uploadToken = token
    }

    private func beginUpdates() {
        switch location.authorizationStatus {
        case .notDetermined:
            location.requestAlwaysAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            break
        default:
            /* Senza permesso non si finge di seguire nessuno - e non si finge
               nemmeno dall'altra parte. Fermarsi qui e basta lasciava il
               percorso aperto sul server fino alla scadenza: chi ha il link
               continuava a vedere l'ultima posizione, ferma, credendola
               attuale. Meglio dirgli che e' finito. */
            stopUnableToFollow()
            return
        }
        location.allowsBackgroundLocationUpdates = true
        location.startUpdatingLocation()
    }

    // MARK: - il tragitto

    private func handle(_ fix: CLLocation) {
        guard isActive else { return }
        upload(fix)

        if uploadOnly {
            if shareExpiresAt > 0, Date().timeIntervalSince1970 >= shareExpiresAt {
                endRemote(reason: "expired")
                stop()
            }
            return
        }

        guard let home = homeCoordinate else { return }
        let distance = fix.distance(from: CLLocation(latitude: home.latitude, longitude: home.longitude))
        // Piu' larga del raggio di casa: si esce davvero prima di poter
        // rientrare, altrimenti un GPS ballerino sul divano dichiara un viaggio.
        let departureRadius = max(homeRadius + 80, homeRadius * 1.65)

        if !defaults.bool(forKey: Key.departed) {
            outsideSamples = distance >= departureRadius ? outsideSamples + 1 : 0
            if outsideSamples >= 2 {
                defaults.set(true, forKey: Key.departed)
                defaults.set(Date().timeIntervalSince1970, forKey: Key.departedAt)
                insideSamples = 0
            }
            return
        }

        insideSamples = distance <= homeRadius ? insideSamples + 1 : 0
        let since = max(defaults.double(forKey: Key.departedAt), defaults.double(forKey: Key.startedAt))
        if insideSamples >= 2, Date().timeIntervalSince1970 - since >= 30 {
            finish(pendingForPage: true)
        }
    }

    private func finish(pendingForPage: Bool) {
        endRemote(reason: "home")
        defaults.set(false, forKey: Key.active)
        defaults.set(pendingForPage, forKey: Key.pending)
        location.stopUpdatingLocation()
        location.allowsBackgroundLocationUpdates = false

        let content = UNMutableNotificationContent()
        content.title = NSLocalizedString("You're home", comment: "")
        content.body = String(
            format: NSLocalizedString("Garly closed your journey to %@.", comment: ""),
            defaults.string(forKey: Key.homeName) ?? "Home"
        )
        content.sound = .default
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "garly.journey.home", content: content, trigger: nil)
        )

        if pendingForPage {
            delegate?.journeyServiceDidComplete(self, payload: consumePeek())
        }
    }

    /// Il payload da mandare alla pagina senza consumarlo: lo consumera' lei.
    private func consumePeek() -> String {
        let payload: [String: Any] = [
            "completed": true,
            "homeName": defaults.string(forKey: Key.homeName) ?? "Home",
            "sessionId": defaults.string(forKey: Key.sessionId) ?? ""
        ]
        return (try? JSONSerialization.data(withJSONObject: payload))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "null"
    }

    // MARK: - invio

    private func upload(_ fix: CLLocation) {
        guard let url = uploadURL, !uploadToken.isEmpty else { return }
        let payload: [String: Any] = [
            "latitude": fix.coordinate.latitude,
            "longitude": fix.coordinate.longitude,
            "accuracy": fix.horizontalAccuracy >= 0 ? fix.horizontalAccuracy : NSNull()
        ]
        guard let body = try? JSONSerialization.data(withJSONObject: payload) else { return }
        post(url: url, body: body)
    }

    private func endRemote(reason: String) {
        guard let url = uploadURL, !uploadToken.isEmpty else { return }
        // Stessa regola di Android: l'endpoint di chiusura e' quello di invio
        // con /end in fondo.
        let ending = url.absoluteString.hasSuffix("/upload")
            ? url.absoluteString + "/end"
            : url.absoluteString
        guard let endURL = URL(string: ending),
              let body = try? JSONSerialization.data(withJSONObject: ["reason": reason])
        else { return }
        post(url: endURL, body: body)
    }

    private func post(url: URL, body: Data) {
        pending = (url, body)
        guard !uploading else { return }
        drain()
    }

    private func drain() {
        guard let next = pending else { uploading = false; return }
        pending = nil
        uploading = true

        var request = URLRequest(url: next.url)
        request.httpMethod = "POST"
        request.setValue("Journey \(uploadToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = next.body

        URLSession.shared.dataTask(with: request) { [weak self] _, _, _ in
            DispatchQueue.main.async {
                guard let self else { return }
                // Un invio fallito non si ripete: al prossimo punto ne partira'
                // uno piu' aggiornato, ed e' quello che serve a chi guarda.
                self.uploading = false
                if self.pending != nil { self.drain() }
            }
        }.resume()
    }
}

extension JourneyService: CLLocationManagerDelegate {

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let fix = locations.last else { return }
        handle(fix)
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard isActive else { return }
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            manager.allowsBackgroundLocationUpdates = true
            manager.startUpdatingLocation()
        default:
            // Permesso revocato a percorso in corso: stessa ragione di sopra.
            stopUnableToFollow()
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Un punto mancato non chiude un viaggio: arrivera' il prossimo.
    }
}
