import Foundation

/// Everything that changes between a build that ships and a build that does not.
enum GarlyConfig {

    /// Same start URL as the Android TWA: garly-android/twa-manifest.json.
    static let appURL = URL(string: "https://garlyapp.pro/app/index.html")!

    /// Requests to this host stay inside the app. Everything else is somebody
    /// else's website and opens outside it.
    static let appHost = "garlyapp.pro"

    /// Google OAuth client of type **iOS**, created in Google Cloud Console.
    ///
    /// Empty on purpose. Google refuses its own web sign-in button inside an
    /// embedded web view, so the native flow is the only one that can work
    /// here — but it issues an id_token with this client as its audience, and
    /// `/api/garly/google-auth` currently accepts exactly one audience, the web
    /// client. Filling this in without widening the server check produces a
    /// sign-in that fails after the user has already typed their password.
    ///
    /// Leave it empty and `startGoogleSignIn` is not offered to the page at
    /// all; the e-mail form is the sign-in path. See README.md, "Google".
    ///
    /// Same Google Cloud project as the web client the server already knows
    /// (815085989019), so the accounts are the same accounts.
    static let googleIOSClientID = "815085989019-ngprmcpgbn1go25drofs829n7t7d9ls6.apps.googleusercontent.com"

    /// What this build can actually do. The shim defines a bridge method only
    /// where the answer here is `true`; everything else falls through to the
    /// web implementation the app already uses in mobile Safari.
    ///
    /// All false is not a placeholder — it is the honest description of a
    /// shell build. Phase 3 flips these one at a time, each with the native
    /// service behind it already working.
    static let capabilities = BridgeCapabilities(
        motion: true,
        walkMode: true,
        journey: true,
        acousticProtection: false,
        acousticDiagnostics: false,
        billing: false,
        googleSignIn: !googleIOSClientID.isEmpty
    )
}

struct BridgeCapabilities: Encodable {
    let motion: Bool
    let walkMode: Bool
    let journey: Bool
    let acousticProtection: Bool
    let acousticDiagnostics: Bool
    let billing: Bool
    let googleSignIn: Bool
}
