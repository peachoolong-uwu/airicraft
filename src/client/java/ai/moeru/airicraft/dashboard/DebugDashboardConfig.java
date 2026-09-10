package ai.moeru.airicraft.dashboard;

public record DebugDashboardConfig(
	boolean enabled,
	int basePort,
	int portScanLimit,
	long historyByteBudget,
	boolean visualCaptureEnabled,
	int visualCaptureIntervalTicks
) {
	public static final int DEFAULT_BASE_PORT = 8765;
	public static final int DEFAULT_PORT_SCAN_LIMIT = 100;
	public static final long DEFAULT_HISTORY_BYTE_BUDGET = 64L * 1024L * 1024L;

	public DebugDashboardConfig {
		basePort = Math.max(1, Math.min(65_535, basePort));
		portScanLimit = Math.max(1, Math.min(1_000, portScanLimit));
		historyByteBudget = Math.max(1024L * 1024L, historyByteBudget);
		visualCaptureIntervalTicks = Math.max(20, visualCaptureIntervalTicks);
	}

	public DebugDashboardConfig(boolean enabled, int basePort, int portScanLimit, long historyByteBudget) {
		this(enabled, basePort, portScanLimit, historyByteBudget, false, 20);
	}

	public static DebugDashboardConfig defaults() {
		return new DebugDashboardConfig(
			true,
			DEFAULT_BASE_PORT,
			DEFAULT_PORT_SCAN_LIMIT,
			DEFAULT_HISTORY_BYTE_BUDGET,
			true,
			20
		);
	}

	public int lastPort() {
		return Math.min(65_535, basePort + portScanLimit - 1);
	}
}
