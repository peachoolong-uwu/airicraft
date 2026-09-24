package ai.moeru.airicraft.agent.events;

import java.util.Objects;

/** Measures a single continuous break and limits repeated planner interruptions. */
public final class SlowMiningObserver {
	private String target;
	private long startedTick;
	private long lastWarning = Long.MIN_VALUE;
	private boolean warned;

	public boolean observe(long tick, String target, double estimatedTicks) {
		if (!Objects.equals(this.target, target)) {
			this.target = target;
			startedTick = tick;
			warned = false;
		}
		if (target == null || warned) return false;
		long elapsed = tick - startedTick;
		boolean slow = elapsed >= 100 || elapsed >= 40 && (estimatedTicks >= 100 || estimatedTicks < 0);
		if (!slow || lastWarning != Long.MIN_VALUE && tick - lastWarning < 600) return false;
		warned = true;
		lastWarning = tick;
		return true;
	}

	public long elapsedTicks(long tick) { return tick - startedTick; }

	public void reset() {
		target = null;
		warned = false;
		lastWarning = Long.MIN_VALUE;
	}
}
