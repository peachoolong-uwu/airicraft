# Behavior service loop

`BehaviorLoop` connects installed, pinned generator and recurring-offer definitions to the observation/wait registry, work scheduler and owned child execution. It uses the real `InstallationHost` and supervised `RunnerPool`; no guest code runs in the host JavaScript realm. The earlier prototype entrypoint remains separate. This replaces the experimental `GeneratorLoop` export.

The host installs a validated revision, calls `loop.attach(rootId)`, and pulses `loop.tick()` independently of native heartbeat/reconciliation and passive observation acquisition. `InstallationHost.describe(id, alias?)` exposes the pinned kind/mode/digest for dispatch, without returning source or granting access to another installation. Child aliases resolve against that installation's immutable closure.

## Effects and replies

| Effect | Reply and lifetime |
| --- | --- |
| `observe({scopes, offset?})` | One copied observation page from `ConditionWaits`, including capture provenance and `nextOffset`. This is a passive cache read; missing facts do not trigger navigation or open a chest. |
| `wait(condition, options)` | Suspends only this generator until the owned wait returns `met`, `deadline`, `gap` or `epoch_changed`. No player/resource claim is acquired. |
| `spawn(alias, input, options)` | Creates a typed, attenuated child in the same root process and returns its owned handle after VM initialization. That child begins independently of when its parent joins it. |
| `join(handle)` | Waits for the broker's settled child outcome, consumes it once, and returns its distinct success/failure/cancellation value. Physical cleanup can keep the outcome pending. |
| `target(alias, quantity)` | Updates this installed consumer's stock floor through the configured resource service. |
| `demand(alias, quantity, methods)` | Suspends for an owned finite delivery; shared verified output and cleanup determine completion. |

Replies are plain copied data passed to the generator's next step. Declared validation, capability and capacity rejections return `{status: "rejected", reason}`. Unknown host faults fail the invocation through the runner/broker's normal sibling policy. Arbitrary error stacks are not sent to guests. A response exceeding the enclosing runner wire becomes an explicit `effect_response_limit` rejection; a joined child is still consumed once, and its full outcome remains in the broker's terminal/join trace rather than being silently truncated into a successful value.

The [resource service](resource-api.md) implements `target` and `demand` when supplied to the loop. The optional [work service](work-api.md) registers finite `work` requests and waits for coordinator-verified physical completion. The optional [worker service](worker-api.md) owns inference subscriptions and sandboxed fallbacks. Absent configuration returns `service_unavailable`. Retained-context/fishing dispatch and broader native eligibility production remain separate integrations. Offer definitions require both `work` and `observations`; an offer child rejects before allocation if either is absent. There is no generic callback/plugin dispatcher that could bypass these boundaries.

## Recurring offers

Pass the [native observation feed](native-feed-api.md) as `observations`. An installed offers-mode root or owned child evaluates `offers(os, input, view)` at most once per fresh capture/generation. Its copied, grant-filtered view contains only feed-configured scopes. The returned work array atomically replaces that invocation's declarations. A delayed result is discarded if its capture or physical generation changed. The next host pulse can evaluate a current basis; there is never a second concurrent evaluation of that invocation.

An invalid batch, evaluation fault or oversized view fails only its invocation through the existing sibling policy. No partial batch is registered. An empty array keeps a duty installed and passive. Offers do not yield generator effects or receive finite work results; physical outcomes remain in native evidence, the work trace and the broker's last activity. See the [offer contract](offers-api.md).

## Scheduling and ownership

At most 32 invocations and 32 asynchronous computation/initialization jobs are retained. Each generator has one pending effect; each offer duty has at most one evaluation in flight. Completed jobs cannot recursively start another job; the next host pulse schedules further work. The existing pool applies shared root CPU/message limits and round-robin process dispatch. A slow child initialization, offer evaluation or growth/join wait does not hold up unrelated roots. This loop is the code execution mechanism, not the player-priority scheduler.

Each asynchronous result is gated against the exact registered invocation record and its current broker phase. Cancellation or replacement removes that registration; a late result cannot enroll a child or resume old code. Cancelled jobs still count against the bounded job set until their pool requests settle. No copied response, including a ready condition or child judgment, is current physical admission evidence: the activity coordinator must revalidate before actions.

A body return stops its VM but leaves children running and preserves mandatory joins. The host calls `InstallationHost.advance()` separately to retire settled roots or install queued replacements, then attaches any replacement root explicitly. `loop.cancel(id)` also works on a returned parent still awaiting children. `loop.close()` stops the registered roots and removes wait subscriptions; it never acknowledges native release. The coordinator must still drain or report unresolved ownership before the journal/trace close. Already-retired processes with pending metadata reconciliation do not block this stop path.

Trace records link attached invocations, effect sequence/kind/digest and response digests. These are bounded dispatch links; complete observation/effect artifact capture and evaluator accounting remain pending. The dispatcher does not restart failed duties or replay execution stacks.

## Verification scope

The integration suite creates actual local root processes and QuickJS VMs from fixture revisions. It exercises independent waits, concurrent children, separate joined values, parent return, delayed child initialization, cancellation, typed rejections, default sibling failure, explicit collect-all, bounded replies, and retirement retry. Physical release in these tests is injected broker evidence; no Minecraft action, live efficiency comparison or model inference is claimed. Revision-validation mechanics have their own suite; the loop suite's candidate status is trusted fixture setup.
