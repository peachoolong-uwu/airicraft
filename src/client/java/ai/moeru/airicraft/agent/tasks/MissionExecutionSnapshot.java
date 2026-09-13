package ai.moeru.airicraft.agent.tasks;

public record MissionExecutionSnapshot(
	MissionSpec mission,
	TaskLedger ledger,
	LedgerStep activeStep,
	WorldEvidence evidence,
	StepExecutionResult lastStepResult,
	TaskExecutionSnapshot primitiveExecution
) {
	public MissionExecutionSnapshot withEvidence(WorldEvidence currentEvidence) {
		return new MissionExecutionSnapshot(mission, ledger, activeStep, currentEvidence, lastStepResult, primitiveExecution);
	}

	public static MissionExecutionSnapshot idle() {
		return new MissionExecutionSnapshot(null, null, null, null, StepExecutionResult.idle(), TaskExecutionSnapshot.idle());
	}
}
