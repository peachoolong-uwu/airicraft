package ai.moeru.airicraft.agent.llm;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Single-owner FIFO. Model latency is never part of dispatch or work completion. */
final class PlannerToolQueue<T> {
	record Completion<T>(PlannerToolCall call, T result, String workOutcome, Throwable failure) {}
	private final ArrayDeque<PlannerToolCall> pending = new ArrayDeque<>();
	private PlannerToolCall active;
	private CompletableFuture<T> future;
	private T result;
	private String workId;

	void append(List<PlannerToolCall> calls) { pending.addAll(calls); }
	PlannerToolCall active() { return active; }
	String activeWorkId() { return workId; }
	List<PlannerToolCall> pending() { return List.copyOf(pending); }
	boolean isEmpty() { return active == null && pending.isEmpty(); }

	Completion<T> tick(Function<PlannerToolCall, CompletableFuture<T>> execute,
		Function<T, String> acceptedWork, Function<String, String> terminalWork) {
		return tick(execute, acceptedWork, terminalWork, true);
	}

	Completion<T> tick(Function<PlannerToolCall, CompletableFuture<T>> execute, Function<T, String> acceptedWork,
		Function<String, String> terminalWork, boolean canDispatch) {
		Completion<T> completed = null;
		if (active != null && future.isDone()) {
			try {
				if (result == null) { result = future.join(); workId = acceptedWork.apply(result); }
				String outcome = workId == null ? null : terminalWork.apply(workId);
				if (workId == null || outcome != null) completed = new Completion<>(active, result, outcome, null);
			} catch (java.util.concurrent.CancellationException failure) {
				completed = new Completion<>(active, null, null, failure);
			} catch (CompletionException failure) {
				completed = new Completion<>(active, null, null, failure.getCause());
			}
			if (completed != null) { active = null; future = null; result = null; workId = null; }
		}
		if (canDispatch && active == null && !pending.isEmpty()) {
			active = pending.removeFirst();
			try { future = execute.apply(active); }
			catch (RuntimeException failure) { future = CompletableFuture.failedFuture(failure); }
		}
		return completed;
	}

	void clear(BiConsumer<PlannerToolCall, String> abort) {
		pending.clear();
		if (active != null) {
			abort.accept(active, workId);
			future.cancel(true);
		}
		active = null; future = null; result = null; workId = null;
	}
}
