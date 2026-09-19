package ai.moeru.airicraft.agent.control;

import net.minecraft.util.math.MathHelper;

/** Critically damped angular spring. Retargeting preserves angular velocity. */
final class RotationSpring {
	private double yaw;
	private double pitch;
	private double yawVelocity;
	private double pitchVelocity;

	RotationSpring(CameraController.Rotation start) {
		yaw = start.yaw();
		pitch = start.pitch();
	}

	boolean atRest() { return Math.abs(yawVelocity) < 0.1D && Math.abs(pitchVelocity) < 0.1D; }

	CameraController.Rotation advance(CameraController.Rotation target, double seconds, double frequency) {
		double yawError = MathHelper.wrapDegrees(yaw - target.yaw());
		double pitchError = pitch - MathHelper.clamp(target.pitch(), -90.0F, 90.0F);
		double decay = Math.exp(-frequency * seconds);
		double yawTerm = (yawVelocity + frequency * yawError) * seconds;
		double pitchTerm = (pitchVelocity + frequency * pitchError) * seconds;
		yaw += (yawError + yawTerm) * decay - yawError;
		pitch += (pitchError + pitchTerm) * decay - pitchError;
		yawVelocity = (yawVelocity - frequency * yawTerm) * decay;
		pitchVelocity = (pitchVelocity - frequency * pitchTerm) * decay;
		if (pitch < -90.0D || pitch > 90.0D) {
			pitch = MathHelper.clamp(pitch, -90.0D, 90.0D);
			pitchVelocity = 0.0D;
		}
		return new CameraController.Rotation((float) yaw, (float) pitch);
	}
}
