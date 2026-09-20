package ai.moeru.airicraft.agent.control;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;

public final class CameraController {
	private int defaultLerpTicks;
	private CameraMotion activeMotion;
	private RotationSpring spring;
	private boolean directRequest;
	private ClientPlayerEntity controlledPlayer;
	private CompletableFuture<Void> alignment;

	public CameraController() { this(0); }

	public CameraController(int defaultLerpTicks) {
		updateDefaultLerpTicks(defaultLerpTicks);
	}

	public void updateDefaultLerpTicks(int ticks) { defaultLerpTicks = Math.max(0, ticks); }
	public int defaultLerpTicks() { return defaultLerpTicks; }

	/** Submit an aim target. Exact interactions use isLookingAt; mining uses blockHit. */
	public Optional<Rotation> lookAt(MinecraftClient client, Vec3d target) {
		return startLookAt(client, target, defaultLerpTicks, "action");
	}

	public Optional<Rotation> faceDirection(ClientPlayerEntity player, String direction) {
		if (player == null) return Optional.empty();
		Optional<Rotation> rotation = directionRotation(direction);
		rotation.ifPresent(value -> request(player, value, defaultLerpTicks, "vision", true));
		return rotation;
	}

	public Optional<Rotation> startLookAt(MinecraftClient client, Vec3d target, String reason) {
		return startLookAt(client, target, defaultLerpTicks, reason);
	}

	public Optional<Rotation> startLookAt(MinecraftClient client, Vec3d target, int durationTicks, String reason) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null) return Optional.empty();
		Optional<Rotation> rotation = lookRotation(player.getEyePos(), target);
		rotation.ifPresent(value -> request(player, value, durationTicks, reason, true));
		return rotation;
	}

	/** Baritone supplies targets only; this controller owns rotation writes. */
	public void lookFromBaritone(ClientPlayerEntity player, float yaw, float pitch) {
		if (acceptsBaritoneTarget()) request(player, new Rotation(yaw, pitch), defaultLerpTicks, "baritone", false);
	}

	boolean acceptsBaritoneTarget() {
		return !directRequest && alignment == null
			&& (activeMotion == null || "baritone".equals(activeMotion.reason()));
	}

	private void request(ClientPlayerEntity player, Rotation target, int ticks, String reason, boolean direct) {
		if (controlledPlayer != player) {
			clear();
			controlledPlayer = player;
		}
		if (alignment != null) return;
		directRequest |= direct;
		startMotion(new Rotation(player.getYaw(), player.getPitch()), target, ticks, reason);
	}

	public boolean isLookingAt(MinecraftClient client, Vec3d target) {
		return isLookingAt(client, target, 0.01F);
	}

	public boolean isLookingAt(MinecraftClient client, Vec3d target, float tolerance) {
		if (client == null || client.player == null) return false;
		return lookRotation(client.player.getEyePos(), target)
			.map(rotation -> aligned(new Rotation(client.player.getYaw(), client.player.getPitch()), rotation, tolerance))
			.orElse(false);
	}

	public boolean isAimingAt(MinecraftClient client, net.minecraft.util.math.Box bounds) {
		if (client == null || client.player == null) return false;
		Vec3d eye = client.player.getEyePos();
		return bounds.contains(eye) || bounds.raycast(eye,
			eye.add(client.player.getRotationVec(1.0F).multiply(6.0D))).isPresent();
	}

	/** Aim inside the selection shape, including thin blocks such as leaf litter and crops. */
	public Optional<Rotation> lookAtBlock(MinecraftClient client, BlockPos pos) {
		if (client == null || client.player == null || client.world == null) return Optional.empty();
		return blockAim(pos, client.world.getBlockState(pos).getOutlineShape(client.world, pos), client.player.getEyePos())
			.flatMap(aim -> lookAt(client, aim));
	}

	static Optional<Vec3d> blockAim(BlockPos pos, VoxelShape shape, Vec3d eye) {
		return shape.getBoundingBoxes().stream()
			.map(box -> box.getCenter().add(pos.getX(), pos.getY(), pos.getZ()))
			.min(java.util.Comparator.comparingDouble(eye::squaredDistanceTo));
	}

	/** Fresh player-direction raycast: render-frame crosshairTarget can lag camera ticks. */
	public Optional<BlockHitResult> blockHit(MinecraftClient client, BlockPos target) {
		if (client == null || client.player == null || client.world == null) return Optional.empty();
		return blockHit(raycast(client.player, client.player.getRotationVec(1.0F)), target);
	}

	static Optional<BlockHitResult> blockHit(HitResult hit, BlockPos target) {
		return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK && block.getBlockPos().equals(target)
			? Optional.of(block) : Optional.empty();
	}

	private static BlockHitResult raycast(ClientPlayerEntity player, Vec3d direction) {
		Vec3d eye = player.getEyePos();
		return player.getWorld().raycast(new RaycastContext(eye, eye.add(direction.multiply(player.getBlockInteractionRange())),
			RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
	}

	public boolean capturePending() { return alignment != null; }

	/** Hold path input while turning on the ground; preserve airborne/swimming control. */
	public boolean allowsBaritoneInput(ClientPlayerEntity player, baritone.api.utils.input.Input input) {
		if (activeMotion == null || input == baritone.api.utils.input.Input.SNEAK) return true;
		if (!"baritone".equals(activeMotion.reason())) return false;
		return switch (input) {
			case CLICK_LEFT -> {
				var intended = raycast(player, Vec3d.fromPolar(activeMotion.target().pitch(), activeMotion.target().yaw()));
				yield intended.getType() == HitResult.Type.BLOCK
					&& blockHit(raycast(player, player.getRotationVec(1.0F)), intended.getBlockPos()).isPresent();
			}
			case CLICK_RIGHT -> aligned(new Rotation(player.getYaw(), player.getPitch()), activeMotion.target(), 0.5F);
			case MOVE_FORWARD, MOVE_BACK, MOVE_LEFT, MOVE_RIGHT, JUMP, SPRINT ->
				!player.isOnGround() || player.isTouchingWater()
					|| Math.abs(MathHelper.wrapDegrees(activeMotion.target().yaw() - player.getYaw())) < 10.0F;
			case SNEAK -> true;
		};
	}

	public CompletableFuture<Void> whenAligned() {
		if (activeMotion == null) return CompletableFuture.completedFuture(null);
		if (alignment == null) alignment = new CompletableFuture<>();
		return alignment;
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null || !player.isAlive() || (controlledPlayer != null && controlledPlayer != player)) {
			clear();
			return;
		}
		tickMotion().ifPresent(rotation -> {
			// Leave previous angles intact for Minecraft's render interpolation.
			player.setYaw(rotation.yaw());
			player.setPitch(rotation.pitch());
			player.setHeadYaw(rotation.yaw());
		});
		directRequest = false;
		if (activeMotion == null && alignment != null) {
			var completed = alignment;
			alignment = null;
			completed.complete(null);
		}
	}

	public void clear() {
		activeMotion = null;
		spring = null;
		controlledPlayer = null;
		directRequest = false;
		if (alignment != null) {
			var cancelled = alignment;
			alignment = null;
			cancelled.completeExceptionally(new CancellationException("Camera control released"));
		}
	}

	public Optional<String> activeReason() {
		return activeMotion == null ? Optional.empty() : Optional.of(activeMotion.reason());
	}

	void startMotion(Rotation start, Rotation target, int durationTicks, String reason) {
		if (spring == null) spring = new RotationSpring(Objects.requireNonNull(start));
		activeMotion = new CameraMotion(Objects.requireNonNull(target),
			durationTicks > 0 ? 120.0D / durationTicks : 18.0D, normalizeReason(reason));
	}

	Optional<Rotation> tickMotion() {
		if (activeMotion == null) return Optional.empty();
		Rotation rotation = spring.advance(activeMotion.target(), 0.05D, activeMotion.frequency());
		if (aligned(rotation, activeMotion.target(), 0.01F) && spring.atRest()) {
			rotation = new Rotation(rotation.yaw() + MathHelper.wrapDegrees(activeMotion.target().yaw() - rotation.yaw()),
				activeMotion.target().pitch());
			activeMotion = null;
			spring = null;
		}
		return Optional.of(rotation);
	}

	private static boolean aligned(Rotation current, Rotation target, float tolerance) {
		return Math.abs(MathHelper.wrapDegrees(target.yaw() - current.yaw())) < tolerance
			&& Math.abs(target.pitch() - current.pitch()) < tolerance;
	}

	public static Optional<Rotation> lookRotation(Vec3d eyePos, Vec3d target) {
		if (eyePos == null || target == null) {
			return Optional.empty();
		}
		Vec3d delta = target.subtract(eyePos);
		double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		if (horizontalDistance < 1.0E-7D && Math.abs(delta.y) < 1.0E-7D) {
			return Optional.empty();
		}
		float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
		float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontalDistance));
		return Optional.of(new Rotation(yaw, MathHelper.clamp(pitch, -90.0F, 90.0F)));
	}

	public static Optional<Rotation> directionRotation(String direction) {
		return switch (direction == null ? "" : direction) {
			case "north" -> Optional.of(new Rotation(180.0F, 0.0F));
			case "northeast" -> Optional.of(new Rotation(-135.0F, 0.0F));
			case "east" -> Optional.of(new Rotation(-90.0F, 0.0F));
			case "southeast" -> Optional.of(new Rotation(-45.0F, 0.0F));
			case "south" -> Optional.of(new Rotation(0.0F, 0.0F));
			case "southwest" -> Optional.of(new Rotation(45.0F, 0.0F));
			case "west" -> Optional.of(new Rotation(90.0F, 0.0F));
			case "northwest" -> Optional.of(new Rotation(135.0F, 0.0F));
			default -> Optional.empty();
		};
	}

	private static String normalizeReason(String reason) {
		return reason == null || reason.isBlank() ? "unspecified" : reason.trim();
	}

	public record Rotation(float yaw, float pitch) {
	}

	private record CameraMotion(Rotation target, double frequency, String reason) { }

}
