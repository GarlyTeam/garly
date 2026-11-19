# Garly

**An AI personal safety companion for everyday life.**

Garly is available on **iOS and Android**. It combines optional safety monitoring, trusted-contact alerts and a companion that can remember information you choose to share.

[App Store](https://apps.apple.com/app/id6800198393) · [Google Play](https://play.google.com/store/apps/details?id=pro.garlyapp.app) · [Website](https://garlyapp.pro/)

Product information updated **19 November 2026**. This repository contains public documentation and selected native source snapshots; it is not a complete copy of the current production application.

[Whitepaper v1.4 (PDF)](docs/Garly-Whitepaper.pdf) · [Published whitepaper](https://garlyapp.pro/assets/Garly-Whitepaper.pdf) · [Public updates](docs/UPDATES.md)

## Safety features

- **Manual SOS:** start a cancellable alert flow without waiting for the AI or enabling Protection Mode.
- **Protection Mode:** optional monitoring of supported, permitted device signals, including motion and stillness checks.
- **Walk Mode:** an explicitly enabled check-in timer. Missing a check-in can start a cancellable countdown and the configured trusted-contact alert flow.
- **Garly Meet:** prepare a check-in for a meeting, choose trusted contacts and add useful details. If confirmation is missing after the grace period, the selected contacts can receive the configured alert.
- **Live Journey:** deliberately share a temporary, updating location link and stop sharing when you choose.
- **Last-Known Location:** enable it while online, choose recipients and a threshold of **10 minutes, 30 minutes, 1 hour or 2 hours**. If the phone stops checking in, the server can send the last saved position through Telegram or email, even while that phone remains offline.
- **Trusted contacts and saved places:** configure the people and places relevant to your safety.
- **Voice SOS - beta:** explicitly start a listening session using a prepared language model and the selected exact emergency phrase. Recognition runs locally. A recognised command starts a cancellable **15-second countdown** before the real SOS request.

Core safety is free. It does not depend on Pro, evolution level, token ownership or participation in the community programme. Permissions, setup, device restrictions and delivery-channel availability still matter.

Last-Known Location retains only the latest saved point for up to **24 hours**, with capture time and available accuracy. A missing connection is not proof of danger, and the saved position is not live tracking. Voice SOS duration options follow the installed release; a two-hour Last-Known Location threshold is not a claim of two-hour voice listening. [How the safety flows work](docs/HOW_GARLY_WORKS.md).

## A companion that grows with you

Garly has **seven evolutions**: Germy, Plop, Boing, Peep, Wobble, Orbit and Oddity. Each has its own character and visual form. Eligible daily visits, confirmed ordinary routines, initial trusted-contact/place setup and daily Protection/Walk Mode activations contribute to progression. Ordinary chat and SOS do not award XP.

Evolution and conversation personality are separate. **Balanced** is available in Free; Pro adds **Professional, Friendly, Witty, Motivational, Minimal, Calm and Energetic**. Serious safety situations override the selected tone.

The companion uses authorised memories and app context. It can explain the user's recorded evolution, unlocked forms and next milestone. A high evolution level does not mean Garly knows facts that the person never shared or allowed it to remember.

The October companion update adds foreground follow-ups after selected app events and confirmed routine schedules. These questions do **not** notify contacts or trigger SOS when ignored. They do not establish that an actual route deviation or emergency occurred. [Companion, memory and evolutions](docs/COMPANION.md).

## Garly Pro

Pro is available through the supported in-app store purchase flow. It adds companion personalisation, expanded saved memory, model choices, themes and personal usage insights. The current price and subscription terms appear at checkout. Core protection remains free.

The published contribution policy allocates 30% of qualifying Pro revenue to the ICRC. See the [policy and published records](https://garlyapp.pro/cause.html) for the accounting basis and reporting schedule. This is not a claim of an ICRC partnership or endorsement.

## Optional community features

City Ambassadors is Garly's optional website community programme. People can join a city as citizens **without buying tokens or entering a payout wallet**; the joining rules still apply. Eligible users can acquire an available Ambassador title. It is a role in the programme, not ownership of a real city.

City Studio brings together a visual city, Piazza initiatives, creative tools, the Kit, mission progress and Whistle. The configured new mission weights are 12 XP for a qualified retained invitation, up to 8 XP for the social mission, 6 XP for qualified artwork reuse and 4 XP for qualified Piazza engagement. Activation, cadence, daily-cap and anti-reuse rules apply; these are not fixed token amounts.

Instagram/TikTok content checks and the X declaration route have different evidence standards. An X declaration is not automatically verified publication, authorship or a native partnership label. [Community, missions and rewards](docs/COMMUNITY.md).

$GARLY is a community token on Solana. A wallet is not required for the core safety app. Buying an Ambassador title is not staking, and rewards are not guaranteed. Store Pro, Ambassador Premium and the community vault are distinct features; do not treat Pro subscription revenue as automatic Ambassador income.

The official mint and dated chain evidence are in [Token facts](docs/TOKEN.md). Allocation, team vesting, LP locks and new listings are not presented as confirmed without supporting evidence.

The [programme manual](https://garlyapp.pro/app/ambassadors/user-manual/) describes the legacy framework; older wallet-required joining text and earlier mission values may predate the current Studio rules. Check the active mission and this edition's documentation. Safety Pool remains a pilot, not a promise of automatic emergency fund payouts.

## Whistle inside City Studio

Whistle is an external AI workspace embedded in the Garly website: **own an AI that has on-chain context**. Whistle manages its own sign-in, optional in-app wallet and credit balance.

The agreed Ambassador ticket grants **2,000 Whistle credits** once per entitlement. An unused card has a **30-day redemption window**, applied by Whistle; this does not establish an expiry for credits already redeemed. The same card cannot add credits twice. Eligibility and issuance availability apply, and a pending redemption is not a confirmed balance increase. [Ticket flow and boundaries](docs/COMMUNITY.md#whistle-tickets).

## Privacy and languages

Optional memory, account-scoped data, Privacy Lock, two-factor authentication, and account export/deletion controls are part of Garly's privacy model. Chat may be processed by external AI providers; Garly does not claim that every operation happens on the phone.

Voice SOS audio and calibration stay temporarily on the device for the session; the feature does not upload recordings or transcripts. Optional analytics has its own consent and is disabled for known minors. Necessary service providers and configured alert recipients can receive data for their respective functions. **Garly does not sell personal information.**

The product includes 18 language options: English, Italian, Spanish, French, German, Portuguese, Brazilian Portuguese, Russian, Turkish, Arabic, Hindi, Telugu, Indonesian, Japanese, Korean, Chinese, Swedish and Persian. Persian includes right-to-left layouts.

[Privacy model](docs/PRIVACY_MODEL.md) · [Published privacy policy](https://garlyapp.pro/privacy.html)

## Public source scope

- [`android-client/`](android-client/): a previously reviewed Android native source snapshot.
- [`ios-client/`](ios-client/): a previously reviewed iOS native source snapshot.
- [`docs/`](docs/): product behaviour, privacy boundaries and public release notes.

The hosted web application, backend services, production configuration, credentials, signing keys, private analytics and user data are intentionally excluded. The native snapshots do not yet contain every feature described in the current documentation or store releases. Their presence is not a claim of build-for-build parity with the stores.

The 19 November repository refresh updates documentation and includes the published whitepaper; it does not replace these native source snapshots. [Latest public update](docs/UPDATES.md).

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
- [Telegram community](https://t.me/+_AzC4HfEiiBjOWE0)
- [Whitepaper v1.4](docs/Garly-Whitepaper.pdf)
- [Product Hunt](https://www.producthunt.com/products/garly)
