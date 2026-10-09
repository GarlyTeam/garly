# Garly

**An AI personal safety companion for everyday life.**

Garly is available on **iOS and Android**. It combines optional safety monitoring, trusted-contact alerts and a companion that can remember information you choose to share.

[App Store](https://apps.apple.com/app/id6800198393) · [Google Play](https://play.google.com/store/apps/details?id=pro.garlyapp.app) · [Website](https://garlyapp.pro/)

Product information updated **9 October 2026**. This repository contains public documentation and selected native source snapshots; it is not a complete copy of the current production application.

## Safety features

- **Manual SOS:** start a cancellable alert flow without waiting for the AI or enabling Protection Mode.
- **Protection Mode:** optional monitoring of supported, permitted device signals, including motion and stillness checks.
- **Walk Mode:** an explicitly enabled check-in timer. Missing a check-in can start a cancellable countdown and the configured trusted-contact alert flow.
- **Live Journey:** deliberately share a temporary, updating location link and stop sharing when you choose.
- **Last-Known Location:** enable it while online, choose recipients and a threshold of **10 minutes, 30 minutes, 1 hour or 2 hours**. If the phone stops checking in, the server can send the last saved position through Telegram or email, even while that phone remains offline.
- **Trusted contacts and saved places:** configure the people and places relevant to your safety.

Core safety is free. It does not depend on Pro, evolution level, token ownership or participation in the community programme. Permissions, setup, device restrictions and delivery-channel availability still matter.

Last-Known Location includes the saved position's capture time and available accuracy. A missing connection is not proof of danger, and the saved position is not live tracking. [How the safety flows work](docs/HOW_GARLY_WORKS.md).

## A companion that grows with you

Garly has **seven evolutions**: Germy, Plop, Boing, Peep, Wobble, Orbit and Oddity. Each has its own character and visual form. Eligible daily visits, confirmed ordinary routines, initial trusted-contact/place setup and daily Protection/Walk Mode activations contribute to progression. Ordinary chat and SOS do not award XP.

Evolution and conversation personality are separate. **Balanced** is available in Free; Pro adds **Professional, Friendly, Witty, Motivational, Minimal, Calm and Energetic**. Serious safety situations override the selected tone.

The companion uses authorised memories and app context. It can explain the user's recorded evolution, unlocked forms and next milestone. A high evolution level does not mean Garly knows facts that the person never shared or allowed it to remember.

The October companion update adds foreground follow-ups after selected app events and confirmed routine schedules. These questions do **not** notify contacts or trigger SOS when ignored. They do not establish that an actual route deviation or emergency occurred. [Companion, memory and evolutions](docs/COMPANION.md).

## Garly Pro

Pro is available through the supported in-app store purchase flow. It adds companion personalisation, expanded saved memory, model choices, themes and personal usage insights. The current price and subscription terms appear at checkout. Core protection remains free.

The published contribution policy allocates 30% of qualifying Pro revenue to the ICRC. See the [policy and published records](https://garlyapp.pro/cause.html) for the accounting basis and reporting schedule. This is not a claim of an ICRC partnership or endorsement.

## Optional community features

City Ambassadors is Garly's optional community programme. People can join a city as citizens; eligible users can acquire an available Ambassador title. The title is a role in the programme, not ownership of a real city. Participation, missions and any rewards are separate from personal safety and from Home evolution XP.

$GARLY is a community token on Solana. A wallet is not required for the core safety app. Buying an Ambassador title is not staking, and rewards are not guaranteed. Store Pro, Ambassador Premium and the community vault are distinct features; do not treat Pro subscription revenue as automatic Ambassador income.

Use the [City Ambassadors manual](https://garlyapp.pro/app/ambassadors/user-manual/) for current eligibility, payment, membership and reward rules. Safety Pool availability and other community features must be checked in the product; this repository does not promise automatic emergency fund payouts.

## Privacy and languages

Optional memory, account-scoped data, Privacy Lock, two-factor authentication, and account export/deletion controls are part of Garly's privacy model. Chat may be processed by external AI providers; Garly does not claim that every operation happens on the phone.

The product includes 18 language options: English, Italian, Spanish, French, German, Portuguese, Brazilian Portuguese, Russian, Turkish, Arabic, Hindi, Telugu, Indonesian, Japanese, Korean, Chinese, Swedish and Persian. Persian includes right-to-left layouts.

[Privacy model](docs/PRIVACY_MODEL.md) · [Published privacy policy](https://garlyapp.pro/privacy.html)

## Public source scope

- [`android-client/`](android-client/): a previously reviewed Android native source snapshot.
- [`ios-client/`](ios-client/): a previously reviewed iOS native source snapshot.
- [`docs/`](docs/): product behaviour, privacy boundaries and public release notes.

The hosted web application, backend services, production configuration, credentials, signing keys, private analytics and user data are intentionally excluded. The native snapshots do not yet contain every feature in the current store releases, including the latest Last-Known Location integration. Their presence is not a claim of build-for-build parity with the stores.

The 9 October repository refresh updates documentation; it does not replace these native source snapshots. [Latest public update](docs/UPDATES.md).

## Safety limitations

Garly supports personal safety; it does not replace emergency services or guarantee detection, delivery or rescue. Background behaviour depends on the operating system, permissions, battery management and whether the app remains able to run. Location may be unavailable or outdated. No AI conversation should be treated as confirmation that an alert was delivered.

For immediate danger, contact the emergency services available in your country when it is safe to do so.

## Security and source use

Report vulnerabilities privately, following [SECURITY.md](SECURITY.md), rather than in public issues. Do not test emergency flows against uninformed contacts.

Garly-owned files are source-available for transparency and security review; they are not released under an open-source licence. See [NOTICE.md](NOTICE.md) and [third-party notices](THIRD_PARTY_NOTICES.md).

## Links

- [Website](https://garlyapp.pro/)
- [Web app](https://garlyapp.pro/app/)
- [X](https://x.com/appgarly)
- [Product Hunt](https://www.producthunt.com/products/garly)
