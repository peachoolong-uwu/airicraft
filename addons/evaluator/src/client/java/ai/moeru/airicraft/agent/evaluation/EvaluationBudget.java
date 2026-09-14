package ai.moeru.airicraft.agent.evaluation;

/** heartbeatIntervalTicks is retained only to read frozen legacy manifests; it is never scheduled. */
public record EvaluationBudget(
	int maxPlannerTurns,
	long maxElapsedTicks,
	long maxElapsedMillis,
	long heartbeatIntervalTicks
) {
	private static final int DEFAULT_MAX_PLANNER_TURNS = 12;
	private static final long DEFAULT_MAX_ELAPSED_TICKS = 2_400L;
	private static final long DEFAULT_HEARTBEAT_INTERVAL_TICKS = 80L;

	public EvaluationBudget {
		maxPlannerTurns = maxPlannerTurns <= 0 ? DEFAULT_MAX_PLANNER_TURNS : maxPlannerTurns;
		maxElapsedTicks = maxElapsedTicks <= 0L ? DEFAULT_MAX_ELAPSED_TICKS : maxElapsedTicks;
		maxElapsedMillis = Math.max(0L, maxElapsedMillis);
		heartbeatIntervalTicks = heartbeatIntervalTicks <= 0L ? DEFAULT_HEARTBEAT_INTERVAL_TICKS : heartbeatIntervalTicks;
	}

	public static EvaluationBudget defaults() {
		return new EvaluationBudget(
			DEFAULT_MAX_PLANNER_TURNS,
			DEFAULT_MAX_ELAPSED_TICKS,
			0L,
			DEFAULT_HEARTBEAT_INTERVAL_TICKS
		);
	}
}
