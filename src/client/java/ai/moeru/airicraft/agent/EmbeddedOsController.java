package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.os.EmbeddedOsRuntime;
import ai.moeru.airicraft.os.OsJson;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** The mod-facing lifecycle and operator interface; no gameplay policy lives here. */
final class EmbeddedOsController {
	private static final Set<String> NAMES = Set.of("os_runtime", "os_library", "os_skills");
	private static final AtomicInteger RETIRING = new AtomicInteger();
	private final NativeDriverService nativeDriver;
	private final AtomicInteger nativeQueue = new AtomicInteger();
	private EmbeddedOsRuntime runtime;
	private volatile boolean closing;
	EmbeddedOsController(NativeDriverService nativeDriver) { this.nativeDriver = nativeDriver; }
	static boolean handles(String name) { return NAMES.contains(name); }
	static boolean cleanupPending() { return RETIRING.get() > 0; }
	CompletableFuture<JsonObject> execute(String name, JsonObject arguments) {
		if (closing) return CompletableFuture.completedFuture(OsJson.obj("schemaVersion", 1, "status", "rejected", "code", "os_closed"));
		try {
			var args = OsJson.object(OsJson.copy(arguments, 524_288, 24, 16_384));
			String action = OsJson.text(args, "action");
			if (runtime == null) {
				var client = MinecraftClient.getInstance();
				runtime = new EmbeddedOsRuntime(client.runDirectory.toPath().resolve("config/airicraft/os"), this::nativeCall);
			}
			CompletableFuture<?> result;
			switch (name) {
				case "os_runtime" -> {
					OsJson.keys(args, Set.of("action", "configuration"), Set.of("action"));
					result = switch (action) {
						case "start" -> runtime.start(args.has("configuration") ? OsJson.object(args.get("configuration")) : new JsonObject());
						case "status" -> runtime.status();
						case "stop" -> runtime.stopAll();
						default -> throw new IllegalArgumentException("unknown_os_action");
					};
				}
				case "os_library" -> {
					OsJson.keys(args, Set.of("action", "definition", "reason", "digest", "offset", "limit"), Set.of("action"));
					result = switch (action) {
						case "propose" -> runtime.propose(OsJson.object(args.get("definition")), OsJson.text(args, "reason"));
						case "validate" -> runtime.validate(OsJson.text(args, "digest"));
						case "get" -> runtime.definition(OsJson.text(args, "digest"));
						case "list" -> runtime.library((int) OsJson.number(args, "offset", 0), (int) OsJson.number(args, "limit", 32));
						case "bundled" -> runtime.bundled();
						default -> throw new IllegalArgumentException("unknown_library_action");
					};
				}
				case "os_skills" -> {
					OsJson.keys(args, Set.of("action", "digest", "input", "installationId", "installations"), Set.of("action"));
					result = switch (action) {
						case "install" -> runtime.install(OsJson.text(args, "digest"), args.get("input"));
						case "install_batch" -> runtime.installAll(args.getAsJsonArray("installations"));
						case "inspect" -> runtime.inspect(OsJson.text(args, "installationId"));
						case "stop" -> runtime.stop(OsJson.text(args, "installationId"));
						case "replace" -> runtime.replace(OsJson.text(args, "installationId"), OsJson.text(args, "digest"), args.get("input"));
						default -> throw new IllegalArgumentException("unknown_skill_action");
					};
				}
				default -> throw new IllegalArgumentException("unknown_os_method");
			}
			return result.handle((value, error) -> error == null ? OsJson.obj("schemaVersion", 1, "status", "ok", "value", value)
				: OsJson.obj("schemaVersion", 1, "status", "rejected", "code", OsJson.reason(error)));
		} catch (RuntimeException failure) { return CompletableFuture.completedFuture(OsJson.obj("schemaVersion", 1, "status", "rejected", "code", OsJson.reason(failure))); }
	}
	void stop() { if (runtime != null) runtime.stopAll(); }
	void close() {
		if (closing || runtime == null) return;
		closing = true; RETIRING.incrementAndGet();
		runtime.closeAsync().whenComplete((result, error) -> {
			// An unresolved close keeps ordinary agent actions fenced until process restart.
			if (error == null && OsJson.bool(result, "released", false)) RETIRING.decrementAndGet();
		});
	}
	private CompletableFuture<JsonObject> nativeCall(String method, JsonObject arguments) {
		if (nativeQueue.incrementAndGet() > 32) { nativeQueue.decrementAndGet(); return CompletableFuture.failedFuture(new IllegalStateException("native_queue_capacity")); }
		var result = new CompletableFuture<JsonObject>(); var copied = arguments.deepCopy();
		try {
			MinecraftClient.getInstance().execute(() -> {
				try {
					if (result.isDone()) return;
					if (closing) nativeDriver.tick();
					result.complete(nativeDriver.execute(method, copied));
				} catch (RuntimeException failure) { result.completeExceptionally(failure); }
				finally { nativeQueue.decrementAndGet(); }
			});
		} catch (RuntimeException failure) { nativeQueue.decrementAndGet(); result.completeExceptionally(failure); }
		return result;
	}
	static List<Map<String, Object>> tools() {
		var text = Map.<String, Object>of("type", "string"); var object = Map.<String, Object>of("type", "object");
		return List.of(
			tool("os_runtime", "Start, inspect or stop the Java Airicraft OS with embedded GraalJS. Configuration binds trusted operations and resources; bindOpenContainer binds the visible chest as chest/container:home. Native effects remain on the Minecraft client thread.", Map.of("action", text, "configuration", object)),
			tool("os_library", "Persistent skill library: propose, validate deterministic examples, get, list or import bundled references. Definitions include contracts, locked dependency digests, JavaScript source or pure worker configuration. Importing does not install or run a behavior.", Map.of("action", text, "definition", object, "reason", text, "digest", text, "offset", Map.of("type", "integer"), "limit", Map.of("type", "integer"))),
			tool("os_skills", "Install a validated skill by digest, install_batch, inspect, stop or replace an installation. Input is copied JSON. Replacements drain owned work before creating a fresh instance. The OS schedules independent installations.", Map.of("action", text, "digest", text, "input", Map.of(), "installationId", text, "installations", Map.of("type", "array", "items", object)))
		);
	}
	private static Map<String, Object> tool(String name, String description, Map<String, Object> properties) {
		return ai.moeru.airicraft.agent.llm.PlannerToolCatalog.toolForProvider(name, description, properties, List.of("action"));
	}
}
