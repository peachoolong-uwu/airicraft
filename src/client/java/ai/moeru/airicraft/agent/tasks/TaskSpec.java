package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.AcquisitionConstraints;

import java.util.Objects;

public record TaskSpec(TaskType type, TaskResourceKind resourceKind, int quantity, AcquisitionConstraints constraints) {
	public TaskSpec(TaskType type, TaskResourceKind resourceKind, int quantity) {
		this(type, resourceKind, quantity, AcquisitionConstraints.nearby());
	}

	public TaskSpec {
		Objects.requireNonNull(constraints, "constraints");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(resourceKind, "resourceKind");
	}
}
