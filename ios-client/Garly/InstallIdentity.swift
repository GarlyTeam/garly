import Foundation
import WebKit

/// The install-scoped identity, mirroring GarlyNativeBridge.ensureInstallId().
///
/// Deliberately `UserDefaults` and **not** the keychain. Keychain items outlive
/// deleting the app, which is the opposite of what this is for: on Android a
/// new install means a new id, so a reinstall or a tester handing the phone on
/// cannot land inside the previous account's session.
enum InstallIdentity {

    private static let idKey = "garly.install_id"
    private static let initialisedKey = "garly.install_initialised"

    private(set) static var isFreshInstall = false

    static var current: String {
        let defaults = UserDefaults.standard
        if defaults.bool(forKey: initialisedKey), let existing = defaults.string(forKey: idKey), !existing.isEmpty {
            return existing
        }
        let generated = UUID().uuidString
        defaults.set(true, forKey: initialisedKey)
        defaults.set(generated, forKey: idKey)
        isFreshInstall = true
        return generated
    }

    /// Wipes cookies and DOM storage, the way clearWebDataNow() does on Android.
    /// On a fresh install this runs before the first paint, so nothing of a
    /// previous account is on screen even briefly.
    static func clearWebData(completion: @escaping () -> Void) {
        let store = WKWebsiteDataStore.default()
        store.removeData(
            ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(),
            modifiedSince: Date(timeIntervalSince1970: 0),
            completionHandler: completion
        )
    }
}
