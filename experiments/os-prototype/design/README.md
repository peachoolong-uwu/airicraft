# Airicraft OS design

The architecture and evaluation decisions for the Codex driver experiment are recorded in [Chart the Airicraft OS driver experiment](https://github.com/shinohara-rin/airicraft/issues/51). That issue indexes the canonical resolution for each contract. Codex is the external behavior author; the OS executes and coordinates; bounded LLM workers return judgment as data.

The chosen implementation uses JavaScript generators and declarative offers in supervised QuickJS-WASM runners, a trusted Node broker for lifecycle/resources/scheduling, and a fenced native action boundary in the mod. Shared pen/chest visits belong to the OS. Restart recovery reconciles journaled effects and starts fresh invocations rather than restoring arbitrary JavaScript stacks.

- [Implementation and evaluation handoff](implementation-handoff.md): start with one guarded native chest transfer, then build outward through explicit qualification gates.
- [Competing-behavior walkthrough](coordination-walkthrough.md): nine paper cases covering shared work, stock, workers, interruptions, waits and restart.
- [Composition evidence](behavior-composition.md): the earlier prototype audit and the accepted child-ownership defaults. Its downstream questions are now resolved through the map.

These documents specify the target. The current prototype implements only part of it. Full native fencing/release, child composition, process supervision, worker calls, safe replacement, recovery and the matched production benchmark still require implementation and qualification. The design work did not run Minecraft or make a production-efficiency claim. Escaped-sheep repair remains separate.
