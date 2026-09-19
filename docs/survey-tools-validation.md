# Self-tool survey validation

Verified on 2026-09-19 through `scripts/codex-driver`, using an isolated copy of
`planner playtest` named `codex-survey-20260919`. The embedded planner was disabled.
These tests exercise the wrapper, bridge, provider registry and GraalJS in Minecraft;
they do not establish that an LLM chooses or interprets the tools correctly.

## Corrections

- Snapshot block states use Minecraft property serialization instead of enum
  `toString()`. Chest type is `single`, matching `inspect_world`.
- Landmark markers no longer depend on finding a body-clear supporting surface.
  Terrain cells use a landmark prefix plus their original terrain suffix, e.g.
  `1?` for landmark 1 with unknown support, `4M` for landmark 4 in a multi-floor
  column. Multiple landmarks share `L`; self uses `@`. Every landmark retains its
  row, column, marker and underlying terrain/height, including shared columns.
- Headroom extending above captured bounds is unknown, not an absent surface.
- Message-less exceptions retain their type instead of returning `null`.
- Read-only query execution allows three seconds; action-policy steps retain one
  second. Both retain the 200,000-statement limit and isolated guest execution.

The earlier first invocation returned only `null`, so its cause cannot be recovered
from that receipt. Two diagnostic cold runs with improved error reporting succeeded
before the budget change (surface and original underground capture). A timeout is
plausible but unconfirmed. The larger query budget adds cold-execution margin; it is
not proof of that original cause.

## Verification

Before the fixes, focused tests failed for the missing capture-ceiling marker and
message-less error. Afterward, the full Gradle build and tests passed. Regression
coverage includes ceiling headroom, overlapping landmarks, self sharing a landmark
column, unknown cells, multiple floors, half-block elevations, grouped doors, and
the dynamic tool lifecycle.

Two separate fresh Minecraft processes each passed a 19-call live suite:

- Cold survey, then five repeated surveys with the maximum landmark limit.
- All 17x17 terrain/height cells aligned, with matching text widths.
- Every landmark's row/column matches its marker and recorded height.
- Seven furniture blocks independently queried through `query_world` all present
  in the survey, with the two door halves grouped as one landmark.
- Canonical chest `type: single`.
- Guest metadata mutation does not alter host-owned coverage.
- Infinite loop rejected at the statement limit; Java access rejected.
- Out-of-range survey/custom inputs rejected.
- Custom tool created, advertised, called, edited, inspected, removed, and then
  rejected as unknown.

Cold wrapper calls took 3.636 and 5.287 seconds including CLI startup, snapshot
capture and Graal initialization. The three-second execution budget covers guest
execution only; initialization has its separate existing ten-second budget.
Raw evidence and readable maps were saved locally in
`/tmp/survey-thorough-first/` and `/tmp/survey-thorough-second/` (temporary artifacts).
A separate blocking-Atomics probe was rejected as an unsupported operation; it did
not exercise the wall-clock timeout. Test clients were stopped after verification.

## Reproduce

Launch `scripts/codex-driver`, join a copied world and confirm
`codexDriverActive: true`. Source `.envrc` before invoking the wrapper:

```sh
source .envrc
wrapper/build/install/airicraft/bin/airicraft agent tools call \
  --name survey_surroundings \
  --arguments '{"focus":"chest,furnace,door,crafting_table"}' --verbose
```

Use `inspect_tool` with `name=survey_surroundings` to retrieve the editable source,
then `define_tool` to create a `custom_` variant. The original world is not modified
by snapshot reads. No live client may use this checkout while its JARs are rebuilt.

## Compact text presentation

The survey now returns a string rather than exposing its internal geometry object.
The query provider emits string results directly with real newlines and a concise
host-owned coverage header; object/array results keep their JSON envelope. Survey
landmarks use `label block(x,y,z) state=value` lines followed by the aligned maps.
The wrapper's single-line transport display escapes newlines, but the provider's
planner-facing result contains actual newline characters.

A fresh Codex-driver run verified the text survey at the same camp position. Its
wrapper result shrank from 6,424 to 3,451 characters (46.3%). This is a character
comparison, not a model-specific token measurement. Full build/tests passed;
regressions cover real newlines, compact landmark syntax, geometry, and protection
of the host coverage when guest code edits its snapshot. The client was stopped.
Local preview: `/tmp/survey-compact-preview.md`.
