# Generator service loop

`GeneratorLoop` connects installed, pinned generator definitions to the observation/wait registry and owned child execution. It uses the real `InstallationHost` and supervised `RunnerPool`; no guest code runs in the host JavaScript realm. The earlier prototype entrypoint remains separate.

The host installs a validated revision, calls `loop.attach(rootId)`, and pulses `loop.tick()` independently of native heartbeat/reconciliation and passive observation acquisition. `InstallationHost.describe(id, alias?)` exposes the pinned kind/mode/digest for dispatch, without returning source or granting access to another installation. Child aliases resolve against that installation's immutable closure.

## Effects and replies

| Effect | Reply and lifetime |
| --- | --- |
| `observe({scopes, offset?})` | One copied observation page from `ConditionWaits`, including capture provenance and `nextOffset`. This is a passive cache read; missing facts do not trigger navigation or open a chest. |
| `wait(condition, options)` | Suspends only this generator until the owned wait returns `met`, `deadline`, `gap` or `epoch_changed`. No player/resource claim is acquired. |
| `spawn(alias, input, options)` | Creates a typed, attenuated child in the same root process and returns its owned handle after VM initialization. That child begins independently of when its parent joins it. |
| `join(handle)` | Waits for the broker's settled child outcome, consumes it once, and returns its distinct success/failure/cancellation value. Physical cleanup can keep the outcome pending. |

Replies are plain copied data passed to the generator's next step. Declared validation, capability and capacity rejections return `{status: "rejected", reason}`. Unknown host faults fail the invocation through the runner/broker's normal sibling policy. Arbitrary error stacks are not sent to guests. A response exceeding the enclosing runner wire becomes an explicit `effect_response_limit` rejection; a joined child is still consumed once, and its full outcome remains in the broker's terminal/join trace rather than being silently truncated into a successful value.

`target`, `demand`, `work` and `worker` currently return `service_unavailable` with the service name. The ledger/coordinator exist, but their guest services and the work scheduler are separate remaining integrations. The loop accepts generator definitions only. An offers-only child is rejected before allocation; recurring offers will be connected with the scheduler. There is no generic callback/plugin dispatcher that could bypass these boundaries.

## Scheduling and ownership

At most 32 generators and 32 asynchronous computation/initialization jobs are retained. Each generator has one pending effect. Completed jobs cannot recursively start another job; the next host pulse schedules further work. The existing pool applies shared root CPU/message limits and round-robin process dispatch. A slow child initialization or a growth/join wait does not hold up unrelated roots. This loop is the code execution mechanism, not the player-priority scheduler.

Each asynchronous result is gated against the exact registered invocation record and its current broker phase. Cancellation or replacement removes that registration; a late result cannot enroll a child or resume old code. Cancelled jobs still count against the bounded job set until their pool requests settle. No copied response, including a ready condition or child judgment, is current physical admission evidence: the activity coordinator must revalidate before actions.

A body return stops its VM but leaves children running and preserves mandatory joins. The host calls `InstallationHost.advance()` separately to retire settled roots or install queued replacements, then attaches any replacement root explicitly. `loop.cancel(id)` also works on a returned parent still awaiting children. `loop.close()` stops the registered roots and removes wait subscriptions; it never acknowledges native release. The coordinator must still drain or report unresolved ownership before the journal/trace close. Already-retired processes with pending metadata reconciliation do not block this stop path.

Trace records link attached invocations, effect sequence/kind/digest and response digests. These are bounded dispatch links; complete observation/effect artifact capture and evaluator accounting remain pending. The dispatcher does not restart failed duties or replay execution stacks.

## Verification scope

The integration suite creates actual local root processes and QuickJS VMs from fixture revisions. It exercises independent waits, concurrent children, separate joined values, parent return, delayed child initialization, cancellation, typed rejections, default sibling failure, explicit collect-all, bounded replies, and retirement retry. Physical release in these tests is injected broker evidence; no Minecraft action, live efficiency comparison or model inference is claimed. Revision-validation mechanics have their own suite; the loop suite's candidate status is trusted fixture setup.
