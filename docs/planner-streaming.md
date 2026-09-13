# Planner streaming

The OpenAI-compatible planner requests SSE streaming, including final usage. Text, `reasoning_content`, and indexed tool-name/argument fragments appear while the request is running. Fragmented calls are assembled into the existing response envelope before parsing or execution. Previews never enter conversation history or authorize actions.

- In Minecraft, `/airicraft debug conversation` shows a temporary **Streaming — incomplete** assistant card for the active generation. Completed responses replace it through the existing conversation projection. Detached/reset generations cannot leave a stale preview behind.
- In the browser dashboard, **LLM transcript** shows the current preview above the request messages. The recorder samples updates every five server ticks; tick-debug pause freezes capture and retention. Provider network activity itself can finish while game ticks are paused.
- Each display preview retains at most 16,384 characters plus an omission marker. Completed response parsing uses the assembled response, not that display tail. The configured request timeout covers the response body, including a provider that stalls after headers.
- Optional null fields from Qwen are accepted. A stream ending without a finish reason fails instead of turning a partial tool call into an action. Providers returning ordinary JSON still use the existing complete-response path.

Recorded `llm_call` observations reference a shared `llm_request` envelope through `request.observationSequence`; old records with inline `requestBody` remain readable. This avoids copying a large prompt into every streaming update. Full exports and history seek preserve these shared-context references. `STREAMING` records contain a preview in `rawResponseBody`; completed streaming records contain the **assembled** JSON response, not the original SSE wire transcript. Partial previews are observability data only.

Protocol reference: [Chat Completions streaming fields](https://github.com/openai/openai-node/blob/main/src/resources/chat/completions/completions.ts). Plain vision and compaction requests are unchanged.

Live validation used the configured `qwen3-8-27b`: tool discovery, inventory/world/entity inspection, then all four known cave-return navigation legs. The browser preview was visually inspected before completion. The in-game projection has automated coverage but was not visually inspected in this run. See D092 in [the playtest log](autonomous-playtest-log.md) for recordings and limitations.
