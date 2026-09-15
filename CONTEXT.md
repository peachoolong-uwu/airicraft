# Airicraft

Airicraft's domain language covers behavior execution and evaluation in Minecraft.

## Language

### Behavior execution

**Airicraft OS**:
The runtime responsible for executing and orchestrating behaviors that share one Minecraft player. Behaviors can combine ordinary code with calls to LLM workers.

**Behavior author**:
The agent that observes execution, studies failures, and defines or improves behaviors. Codex fills this role in the driver experiment.
_Avoid_: Worker

**Worker**:
An LLM invoked as a function by behavior code to provide judgment or interpretation during execution or orchestration.
_Avoid_: Planner

**Behavior definition**:
Reusable behavior code with an input/output contract and declared capabilities. A skill names a behavior definition, rather than a particular run of it.

**Behavior instance**:
One invocation of a behavior definition, with its own inputs, local state, owned child instances, and eventual outcome. Separate invocations remain distinct even when the OS shares their physical work.

**Installed duty**:
An independently supervised root behavior instance, typically recurring until stopped or replaced.

**Work request**:
One behavior instance's identified interest in a physical operation or desired effect. Multiple requests can share service without sharing invocation ownership.

**Activity**:
One bounded attempt at physical work, owned by the OS and carried out through the native action boundary.

**Access context**:
Exclusive setup that compatible activities can reuse, such as a pen visit or an owned chest window. Its lifetime belongs to the OS and may span work from different behavior instances.

**Observation frame**:
A bounded capture of a granted world scope, with identity, capture time, provenance, and explicit coverage and unknown values.

**World epoch**:
One continuous loaded-world generation within a bridge session and dimension. Observations, waits, and physical authority from another epoch are stale.

**Admission fence**:
Native authority identifying the current host lease generation and world epoch. An operation from a revoked generation cannot regain control by arriving late.

**Progress eligibility**:
Evidence that a particular area or entity can advance its relevant world process. Eligibility allows a growth budget to advance; it is not evidence that growth completed.

**Resource demand**:
A consumer's identified unmet requirement for an item, capacity, or reusable asset. Expected future production is demand evidence, not currently available stock.

**Stock target**:
A maintained quantity for one consumer and resource location. Repeated declarations of the same target do not add demand; distinct consumers' targets do.

**Resource reservation**:
An allocation of observed resources to one admitted activity attempt. It accounts for the consumer's existing protected stock rather than protecting the same units twice.

### Evaluation

Airicraft evaluation measures agent behavior in isolated Minecraft scenarios. The evaluation harness collects scenario evidence and optional supporting evidence.

**Evaluation run**:
A set of one or more isolated scenario executions started by the evaluation harness.
_Avoid_: Batch, evaluation batch

**Scenario outcome**:
The evaluator decision about one scenario, independent of recorder success.
_Avoid_: Run result, recorder result

**Harness outcome**:
The orchestration decision for one scenario, including required supporting evidence.
_Avoid_: Scenario result

**Integrated-server capture**:
A recording of one evaluation player's connection from the singleplayer integrated server.
_Avoid_: Client-side recording, trajectory recording

**Recorder Play**:
A completed, replayable recorder artifact for one player connection. It is supporting evidence for a scenario outcome.
_Avoid_: Recorded result, trajectory

**Recording profile**:
A versioned recorder runtime supplied as one self-contained unit for an evaluation run.
_Avoid_: Recorder JAR, extra mods directory

**Recorder-enabled run**:
A run that requires one supplied recording profile and one completed Recorder Play for each executed scenario.
_Avoid_: Optional recording

**Recorder-disabled run**:
An evaluation run that explicitly does not collect a Recorder Play.
_Avoid_: Missing recording
