package ai.moeru.airicraft.agent.recording;

import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared runtime evidence files for evaluation and automatic playtests. */
public final class RuntimeFlightRecorder {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private final Path outputDir;
	private Long latestEventSeqNo;
	private Long latestTimelineEntryId;
	private Long latestLlmSequenceId;
	private boolean eventsTruncated;
	private boolean timelineTruncated;
	private boolean llmCallsTruncated;

	public RuntimeFlightRecorder(Path outputDir) throws IOException {
		this.outputDir = outputDir.toAbsolutePath().normalize();
		Files.createDirectories(this.outputDir);
	}

	public void recordTick(EmbodiedAgentRuntime runtime) throws IOException {
		String collectedAt = Instant.now().toString();
		drainEvents(runtime, collectedAt);
		drainTimeline(runtime, collectedAt);
		drainLlmCalls(runtime, collectedAt);
	}

	public Map<String, Object> statusPayload() {
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("latestEventSeqNo", latestEventSeqNo);
		status.put("debugTimelineLatestEntryId", latestTimelineEntryId);
		status.put("llmCallsLatestSequenceId", latestLlmSequenceId);
		status.put("eventsTruncated", eventsTruncated);
		status.put("debugTimelineTruncated", timelineTruncated);
		status.put("llmCallsTruncated", llmCallsTruncated);
		return status;
	}

	public void writeFinalSnapshots(EmbodiedAgentRuntime runtime) throws IOException {
		runtime.finalizePlannerCallRecordsForEvaluation();
		writeJson(outputDir.resolve("agent-status-final.json"), Map.of(
			"available", true,
			"session", runtime.sessionSnapshot(),
			"task", runtime.taskSnapshot(),
			"taskExecution", runtime.taskExecutionSnapshot(),
			"missionExecution", runtime.missionExecutionSnapshot(),
			"activeJob", runtime.activeJob(),
			"degraded", runtime.isDegraded()
		));
		writeJson(outputDir.resolve("agent-events-final.json"), runtime.recentEvents(null));
		writeJson(outputDir.resolve("agent-debug-timeline-final.json"), runtime.debugTimeline(null));
		writeJson(outputDir.resolve("agent-debug-llm-calls-final.json"), runtime.llmFlightRecords(null));
		writeJsonlSnapshot(outputDir.resolve("planner-calls.jsonl"), runtime.plannerCallRecords());
		writeJson(outputDir.resolve("world-evidence-final.json"), runtime.currentWorldEvidence());
	}

	private void drainEvents(EmbodiedAgentRuntime runtime, String collectedAt) throws IOException {
		var result = runtime.recentEvents(latestEventSeqNo);
		latestEventSeqNo = result.latestSeqNo();
		eventsTruncated = eventsTruncated || result.truncated();
		for (var event : result.events()) {
			appendJsonl(outputDir.resolve("events.jsonl"), Map.of("collectedAt", collectedAt, "event", event));
		}
	}

	private void drainTimeline(EmbodiedAgentRuntime runtime, String collectedAt) throws IOException {
		var result = runtime.debugTimeline(latestTimelineEntryId);
		latestTimelineEntryId = result.latestEntryId();
		timelineTruncated = timelineTruncated || result.truncated();
		for (var entry : result.entries()) {
			appendJsonl(outputDir.resolve("debug-timeline.jsonl"), Map.of("collectedAt", collectedAt, "entry", entry));
		}
	}

	private void drainLlmCalls(EmbodiedAgentRuntime runtime, String collectedAt) throws IOException {
		var result = runtime.llmFlightRecords(latestLlmSequenceId);
		latestLlmSequenceId = result.latestSequenceId();
		llmCallsTruncated = llmCallsTruncated || result.truncated();
		for (var record : result.records()) {
			appendJsonl(outputDir.resolve("llm-calls.jsonl"), Map.of("collectedAt", collectedAt, "record", record));
		}
	}

	private static void appendJsonl(Path path, Object value) throws IOException {
		Files.writeString(
			path,
			GSON.toJson(value) + "\n",
			StandardOpenOption.CREATE,
			StandardOpenOption.APPEND
		);
	}

	private static void writeJson(Path path, Object value) throws IOException {
		Files.writeString(
			path,
			GSON.toJson(value) + "\n",
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING
		);
	}

	private static void writeJsonlSnapshot(Path path, List<?> values) throws IOException {
		StringBuilder content = new StringBuilder();
		for (Object value : values) {
			content.append(GSON.toJson(value)).append('\n');
		}
		Files.writeString(
			path,
			content,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING
		);
	}

}
