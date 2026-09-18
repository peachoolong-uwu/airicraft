# Finite JavaScript policies

`run_policy` lets the controller planner express a finite procedure as a JavaScript generator. Java executes its yielded effects and resumes the same generator with their results. No model call is needed between effects. The existing work history owns the invocation's identity, terminal outcome and cancellation surface.

This first implementation supports **an already-open chest-like container in singleplayer**. It does not navigate, open containers, install persistent skills, schedule multiple policies, or resume JavaScript stacks after restart. It adapts the code-as-policy idea from AIRI's Minecraft integration and the guest-execution approach preserved on `os-exp`; it does not revive the broader OS replacement.

## Authoring

Define `function* main(policy, input)` and supply JSON input:

```js
function* main(policy, input) {
  let state = yield policy.observeContainer();
  const missing = Math.max(0, input.target - (state.inventory[input.itemId] || 0));
  if (missing > (state.container[input.itemId] || 0))
    return {restocked: false, reason: 'insufficient_stock'};
  if (missing > 0)
    state = yield policy.withdraw(state.syncId, [{itemId: input.itemId, quantity: missing}]);
  yield policy.closeContainer(state.syncId);
  return {restocked: true, inventory: state.inventory};
}
```

The bundled `src/main/resources/airicraft/policies/restock-open-container.js` handles several stock targets in one transfer. Its input is `{"stock":{"minecraft:bread":8,"minecraft:torch":16}}`. Counts cover the 36 carried storage/hotbar slots; armor and offhand are excluded.

| Yielded effect | Completion and result |
| --- | --- |
| `policy.observeContainer()` | Fresh integrated-server observation of the same open window: `{syncId, container, inventory}`, with registry-ID count maps. |
| `policy.withdraw(syncId, items)` | Preflights through the existing container controller, submits ordinary screen clicks once, then waits for both server-observed source and destination counts to match the expected transfer. Returns the confirmed snapshot. |
| `policy.closeContainer(syncId)` | Sends the normal close and waits for the server to stop observing that window. Returns `{closed:true,syncId}`. |

Reads run on the integrated server thread; click submission runs on the client thread; guest evaluation runs on its own thread. No guest code receives Java or Minecraft objects. The server adapter reads only the controlled player's currently open window, never unopened containers.

## Run and inspect

The controller's tool schema and external Codex-driver tool list expose `run_policy` with `source` and `input` fields. A call returns an `OPERATION:` work ID and yields the planner turn until the work changes. Use `inspect_work` for the result and effect evidence, or `cancel_work` with the exact work ID. The thinking planner does not receive this mutation tool.

For a driver client with an open chest:

```sh
python3 - <<'PY'
import json, pathlib, subprocess
source = pathlib.Path('src/main/resources/airicraft/policies/restock-open-container.js').read_text()
subprocess.run([
    'wrapper/build/install/airicraft/bin/airicraft', 'agent', 'tools', 'call',
    '--name', 'run_policy', '--arguments', json.dumps({
        'source': source, 'input': {'stock': {'minecraft:bread': 8}}
    })
], check=True)
PY
```

`SUCCEEDED` means the program returned normally. Interpret its returned value too: `{restocked:false}` is a valid program result, not proof that the requested stock was obtained. Unsupported effects or native failures fail the invocation rather than retrying clicks automatically. The work details retain source, input, yielded effects, completed results and the terminal reason.

## Interruption and limits

One invocation owns normal actuation at a time. New planner mutations are rejected while it runs; safety reflexes retain priority. Reflex takeover, death, world leave, reset, cancellation and replacement of normal work stop the invocation. A cancelled policy is not automatically resumed or replayed. Transfers already committed remain committed; a pending effect may have changed the world without its confirmation being retained. Inspect fresh counts before starting again. Cancellation deliberately does not issue cleanup clicks or close a window owned by a reflex/user.

Limits: 32,768 source characters; 16,384 characters per JSON value; 32 effects; 1,200 client ticks per invocation; 100 client ticks per container effect; 200,000 guest statements per evaluation; 10-second initialization and 1-second resume deadlines. The guest has no host-class, filesystem, network or process access. These restrictions do not impose a hard guest heap limit within the shared JVM: this remains an experimental behavior-authoring surface.

GraalJS 25.0.4 is included in the mod as nested dependencies. A separate GraalVM installation is not required; ordinary Java 21 uses the interpreter fallback.

## Verification

Focused tests execute the real GraalJS generator with delayed host completions, cancellation, failed transfers, host-access rejection and bounded loops. The restock test checks that three dependent effects complete in one policy invocation without inference between them. Container arithmetic tests require matching source and destination totals. These tests do not by themselves qualify live Minecraft packet handling or model-authored gameplay.

The 2026-09-19 dev-client smoke used an isolated copy of the latest playtest world, a separate bridge and Codex-driver mode. One invocation (`OPERATION:efafe461-2ca5-4644-8416-22a3b56f6812`) completed observe → withdraw → close in 27 client ticks. The server-observed chest changed from 18 to 10 torches and 13 to 9 oak planks; carried storage changed from zero to 8 and 4 respectively. A separate inventory tool confirmed those counts. Repeating the same stock targets issued only observe and close, transferring nothing. A second policy was explicitly cancelled while observing; a competing transfer was rejected as `policy_active`. Evidence remains locally under `run/policy-smoke-20260919/`.

This smoke validates the hand-authored procedure through the real tool/work path. It does not measure model-authored policy quality, live reflex takeover, multiplayer support, restart recovery, or the packaged production client's Graal class loading. The production artifact is built with the engine JARs nested separately; the dev smoke caught and removed aggregate Graal POM dependencies from Fabric's launch classpath.

```sh
source .envrc
./gradlew :test --tests 'ai.moeru.airicraft.policy.*' --tests 'ai.moeru.airicraft.agent.Container*Test'
```
