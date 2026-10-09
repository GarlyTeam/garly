# Companion, memory and evolutions

Product behaviour as of 9 October 2026. The hosted implementation is not included in this public repository.

## What the companion knows

When answering, Garly can receive authorised profile information, selected saved memories, recent conversation context and a limited snapshot of app state. Relevant context includes configured contacts and places, Protection/Walk Mode/Live Journey status, stillness checks, an active SOS countdown and selected recent app events.

This is not continuous awareness of every device action. Garly does not read other apps, infer an exact live position without supplied location context, or treat an alert countdown as proof of delivery.

## Learning routines

An explicitly shared, durable fact can become a proposed memory. Routines and sensitive information require consent before persistent storage. Users can review or remove saved information and disable optional memory.

A temporary change of plans is not automatically a new permanent routine. If the user explains that plans changed and they are fine, Garly should accept the answer rather than repeat the same check. Actual conversation quality remains dependent on the model and context; these are intended behaviours, not guarantees of every generated sentence.

## Foreground follow-ups

With the app visible, eligible events can prompt a brief question, for example after an SOS cancellation, the end of Walk Mode or Live Journey, or after the usual end of a confirmed routine.

Schedule follow-ups use the phone's timezone and known routine days. A usual scheduled end passing does not establish that the person is late, followed that routine today, or is in danger. The companion asks rather than asserting that it observed a deviation.

The policy avoids repeated prompts about the same event, recent active conversation, typing, locked private screens and active safety checks. Ignoring a conversational follow-up **does not contact anyone or start an SOS**. Existing enabled stillness, Walk Mode and Last-Known Location flows have their own independent rules.

## Seven evolutions

| Form | Character | Starting XP |
| --- | --- | ---: |
| Garly Germy | Gentle, shy and curious | 0 |
| Garly Plop | Affectionate, easygoing and a little clumsy | 100 |
| Garly Boing | Energetic, playful and mischievous | 250 |
| Garly Peep | Sensitive, thoughtful and tender | 450 |
| Garly Wobble | Expressive, enthusiastic and adorably awkward | 700 |
| Garly Orbit | Three companions with adventurous, sleepy and happy moods | 950 |
| Garly Oddity | Imaginative, observant and wonderfully peculiar | 1,250 |

The companion reads the authenticated account's recorded progress to explain the current form, unlocked forms and remaining XP. It does not award points just because the person asks. Oddity is the seventh and final current form.

Each eligible activity earns 5 XP, with a shared daily maximum of 15 XP. Daily visits, Protection activation and Walk Mode activation each count at most once per day. At most one new, explicitly confirmed ordinary routine counts per day. The first five distinct trusted contacts and first five distinct saved places can each earn a setup bonus, once per item. Repeated setup, ordinary chat, SOS and dangerous or distressing situations do not earn progression rewards.

Existing progression is mapped into the seven-form system. The visual journey is not a measure of how much private information Garly knows and does not grant stronger safety features.

## Conversation personalities

| Personality | Intended tone | Availability |
| --- | --- | --- |
| Balanced | Warm, relaxed and clear | Free and Pro |
| Professional | Brief and precise | Pro |
| Friendly | Warm and conversational | Pro |
| Witty | Occasional dry humour | Pro |
| Motivational | Practical encouragement | Pro |
| Minimal | As few words as needed | Pro |
| Calm | Gentle and unhurried | Pro |
| Energetic | Lively and concise | Pro |

Emoji can appear occasionally when the personality and conversation suit them. Professional and Minimal avoid them. Safety situations require a calm, direct tone without jokes or decorative emoji, regardless of the selected personality.

Character evolution and conversation personality are separate. A form's description does not silently select a different conversation personality. If a Pro-only choice is unavailable on the current plan, the effective voice falls back to Balanced.
