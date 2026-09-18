package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/** Build-matched API help; independent of world state and foreground work. */
public final class PolicyDocsToolProvider implements PlannerToolProvider {
	private static final String DOCUMENTATION = readResource("/airicraft/policies/api.md");
	@Override public String id() { return "policy_docs"; }
	@Override public boolean handles(String name) { return name.equals("read_policy_docs"); }
	@Override public List<Map<String, Object>> openAiTools() {
		return List.of(toolForProvider("read_policy_docs", "Read this build's run_policy and query_world API, limits and debugging guide. "
			+ "Use when unsure about a method or after a JavaScript failure. No world or open container required.",
			propertiesForProvider(), List.of()));
	}
	@Override public void validateArguments(String name, JsonObject args) {
		if (!handles(name) || !args.isEmpty()) throw new IllegalArgumentException("read_policy_docs_takes_no_arguments");
	}
	@Override public CompletableFuture<String> execute(PlannerToolCall call) { return CompletableFuture.completedFuture(DOCUMENTATION); }

	static String readResource(String path) {
		try (var stream = PolicyDocsToolProvider.class.getResourceAsStream(path)) {
			if (stream == null) throw new IllegalStateException("Missing policy resource: " + path);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new UncheckedIOException("Cannot read policy resource: " + path, error);
		}
	}
}
