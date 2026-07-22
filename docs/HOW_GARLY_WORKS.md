# How Garly works

This document provides a high-level description of Garly's current public beta and Android testing experience. It is intended to help users, testers and reviewers understand the product without publishing proprietary detection thresholds, calibration methods or production infrastructure.

## Two ways to ask for help

Garly provides a direct manual path and an assisted protection path. The manual SOS does not depend on AI, learned routines or sensor timing.

```mermaid
flowchart LR
    U["User"] --> M["Manual SOS"]
    U --> P["Protection Mode"]

    M --> C["Short cancellable countdown"]
    P --> D["Movement, impact and stillness monitoring"]
    D --> Q["Safety check when appropriate"]
    Q -->|"User confirms they are safe"| R["Protection continues"]
    Q -->|"No confirmation"| C
    D -->|"Potential urgent event"| C

    C -->|"Cancelled"| R
    C -->|"Completed"| A["Emergency alert"]
    A --> T["Every trusted contact added by the user"]
    A --> L["Available location when permission and signal allow"]
```

### Manual SOS

The red SOS button is available for situations where the user wants immediate control. It can be used without enabling Protection Mode. After a short cancellable countdown, Garly attempts to alert every trusted contact the user has added and includes the available location when possible.

### Protection Mode

Protection Mode is an optional monitoring session. When enabled, Garly uses the device signals that are available and permitted to look for meaningful changes associated with movement, impacts and prolonged stillness. A visible Android foreground service supports monitoring while the testing app is in the background.

Automatic detection is experimental. Garly includes confirmation and cancellation steps where appropriate to reduce unnecessary alerts, but no detection system can guarantee that every emergency will be identified or that every false alarm will be prevented.

## Activity and carrying profiles

Different activities can produce very different motion patterns. Garly therefore lets the user select a profile rather than treating every movement in the same way.

| Profile | User-facing purpose |
| --- | --- |
| Everyday | General walking, commuting and normal daily movement |
| Jogging | Accounts for repeated running movement while still watching for unusual events |
| Bag | Supports testing when the phone is carried inside a bag rather than in the hand |
| Stillness check | Lets the user choose when prolonged lack of meaningful movement should trigger a safety confirmation |

Bag and jogging calibration are designed to adapt the experience to the user's device placement and real movement. The underlying signal combinations, thresholds and calibration rules are intentionally not published.

## Stillness safety check

While Protection Mode is active, the user can choose how long Garly should wait without meaningful movement or GPS displacement before asking whether they are safe.

```mermaid
sequenceDiagram
    participant User
    participant Garly
    participant Contacts as Trusted contacts

    User->>Garly: Enables Protection Mode and chooses a stillness delay
    Garly->>Garly: Monitors permitted movement and location signals
    Garly->>User: Are you okay?
    alt User confirms
        User->>Garly: I am okay / I am waiting here
        Garly->>Garly: Clears or pauses the check
    else No response
        Garly->>User: Starts the cancellable SOS countdown
        Garly->>Contacts: Attempts to alert every configured trusted contact
    end
```

Vehicle movement and GPS displacement can help reset the stillness timer when those signals are available. This is especially relevant when the phone is in a bag or the user is travelling.

## Trusted contacts

Trusted contacts are selected by the user. When an emergency alert is completed, Garly attempts to notify every trusted contact added to the account, not only the first contact. Delivery and location accuracy can depend on connectivity, device permissions and third-party messaging availability.

## AI companion and private memory

Garly's companion can support conversations, optional memories and routines chosen by the user. These features are separate from the manual SOS path: the user never needs to wait for the AI to decide whether the red SOS button can be used.

Private memory is optional and includes user controls to review or delete saved information. More detail is available in the [privacy model](PRIVACY_MODEL.md).

## Current limitations

- Garly is in public beta and Android testing.
- Automatic detection remains experimental and requires real-device testing.
- Background behavior depends on Android permissions and device power-management settings.
- Location and message delivery depend on permission, signal, connectivity and external services.
- Planned short event recording is not active in the current prototype and will require explicit user choice before release.
- Garly is not a replacement for emergency services, medical assistance or local authorities.

## Information intentionally not published

For user safety, project security and protection of Garly's original work, this repository does not document sensor-fusion formulas, numeric thresholds, calibration algorithms, anti-abuse controls, private API endpoints, production topology, credentials or signing material.
