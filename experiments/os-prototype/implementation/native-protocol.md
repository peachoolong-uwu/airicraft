# Native driver protocol, version 1

This is the first implementation slice of the [handoff](../design/implementation-handoff.md), available only in Codex driver mode. The public transport is `airicraft agent tools call`. The initial operation is a bounded transfer in an already-open container on an integrated server. Opening the container and sharing a visit across transfers are later context-provider work. Each current transfer closes its owned window.

## Request sequence

1. `os_observe {}` returns a versioned frame: native session ID, world/load epoch, capture sequence/ID, native monotonic time and age, relevant revision, client tick and available server tick. `facts.scope` names the currently open container; `facts.coverage` states what is known. Unopened containers are unknown. Observation never opens a GUI.
2. `os_lease {action:"acquire", hostId, epoch}` acquires a free player. Send `{action:"heartbeat", lease}` every second. Five wall seconds without renewal fences productive effects, including ones already queued on the server. `{action:"release", lease}` requests revocation and cleanup.
3. `os_submit {schemaVersion:1, id, captureId, operation:"transfer_container", arguments, payloadHash}` admits one operation. `id` is `{epoch, generation, sequence}`; sequence increases within the lease generation. Arguments are exactly `{windowId, syncId, direction, itemId, quantity, allowance:{sourceItems, destinationItems}}`. Direction is `withdraw` or `deposit`; quantity and allowances are integers from 1 to 64, and both allowances must cover the quantity.
4. `os_inspect {id}` queries the same request after a lost response. Resending that identity and payload is idempotent while retained. Acceptance is not completion. Preserve the request in the broker journal before submission.
5. `os_cancel {lease, id}` stops that request. Inspect until its receipt proves release, or retain unresolved ownership. Verified partial transfers remain in the world.

The SHA-256 fingerprint covers the UTF-8 JSON object `{arguments, captureId, operation}`, with recursively sorted object keys, preserved array order, compact Gson JSON and HTML escaping disabled. Use the native `fingerprint` implementation as the cross-language reference; do not hash the transport envelope. The broker must verify its encoder against native test vectors before use. Native argument envelopes are bounded to 16 KiB and nesting depth 16.

Receipts retain the request's schema/operation/capture/hash basis. Effects report verified transferred and remaining quantities and server-confirmation status. Release evidence requires the server handler and client GUI to be closed, the cursor empty, and held inputs, navigation, camera, item use, and fishing controls free. The final state and first stop cause cannot be changed by a late cancellation. A confirmation deadline of 30 wall seconds stops productive work but never proves release; late confirmation can still allow cleanup.

The runtime retains all active work and the latest 256 settled receipts, plus a submission high-water mark. A new sequence advances that mark before operation preflight; an acknowledged rejection requires a new sequence for corrected work. A lost rejection can therefore remain `outcome_unknown`. The broker serializes ambiguous submissions. Evicted or unknown identities must never be replayed as new work on the assumption that they failed.

Only item IDs advertised in `facts.supportedItems` are admitted by this first adapter. They cover the reference crops, sheep products, birch, fishing outputs, supplies and basic tools. Bundles and other unsupported item types are rejected before admission: their slot-click hooks must not be mistaken for ordinary stack movement.

## Events and ownership

`os_inspect {sessionId, sinceSeqNo, limit}` reads ordered transitions after the supplied cursor. Limit defaults to 16 and is capped at 32. The buffer retains 512 events. The reply gives oldest/latest sequence, next cursor and an explicit `gap`; a gap invalidates complete-trace claims even when the affected receipt can still be queried. Unchanged reads and heartbeats do not fill the buffer.

Every response includes current authority. Native clock values belong to the reported session clock domain; do not subtract a broker clock from native nanoseconds. A broker uses native-reported age plus its own request round-trip time. The frame's server tick is a sampled clock, not proof of a particular effect's server tick.

Legacy gameplay dispatch and direct camera control are fenced while the lease or cleanup owns the player. Runtime reload is rejected during that ownership. Operator cancellation, reflex takeover, world leave and shutdown revoke queued effects immediately. The first slice does not automatically respawn once OS control has been activated. Read-only inspection remains available.

The affected source/destination slots and cursor are guarded at every step; unrelated slot changes do not invalidate cleanup. The active context is retained across lifecycle changes until release is verified. A detached owned server handler with an empty cursor can supply release evidence; an unreachable old server cannot. An external window closure sets `accountingComplete:false`: `transferred` then means only the verified prefix, and `remaining` is unverified work, not permission to retry. Refresh and reconcile stock before further consumption. Reflex policy configuration remains available during a hold.

## Qualification status

Deterministic tests exercise the public native and JSON boundaries with a fake Minecraft adapter. The real adapter uses vanilla slot-click behavior, validates the current server handler and exact expected stacks at each effect, and verifies client/server reconciliation. Compilation and simulated evidence do not establish live qualification; see [STATUS.md](STATUS.md) for the current gate.
