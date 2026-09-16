# Archived OS prototype

The active implementation is now the [Java Airicraft OS with embedded GraalJS](../../docs/embedded-os.md). Build and run it with Gradle and the normal Codex driver. This directory is historical reference, not a JavaScript application; its npm package, Node/QuickJS host, launchers and tests have been removed.

- `design/` retains the composition and scheduling design discussions.
- `implementation/` and `TRIAL.md` retain earlier contracts, reviews and experimental evidence. Their source paths and launch commands refer to the historical Node implementation, not current instructions.
- `library/` retains the homestead skill sources and old manifest for the later domain-adapter migration. They use the old API and are not installed by the Java runtime.
- `world.example.json` records the old experiment fixture configuration. Ignored local world settings and artifacts are preserved.

The committed prototype is recoverable at Git commit `0af7f5282ce2097bf62580510630a7752e533aa9`. Additional working-tree source was hashed and archived before removal; its recovery location is recorded in ignored `run/os-java-migration/recovery-path.txt`. See the [migration record](../../docs/os-java-migration.md).
