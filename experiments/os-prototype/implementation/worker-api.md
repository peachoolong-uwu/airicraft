# Callable workers

`WorkerService` implements `yield os.worker(alias, input)` in the supervised generator loop. The alias resolves through the caller's immutable library closure to a worker definition and its pinned generator fallback. Worker and fallback capabilities must be empty. Each subscriber owns a broker child; fallback evaluation owns a further child VM under the same root's CPU, memory and effect limits. A fallback must return in one resume. Any yield is rejected without invoking an effect dispatcher.

Construct the service with `installations`, `invocations`, `runners`, `observations`, optional `transport`, `trace` and a monotonic `now` clock, then pass it as `workers` to `BehaviorLoop`. The default `HttpWorkers()` has no profiles and sends no network requests. A missing worker service returns `service_unavailable`; a configured service without the requested profile runs the declared fallback. Shut down the behavior loop before closing the worker service and installation host.

The host API is `request(owner, sequence, alias, input)`, `take(owner, requestId)`, `poll()`, `state()` and `close()`. A request sequence is idempotent for the same unconsumed input and revision. A caller can retain only one result; consumed sequences cannot be reused. Cancellation follows invocation ownership. Waiting for inference acquires no player, resource reservation or access context. Unexpected background or trace faults stop the service and fail its callers through ordinary runner/broker supervision. Fallible dispatch setup precedes provider-slot and budget reservation; an unsent call cannot orphan a slot. `state().fault` retains the bounded diagnostic cause.

## Outcomes and evidence

Successful outcomes contain `status: success`, `value`, `source: provider | cache`, `computationId`, `model` and `evidence`. Fallback outcomes contain `status: fallback`, a typed `reason`, `value` and evidence. A failed fallback returns `status: unavailable`, `reason: worker_fallback_failed` and the original cause. Changed evidence returns `status: stale`, `reason: worker_evidence_changed`, without a usable value. A caller must inspect status before acting on a result.

Evidence records the worker revision, input digest, request identity, profile, model when known, world epoch, material signature and original capture IDs. `interpretation: true` is explicit. This metadata is provenance, not native authority. Model text never enters a tool dispatcher. Physical work still passes the existing coordinator's fresh epoch, grants, target, resource and native-fence checks.

`NativeObservationFeed.workerEvidence(owner)` derives a conservative material signature from all of the caller's granted projections. It includes facts and coverage, excludes inaccessible scopes, and ignores capture sequence/time changes. There is no worker observation tool. Stale native frames cannot supply a basis. A change in any granted projection invalidates the interpretation, even if the author considered that projection unrelated. Finer relevance declarations can be introduced with the mixed-duty adapters; they must not silently weaken freshness checks. The service checks the basis before fallback, during waiting, at provider completion and again when consuming a finished result.

Identical revision, semantic input, profile identity, epoch and material basis share a pending computation. Subscriber cancellation is independent. The final subscriber leaving requests transport cancellation. Late completion is traced but cannot resume that subscriber or populate the cache. Only successful provider results enter the bounded per-run cache; fallback results do not. Revision, input, profile or basis changes cannot reuse an entry.

## Fixed experiment policy

The versioned defaults are in `worker-policy.mjs`:

| Limit | Default |
| --- | --- |
| Outstanding per invocation | 1 |
| Active provider calls | 2 |
| Queued computations | 16, also subject to broker invocation capacity |
| Calls per run | 12 within one 30-minute lifetime; no renewal |
| Queue wait | 5 monotonic wall seconds |
| Inference deadline | 20 monotonic wall seconds |
| Input / output | 16 KiB / 4 KiB, also subject to enclosing VM message bounds |
| Provider response body | 8 KiB, enforced while reading |
| Provider output token ceiling | 512 per call |
| Successful cache entries | 32 |
| Failed-fingerprint reconsideration | material change or 1,200 eligible ticks |

Reserve 512 output tokens and one call before dispatch. A confirmed, internally consistent usage report can reduce the output-token charge; missing, invalid or ambiguous usage retains the full charge. Cancellation and timeout never refund on a late reply. A transport that ignores abort continues occupying its active slot until its promise settles. There are no automatic retries, including after ambiguous submission. The fixed call ceiling bounds aggregate reserved output at 6,144 tokens. These are not hard input-token or currency ceilings: the generic compatible HTTP protocol does not provide enforceable total-cost accounting. A deployment requiring such a ceiling must use a provider/account constraint before enabling its profile.

A failed computation is fingerprinted by its complete semantic key. Repeated identical requests use the fallback with `worker_reconsider_later` until material changes or a continuous known progress clock advances 1,200 ticks from failure. Cancellation retains the computation's last verified material/progress basis before detaching its last subscriber; it does not replace a known counter with unknown just because the owner has stopped.

Workers deciding about stuck physical work additionally declare an immutable optional rule, for example `reconsideration: { fingerprint: ["failure", "fingerprint"] }`. The one-to-eight-field path must resolve to a nonempty string of at most 256 characters. This guard uses revision/profile/epoch, the declared fingerprint and verified material signature, independent of changes to the input's remaining wording. It applies before cached advice as well as new inference. Identical pending requests can share their computation; different pending inputs for the same unresolved fingerprint use the fallback. Accepted decisions, cancellation and failures record their last verified progress basis. After an eligible interval, a pure cache result may be reused and starts a new guard interval. The reference strategy worker enables this rule and its fallback returns defer.

Native projections supply a reconsideration clock only when exactly one granted source has a known eligible counter. Unknown or unrelated clocks cannot be replaced with host time or sampled server ticks. Queue exhaustion/expiry and unavailable configuration do not submit model requests.

The bounded decision trace records requests, dispatch/reservations, provider settlement, typed results and discards. Provider events distinguish queue, provider and validation latency, usage, actual model, and late settlement. Raw credentials, headers and provider error text are not traced. Normal trace overflow rules still invalidate qualification.

## HTTP profiles and reference definitions

`new HttpWorkers({ name: { endpoint, model, apiKey, tokenLimitField? } })` is trusted host configuration. `endpoint` is the complete chat-completions URL. HTTPS requires a key; plain HTTP is limited to loopback. Userinfo, query strings, fragments and redirects are rejected. Profile identity excludes the secret. Each call sends fresh system/user text, JSON-object output, no tools or conversation history, and a provider token limit. `max_completion_tokens` is the default; an explicitly configured compatible endpoint can use `max_tokens`. The transport uses the [official Chat Completions contract](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create). Malformed UTF-8/JSON, tool calls, refusals, incomplete completions and output-contract violations fail to the fallback. The model must wrap its schema-conforming result as `{"value": ...}`.

`examples/workers.mjs` stages and deterministically validates `comment_structure`, `interpret_strategy`, their pure fallbacks, and the reusable `choose_strategy` behavior. Its closed input schemas define allowed evidence fields. `choose_strategy` verifies that `choose_alternative` names a supplied candidate, and that retry/defer carry no target. Invalid advice reduces to defer while preserving interpretation provenance. Use that behavior to consume strategy interpretations, then let the ordinary work service recheck action eligibility. Creating the pack calls no model and installs no duty.

The HTTP adapter is qualified against a real loopback server; service ownership and reference behavior tests run real supervised QuickJS processes. These are protocol and runtime checks, not live model or Minecraft mixed-duty qualification. No real inference profile or paid service was configured for this milestone.
