package ai.moeru.airicraft.os;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Reference skill data packaged by Gradle. Importing the pack grants no installations. */
final class BundledSkills {
	private BundledSkills() {}
	static List<JsonObject> load() throws IOException {
		var definitions = new ArrayList<JsonObject>();
		for (String name : List.of("observe-inventory", "withdraw-wheat", "describe-fallback", "comment-structure", "describe-structure", "strategy-fallback", "interpret-strategy", "choose-strategy")) {
			try (var stream = BundledSkills.class.getResourceAsStream("/airicraft/os/skills/" + name + ".json")) {
				if (stream == null) throw new IOException("bundled_skill_missing:" + name);
				definitions.add(OsJson.parse(new String(stream.readNBytes(SkillDefinition.MAX_BYTES + 1), StandardCharsets.UTF_8), SkillDefinition.MAX_BYTES, 20, 8192).getAsJsonObject());
			}
		}
		return List.copyOf(definitions);
	}
	static CompletableFuture<JsonObject> importInto(EmbeddedOsRuntime runtime, List<JsonObject> definitions) {
		CompletableFuture<JsonObject> chain = CompletableFuture.completedFuture(new JsonObject());
		for (var template : definitions) chain = chain.thenCompose(aliases -> {
			var definition = template.deepCopy(); var dependencies = definition.getAsJsonObject("dependencies");
			for (var entry : List.copyOf(dependencies.entrySet())) {
				String name = entry.getValue().getAsString(); if (!name.startsWith("@") || !aliases.has(name.substring(1))) throw new IllegalStateException("bundled_dependency_missing");
				dependencies.add(entry.getKey(), aliases.get(name.substring(1)));
			}
			return runtime.propose(definition, "Bundled Java OS reference").thenCompose(digest -> runtime.definition(digest).thenCompose(record -> {
				var validation = OsJson.bool(record.getAsJsonObject("state"), "validated", false) ? CompletableFuture.completedFuture(OsJson.obj("passed", true)) : runtime.validate(digest);
				return validation.thenApply(evidence -> { if (!OsJson.bool(evidence, "passed", false)) throw new IllegalStateException("bundled_validation_failed:" + evidence); aliases.addProperty(OsJson.text(definition, "name"), digest); return aliases; });
			}));
		});
		return chain;
	}
}
