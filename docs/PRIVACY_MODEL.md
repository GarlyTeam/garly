# Garly privacy model

Updated 9 October 2026. This is a product overview; the [published privacy policy](https://garlyapp.pro/privacy.html) provides the service's formal privacy information.

## Choice and account separation

Optional memory, saved places, trusted contacts and safety modes are controlled by the user. Account data is scoped to the authenticated identity. Account-bound local state must not be carried into another user's session.

Users can review or delete saved information and use available export, history and account-deletion controls. Privacy Lock protects sensitive app areas, with separate parental controls and two-factor authentication where configured.

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

## Recording and device permissions

Do not infer active microphone or recording behaviour from a planned setting or a source-code capability. Feature availability depends on the shipping build and explicit device permissions. Garly does not advertise continuous listening as the basis of its companion or Last-Known Location features.

## Optional community and wallets

A wallet is not required for the core safety app. Optional community flows may verify control of a public Solana address using a signed message; payment flows are separate transactions shown to the user. Garly must never request a seed phrase or private key.

Private chats, contact lists and personal location history are not intended to be published on-chain. A public community map contribution is different from a private saved place.

## Repository boundary

The public repository contains documentation and selected, older Android and iOS native source snapshots. It excludes user records, private analytics, production credentials, signing keys, hosted web/backend source and deployment configuration.

Report security concerns privately as described in [SECURITY.md](../SECURITY.md). Do not publish another person's location, credentials or private messages in a report.
