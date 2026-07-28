# ADR-0001: Safe named-relay preflight without chunk loading

## Status

Accepted

## Date

2026-07-27

## Context

Pearl Relay originally stored a fake player's spawn position and look direction, then created the fake player before it knew whether the relay could safely activate. Those coordinates describe the activation action, but they do not identify or control an Ender Pearl. A stale target, unloaded chunk, missing pearl, or failed execution could therefore appear to succeed or leave a fake player behind.

Minecraft 26.2 Ender Pearls keep their relevant chunk active. For this project, an unloaded target chunk therefore means that no usable relay pearl is present. The owner explicitly chose rejection instead of automatic chunk loading.

## Decision

- Preserve `spawn` and `lookAt` as activation data only.
- On save, raycast from the fake player's eye position and persist the hit block position plus block registry ID.
- On named fire, use non-loading entity-ticking checks and reject if any relevant chunk is unavailable. A FULL neighbor cached around another ticket is not considered ready.
- Require at least one Ender Pearl owned by the invoking player in the target block's exact chunk.
- Treat pearl count as a boolean readiness condition; never select, move, release, or otherwise control a pearl.
- Fingerprint block type but not mutable block-state properties.
- Complete all preflight checks before fake-player creation.
- Run accepted work through a bounded lifecycle that dispatches one use action and always attempts idempotent cleanup.

## Alternatives considered

### Use saved spawn/look coordinates to locate the pearl

Rejected because those coordinates describe the activator and cannot reliably identify which pearl belongs to the device.

### Automatically load an unavailable chunk

Rejected because it hides a broken or empty device state, creates server-side load, and contradicts the project's Minecraft 26.2 pearl-loading premise.

### Save the full target block state

Rejected because normal activator changes such as open/closed or powered/unpowered would invalidate otherwise healthy devices.

### Select or directly activate a discovered pearl

Rejected because it changes the mod from a safe trigger wrapper into device-specific pearl control and could choose the wrong pearl when several are present.

## Consequences

- Invalid named fires have no fake-player or chunk-loading side effect.
- Legacy relays remain visible but require a one-time resave because their target type cannot be reconstructed safely.
- Devices spanning chunk borders must keep the invoking player's pearl in the target block's chunk.
- Server logs can prove command acceptance, one dispatched interaction, cleanup, and terminal status, but real-player testing is still required to prove an arbitrary stasis device teleports correctly.
