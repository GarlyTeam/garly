import Foundation
import AuthenticationServices
import CryptoKit

/// "Continue with Google", the only way it can work on iOS.
///
/// Google refuses its own web sign-in inside an embedded web view, so the page
/// cannot do this itself here. The flow runs in `ASWebAuthenticationSession` —
/// Safari's own view, with the address bar visible and the app unable to read
/// what is typed into it — and hands the page the same id_token the web button
/// would have produced, through `window.__garlyNativeGoogleCredential`.
///
/// Authorization code + PKCE. iOS OAuth clients have no client secret, because
/// a secret shipped inside an app is not a secret.
final class GoogleSignInCoordinator: NSObject {

    enum SignInError: LocalizedError {
        case notConfigured
        case cancelled
        case noIdentityToken
        case server(String)

        var errorDescription: String? {
            switch self {
            case .notConfigured:
                return NSLocalizedString("Google sign-in is not set up in this build.", comment: "")
            case .cancelled:
                return NSLocalizedString("Google sign-in was cancelled.", comment: "")
            case .noIdentityToken:
                return NSLocalizedString("Google did not return a usable sign-in.", comment: "")
            case .server(let message):
                return message
            }
        }
    }

    private weak var presenting: UIViewController?
    private var session: ASWebAuthenticationSession?
    private let verifier = GoogleSignInCoordinator.randomVerifier()

    init(presenting: UIViewController) {
        self.presenting = presenting
    }

    func start(completion: @escaping (Result<String, Error>) -> Void) {
        let clientID = GarlyConfig.googleIOSClientID
        guard !clientID.isEmpty else {
            completion(.failure(SignInError.notConfigured))
            return
        }

        // Google's iOS convention: the client id reversed is the app's scheme.
        let scheme = clientID.split(separator: ".").reversed().joined(separator: ".")
        let redirectURI = "\(scheme):/oauth2redirect"

        var components = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        components.queryItems = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: "openid email profile"),
            URLQueryItem(name: "code_challenge", value: Self.challenge(for: verifier)),
            URLQueryItem(name: "code_challenge_method", value: "S256")
        ]
        guard let authURL = components.url else {
            completion(.failure(SignInError.notConfigured))
            return
        }

        let session = ASWebAuthenticationSession(url: authURL, callbackURLScheme: scheme) { [weak self] callback, error in
            guard let self else { return }
            if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                completion(.failure(SignInError.cancelled))
                return
            }
            if let error {
                completion(.failure(error))
                return
            }
            guard
                let callback,
                let code = URLComponents(url: callback, resolvingAgainstBaseURL: false)?
                    .queryItems?.first(where: { $0.name == "code" })?.value
            else {
                completion(.failure(SignInError.noIdentityToken))
                return
            }
            self.exchange(code: code, clientID: clientID, redirectURI: redirectURI, completion: completion)
        }
        session.presentationContextProvider = self
        session.prefersEphemeralWebBrowserSession = false
        self.session = session
        session.start()
    }

    private func exchange(code: String,
                          clientID: String,
                          redirectURI: String,
                          completion: @escaping (Result<String, Error>) -> Void) {
        var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")

        var body = URLComponents()
        body.queryItems = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "code", value: code),
            URLQueryItem(name: "code_verifier", value: verifier),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "grant_type", value: "authorization_code")
        ]
        request.httpBody = body.query?.data(using: .utf8)

        URLSession.shared.dataTask(with: request) { data, _, error in
            DispatchQueue.main.async {
                if let error {
                    completion(.failure(error))
                    return
                }
                guard
                    let data,
                    let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
                else {
                    completion(.failure(SignInError.noIdentityToken))
                    return
                }
                if let description = payload["error_description"] as? String {
                    completion(.failure(SignInError.server(description)))
                    return
                }
                guard let idToken = payload["id_token"] as? String else {
                    completion(.failure(SignInError.noIdentityToken))
                    return
                }
                completion(.success(idToken))
            }
        }.resume()
    }

    // MARK: - PKCE

    private static func randomVerifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return base64URL(Data(bytes))
    }

    private static func challenge(for verifier: String) -> String {
        base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    private static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

extension GoogleSignInCoordinator: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        presenting?.view.window ?? ASPresentationAnchor()
    }
}
