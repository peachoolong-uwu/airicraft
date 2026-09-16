package ai.moeru.airicraft.os;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Persistent immutable definitions, pinned closure resolution and deterministic validation. */
public final class SkillLibrary {
	private final Path directory;
	private final GraalSkillEngine engine;
	private final Set<String> validating = new java.util.HashSet<>();
	public SkillLibrary(Path directory, GraalSkillEngine engine) throws IOException {
		this.directory = directory.toAbsolutePath(); this.engine = engine; Files.createDirectories(directory);
	}
	public synchronized String propose(JsonElement value, String reason) throws IOException {
		var definition = new SkillDefinition(value);
		if (reason == null || reason.isBlank() || reason.length() > 4096) throw new IllegalArgumentException("revision_reason_required");
		Path path = path(definition.digest());
		if (!Files.exists(path.resolve("definition.json"))) {
			if (digests().size() >= 256) throw new IllegalStateException("library_capacity");
			OsStorage.write(path.resolve("definition.json"), definition.value(), SkillDefinition.MAX_BYTES);
		}
		if (!Files.exists(path.resolve("history.json"))) record(definition.digest(), "candidate", OsJson.obj("reason", reason));
		return definition.digest();
	}
	public synchronized SkillDefinition get(String digest) throws IOException {
		var definition = new SkillDefinition(OsStorage.read(path(digest).resolve("definition.json"), SkillDefinition.MAX_BYTES));
		if (!definition.digest().equals(digest)) throw new IOException("definition_integrity");
		return definition;
	}
	public synchronized Map<String, SkillDefinition> resolve(String digest, Set<String> grants, Map<String, String> environment) throws IOException {
		return resolve(digest, grants, environment, false);
	}
	private Map<String, SkillDefinition> resolve(String digest, Set<String> grants, Map<String, String> environment, boolean candidate) throws IOException {
		var result = new LinkedHashMap<String, SkillDefinition>();
		visit(digest, digest, grants, environment, candidate, result, new ArrayList<>());
		return Map.copyOf(result);
	}
	private void visit(String id, String root, Set<String> grants, Map<String, String> environment, boolean candidate,
		Map<String, SkillDefinition> resolved, List<String> stack) throws IOException {
		if (stack.contains(id)) throw new IllegalStateException("dependency_cycle");
		if (resolved.containsKey(id)) return;
		if (stack.size() >= 8 || stack.size() + resolved.size() >= 32) throw new IllegalStateException("dependency_capacity");
		var definition = get(id);
		if (!(candidate && id.equals(root)) && !OsJson.bool(state(id), "validated", false)) throw new IllegalStateException("definition_unvalidated");
		if (!grants.containsAll(definition.capabilities())) throw new IllegalStateException("capability_missing");
		for (var entry : definition.environment().entrySet()) if (!entry.getValue().equals(environment.get(entry.getKey()))) throw new IllegalStateException("environment_incompatible");
		stack.add(id);
		for (var dependency : definition.dependencies().values()) {
			visit(dependency, root, grants, environment, candidate, resolved, stack);
			if (!definition.capabilities().containsAll(resolved.get(dependency).capabilities())) throw new IllegalStateException("dependency_capability_undeclared");
		}
		if (definition.kind().equals("worker")) {
			var fallback = resolved.get(definition.dependencies().get(OsJson.text(definition.value(), "fallback")));
			if (fallback == null || !fallback.kind().equals("behavior") || !fallback.mode().equals("generator") ||
				!fallback.value().get("inputContract").equals(definition.value().get("inputContract")) || !fallback.value().get("outputContract").equals(definition.value().get("outputContract"))) throw new IllegalStateException("fallback_contract_mismatch");
		}
		stack.removeLast(); resolved.put(id, definition);
	}
	public JsonObject validate(String digest, Set<String> grants, Map<String, String> environment) throws IOException {
		synchronized (this) {
			if (!state(digest).getAsJsonObject("installations").isEmpty()) throw new IllegalStateException("definition_installed");
			if (!validating.add(digest)) throw new IllegalStateException("validation_busy");
		}
		var evidence = OsJson.obj("passed", false, "scope", "deterministic", "liveQualified", false, "inferenceQualified", false,
			"policy", GraalSkillEngine.POLICY, "javaVersion", System.getProperty("java.version"));
		try {
			var definition = resolve(digest, grants, environment, true).get(digest);
			if (definition.examples().isEmpty()) throw new IllegalStateException("examples_required");
			for (var example : definition.examples()) {
				var item = example.getAsJsonObject(); var input = definition.input().check(item.get("input"));
				if (definition.kind().equals("worker")) { definition.output().check(item.get("result")); continue; }
				try (var skill = engine.open(definition.source(), definition.mode(), input)) {
					var responses = item.getAsJsonArray("responses").deepCopy();
					if (definition.mode().equals("generator")) { var initial = new JsonArray(); initial.add(JsonNull.INSTANCE); responses.forEach(initial::add); responses = initial; }
					for (int step = 0; step < responses.size(); step++) {
						var result = (definition.mode().equals("offers") ? skill.offers(responses.get(step)) : skill.resume(responses.get(step))).get(15, TimeUnit.SECONDS);
						SkillEffects.result(result, definition.mode());
						if (!result.equals(item.getAsJsonArray("expected").get(step))) throw new IllegalStateException("example_mismatch");
						if (definition.mode().equals("generator") && result.getAsJsonObject().get("done").getAsBoolean()) {
							if (step != responses.size() - 1) throw new IllegalStateException("example_completed_early");
							definition.output().check(result.getAsJsonObject().get("value"));
						}
					}
				}
			}
			evidence.addProperty("passed", true);
		} catch (Exception failure) { evidence.addProperty("reason", OsJson.reason(failure)); }
		synchronized (this) { try { record(digest, "validation", evidence); } finally { validating.remove(digest); } }
		return evidence;
	}
	public synchronized JsonObject list(int offset, int limit) throws IOException {
		if (offset < 0 || limit < 1 || limit > 32) throw new IllegalArgumentException("invalid_library_page");
		var ids = digests(); var items = new JsonArray();
		for (int index = offset; index < Math.min(ids.size(), offset + limit); index++) {
			var definition = get(ids.get(index)); var state = state(definition.digest());
			items.add(OsJson.obj("digest", definition.digest(), "name", definition.name(), "kind", definition.kind(), "validated", state.get("validated"), "installations", state.get("installations")));
		}
		return OsJson.obj("items", items, "nextOffset", offset + limit < ids.size() ? offset + limit : null);
	}
	public synchronized void installed(String digest, String id, JsonObject binding) throws IOException {
		if (validating.contains(digest)) throw new IllegalStateException("validation_busy");
		var state = state(digest);
		if (!OsJson.bool(state, "validated", false)) throw new IllegalStateException("definition_unvalidated");
		if (state.getAsJsonObject("installations").has(id)) throw new IllegalStateException("installation_reused");
		if (history(digest).size() + state.getAsJsonObject("installations").size() + 2 > 128) throw new IllegalStateException("library_record_capacity");
		record(digest, "installed", OsJson.obj("id", id, "binding", binding));
	}
	public synchronized void retired(String digest, String id, JsonObject outcome) throws IOException {
		if (!state(digest).getAsJsonObject("installations").has(id)) throw new IllegalStateException("installation_unknown");
		if (!OsJson.bool(outcome, "released", false)) throw new IllegalArgumentException("retirement_unreleased");
		record(digest, "retired", OsJson.obj("id", id, "outcome", outcome));
	}
	public synchronized JsonObject state(String digest) throws IOException {
		var active = new JsonObject(); boolean validated = false;
		for (var item : history(digest)) {
			var record = item.getAsJsonObject(); var event = record.getAsJsonObject("event");
			switch (OsJson.text(record, "type")) {
				case "validation" -> validated = OsJson.bool(event, "passed", false) && GraalSkillEngine.POLICY.equals(OsJson.string(event, "policy", ""));
				case "installed" -> active.add(OsJson.text(event, "id"), event.get("binding"));
				case "retired" -> active.remove(OsJson.text(event, "id"));
				case "candidate" -> { }
				default -> throw new IOException("library_history_invalid");
			}
		}
		return OsJson.obj("validated", validated, "installations", active);
	}
	private JsonArray history(String digest) throws IOException {
		Path file = path(digest).resolve("history.json");
		if (!Files.exists(file)) return new JsonArray();
		var history = OsStorage.read(file, 4_194_304).getAsJsonArray();
		if (history.size() > 128) throw new IOException("library_history_limit");
		String previous = "";
		for (var item : history) {
			var record = item.getAsJsonObject().deepCopy(); String signature = OsJson.text(record, "digest"); record.remove("digest");
			if (!OsJson.string(record, "previous", "").equals(previous) || !OsJson.digest(record).equals(signature)) throw new IOException("library_history_integrity");
			previous = signature;
		}
		return history;
	}
	private void record(String digest, String type, JsonObject event) throws IOException {
		var records = history(digest);
		if (records.size() >= 128) throw new IllegalStateException("library_record_capacity");
		String previous = records.isEmpty() ? "" : records.get(records.size() - 1).getAsJsonObject().get("digest").getAsString();
		var record = OsJson.obj("previous", previous.isEmpty() ? null : previous, "type", type, "event", OsJson.copy(event, 32_768, 20, 4096), "atMillis", System.currentTimeMillis());
		record.addProperty("digest", OsJson.digest(record)); records.add(record);
		OsStorage.write(path(digest).resolve("history.json"), records, 4_194_304);
	}
	private List<String> digests() throws IOException {
		try (var paths = Files.list(directory)) {
			var result = paths.filter(Files::isDirectory).map(path -> path.getFileName().toString()).filter(name -> name.matches("[0-9a-f]{64}")).map(name -> "sha256:" + name).sorted().limit(257).toList();
			if (result.size() > 256) throw new IOException("library_capacity");
			return result;
		}
	}
	private Path path(String digest) {
		if (!SkillDefinition.isDigest(digest)) throw new IllegalArgumentException("invalid_definition_digest");
		return directory.resolve(digest.substring(7));
	}
}
