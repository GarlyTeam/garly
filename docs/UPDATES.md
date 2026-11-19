# Public product updates

## 19 November 2026

This documentation refresh aligns the public overview with the [whitepaper v1.4](Garly-Whitepaper.pdf), published on the [Garly website](https://garlyapp.pro/assets/Garly-Whitepaper.pdf) on 19 November 2026.

- Added Garly Meet's optional meeting check-ins and configured alert flow.
- Documented Voice SOS beta, exact command phrases, deliberate session start/stop, the cancellable 15-second countdown and local voice processing. No 120-minute listening release or date is announced here.
- Clarified Last-Known Location activation, its 24-hour latest-point retention, selected recipients and the distinction between missing updates and an emergency.
- Updated the privacy model for microphone uses, account/server storage, optional analytics, retention boundaries and external Whistle use.
- Added City Studio spaces, configured mission weights, the distinction between social content checks and X declarations, and variable $GARLY distributions.
- Corrected Citizen joining: no token purchase or payout-wallet entry is needed to join; joining rules still apply and eligible token payments still need a payout address.
- Added the 2,000-credit Whistle ticket flow, single redemption and the agreed 30-day unused-card redemption window applied by Whistle.
- Added a dated public-chain token snapshot, distinguishing current total supply from the historical manual figure and from unverified allocation, vesting, LP-lock and listing claims.

The PDF is the same file as the website edition, SHA-256:

```text
ebbbdc96cf2d86d8a9d6ccae008cbc4627b96dee725df9abce35be61c14379ac
```

This refresh documents confirmed behaviour and explicitly labels undecided or unverified matters. It does not activate campaigns, change balances or rewards, issue gift cards, update native store builds, or replace the selected native source snapshots.

## 9 October 2026

- Garly is available on both iOS and Android.
- Last-Known Location is live: explicit activation, selected contacts, configurable loss-of-check-in threshold, last saved position and server-side Telegram/email delivery.
- Companion improvements include more natural personality guidance, occasional context-appropriate emoji, more relevant memory recall and additional app-state context.
- Foreground conversational follow-ups are separate from emergency alerts. Ignoring one does not notify contacts.
- The companion can explain the seven evolutions and the authenticated user's recorded progress, without treating evolution as proof of personal knowledge.
- Internal startup screens now use the updated Garly icon.

The companion release passed 20 local regression checks and deployment health/file-integrity checks. That does not establish exhaustive real-device, background-delivery or language-model behaviour testing.

## Other recent product updates

- Seven forms: Germy, Plop, Boing, Peep, Wobble, Orbit and Oddity, with progression carried over from the previous system.
- Pro companion personalisation and personal usage insights.
- Updated settings and Live Journey recipient presentation.
- Persian localisation and right-to-left layouts, bringing the language selector to 18 options.

## What this repository refresh changes

This refresh updates public documentation only. Hosted web/API changes remain private, consistent with the established repository scope. The checked-in native clients are retained as earlier reviewed snapshots; they have not been replaced with the latest store source in this update.
