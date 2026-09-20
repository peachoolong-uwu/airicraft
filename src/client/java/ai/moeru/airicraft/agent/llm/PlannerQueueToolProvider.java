package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

public final class PlannerQueueToolProvider implements PlannerToolProvider {
	public static final String CONTINUE = "continue", CLEAR = "clear_queue";
	private final PlannerActionToolExecutor executor;
	public PlannerQueueToolProvider(PlannerActionToolExecutor executor) { this.executor = executor; }
	public String id() { return "tool_queue"; }
	public boolean isReadTool(String name) { return CONTINUE.equals(name); }
	public boolean handles(String name) { return CONTINUE.equals(name) || CLEAR.equals(name); }
	public List<Map<String, Object>> openAiTools() {
		return List.of(toolForProvider(CONTINUE, "Skip this decision turn. Already queued tools continue automatically, even while you think.", propertiesForProvider(), List.of()),
			toolForProvider(CLEAR, "Immediately discard pending tool calls and abort current running work. New calls in this response form a replacement plan. Completed effects are not undone.", propertiesForProvider(), List.of()));
	}
	public void validateArguments(String name, JsonObject arguments) {
		if (!arguments.isEmpty()) throw new IllegalArgumentException(name + " takes no arguments");
	}
	public CompletableFuture<String> execute(PlannerToolCall call) {
		validateArguments(call.name(), call.arguments());
		return CLEAR.equals(call.name()) ? executor.execute(call) : CompletableFuture.completedFuture("Turn skipped; queue continues.");
	}
	public String promptInstructions() {
		return "Plan ahead by returning multiple tool calls in intended order. Calls append to a FIFO and execute sequentially, "
			+ "waiting for actual work completion. Execution continues while you think; quick results are coalesced into one review. "
			+ "TOOL QUEUE shows active and pending calls at request time; it may advance before your reply. "
			+ "Use continue to skip a turn, or clear_queue to discard pending calls AND abort active work before a replacement plan. "
			+ "New calls append behind existing pending calls. Only queue calls whose arguments are already known; do not invent outputs of earlier queries.";
	}
}
