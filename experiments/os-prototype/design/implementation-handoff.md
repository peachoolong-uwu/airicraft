# Airicraft OS: implementation and evaluation handoff

This is the implementation route selected by [Choose the staged driver experiment and evaluation plan](https://github.com/shinohara-rin/airicraft/issues/66). The canonical architecture index is [Chart the Airicraft OS driver experiment](https://github.com/shinohara-rin/airicraft/issues/51); individual contracts live in its linked resolution comments. The [paper walkthrough](coordination-walkthrough.md) shows their interaction. Decisions were delegated to Codex on 2026-09-15.

This document specifies future work. It does not report a full reference run, a working LLM adapter, crash-safe execution, or measured production improvement.

## Start here

Build one native admission/inspection/cancellation slice around a guarded chest transfer. Include the world epoch, host lease generation, submission sequence, resource allowance, exact receipt, and affirmative release evidence. Exercise acceptance with a lost response and cancellation after a partial transfer. The host must reconcile the item counts and owned window before any replacement work starts.

That is the next implementation step. It establishes the boundary the scheduler, resource ledger, recovery journal, and independently authored behaviors all depend on. Keep the existing executor behind it; do not add another behavior or implement an embedded author first.

## Implementation sequence

| Order | Coherent change | Exit evidence |
| --- | --- | --- |
| 1. Native authority | Versioned scoped observations; lease/fence and submission high-water mark; submit/query/cancel receipts; uniform cleanup/release; first bounded transfer. | Stale/duplicate/lost submissions, actor revocation, partial effects and failed cleanup are distinguishable. No false release. |
| 2. Broker ownership | Owned child lifecycle and result slots; shared subscribers; one resource ledger; native consumption/capacity allowances; durable unfinished-effect journal. | The paper stock/cancellation/restart cases hold with a fake native boundary, then the real transfer slice. |
| 3. Execution and library | Per-root QuickJS runner supervision; bounded typed IPC; source/dependency digests; candidate validation and drain-before-replacement. | Infinite loop/allocation/serialization/process-fault cases stay bounded; children cannot increase root authority; old revisions remain reproducible. |
| 4. Worker functions | Fresh inference-only calls, schema validation, strict deadlines/bytes/call limits, cancellation and deterministic fallbacks. | First a controlled fake transport, then a configured real text model. Tool-bearing output never enters a dispatcher. |
| 5. Scheduling and domain operations | Overdue-root arbitration, shared pen/chest contexts, bounded crop/tree/compost steps, fishing early yield, stock/crafting/storage supply rules. | Each operation honors its grants/allowance/checkpoints. Independent definitions share visits while unrelated useful work still receives service. |
| 6. Evidence and trials | Joined trace, opportunity oracle, time accounting, immutable manifests, harness verdict mapping and paired comparisons. | The stages below can be run from fresh fixtures and audited from retained artifacts. |

Basic trace/journal/release evidence must accompany each earlier slice; order six adds complete measurement and reporting, not the first observability. Reuse current root/wrapper/evaluator modules at their existing boundaries. Commit implementation groups independently from unrelated JourneyMap or gameplay work.

## Qualification and experiment stages

| Stage | Trial | Advance only when |
| --- | --- | --- |
| A. Deterministic contract qualification | Fake native/provider transports and focused native tests cover owned joins; sibling cancellation/collect-all; retained-result limits; shared demand credit; floor/claim shortages; stale frames/epochs; event gaps; sequence eviction; worker lateness/limits; runner and host death; lease fencing; partial effects; cleanup uncertainty; full journal/trace budgets. | Every admission and owner is accounted for. Failure injection produces typed outcomes or unresolved reconciliation, not double spending or a silently free player. No live-yield or real-worker claim follows from mocks. |
| B. Prepared live microcases | Fresh isolated fixture copies: a ready crop beside fishing; two sheep operations in one visit; shared restock/deposit; mature managed birch plus sapling replanting; mature crops of each type; compost/extract/apply; a surplus already-sheared adult above target; bounded tool replacement. Add cancellation after admission and a host crash with a lost receipt. Limit each case to five advancing minutes and ten wall minutes. | Every required transition has at least one verified live success, and each critical interruption/ownership case survives its injected failure. Fixture preparation is recorded outside measurement. Sheep escape repair is not added to make a case pass. |
| C. Real worker qualification | Configure one authorized inference-only model profile. Use three frozen stuck-birch cases with bounded evidence and permitted alternatives. For each case compare worker-on against the identical deterministic fallback from fresh fixtures. Include a delayed response while another duty acts, plus an alternative that becomes invalid before the reply. Limit each run to five advancing minutes and ten wall minutes. | At least one real recommendation resolves the fixture's stated ambiguity and improves verified recovery relative to fallback; every returned action choice is revalidated. Other duties progress during the worker wait. If judgment adds no value, report that result; fake replies do not qualify this stage. |
| D. Exploratory mixed pair | One 30-minute fixed-order baseline run and one OS-policy run from matching copies of the beachside world. All required duties installed; same definitions, workers, grants, resource rules and native operations. | Inspect overhead, trace size, opportunity coverage and limits. Repair defects or calibrate numeric policy before freezing a new configuration. Do not include this exploratory pair in confirmatory results. |
| E. Frozen mixed comparison | Three new matched pairs, with baseline-first, OS-first, baseline-first order. Each arm runs 36,000 completed advancing server ticks, nominally thirty game minutes, with a 45-minute wall cap. Use identical prepared starting saves/configuration within each pair. | Apply the correctness and efficiency rules below. Report insufficient opportunities or contaminated/gapped trials as inconclusive, not a pass. |
| F. Attribution and reuse | With the frozen policy, run three short matched context-enabled/disabled fixtures. Separately compare a failure-triggering old definition with a revised definition on three fresh fixtures, then install the same revised digest on a second compatible target binding. Keep OS/native code fixed. | Attribute context savings to fewer verified entries/exits; attribute revision improvement to the changed library entry and successful reuse. Preserve unfavorable results and previous-version controls. |

The fixed-order baseline rotates among ready land roots in stable configured order, skipping blocked ones; fishing is filler only. Both arms have the same operation sizes, native checkpoints, stock protection, worker availability, and safety rules. It is a usable ready-duty baseline, not a monolithic script that deliberately waits idle for wheat. Shared contexts remain enabled in both main arms; stage F isolates their contribution by disabling reuse alone.

Code composition is demonstrated by a common child definition used by separate parents/bindings, distinct outcomes, cancellation semantics, and reclaimed live capacity. This establishes reusable structure, not a speedup by itself. Main-pair timing isolates dispatch policy; context ablation isolates setup reuse; worker-on/off isolates judgment; old/new source comparison isolates behavior revision. Do not attribute all gains to whichever component was added most recently.

## Reference and run controls

The reference duties are wheat, potatoes, carrots, sheep breeding/shearing and eligible surplus food with target eight, managed birch planting/harvesting, excess-seed compost/bone-meal use, and beach fishing. No mining or required smelting. Protect babies and at least two adults. Use the stock floors, operation sizes and scheduler limits from the linked contracts; record them as versioned run policy rather than burying them in source.

Record world archive/seed, player inventory and tool durability, chest stock/capacity, crop/root/pen/water bindings, managed entity IDs, difficulty, time/weather, gamerules, simulation/loaded scope, mod/native build, broker/Node/QuickJS/package-lock identities, definition closure, worker profile and policy hashes. Reinspect the original fixture; the chest and tools from an earlier session are not guaranteed to be replenished. Prepare every pair identically before measurement. Natural growth remains stochastic even with matching saves.

Use survival mode for resource-consumption and tool-durability measurements. If the construction save is in creative mode, prepare the test copy in survival before the window and record that preparation. Preserve and match the fixture's difficulty, time/weather and gamerules; do not change them during measurement to force an opportunity.

Codex is read-only during measured runs. No gameplay command, source edit, scheduler nudge, inventory refill, controlled growth, prompt change, or recovery intervention is permitted inside the window. Codex studies evidence and revises definitions between runs. Emergency stop is always available and produces an interrupted result.

Stop new work and drain on death, wrong-world/epoch transition, unresolved physical release, failed authority/journal infrastructure, required-evidence loss, the wall cap, or user stop. A worker timeout uses its fallback and does not stop healthy roots. Missing tools/materials block the affected duty and remain visible. A terminal root failure is reported; no hidden automatic restart turns it into a clean run.

A sheep-containment incident marks affected opportunities unavailable and prevents a full-herd success claim. An incident that materially changes a paired fixture makes that comparison inconclusive. Do not repair the pen mid-run or merge sheep recovery into this roadmap.

## Time accounting

Use one exclusive player timeline in **completed server ticks**, converted to nominal game seconds by dividing by twenty. Record wall duration/TPS, provider latency, broker latency and CPU separately. Paused/stalled intervals add wall time but no game ticks. The 45-minute cap still applies. If the required authoritative tick clock is unavailable, the timed benchmark is unavailable rather than guessed from client polling.

Classify every advancing interval once, with this precedence:

1. **Unknown** when required classification evidence is missing.
2. **Interruption/recovery** during a reflex takeover, unresolved ownership or active failure reconciliation.
3. **Travel/setup/cleanup** for navigation, physical inspection, context entry/exit, inventory-window handling, and normal action release.
4. **Productive actuation** for a bounded operation step with a verified intended material effect. Failed attempts retain their failure/recovery classification; acceptance alone is insufficient.
5. **Active waiting** while native work must retain the actor but is waiting for a game process, including an ordinary cast waiting for a bite.
6. **Orchestration overhead** while the actor is free and required read-only observation/admission transport or broker computation prevents dispatch. Repeated unnecessary polling does not qualify.
7. **Avoidable idle** while the actor is free, useful granted land work is feasible, and no required dispatch/refresh is in progress.
8. **Justified quiescence** when no useful feasible operation exists and no other category applies.

Overlapping worker/observation time is a diagnostic, not a second charge against a productive or waiting player interval. Native action steps need phase/effect markers fine enough to distinguish actuation from waiting and navigation; otherwise that segment remains unknown. Use an independent evaluator view of configured duty readiness to detect a ready chore omitted from the broker's candidate list.

The avoidable-idle denominator is all classified advancing ticks with the ordinary OS permitted to control a live player in the matching world: categories three through eight. It includes time spent using the player and excludes unknown and interruption/recovery. Report excluded time separately. Target idle below 5% of this denominator; require at least 99% timeline classification and no gap in essential owner/claim/outcome events before making a timed efficiency claim.

Anti-starvation is audited separately: an eligible root enters the overdue queue after 120 accumulated eligible game seconds. Once overdue, at most the already-ahead overdue roots may receive their next ordinary grant before it; new arrivals cannot overtake it. With twelve roots, at most eleven other root grants precede its service, apart from excluded reflex/reconciliation periods. Operation/cleanup deadlines bound each grant conditionally; 120 seconds is not itself a maximum wall-clock response time. Missing resources, unknown readiness and unavailable world progress are recorded rather than counted as eligible waiting.

## Opportunities and completion rates

Use a bounded **evaluation-only world observer** to track readiness episodes and verified effects in the configured scopes. It must not feed privileged server-side facts into either arm's behavior inputs. The existing evaluator's state checks are a starting point; the episode accounting below still needs implementation. Both arms use the same observer, coverage and rules.

An opportunity is a distinct target readiness episode, not a poll sample or submitted request. Keep its target IDs, episode generation, first-ready tick, unavailable/blocked intervals, and terminal effect evidence. Repeated ready observations refer to the same episode; a regrowth/replant/cooldown transition can create a new one. Resource-blocked episodes remain in the production denominator once registered, with blocked reasons reported; they do not vanish because the scheduler spent the stock elsewhere. Readiness hidden by missing coverage is unknown.

| Transition | Opportunity identity/unit | Verified completion |
| --- | --- | --- |
| Plant each crop type | Assigned empty cell episode, initially or left as replant debt. Do not also count a transient empty cell inside one successful harvest/replant pair. | Correct crop planted in that cell with accounted planting consumption. |
| Harvest each crop type | One mature crop generation at one assigned cell. | Harvest effects accounted for and the same cell replanted; an interruption leaves this incomplete until repaired. |
| Sheep shearing | Adult UUID and wool-regrowth episode. | Sheared state plus accounted wool/drop effects. |
| Sheep breeding | One unit of non-overlapping eligible adult-pair capacity while the herd needs another lamb. Use adult UUID/readiness generations; do not count all combinations of adults as separate pairs. Credit the two actually fed adults once and reconcile their pair-capacity slot. | Both eligible adults fed with accounted wheat and a newly observed lamb attributable to that breeding episode. Ambiguous attribution is unknown, not an extra birth. |
| Surplus sheep food | Surplus-removal capacity slots, capped by herd surplus, eligible already-sheared adults, and preservation of at least two adults. Bind a slot to the actual removed UUID; changing candidates does not create another slot. | Authorized removal and accounted food/drop effects. |
| Birch planting | One empty, plantable managed root episode. | Correct sapling planted with accounted consumption. |
| Birch harvest | One grown managed-tree generation. | Assigned trunk work completed and log/drop effects accounted for; remaining leaf decay and later replanting are separate states. |
| Compost and bone meal | Separate surplus-stock/bin-ready/growing-target readiness episodes with current stock eligibility. | Observed insertion, extraction, or application effects; probabilistic yield is reported as raw output, not assumed per seed. |
| Fishing | Eligible filler time and completed casts; a catch opportunity is an observed bite, not time spent standing at the beach. | Verified fish catch; other catches and yields without a catch are reported separately. |

For breeding, the observer tracks available adult readiness tokens and the maximum non-overlapping pair capacity, capped by unmet herd target after pending births. A successful feeding consumes two tokens and one slot; changing which compatible adults form a pair must not create additional opportunities. If the implementation cannot establish this accounting or authoritative readiness, breeding-rate comparison stays inconclusive until that observer is qualified. This is an evaluation gate, not permission for an infinite design expansion or for assumed cooldowns to count as observed births.

Likewise, ten eligible adults with a target of eight create two surplus-removal slots, not ten culling opportunities. Successful removal consumes a slot; only a new surplus episode creates new capacity. Babies and protected breeding adults never add removable capacity.

For the primary crop/sheep/birch transition rate, include opportunities first ready at least 120 game seconds before the fixed end of the run. Apply that same cutoff whether an opportunity completed early or not; later ones are right-censored and reported separately. Numerator is included episodes verified complete by run end; denominator is all included episodes. An episode never completes twice. Unknown/censored episodes have separate counts and cannot be converted to success.

Require at least five included opportunities per arm for a transition across the three confirmatory pairs before applying its regression threshold. Report per-pair counts and pooled numerator/denominator. Fewer opportunities mean that transition's efficiency comparison is inconclusive, even if its prepared functional test passed. If baseline rate is zero, the rate-ratio gate is uninformative; show counts and require live functional evidence rather than announcing an improvement from division by zero.

Apply the reference decision rule: combined avoidable-idle plus travel/setup/cleanup time must be lower in at least two of three pairs and have a lower paired median. No sufficiently exercised primary transition may have an OS completion rate below 90% of its baseline rate. Also report orchestration overhead, interruption, fish, stock balance, durability and the raw product vector. No single invented value combines wool, logs and food.

Do not claim full mixed-production efficiency if required transitions lack coverage, sufficient opportunities, or any verified completion despite being ready. The report may identify a narrower supported finding, such as lower setup cost with exercised crop/sheep work while natural birch growth remains inconclusive. Prepared fixtures establish capability for rare transitions; they cannot be substituted into a natural-run throughput total. These small-sample rules are experiment decision thresholds, not statistical significance guarantees.

## Revision and reuse evidence

Pick one repeatable, scoped failure from a recorded trial, such as a birch alternative that repeatedly becomes stale or a poorly chosen bounded work offer. The revision changes a behavior/worker definition and its explicit dependency closure. Freeze the revised source before the new runs; preserve the old revision as a control. Do not change the native interface or scheduler in this comparison.

Run three fresh matched failure fixtures for each revision, then use the revised digest with a second compatible target binding. A successful revision trial requires the targeted repeated-failure count to fall by at least half across the matched fixtures, verified completion in every revised fixture, and no new authority/stock/release violation. State low counts and worker variability; this is a bounded reuse demonstration, not proof of monotonic or general self-improvement. If the hypothesis fails, retain the failed candidate and evidence rather than editing it during measurement.

## Evidence and harness

Retain a manifest, immutable definition/dependency bundle, fixture preparation/hash, critical joined trace, opportunity episodes and verification frames, time-category totals/intervals, native incident export, worker inputs/results and usage/unknowns, per-transition numerator/denominator tables, product/resource balances, paired comparison, and any revision hypothesis/diff. Results must state executed, simulated, not exercised, blocked, failed, interrupted or inconclusive as applicable.

Use the trace contract's 64 MiB OS trace and bounded native recorder exports within the 2 GiB archive quota. Reserve the planned artifact capacity before each run (128 MiB initially, including selected native exports and metadata), reconcile actual use at completion, and pin the evidence selected for a comparison. Refuse a run whose reservation cannot fit; do not delete pinned controls or silently lose essential events. Full video/replay recording is optional and requires its own explicit quota; it is not necessary for the core decision trace.

The existing Airicraft evaluator supports archived full-world fixtures, external-driver mode, scenario-specific mods and isolated client/bridge directories. Reuse that outer lifecycle for the mixed trials. Run performance pairs serially on one machine to avoid sibling-client contention. Focused host tests and root Gradle tests establish deterministic contracts; server/client GameTest can support narrow mechanics/input fixtures when useful, but migrating the evaluator is not a prerequisite. See the earlier [GameTest fit research](../../../docs/research/gametest-evaluator-fit.md); its historical details are context, not a new live validation claim.

The current harness does not automatically measure the OS ledger, worker function boundary, episode rates, or the complete exclusive timeline. Add those checks/artifact imports at the evaluator seam. A functional scenario may report passed while its throughput metric is inconclusive; map metric inconclusiveness to a visible review verdict and a structured reason rather than a global pass. Preserve interrupted/failed/review worker directories for inspection.

## What is settled and what still needs evidence

The architecture and experiment policy are settled. Numeric budgets may be calibrated in exploratory qualification and then versioned/frozen; that is an implementation/evaluation gate, not an unanswered architectural question. Runtime memory/latency, uniform native release, consumption limits, restart reconciliation and complete measurement are not yet demonstrated by the prototype.

The HTTP worker transport is selected, but the local worker model/credentials are not configured. Configure and qualify an authorized profile before the real-worker stage; no inference or paid service was started by this design work. Codex already supplies authorship. Production confinement/distribution, embedded migration, the deferred logbook, and escaped-sheep repair remain outside this map.
