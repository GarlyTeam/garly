# Garly

**A privacy-first AI safety companion for everyday life.**

Garly is an AI-powered personal safety project designed to support people during everyday activities such as walking alone, commuting, travelling and outdoor exercise.

## Current development

Garly is currently in active development and Android testing. We are testing and improving:

- Protection Mode with motion and impact detection
- Background monitoring on Android
- Trusted-contact SOS alerts
- Adjustable sensor sensitivity
- Jogging and bag movement profiles
- Stillness detection and configurable timers
- Privacy Lock for sensitive areas
- Optional private memory
- Multilingual support
- Community referrals and rewards

Garly is being developed through real-device testing and community feedback. Detection systems are experimental and continue to be calibrated to reduce false alarms and missed events.

## How Garly works

The public documentation explains Garly's user-facing safety and privacy flows without disclosing proprietary detection thresholds, calibration methods or production infrastructure:

- [How Garly works](docs/HOW_GARLY_WORKS.md)
- [Privacy model](docs/PRIVACY_MODEL.md)

## Privacy and security

Garly is being designed around user consent, account-level data isolation and restricted access to sensitive information.

This public repository is being prepared gradually. Only reviewed documentation and source components will be published. Production credentials, signing keys, private analytics and infrastructure configuration are never included.

Security issues should not be reported through public issues. Please use GitHub's private vulnerability reporting feature.

## Important safety notice

Garly is not a replacement for emergency services, professional medical assistance or local authorities. Experimental safety detection cannot guarantee that every dangerous event will be identified.

If you are in immediate danger, contact the emergency services available in your country.

## Links

- Website: https://garlyapp.pro/
- Live demo: https://garlyapp.pro/app/
- Product Hunt: https://www.producthunt.com/products/garly
- X: https://x.com/appgarly

## Project status

Public beta and Android testing. Additional reviewed source code and technical documentation will be added progressively.

## Public source scope

The reviewed native Android development client is available in [`android-client/`](android-client/). It contains the WebView host, native Google sign-in bridge, foreground protection service, sensor bridge and Android resources used by the current development branch.

The web application, backend services, production deployment configuration, private analytics and signing material are intentionally not included in this public repository.

## Source use

Garly-owned files are source-available for transparency and security review, but they are not currently released under an open-source license. See [`NOTICE.md`](NOTICE.md). Files carrying their own third-party license header remain governed by that license; see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
