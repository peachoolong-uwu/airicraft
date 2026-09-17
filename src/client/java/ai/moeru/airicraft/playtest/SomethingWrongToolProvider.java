package ai.moeru.airicraft.playtest;

import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

public final class SomethingWrongToolProvider implements PlannerToolProvider {
	private final Function<String, String> report;
	private final Runnable resultCommitted;
	private final Executor clientExecutor;

	public SomethingWrongToolProvider(Function<String, String> report, Runnable resultCommitted, Executor clientExecutor) {
		this.report = report;
		this.resultCommitted = resultCommitted;
		this.clientExecutor = clientExecutor;
	}
	@Override public String id() { return "automatic_playtest"; }
	@Override public boolean handles(String name) { return "something_wrong".equals(name); }
	@Override public boolean isReadTool(String name) { return false; }
	@Override public boolean endsTurn(String name) { return handles(name); }
	@Override public void afterResultCommitted(String name) { resultCommitted.run(); }
	@Override public String promptInstructions() {
		return "This is an automatic playtest. Call something_wrong when observed behavior suggests a bug in Airicraft's tools, execution, observations or harness. Describe what you tried, expected behavior, actual behavior, and any useful work IDs in ordinary language. You do not need a root cause or certainty. Ordinary survival difficulty, missing materials, an unreachable target or your own mistaken plan alone are not interface bugs. A report pauses the game and preserves the recording for later review; do not continue gameplay after reporting.";
	}
	@Override public List<Map<String, Object>> openAiTools() {
		return List.of(toolForProvider("something_wrong", "Report a suspected Airicraft/tool/harness bug in natural language. Pauses this local playtest and saves its recording for offline review.",
			propertiesForProvider(propForProvider("description", stringForProvider("What seems wrong: attempted action, expected versus observed behavior, and relevant details. Up to 8192 characters."))), List.of("description")));
	}
	@Override public void validateArguments(String name, JsonObject args) {
		if (!handles(name) || args == null || args.size() != 1 || !args.has("description")
			|| !args.get("description").isJsonPrimitive() || !args.getAsJsonPrimitive("description").isString()
			|| args.get("description").getAsString().isBlank() || args.get("description").getAsString().length() > 8192)
			throw new JsonParseException("something_wrong requires one nonblank description, at most 8192 characters");
	}
	@Override public CompletableFuture<String> execute(PlannerToolCall call) {
		validateArguments(call.name(), call.arguments());
		return CompletableFuture.supplyAsync(() -> report.apply(call.arguments().get("description").getAsString()), clientExecutor);
	}
}
