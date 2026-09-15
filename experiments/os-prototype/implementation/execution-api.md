# Isolated execution foundation

`RunnerPool` connects the owned invocation broker to disposable root processes. The existing prototype's `src/main.mjs` does not yet use this installation path. The [library](library-api.md), [condition waits](wait-api.md), and [generator service loop](loop-api.md) now connect pinned definitions to passive observations, owned children, resource declarations and finite [work requests](work-api.md). Worker functions, automatic supply selection and the full mixed-world scheduler remain subsequent layers.

## Ownership and lifecycle

Create an owned root or child through `InvocationBroker`, open one pool runner for the root, and create its VM with the same definition identity. The broker supplies the root association; a child cannot choose another process or acquire a fresh root budget. Siblings may initialize in any order. The pool rejects recreating a closing, completed or consumed invocation.

`resume` returns a copied `{done, value}` result. A yielded value is a closed effect declaration. A body return enters the broker's normal close/join phase; it does not declare physical effects released. `offers` evaluates up to 32 work declarations without directly dispatching any of them. Only the trusted host can translate these declarations into granted, currently feasible activity.

Guest failure fails the invocation according to its existing sibling policy. Process failure fails its root subtree. Both preserve the broker's native cleanup owners and claims. `onOwnershipChanged` is a notification for the host execution loop to poll/reconcile owned activity; it is not release evidence. The process pool itself never submits or cancels a native action.

`cancel` also accepts a parent whose body has already returned while children are still pending. `retire` requires a settled root outcome before destroying its pool entry. `close` cancels roots and stops their processes; the caller must still drain native obligations through the coordinator before closing the journal and trace. A destroyed VM is never resumed or restored.

`syncOwnership(rootId)` disposes computations whose broker owners have stopped or been consumed, using the root's retained process association. This lets the generator loop notice a native-origin failure even when the VM is suspended and mandatory join already deleted its child handle. It preserves running children of a closing parent and leaves physical release to the broker/coordinator. A previously retired process is an idempotent no-op.

## Guest surface

Behavior source defines `function* main(os, input)` or `function offers(os, input, view)`. Input and each response are copied and deeply frozen inside that VM. The guest receives these data constructors:

| Constructor | Declaration |
| --- | --- |
| `os.observe(query)` | Request bounded observation data. |
| `os.wait(condition, options)` | Request a persistent condition wait; the later wait service validates scopes and clocks. |
| `os.spawn(definition, input, options)` / `os.join(handle)` | Request an owned child or consume its result. |
| `os.target(resource, quantity)` / `os.demand(resource, quantity, methods)` | Declare maintained stock or finite delivery demand. |
| `os.work(operation, arguments, context)` | Offer bounded physical work for host admission. |
| `os.worker(definition, input)` | Request inference-only judgment through the later worker service. |

These constructors do not invoke host callbacks. The host validates copied declarations again. Additional authority fields such as a caller-selected owner are rejected. The VM has no Node objects, filesystem, network, subprocess, package loader or provider credentials. Async generators and arbitrary promise-job pumping are not part of execution.

The trusted guest kernel captures its encoder intrinsics before loading source. It rejects accessors and non-JSON values, bounds traversal, and checks the encoded UTF-8 length inside the VM before copying a string to Node. Exception reporting reads bounded own data properties without evaluating exception accessors. Source compilation, serialization and evaluation remain under the invocation interrupt budget.

## Process and compute limits

The versioned [execution policy](../src/os/execution-policy.mjs) supplies the VM, process, dispatch and supervision limits. Node 26.7.0 and quickjs-emscripten 0.32.0 are checked in the runner handshake. A process has an empty credential environment apart from fixed locale/timezone settings. Each live invocation has a separate QuickJS runtime/heap; a root shares one engine module in its process.

IPC uses a four-byte length followed by at most 16 KiB of closed JSON. A decoder validates length before allocating the body. Approved source is transferred in bounded chunks and verified against its byte count and SHA-256 digest. Only one request is in flight per root process, with at most 64 queued control slots and a reserved lifecycle slot. Shutdown kills the process without waiting behind ordinary messages.

The broker independently kills a process that does not answer within one wall second. RSS sampling runs every 250 ms, with a bounded external `ps` read. A runner above 256 MiB stops; unattributed aggregate pressure above 768 MiB stops the group. Unavailable measurements stop the affected group and retain bounded diagnostics. These are supervisory thresholds with possible overshoot, not OS-enforced memory limits.

Each root shares a 100 ms CPU/second bucket with a 250 ms burst, and 64 ordinary effects/second with a burst of 64. Creation reserves its 250 ms initialization allowance; evaluation reserves 25 ms. Actual measured CPU is charged afterwards, including overruns. Insufficient budget produces a recorded wait; ready roots receive dispatch in round-robin order. Native lease renewal and cleanup remain outside guest budgets.

## Qualification

Focused tests cover capability absence, copied/frozen data, hostile serialization changes, source/input/output limits, infinite loops, allocation failure, bounded diagnostics, offer limits, UTF-8 source chunking, stopped-process watchdogs, queue saturation, shared root budgets, reverse child initialization, retained physical ownership after process death, and individual/group RSS stop rules. The RSS threshold tests inject readings rather than deliberately exhausting host memory.

The local full-root footprint probe and its manifests are retained under `run/os-implementation/runner-qualification/`. It exercises lightweight live VMs, not the Minecraft reference workload or adversarial engine escapes. Final measurements and regression results are recorded in [STATUS.md](STATUS.md).
