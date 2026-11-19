# Garly privacy model

Updated 19 November 2026. This is a product overview; the [published privacy policy](https://garlyapp.pro/privacy.html) provides the service's formal privacy information. The [v1.4 whitepaper](Garly-Whitepaper.pdf) includes the updated product and ecosystem overview.

## Choice and account separation

Optional memory, saved places, trusted contacts and safety modes are controlled by the user. Account data is scoped to the authenticated identity. Account-bound local state must not be carried into another user's session.

Users can review or delete saved information and use available export, history and account-deletion controls. Privacy Lock protects sensitive app areas, with separate parental controls and two-factor authentication where configured.

Account, profile, chat, saved memories, routines, trusted-contact details, places, security settings and programme records may be processed or stored on Garly servers, alongside account-scoped caches and preferences on the device. Garly does not claim that all data remains exclusively on the phone. Necessary infrastructure and service providers process information for their stated functions; **Garly does not sell personal information**.

## Conversations and memory

The companion receives selected authorised context rather than unrestricted access to the phone. Explicit durable facts may be proposed as memories; routines and sensitive information require consent. Inferred patterns and passing moods must not be presented as confirmed saved facts.

Chat may be processed by external AI providers. This repository does not claim that the entire AI service is on-device or that all conversations are end-to-end encrypted.

Foreground check-ins use a small set of app events and confirmed routine schedules. They respect the privacy lock and avoid repetitive questions. They do not share the conversation with trusted contacts or automatically alert them when ignored.

## Location and emergency sharing

- Manual and configured automatic SOS flows attempt to share an emergency message and available location with configured trusted contacts.
- Live Journey deliberately shares a temporary updating location link. Keep the link private.
- Last-Known Location requires opt-in, selected recipients and server-confirmed activation. It retains the latest position for the feature rather than a route history; that position has a 24-hour retention limit.
- A saved location has a capture time and available accuracy. It is not proof of the user's current position or safety.
- Deleting a server-held position cannot recall copies of messages already delivered to recipients.
- An offline local pause is not server-confirmed cancellation; the app must communicate the distinction.

Location, signal and delivery may be unavailable. There is no guarantee that every alert reaches or is read by its recipient.

## Voice SOS and other microphone uses

Voice SOS is an optional beta listening session, separate from the companion and Last-Known Location. The selected emergency phrase is recognised locally. Calibration audio and the temporary voice-filter representation are held in memory for that session and released when it ends. Voice SOS does not save audio recordings or transcripts or upload the user's voice to Garly, messaging services or cloud AI. Calibration helps voice separation; it is not identity authentication.

Preparing a language downloads model files to the phone. These files can remain for later sessions; they are not recordings of the user. The download provider receives ordinary network information needed to serve the files. Preparing a language does not start the microphone.

If separately enabled, Voice SOS can keep the latest available position in session memory for inclusion in the SOS. This is not a route history. Other independently enabled location features retain their own behaviour. Stopping or expiry ends the Voice SOS session; closing its panel does not.

Optional ambient-sound detection is another local feature. Short fragments are analysed in memory and overwritten, rather than uploaded as recordings. The separate user-started microphone test may retain one short encrypted clip locally for playback, export or deletion. A planned event-recording preference must not be described as an active recording service.

Feature availability and background operation depend on the shipping build, user choices and device permissions. Do not repeat older blanket statements that Garly never uses the microphone.

## Retention, analytics and deletion

- Last-Known Location retains its latest saved point for up to 24 hours.
- Voice SOS session audio and calibration are temporary memory, not saved recordings.
- Trusted-contact and other account data remain subject to user changes, removal and account-deletion controls, with limited exceptions for security, accounting or legal obligations.
- First-party registration-journey statistics can retain daily aggregates for up to 90 days; those aggregates do not contain new visitor identifiers, names, form values, account IDs, IP addresses or full URLs.
- Optional Firebase usage analytics requires explicit consent and confirmation of adulthood. It is disabled for accounts known to be under 18; refusing it does not prevent registration or use of safety features.

The policy does not specify one fixed numeric retention period for every record type or a complete country-by-country provider inventory. Do not invent either or promise that account deletion recalls messages already delivered, removes public blockchain transactions or erases records that must lawfully be retained.

Future features may change data requirements. Their actual collection, providers, retention and choices must be explained before use; a roadmap is not permission to collect additional data.

## Optional community and wallets

A wallet is not required for the core safety app. Optional community flows may verify control of a public Solana address using a signed message; payment flows are separate transactions shown to the user. Garly must never request a seed phrase or private key.

Citizen membership does not require buying tokens or entering a payout wallet. A valid payout address is needed for eligible token payments. Solana payments are public transactions; programme XP is not a private or public token transfer by itself.

Whistle is an external service embedded in City Studio. It manages its own authentication, optional in-app wallet and credit balance. A Whistle ticket is not a $GARLY transfer. Whistle's own service and privacy terms apply to its experience; Garly must not claim it holds Whistle recovery material.

Private chats, contact lists and personal location history are not intended to be published on-chain. A public community map contribution is different from a private saved place.

## Repository boundary

The public repository contains documentation and selected, older Android and iOS native source snapshots. It excludes user records, private analytics, production credentials, signing keys, hosted web/backend source and deployment configuration.

Report security concerns privately as described in [SECURITY.md](../SECURITY.md). Do not publish another person's location, credentials or private messages in a report.
