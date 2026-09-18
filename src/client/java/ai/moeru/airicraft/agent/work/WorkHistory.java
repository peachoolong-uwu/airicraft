package ai.moeru.airicraft.agent.work;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Bounded terminal projections, never a second scheduler or executor. Client-thread owned. */
public final class WorkHistory {
	private static final int TERMINAL_LIMIT = 128;
	private final LinkedHashMap<WorkHandle, WorkSnapshot> work = new LinkedHashMap<>();
	public boolean observe(WorkSnapshot snapshot) {
		WorkSnapshot known = work.get(snapshot.handle());
		if (known != null && !known.parentWorkId().isBlank() && snapshot.parentWorkId().isBlank()) {
			snapshot = new WorkSnapshot(snapshot.handle(), known.parentWorkId(), snapshot.state(), snapshot.label(),
				snapshot.phase(), snapshot.foreground(), snapshot.updatedTick(), snapshot.details());
		}
		// Executor cleanup must not erase the result delivered with the terminal transition.
		if (known != null && known.state().terminal() && known.state() == snapshot.state()
			&& !String.valueOf(known.details().getOrDefault("message", "")).isBlank()
			&& String.valueOf(snapshot.details().getOrDefault("message", "")).isBlank()) {
			var details = new LinkedHashMap<>(snapshot.details());
			details.put("message", known.details().get("message"));
			snapshot = new WorkSnapshot(snapshot.handle(), snapshot.parentWorkId(), snapshot.state(), snapshot.label(),
				snapshot.phase(), snapshot.foreground(), snapshot.updatedTick(), details);
		}
		WorkSnapshot previous = work.remove(snapshot.handle());
		work.put(snapshot.handle(), snapshot);
		while (work.values().stream().filter(value -> value.state().terminal()).count() > TERMINAL_LIMIT) {
			WorkHandle oldest = work.values().stream().filter(value -> value.state().terminal()).findFirst().orElseThrow().handle();
			work.remove(oldest);
		}
		return previous == null || previous.state() != snapshot.state() || !previous.phase().equals(snapshot.phase());
	}
	public Optional<WorkSnapshot> find(WorkHandle handle) { return Optional.ofNullable(work.get(handle)); }
	public List<WorkSnapshot> list() { return List.copyOf(work.values()); }
	public Optional<WorkSnapshot> current() {
		return work.values().stream().filter(value -> (value.foreground() || value.state() == WorkSnapshot.State.PAUSED) && !value.state().terminal() && value.parentWorkId().isBlank()).findFirst();
	}
	public void clear() { work.clear(); }
}
