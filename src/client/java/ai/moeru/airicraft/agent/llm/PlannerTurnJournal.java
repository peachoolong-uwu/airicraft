package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonElement;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PlannerTurnJournal {
	private final Clock clock;
	private final int maxEvents;
	private final ArrayList<PlannerTurnEvent> events = new ArrayList<>();
	private final Set<Long> supersededGenerations = new HashSet<>();
	private long nextEventId = 1L;

	public PlannerTurnJournal(Clock clock, int maxEvents) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.maxEvents = Math.max(32, maxEvents);
	}

	public synchronized void recordSubmission(
		long generation,
		int attempt,
		PlannerSessionPhase phase,
		PlannerRequest request,
		LlmConversation conversation
	) {
		append(new PlannerTurnEvent(
			nextEventId++,
			PlannerTurnEvent.Kind.SUBMISSION,
			clock.millis(),
			generation,
			attempt,
			phaseName(phase),
			request,
			conversation,
			null,
			null,
			null,
			null,
			request == null ? -1L : request.tick(),
			false
		));
	}

	public synchronized void recordDebugCard(PlannerConversationDebugMessage message) {
		if (message == null) {
			return;
		}
		append(new PlannerTurnEvent(
			nextEventId++,
			PlannerTurnEvent.Kind.DEBUG_CARD,
			clock.millis(),
			message.generation(),
			message.attempt(),
			message.phase(),
			null,
			null,
			message,
			null,
			null,
			null,
			-1L,
			message.hasImageAttachment()
		));
	}

	public synchronized void recordToolExchange(
		long generation,
		PlannerContextSnapshot snapshot,
		JsonElement assistantRawContent,
		PlannerToolCall toolCall,
		String toolResultText,
		boolean imageAttached
	) {
		if ((assistantRawContent == null && toolCall == null) || snapshot == null) {
			return;
		}
		PlannerRequest request = snapshot.request();
		append(new PlannerTurnEvent(
			nextEventId++,
			PlannerTurnEvent.Kind.TOOL_EXCHANGE,
			clock.millis(),
			generation,
			0,
			PlannerSessionPhase.TOOL_FOLLOW_UP.name(),
			request,
			null,
			null,
			assistantRawContent,
			toolCall,
			toolResultText,
			request == null ? -1L : request.tick(),
			imageAttached
		));
	}

	public synchronized void recordAcceptedReply(PlannerExecutionResult result) {
		if (result == null) {
			return;
		}
		append(new PlannerTurnEvent(
			nextEventId++,
			PlannerTurnEvent.Kind.ACCEPTED_REPLY,
			clock.millis(),
			result.generation(),
			result.attempt(),
			result.phase() == null ? "UNKNOWN" : result.phase().name(),
			result.request(),
			null,
			null,
			result.response() == null ? null : result.response().rawAssistantContent(),
			null,
			result.response() == null ? "" : result.response().replyText(),
			result.request() == null ? -1L : result.request().tick(),
			false
		));
	}

	public synchronized void recordCompaction(CompactionExecutionResult result) {
		boolean succeeded = result != null && result.succeeded();
		String text = succeeded ? "Compaction completed\n" + result.checkpoint().renderMessage()
			: "Compaction failed: " + (result == null ? "No result" : result.failureType() + ": " + result.failureMessage());
		append(new PlannerTurnEvent(
			nextEventId++,
			PlannerTurnEvent.Kind.COMPACTION,
			clock.millis(),
			0L,
			0,
			"COMPACTION",
			null,
			null,
			new PlannerConversationDebugMessage("system", succeeded ? PlannerConversationDebugKind.CHECKPOINT
				: PlannerConversationDebugKind.FAILURE, text, 0, "COMPACTION", 0, false),
			null,
			null,
			result == null ? "" : result.succeeded() ? "checkpoint_updated" : String.valueOf(result.failureType()),
			-1L,
			false
		));
	}

	public synchronized void markSuperseded(long generation) {
		if (generation <= 0L) {
			return;
		}
		supersededGenerations.add(generation);
		append(new PlannerTurnEvent(
			nextEventId++,
			PlannerTurnEvent.Kind.SUPERSEDED,
			clock.millis(),
			generation,
			0,
			PlannerSessionPhase.SUPERSEDED.name(),
			null,
			null,
			null,
			null,
			null,
			"",
			-1L,
			false
		));
	}

	public synchronized boolean isSuperseded(long generation) {
		return supersededGenerations.contains(generation);
	}

	public synchronized List<PlannerTurnEvent> snapshot() {
		return List.copyOf(events);
	}

	public synchronized List<PlannerTurnEvent> toolExchanges(long generation) {
		ArrayList<PlannerTurnEvent> exchanges = new ArrayList<>();
		for (PlannerTurnEvent event : events) {
			if (event.kind() == PlannerTurnEvent.Kind.TOOL_EXCHANGE && event.generation() == generation && !isSuperseded(generation)) {
				exchanges.add(event);
			}
		}
		return List.copyOf(exchanges);
	}

	public synchronized void clear(String reason) {
		events.clear();
		supersededGenerations.clear();
		nextEventId = 1L;
		append(new PlannerTurnEvent(
			nextEventId++,
			PlannerTurnEvent.Kind.RESET,
			clock.millis(),
			0L,
			0,
			"RESET",
			null,
			null,
			null,
			null,
			null,
			reason == null ? "reset" : reason,
			-1L,
			false
		));
	}

	private void append(PlannerTurnEvent event) {
		events.add(event);
		trim();
	}

	private void trim() {
		while (events.size() > maxEvents) {
			PlannerTurnEvent removed = events.remove(0);
			if (removed.kind() == PlannerTurnEvent.Kind.SUPERSEDED) {
				boolean stillPresent = events.stream()
					.anyMatch(event -> event.kind() == PlannerTurnEvent.Kind.SUPERSEDED && event.generation() == removed.generation());
				if (!stillPresent) {
					supersededGenerations.remove(removed.generation());
				}
			}
		}
	}

	private static String phaseName(PlannerSessionPhase phase) {
		return phase == null ? "UNKNOWN" : phase.name();
	}
}
