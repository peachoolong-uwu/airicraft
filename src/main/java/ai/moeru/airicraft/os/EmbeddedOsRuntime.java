package ai.moeru.airicraft.os;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The in-mod OS: one Java actor owns policy; Graal instances only evaluate skill code. */
public final class EmbeddedOsRuntime implements AutoCloseable {
	private static final Set<String> REJECTIONS = Set.of("operation_unknown", "invalid_work_context", "work_capacity", "resource_unknown", "supply_method_unavailable",
		"invalid_target", "invalid_demand", "target_capacity", "demand_capacity", "invocation_demand_capacity", "stale_observation", "observation_scope_not_granted",
		"invalid_condition", "condition_limit", "invalid_deadline", "invalid_deadline_clock", "invalid_wait_cursor", "invalid_observation_path", "invalid_child_handle",
		"already_joined", "dependency_not_declared", "capability_escalation", "capability_missing", "contract_mismatch", "child_result_capacity", "live_invocation_capacity",
		"invocation_depth", "worker_definition_required", "worker_effects_forbidden", "worker_evidence_unavailable", "worker_failure_fingerprint_missing");
	private final Path directory;
	private final NativeAccess access;
	private final ScheduledExecutorService actor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("airicraft-os").factory());
	private final ExecutorService validator = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("airicraft-os-validation").factory());
	private final AtomicInteger queued = new AtomicInteger();
	private final AtomicInteger validations = new AtomicInteger();
	private final InvocationTree tree = new InvocationTree();
	private final ResourceLedger ledger = new ResourceLedger();
	private final Map<String, Installation> installations = new LinkedHashMap<>();
	private final Map<String, Session> sessions = new LinkedHashMap<>();
	private GraalSkillEngine engine;
	private SkillLibrary library;
	private EffectJournal journal;
	private DecisionTrace trace;
	private FileChannel lockChannel;
	private FileLock lock;
	private NativeEffects effects;
	private OsConfiguration config;
	private WorkService work;
	private InferenceWorkers workers;
	private boolean started, stopping;
	private volatile boolean closed;
	private String fault, worldId, lastWorkState;
	private CompletableFuture<JsonObject> closeResult;
	private long closeDeadline;
	private int sessionCursor;
	public EmbeddedOsRuntime(Path directory, NativeAccess access) {
		this.directory = directory.toAbsolutePath(); this.access = access;
		actor.scheduleWithFixedDelay(this::step, 50, 50, TimeUnit.MILLISECONDS);
	}
	public CompletableFuture<JsonObject> start(JsonObject configuration) {
		var copied = OsJson.object(OsJson.copy(configuration));
		return command(() -> {
			if (started) throw new IllegalStateException("os_already_started");
			initialize();
			var observed = access.call("os_observe", new JsonObject()).get(3, TimeUnit.SECONDS);
			if (!OsJson.text(observed, "status").equals("ok")) throw new IllegalStateException("world_not_ready");
			var frame = observed.getAsJsonObject("frame"); worldId = OsJson.text(frame.getAsJsonObject("world"), "worldId");
			config = new OsConfiguration(copied, frame);
			effects = new NativeEffects(access, journal, worldId, EmbeddedOsRuntime::millis);
			try {
				effects.start();
				var views = new NativeViews(config, frame, EmbeddedOsRuntime::millis);
				work = new WorkService(tree, ledger, effects, config, views, EmbeddedOsRuntime::millis); work.refresh();
				workers = new InferenceWorkers(engine, config.workerProfiles(), EmbeddedOsRuntime::millis);
				started = true; stopping = false; fault = null;
				restore();
				trace.record("runtime.started", OsJson.obj("worldId", worldId, "policy", GraalSkillEngine.POLICY, "grants", config.grants));
				return snapshot();
			} catch (Exception error) {
				fault = OsJson.reason(error);
				if (started) requestStop(fault);
				else { effects.close(); if (workers != null) workers.close(); }
				throw error;
			}
		});
	}
	public CompletableFuture<String> propose(JsonObject definition, String reason) {
		var value = new SkillDefinition(definition).value();
		return command(() -> { initialize(); return library.propose(value, reason); });
	}
	public CompletableFuture<JsonObject> validate(String digest) {
		return command(() -> {
			initialize(); Set<String> grants = library.get(digest).capabilities(); Map<String, String> environment = config == null ? Map.of("minecraft", "1.21.8", "host", "mod-java") : config.environment;
			if (validations.incrementAndGet() > 16) { validations.decrementAndGet(); throw new IllegalStateException("validation_capacity"); }
			try {
				return CompletableFuture.supplyAsync(() -> {
					try { return library.validate(digest, grants, environment); }
					catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
					finally { validations.decrementAndGet(); }
				}, validator);
			} catch (RuntimeException failure) { validations.decrementAndGet(); throw failure; }
		}).thenCompose(value -> value);
	}
	public CompletableFuture<JsonObject> library(int offset, int limit) { return command(() -> { initialize(); return library.list(offset, limit); }); }
	public CompletableFuture<JsonObject> definition(String digest) { return command(() -> { initialize(); return OsJson.obj("digest", digest, "definition", library.get(digest).value(), "state", library.state(digest)); }); }
	public CompletableFuture<JsonObject> bundled() { return command(BundledSkills::load).thenCompose(definitions -> BundledSkills.importInto(this, definitions)); }
	public CompletableFuture<JsonObject> install(String digest, JsonElement input) {
		var copy = OsJson.copy(input); return command(() -> { requireRunning(); var installed = installNow(digest, copy); persist(); return describe(installed); });
	}
	public CompletableFuture<JsonObject> installAll(JsonArray values) {
		var copied = OsJson.copy(values).getAsJsonArray();
		return command(() -> {
			requireRunning(); if (copied.isEmpty() || tree.roots().size() + copied.size() > 12) throw new IllegalArgumentException("root_capacity");
			for (var value : copied) { var item = value.getAsJsonObject(); String digest = OsJson.text(item, "digest"); var definition = library.resolve(digest, config.grants, config.environment).get(digest); definition.input().check(item.get("input")); }
			var result = new JsonArray();
			for (var value : copied) { var item = value.getAsJsonObject(); result.add(describe(installNow(OsJson.text(item, "digest"), item.get("input")))); }
			persist(); return OsJson.obj("installations", result);
		});
	}
	public CompletableFuture<JsonObject> replace(String id, String digest, JsonElement input) {
		var copy = input == null ? null : OsJson.copy(input);
		return command(() -> {
			requireRunning(); var record = installation(id);
			if (!record.phase.equals("installed")) throw new IllegalStateException("installation_stopping");
			var closure = library.resolve(digest, config.grants, config.environment); var next = closure.get(digest);
			if (!next.kind().equals("behavior")) throw new IllegalArgumentException("installed_root_requires_behavior");
			record.replacement = digest; record.replacementInput = next.input().check(copy == null ? record.input : copy);
			tree.cancel(record.root, "definition_replaced"); record.phase = "draining"; persist(); return describe(record);
		});
	}
	public CompletableFuture<JsonObject> stop(String id) {
		return command(() -> { var record = installation(id); record.replacement = null; if (tree.contains(record.root)) tree.cancel(record.root, "installation_stopped"); record.phase = record.phase.equals("retired") ? "retired" : "draining"; persist(); return describe(record); });
	}
	public CompletableFuture<JsonObject> stopAll() { return command(() -> { requestStop("operator_stopped"); return snapshot(); }); }
	public CompletableFuture<JsonObject> inspect(String id) { return command(() -> describe(installation(id))); }
	public CompletableFuture<JsonObject> status() { return command(this::snapshot); }
	public CompletableFuture<JsonObject> closeAsync() {
		synchronized (this) {
			if (closeResult != null) return closeResult;
			closeResult = new CompletableFuture<>();
		}
		command(() -> { requestStop("runtime_closed"); closeDeadline = millis() + 5000; return null; }).exceptionally(error -> { closeResult.completeExceptionally(error); return null; });
		return closeResult;
	}
	private void initialize() throws IOException {
		if (library != null) return;
		Files.createDirectories(directory);
		try {
			lockChannel = FileChannel.open(directory.resolve("runtime.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			try { lock = lockChannel.tryLock(); } catch (java.nio.channels.OverlappingFileLockException unavailable) { throw new IOException("runtime_directory_owned", unavailable); }
			if (lock == null) throw new IOException("runtime_directory_owned");
			engine = new GraalSkillEngine(); journal = new EffectJournal(directory);
			trace = new DecisionTrace(directory.resolve("trace"));
			library = new SkillLibrary(directory.resolve("library"), engine);
		} catch (IOException | RuntimeException failure) {
			closeResource(trace); closeResource(engine); closeResource(lock); closeResource(lockChannel);
			trace = null; engine = null; journal = null; library = null; lock = null; lockChannel = null;
			throw failure;
		}
	}
	private Installation installNow(String digest, JsonElement input) throws Exception {
		var closure = library.resolve(digest, config.grants, config.environment); var definition = closure.get(digest);
		if (!definition.kind().equals("behavior")) throw new IllegalArgumentException("installed_root_requires_behavior");
		input = definition.input().check(input);
		String id = UUID.randomUUID().toString(); String root = tree.install(digest, definition.capabilities(), definition.output(), false);
		var record = new Installation(id, digest, root, input, closure); installations.put(id, record);
		try {
			library.installed(digest, id, OsJson.obj("rootId", root, "inputDigest", OsJson.digest(input), "grants", definition.capabilities(), "policy", GraalSkillEngine.POLICY));
			record.recorded = true; sessions.put(root, new Session(root, record, definition, engine.open(definition.source(), definition.mode(), input)));
			record.phase = "installed"; trace.record("skill.installed", describe(record)); return record;
		} catch (Exception error) { tree.fail(root, OsJson.reason(error)); record.phase = "draining"; throw error; }
	}
	private void step() {
		if (closed) return;
		try {
			if (started) {
				if (stopping) {
					sweep();
					boolean released = work.stopped(); retire();
					if (released && tree.roots().isEmpty()) { started = false; if (workers != null) workers.close(); }
				} else {
					work.tick(authorsPending());
					sweep();
					var turns = List.copyOf(sessions.values());
					int budget = Math.min(16, turns.size());
					for (int index = 0; index < budget; index++) {
						var session = turns.get((sessionCursor + index) % turns.size());
						if (!tree.running(session.id)) continue;
						try { advance(session); }
						catch (Exception failure) {
							String reason = OsJson.reason(failure);
							if (session.definition.mode().equals("generator") && tree.running(session.id) && REJECTIONS.contains(reason)) ready(session, OsJson.obj("status", "rejected", "reason", reason));
							else { tree.fail(session.id, reason); trace.record("skill.failed", OsJson.obj("invocation", session.id, "reason", reason)); }
						}
					}
					if (!turns.isEmpty()) sessionCursor = (sessionCursor + budget) % turns.size();
					retire();
					String state = OsJson.canonical(work.state());
					if (!state.equals(lastWorkState)) { trace.record("work.state", work.state()); lastWorkState = state; }
				}
			}
			if (closeResult != null && (!started || closeDeadline != 0 && millis() >= closeDeadline)) dispose();
		} catch (Exception failure) {
			fault = OsJson.reason(failure);
			try { requestStop(fault); } catch (Exception ignored) { stopping = true; }
			if (closeResult != null && closeDeadline != 0 && millis() >= closeDeadline) dispose();
		}
	}
	private boolean authorsPending() {
		return sessions.values().stream().anyMatch(session -> tree.running(session.id) && (session.future != null || session.phase.equals("ready") || session.definition.mode().equals("offers") && session.offerGeneration != work.generation()));
	}
	private void advance(Session session) throws Exception {
		if (session.future != null) {
			if (!session.future.isDone()) return;
			var value = session.future.join(); session.future = null; SkillEffects.result(value, session.definition.mode());
			if (session.definition.mode().equals("offers")) { work.offers(session.id, value.getAsJsonArray(), session.offerGeneration, session.offerCapture); session.phase = "offers"; }
			else {
				var result = value.getAsJsonObject();
				if (OsJson.bool(result, "done", false)) { tree.returned(session.id, result.get("value")); return; }
				dispatch(session, SkillEffects.effect(result.get("value")));
			}
		}
		if (!tree.running(session.id)) return;
		if (session.phase.equals("waiting")) { readyIfCompleted(session, work.views.poll(session.wait)); }
		else if (session.phase.equals("working")) readyIfCompleted(session, work.take(session.id, session.workId));
		else if (session.phase.equals("delivery")) readyIfCompleted(session, work.takeDemand(session.id, session.demand));
		else if (session.phase.equals("joining")) readyIfCompleted(session, tree.join(session.id, session.join));
		else if (session.phase.equals("worker")) {
			var basis = work.views.workerEvidence(session.definition.capabilities());
			boolean stale = basis == null || !Objects.equals(session.workerBasis, basis.signature());
			if (!stale) session.worker.progress(basis.clock(), basis.ticks());
			if (stale || session.worker.result().isDone()) {
				var result = stale ? OsJson.obj("status", "stale", "reason", "worker_evidence_changed") : session.worker.result().join();
				session.worker.close(); session.worker = null;
				trace.record("worker.completed", OsJson.obj("invocation", session.id, "basis", session.workerBasis, "result", result));
				if (tree.running(session.workerChild.id())) tree.returned(session.workerChild.id(), result);
				var outcome = tree.join(session.id, session.workerChild); ready(session, outcome.get("value"));
			}
		}
		if (session.phase.equals("ready")) { session.future = session.guest.resume(session.response); session.response = null; session.phase = "evaluating"; }
		else if (session.definition.mode().equals("offers") && work.views.current()) {
			String capture = work.capture(); long generation = work.generation();
			if (session.offerGeneration != generation || !Objects.equals(session.offerCapture, capture)) {
				session.offerGeneration = generation; session.offerCapture = capture;
				session.future = session.guest.offers(work.views.grantedView(session.definition.capabilities())); session.phase = "evaluating";
			}
		}
	}
	private void dispatch(Session session, JsonObject effect) throws Exception {
		String kind = OsJson.text(effect, "kind");
		trace.record("skill.effect", OsJson.obj("invocation", session.id, "kind", kind, "declaration", effect));
		switch (kind) {
			case "observe" -> ready(session, work.views.observe(effect.getAsJsonObject("query"), session.definition.capabilities()));
			case "wait" -> { session.wait = work.views.waitFor(effect.get("condition"), effect.getAsJsonObject("options"), session.definition.capabilities()); session.phase = "waiting"; }
			case "work" -> { session.workId = work.request(session.id, effect); session.phase = "working"; }
			case "target" -> ready(session, work.target(session.id, OsJson.text(effect, "resource"), OsConfiguration.integer(effect, "quantity", 0, Integer.MAX_VALUE)));
			case "demand" -> { session.demand = work.demand(session.id, OsJson.text(effect, "resource"), OsConfiguration.integer(effect, "quantity", 0, Integer.MAX_VALUE), SkillDefinition.strings(effect.get("methods"), 32)); session.phase = "delivery"; }
			case "spawn" -> {
				var child = dependency(session, OsJson.text(effect, "definition")); if (!child.kind().equals("behavior")) throw new IllegalArgumentException("dependency_requires_worker_service");
				var options = effect.getAsJsonObject("options"); OsJson.keys(options, Set.of("grants", "failurePolicy"), Set.of());
				if (options.has("grants") && !SkillDefinition.strings(options.get("grants"), 64).equals(child.capabilities())) throw new IllegalArgumentException("capability_escalation");
				String policy = OsJson.string(options, "failurePolicy", "cancel_siblings"); if (!Set.of("cancel_siblings", "collect_all").contains(policy)) throw new IllegalArgumentException("invalid_failure_policy");
				var input = child.input().check(effect.get("input")); var handle = tree.spawn(session.id, child.digest(), child.capabilities(), child.output(), policy.equals("collect_all"));
				sessions.put(handle.id(), new Session(handle.id(), session.installation, child, engine.open(child.source(), child.mode(), input))); ready(session, OsJson.json(handle));
			}
			case "join" -> {
				var handle = effect.getAsJsonObject("handle"); OsJson.keys(handle, Set.of("id", "parentId", "sequence"), Set.of("id", "parentId", "sequence"));
				session.join = new InvocationTree.Handle(OsJson.text(handle, "id"), OsJson.text(handle, "parentId"), OsJson.number(handle, "sequence", 0)); session.phase = "joining";
			}
			case "worker" -> {
				var definition = dependency(session, OsJson.text(effect, "definition"));
				if (!definition.kind().equals("worker")) throw new IllegalArgumentException("worker_definition_required");
				var fallback = session.installation.closure.get(definition.dependencies().get(OsJson.text(definition.value(), "fallback")));
				var basis = work.views.workerEvidence(session.definition.capabilities());
				if (basis == null) throw new IllegalStateException("worker_evidence_unavailable");
				session.workerBasis = basis.signature();
				session.workerChild = tree.spawn(session.id, definition.digest(), Set.of(), new JsonContract(OsJson.json(true)), true);
				session.worker = workers.request(definition, fallback, effect.get("input"), work.views.epoch(), session.workerBasis, basis.clock(), basis.ticks()); session.phase = "worker";
			}
			default -> throw new IllegalArgumentException("effect_unknown");
		}
	}
	private SkillDefinition dependency(Session session, String alias) {
		String digest = session.definition.dependencies().get(alias); if (digest == null) throw new IllegalArgumentException("dependency_not_declared"); return session.installation.closure.get(digest);
	}
	private void readyIfCompleted(Session session, JsonObject value) throws IOException { if (!OsJson.text(value, "status").equals("pending")) ready(session, value); }
	private void ready(Session session, JsonElement value) throws IOException {
		try { session.response = OsJson.copy(value); } catch (IllegalArgumentException limit) { session.response = OsJson.obj("status", "rejected", "reason", "effect_response_limit"); }
		trace.record("skill.response", OsJson.obj("invocation", session.id, "value", session.response));
		session.phase = "ready";
	}
	private void sweep() {
		for (var session : List.copyOf(sessions.values())) if (!tree.running(session.id)) {
			if (session.worker != null) session.worker.close(); session.guest.close(); sessions.remove(session.id);
		}
	}
	private void retire() throws Exception {
		for (var record : List.copyOf(installations.values())) {
			if (record.phase.equals("retired") || !tree.contains(record.root) || tree.outcome(record.root) == null) continue;
			boolean otherLive = tree.roots().stream().anyMatch(root -> !root.equals(record.root) && tree.outcome(root) == null);
			if (!otherLive && effects.unresolved()) continue;
			record.outcome = tree.outcome(record.root); record.outcome.addProperty("released", true);
			if (record.recorded) library.retired(record.digest, record.id, record.outcome);
			record.phase = "retired"; tree.retire(record.root); trace.record("skill.retired", describe(record), true);
			if (record.replacement != null && !stopping) { var replacement = installNow(record.replacement, record.replacementInput); record.replacedBy = replacement.id; record.replacement = null; }
			persist();
		}
		sweep();
		while (installations.size() > 128) {
			String oldest = installations.values().stream().filter(record -> record.phase.equals("retired")).map(record -> record.id).findFirst().orElseThrow(); installations.remove(oldest);
		}
	}
	private void requestStop(String reason) throws IOException {
		stopping = true;
		for (var record : installations.values()) if (!record.phase.equals("retired")) { record.replacement = null; if (tree.contains(record.root)) tree.cancel(record.root, reason); record.phase = "draining"; }
		if (library != null) persist();
	}
	private void persist() throws IOException {
		var desired = new JsonArray();
		for (var record : installations.values()) {
			if (record.phase.equals("installed")) desired.add(OsJson.obj("id", record.id, "digest", record.digest, "input", record.input));
			else if (record.replacement != null) desired.add(OsJson.obj("id", record.id, "digest", record.digest, "input", record.input, "replacement", record.replacement, "replacementInput", record.replacementInput));
		}
		try { OsStorage.write(directory.resolve("installations.json"), OsJson.obj("worldId", worldId, "installations", desired), 524_288); }
		catch (IOException failure) {
			fault = OsJson.reason(failure); stopping = true;
			for (String root : tree.roots()) tree.cancel(root, "persistence_failed");
			throw failure;
		}
	}
	private void restore() throws Exception {
		Path path = directory.resolve("installations.json"); if (!Files.exists(path)) return;
		var saved = OsStorage.read(path, 524_288).getAsJsonObject(); var desired = saved.getAsJsonArray("installations");
		if (desired.isEmpty()) return;
		if (!Objects.equals(worldId, OsJson.string(saved, "worldId", null))) throw new IllegalStateException("installation_world_mismatch");
		if (desired.size() > 12) throw new IllegalStateException("root_capacity");
		for (var value : desired) {
			var item = value.getAsJsonObject(); String digest = OsJson.text(item, "digest"), oldId = OsJson.text(item, "id");
			if (library.state(digest).getAsJsonObject("installations").has(oldId)) library.retired(digest, oldId, OsJson.obj("status", "cancelled", "reason", "runtime_restarted", "released", true));
			installNow(OsJson.string(item, "replacement", digest), item.has("replacement") ? item.get("replacementInput") : item.get("input"));
		}
		persist();
	}
	private JsonObject snapshot() {
		var records = new JsonArray(); installations.values().stream().skip(Math.max(0, installations.size() - 32)).forEach(record -> records.add(describe(record)));
		return OsJson.obj("schemaVersion", 1, "host", "java-mod", "engine", "GraalJS", "policy", GraalSkillEngine.POLICY, "started", started, "stopping", stopping, "fault", fault,
			"worldId", worldId, "liveInvocations", sessions.size(), "installations", records, "native", effects == null ? OsJson.obj("unresolved", false) : effects.state(),
			"work", work == null ? null : work.state(), "workers", workers == null ? null : workers.state(), "libraryPath", directory.resolve("library").toString());
	}
	private JsonObject describe(Installation record) {
		return OsJson.obj("installationId", record.id, "digest", record.digest, "name", record.closure.get(record.digest).name(), "rootId", record.root, "phase", record.phase,
			"outcome", record.outcome == null && tree.contains(record.root) ? tree.outcome(record.root) : record.outcome, "replacedBy", record.replacedBy);
	}
	private Installation installation(String id) { var record = installations.get(id); if (record == null) throw new IllegalArgumentException("installation_unknown"); return record; }
	private void requireRunning() { if (!started || stopping || fault != null) throw new IllegalStateException("os_not_running"); }
	private <T> CompletableFuture<T> command(Callable<T> operation) {
		if (closed) return CompletableFuture.failedFuture(new IllegalStateException("os_closed"));
		if (queued.incrementAndGet() > 64) { queued.decrementAndGet(); return CompletableFuture.failedFuture(new IllegalStateException("os_command_capacity")); }
		var result = new CompletableFuture<T>();
		try { actor.execute(() -> { try { if (closed) throw new IllegalStateException("os_closed"); result.complete(operation.call()); } catch (Exception error) { result.completeExceptionally(error); } finally { queued.decrementAndGet(); } }); }
		catch (RuntimeException failure) { queued.decrementAndGet(); result.completeExceptionally(failure); }
		return result;
	}
	private void dispose() {
		closed = true;
		validator.shutdownNow();
		for (var session : sessions.values()) closeResource(session.guest);
		sessions.clear(); closeResource(workers); closeResource(effects); closeResource(engine); closeResource(trace);
		closeResource(lock); closeResource(lockChannel);
		boolean released = effects == null || !effects.unresolved() && effects.state().get("lease").isJsonNull();
		actor.shutdown();
		if (closeResult != null) closeResult.complete(OsJson.obj("released", released, "fault", fault));
	}
	private void closeResource(AutoCloseable resource) {
		if (resource == null) return;
		try { resource.close(); } catch (Exception failure) { fault = OsJson.reason(failure); }
	}
	@Override public void close() { try { closeAsync().get(10, TimeUnit.SECONDS); } catch (Exception ignored) { /* The mod's native fence and durable journal retain unresolved effects. */ } }
	private static long millis() { return System.nanoTime() / 1_000_000; }
	private static final class Installation {
		final String id, digest, root; final JsonElement input; final Map<String, SkillDefinition> closure;
		String phase = "preparing", replacement, replacedBy; JsonElement replacementInput; JsonObject outcome; boolean recorded;
		Installation(String id, String digest, String root, JsonElement input, Map<String, SkillDefinition> closure) { this.id = id; this.digest = digest; this.root = root; this.input = OsJson.copy(input); this.closure = closure; }
	}
	private static final class Session {
		final String id; final Installation installation; final SkillDefinition definition; final GraalSkillEngine.Instance guest;
		CompletableFuture<JsonElement> future; JsonElement response = JsonNull.INSTANCE; String phase, workId, offerCapture, workerBasis;
		long demand, offerGeneration = -1; ObservationIndex.Wait wait; InvocationTree.Handle join, workerChild; InferenceWorkers.Call worker;
		Session(String id, Installation installation, SkillDefinition definition, GraalSkillEngine.Instance guest) { this.id = id; this.installation = installation; this.definition = definition; this.guest = guest; phase = definition.mode().equals("offers") ? "offers" : "ready"; }
	}
}
