# Recurring work declarations

An offers-mode behavior declares useful bounded work from a fresh, scoped observation. The OS selects, prepares, admits and accounts for actual work. The function does not sequence native actions, sleep for growth or claim the player.

```js
function offers(os, input, view) {
  const carried = view.scopes.find(scope => scope.scope === 'carried');
  const stock = carried?.frame?.facts.find(cell => cell.path[1] === 'wheat');
  if (!carried?.current || !stock?.known || stock.value >= input.target) return [];
  return [os.work('chest', {
    direction: 'withdraw', itemId: 'minecraft:wheat',
    quantity: Math.min(2, input.target - stock.value)
  })];
}
```

The example requires a trusted `chest` operation/rule and grant plus a configured `carried` view. The currently integrated native operation transfers from an already-open bound chest. This example does not open it or assert that its contents are available; native preparation and resource admission make that decision.

## Evaluation and evidence

`BehaviorLoop` attaches a validated, installed root or owned child with `mode: 'offers'`. Its pure function runs in that invocation's supervised QuickJS VM with existing root CPU/message/process limits. It receives immutable installation input and a copied view `{ epoch, sequence, scopes }`. The view contains only configured, granted scopes from the same native capture. Missing coverage is explicit. It must fit 12 KiB, depth 10 and 1,900 nodes; oversize fails the invocation rather than truncating its facts.

One evaluation runs per invocation at a time and at most once per native capture/generation. Host pulses with no new basis do not rerun it. New observations can trigger reevaluation while another physical action is active, but they confer no authority. If evaluation finishes after a newer capture or a physical generation change, its result is discarded. New work starts only with current authored and readiness bases, then the coordinator reobserves immediately before admission.

Return at most 32 `os.work` effects within the existing 16 KiB guest value/wire envelope. An empty array withdraws queued declarations and leaves the duty installed. Invalid output, ungranted operations, capacity failures and VM faults fail the invocation under the existing sibling policy. Batch admission is atomic: no prefix is installed if a later declaration fails.

## Identity, repetition and withdrawal

Trusted code calls `work.replaceOffers(owner, sequence, declarations, basis)`, where basis is `{ epoch, captureId, captureSequence, generation }`. The host supplies all identity, sequence and evidence fields. Guests cannot forge scheduling priority, owner or readiness. A replay of one replacement sequence must be identical; retired or conflicting versions reject.

Declarations are keyed by their complete operation, arguments and null context. Identical entries collapse. Unchanged entries retain their work ID across evaluations, including reordered arrays, rather than acquiring new queue positions. Distinct invocations retain distinct declarations and root-owned fairness; duplicate offers do not multiply a root's age or service credit. This is not semantic merging of different argument shapes.

A successful bounded attempt becomes eligible to repeat only after a fresh evaluation still declares it and current native evidence permits it. Recurring offers have no consumable finite result slot; outcomes remain in work/native traces and the broker's last activity. `take` rejects recurring IDs. Generators can use passive observations or explicit finite work when they need an individual result.

Changing a declaration set removes queued/finished work but does not interrupt an already-admitted bounded attempt. A removed active record remains until verified release and accounting, then disappears. Reintroducing the same active declaration reuses that record. Root/parent cancellation still requests cancellation and retains cleanup ownership. A normally returned parent continues to own recurring children and cannot settle until those children stop.

Failures defer recurring work for five wall seconds per consumer root and operation. Withdrawal, changed arguments and new children cannot evade the timer. This deliberately also delays another target using that same operation within the same consumer; other operations and consumers remain independent. At most twelve roots times thirty-two configured operations retain retry metadata. Expiry alone supplies no fresh readiness. No elapsed wall time is interpreted as crop, animal or tree progress.

Finite and recurring requests share the 32-per-invocation work limit and global budget after automatic-supply reservation. At most one removed admitting/active attempt is retained in addition to current declarations, because there is one physical actor. Stopping an owner reclaims queued offers and its replacement cursor after physical cleanup.

## Scope

This connects recurring declarations to the same work/resource/native admission path used by finite effects. It does not yet add domain operations, visit batching, continuous native eligibility counters, fishing handover, LLM workers or mixed-duty evaluation. The earlier prototype remains the default entrypoint.
