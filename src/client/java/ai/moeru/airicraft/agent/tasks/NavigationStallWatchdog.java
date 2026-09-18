package ai.moeru.airicraft.agent.tasks;

/** Movement measured in active game ticks, so debug pauses do not consume the budget. */
final class NavigationStallWatchdog {
	private static final long STALL_TICKS = 100;
	private static final double MIN_MOVEMENT_SQUARED = 0.75 * 0.75;
	private long anchorTick = -1;
	private double anchorX, anchorY, anchorZ;

	boolean observe(long tick, double x, double y, double z) {
		double dx = x - anchorX, dy = y - anchorY, dz = z - anchorZ;
		if (anchorTick < 0 || tick < anchorTick || dx * dx + dy * dy + dz * dz >= MIN_MOVEMENT_SQUARED) {
			anchorTick = tick;
			anchorX = x;
			anchorY = y;
			anchorZ = z;
		}
		return tick - anchorTick >= STALL_TICKS;
	}

	void clear() { anchorTick = -1; }
}
