# In-game settings menu conventions

Date: 2026-09-25
Scope: Fabric, Minecraft 1.21.8, existing Airicraft YAML configuration.
Status: Research and recommendation; no menu implemented.

## Recommendation

Use **Cloth Config** to build Airicraft's settings screen and expose it through
**Mod Menu**. Keep Mod Menu optional and provide an Airicraft keybinding opening
the same screen; `/airicraft config` can be a secondary shortcut. Retain the
existing YAML files as persistence, so users can configure the mod without
editing them and existing installations continue to work.

This is an ecosystem integration pattern, not a mandatory Fabric standard.
Mod Menu supplies the installed-mod list and configuration button; Airicraft
supplies the actual screen through `ModMenuApi.getModConfigScreenFactory()` and
a `modmenu` entrypoint. Its API can be compiled against without requiring users
to install Mod Menu. [Mod Menu developer documentation](https://github.com/TerraformersMC/ModMenu#java-api)

## Libraries and version availability

| Component | Role | Verified Fabric release for Minecraft 1.21.8 |
| --- | --- | --- |
| Mod Menu | Discover and open a mod's configuration screen | [15.0.2](https://modrinth.com/mod/modmenu/version/ku5NivOP) |
| Cloth Config | Build the settings screen and bind fields to save callbacks | [19.0.147+fabric](https://modrinth.com/mod/cloth-config/version/cz0b1j8R) |
| YetAnotherConfigLib (YACL) | Alternative screen builder | [3.8.2+1.21.6-fabric](https://modrinth.com/mod/yacl/version/iPLhsWMM) |

These are compatible releases, not claims about the newest release. Mod Menu
15.0.2 lists Fabric API and Text Placeholder API as dependencies; installation
and the development launch must include the required dependencies.

Cloth explicitly separates per-entry save consumers from the screen-level
saving runnable. Airicraft can therefore persist its existing YAML through its
own writer. Adopting Auto Config or migrating storage formats is unnecessary.
[Cloth saving documentation](https://shedaniel.gitbook.io/cloth-config/using-cloth-config/saving-the-config)

YACL is another viable choice with a programmatic configuration-screen API.
Choose it if its presentation or controllers materially improve the desired
screen. For this repository, Cloth has the stronger immediate fit because it is
already a compatibility dependency. [YACL documentation](https://docs.isxander.dev/yet-another-config-lib)

## Current Airicraft integration points

- [`gradle.properties`](../../gradle.properties) already pins Cloth Config
  `19.0.147`; the [REI](../../compat/rei/build.gradle) and
  [JourneyMap](../../compat/journeymap/build.gradle) integrations consume it.
  This is not yet a root settings-screen dependency. Declare it explicitly if
  the core menu needs it; do not rely on an optional integration installing it.
- [`fabric.mod.json`](../../src/main/resources/fabric.mod.json) has no Mod Menu
  entrypoint.
- [`AiricraftConfigLoader`](../../src/client/java/ai/moeru/airicraft/AiricraftConfigLoader.java)
  and [`AgentConfigLoader`](../../src/client/java/ai/moeru/airicraft/agent/AgentConfigLoader.java)
  load YAML and create/migrate initial files, but do not expose a general editing
  save API. The GUI needs a shared validation/persistence path.
- [`ClientRuntimeController.reload()`](../../src/client/java/ai/moeru/airicraft/ClientRuntimeController.java)
  strictly loads configuration, creates a replacement agent runtime, shuts down
  the previous runtime, clears camera control, interrupts captures, and
  reconfigures the dashboard. Reload is not an innocuous per-field update.
- The settings inventory is documented in
  [`airicraft.yml.example`](../../src/client/resources/config/airicraft/airicraft.yml.example)
  and [`agent.yml.example`](../../src/client/resources/config/airicraft/agent.yml.example).
  Inspect these templates rather than private runtime files when designing UI.

## Proposed user experience

These are Airicraft design recommendations, not requirements imposed by a library.

1. **Connection:** backend selector; show API endpoint, masked key and model for
   OpenAI-compatible mode, or local executable/model overrides for Codex mode.
2. **Behaviour:** chat range, proactive social mode, system messages, idle timing,
   emergency reflexes, focus behaviour and camera smoothing.
3. **Vision:** native vision toggle, external vision connection and image detail.
4. **Advanced:** planner context/concurrency tuning, timeouts, dashboard and
   observability. Do not foreground deprecated or experimental controls.

Use readable labels, translated descriptions, appropriate boolean/number/enum
controls, field validation, reset-to-default controls, and Save/Cancel semantics.
Keep edits in a draft until Save. Mask API keys with an explicit reveal action.
Preserve values for the inactive backend when switching between backends.

For an initial implementation, an explicit **Save and reload agent** action can
reuse the existing reload path, with a visible explanation that it ends current
agent work. Opening the screen, cancelling, or saving an unchanged draft must
not reload. A later refinement can apply genuinely live settings without
replacing the agent; that requires defining which settings support it.

Persist only intended changes while preserving unrelated/unknown YAML keys.
Decide explicitly how comments and formatting survive editing; a plain map dump
must not be assumed to preserve them. Validate before replacing files, retain
the draft on failure, and report save/apply errors in the screen. If multiple
files change, handle partial writes deliberately rather than reporting success
before both persistence and runtime application have succeeded.

## Implementation validation

Verify YAML round-tripping, preservation of unexposed values, validation failures,
and Cancel/no-change behaviour. Then exercise a real 1.21.8 client with and
without Mod Menu, including title-screen access, in-world access, GUI scaling,
masked credentials, save/reload feedback, and restart persistence. Release
availability and source inspection do not establish that live integration yet.
