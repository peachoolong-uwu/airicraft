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
