package ai.moeru.airicraft.agent.goals;

/** A fixed, loaded search volume. Surface excludes terrain below the top ground layer. */
public record AcquisitionConstraints(GoalPosition center, int radius, int verticalRadius, boolean surfaceOnly, boolean visibleOnly) {
	public AcquisitionConstraints(GoalPosition center, int radius, int verticalRadius, boolean surfaceOnly) {
		this(center, radius, verticalRadius, surfaceOnly, false);
	}

	public AcquisitionConstraints {
		if (radius < 1 || radius > 32) throw new IllegalArgumentException("constraints.radius must be 1..32");
		if (verticalRadius < 1 || verticalRadius > 32) throw new IllegalArgumentException("constraints.verticalRadius must be 1..32");
	}

	public static AcquisitionConstraints nearby() {
		return new AcquisitionConstraints(null, 16, 16, false);
	}

	public AcquisitionConstraints anchoredAt(GoalPosition position) {
		return center == null ? new AcquisitionConstraints(java.util.Objects.requireNonNull(position), radius, verticalRadius, surfaceOnly, visibleOnly) : this;
	}

	public boolean contains(GoalPosition position) {
		java.util.Objects.requireNonNull(center, "acquisition center must be anchored");
		long dx = (long) position.x() - center.x();
		long dz = (long) position.z() - center.z();
		return dx * dx + dz * dz <= (long) radius * radius
			&& Math.abs((long) position.y() - center.y()) <= verticalRadius;
	}
}
