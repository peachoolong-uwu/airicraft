# Embed Airicraft OS in the mod

Airicraft OS runs as Java code inside the mod, with GraalJS executing JavaScript behavior definitions. This supersedes the driver experiment's standalone Node/QuickJS hosting choice: scheduling, invocation ownership, resources, persistence, workers and native action orchestration belong to Java. Gradle owns dependencies and tests; running skills must not require Node or npm.

JavaScript receives copied, bounded data and effect declarations, without Java class, filesystem, network or Minecraft object access. Skill evaluation runs away from the Minecraft thread; native mutations retain their existing client/server ownership and reconciliation checks. Definitions persist, while a restarted runtime reconciles unfinished effects and starts fresh instances. This migration does not imply a hardened hostile-code sandbox or completion of the mixed-duty benchmark.

The previous prototype and its trial evidence remain recoverable. Existing behavior, resource and lifecycle contracts are migration requirements, not permission to substitute JavaScript implementations of OS policy inside GraalJS.
