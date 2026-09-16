package ai.moeru.airicraft.os;

import java.util.Objects;
import java.util.UUID;

/** A covered window of unchanged native material state, measured only in completed advancing ticks. */
public final class NativeAvailabilityClock {
	private String clockId = UUID.randomUUID().toString();
	private long throughTick;
	private long fromTick;
	private String stamp;
	private String beginning;
	private boolean ticking;

	public synchronized void begin(String current) {
		if (ticking || throughTick == 9_007_199_254_740_991L) {
			clockId = UUID.randomUUID().toString();
			throughTick = 0;
			invalidate();
		}
		beginning = current;
		ticking = true;
	}
	public synchronized void complete(boolean advancing, String current) {
		if (!ticking) return;
		ticking = false;
		if (!advancing) return;
		throughTick++;
		if (beginning == null || !Objects.equals(beginning, current)) {
			stamp = null;
			return;
		}
		if (!Objects.equals(stamp, current)) fromTick = throughTick - 1;
		stamp = current;
	}
	public synchronized Snapshot snapshot() {
		return new Snapshot(stamp != null, clockId, stamp == null ? null : fromTick, throughTick, stamp);
	}
	public synchronized void invalidate() {
		beginning = null;
		stamp = null;
	}
	public record Snapshot(boolean available, String clockId, Long fromTick, long throughTick, String stamp) {}
}
