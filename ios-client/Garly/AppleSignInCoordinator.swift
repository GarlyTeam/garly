import AuthenticationServices
import UIKit

/**
 "Accedi con Apple".

 Esiste per due ragioni. La prima e' che la linea guida 4.8 lo chiede quando
 un'app offre un accesso di terze parti, e Garly offre Google. La seconda e'
 che togliere Google da iOS non era un'alternativa: gli account sono
 identificati dal provider, quindi chi si e' registrato con Google altrove non
 riuscirebbe piu' a entrare dal telefono.

 Non c'e' niente da configurare qui: il sistema parla direttamente con Apple e
 restituisce un identity token firmato. Chi decide se crederci e' il server,
 che ne verifica firma, emittente, destinatario e scadenza.
 */
final class AppleSignInCoordinator: NSObject {

    enum SignInError: LocalizedError {
        case cancelled
        case noIdentityToken

        var errorDescription: String? {
            switch self {
            case .cancelled:
                return NSLocalizedString("Sign in with Apple was cancelled.", comment: "")
            case .noIdentityToken:
                return NSLocalizedString("Apple did not return a usable sign-in.", comment: "")
            }
        }
    }

    /// Il nome arriva una volta sola, al primo accesso, e solo se la persona
    /// sceglie di darlo: non e' dentro il token e non si puo' richiedere dopo.
    struct Result {
        let identityToken: String
        let fullName: String
    }

    private weak var presenting: UIViewController?
    private var completion: ((Swift.Result<Result, Error>) -> Void)?

    init(presenting: UIViewController) {
        self.presenting = presenting
    }

    func start(completion: @escaping (Swift.Result<Result, Error>) -> Void) {
        self.completion = completion
        let request = ASAuthorizationAppleIDProvider().createRequest()
        // Solo nome e indirizzo: e' anche cio' che la 4.8 chiede a un accesso
        // per essere considerato equivalente.
        request.requestedScopes = [.fullName, .email]

        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        controller.performRequests()
    }
}

extension AppleSignInCoordinator: ASAuthorizationControllerDelegate {

    func authorizationController(controller: ASAuthorizationController,
                                 didCompleteWithAuthorization authorization: ASAuthorization) {
        guard
            let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
            let data = credential.identityToken,
            let token = String(data: data, encoding: .utf8)
        else {
            completion?(.failure(SignInError.noIdentityToken))
            completion = nil
            return
        }

        let parts = [credential.fullName?.givenName, credential.fullName?.familyName]
        let name = parts.compactMap { $0 }.joined(separator: " ")

        completion?(.success(Result(identityToken: token, fullName: name)))
        completion = nil
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        let cancelled = (error as? ASAuthorizationError)?.code == .canceled
        completion?(.failure(cancelled ? SignInError.cancelled : error))
        completion = nil
    }
}

extension AppleSignInCoordinator: ASAuthorizationControllerPresentationContextProviding {
    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        presenting?.view.window ?? ASPresentationAnchor()
    }
}
