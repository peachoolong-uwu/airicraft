package ai.moeru.airicraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Detached "world camera" prototype: positions the render camera at an
 * arbitrary pose (tactical 2.5D by default), optionally auto-frames a focus
 * region by raycast-scoring candidate poses, and captures the framebuffer
 * through {@link FirstPersonScreenshotService}.
 *
 * All methods must run on the Minecraft client thread.
 */
public final class WorldCameraService {
	private static final int MAX_SAMPLE_POINTS = 384;
	private static final int MAX_RADIUS = 32;
	private static final double MIN_DISTANCE = 6.0;
	private static final double MAX_DISTANCE = 48.0;

	private final FirstPersonScreenshotService screenshotService;

	private CameraPose pose;
	private PendingCapture pending;
	private boolean hudHiddenSaved;

	public WorldCameraService(FirstPersonScreenshotService screenshotService) {
		this.screenshotService = screenshotService;
	}

	public record CameraPose(double x, double y, double z, float yaw, float pitch) {
	}

	public record FrameResult(
		CameraPose pose,
		int candidates,
		int samples,
		int visibleSamples,
		double score
	) {
	}

	public record TacticalResult(
		FrameResult framing,
		FirstPersonScreenshotService.CapturedScreenshot screenshot
	) {
	}

	public synchronized CameraPose pose() {
		return pose;
	}

	public synchronized void setPose(CameraPose nextPose) {
		pose = nextPose;
	}

	public synchronized void clear() {
		pose = null;
		if (pending != null) {
			pending.future().completeExceptionally(
				new BridgeUnavailableException("capture_failed", "World camera was cleared during capture"));
			pending = null;
		}
		restoreHud();
	}

	/**
	 * Called once per rendered world frame from the Camera mixin.
	 */
	public void onWorldFrame(MinecraftClient client) {
		PendingCapture current;
		synchronized (this) {
			current = pending;
			if (current == null) {
				return;
			}
			if (current.framesRemaining() > 0) {
				pending = current.tick();
				return;
			}
			pending = null;
		}
		screenshotService.requestCapture(client).whenComplete((screenshot, throwable) -> {
			restoreHud();
			if (throwable == null) {
				current.future().complete(screenshot);
			}
			else {
				current.future().completeExceptionally(throwable);
			}
		});
	}

	/**
	 * Set a pose, wait {@code settleFrames} rendered frames, capture, and
	 * (unless {@code keepPose}) restore the normal camera.
	 */
	public CompletableFuture<TacticalResult> capture(
		MinecraftClient client,
		CameraPose nextPose,
		FrameResult framing,
		int settleFrames,
		boolean keepPose
	) {
		synchronized (this) {
			if (pending != null) {
				throw new BridgeUnavailableException("capture_in_progress", "A world camera capture is already in progress");
			}
			pose = nextPose;
			if (!client.options.hudHidden) {
				hudHiddenSaved = true;
				client.options.hudHidden = true;
			}
			CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> future = new CompletableFuture<>();
			pending = new PendingCapture(Math.max(0, settleFrames), future);
			return future.thenApply(screenshot -> {
				if (!keepPose) {
					clear();
				}
				return new TacticalResult(framing, screenshot);
			});
		}
	}

	private void restoreHud() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (hudHiddenSaved && client != null) {
			client.options.hudHidden = false;
		}
		hudHiddenSaved = false;
	}

	/**
	 * Auto-frame a focus region: generate candidate poses on rings around the
	 * focus at several pitch/distance bands, score each by raycasting to
	 * task-relevant sample points, and return the best.
	 */
	public FrameResult autoFrame(MinecraftClient client, Vec3d focus, double radius, String purpose) {
		if (client.world == null || client.player == null) {
			throw new BridgeUnavailableException("world_not_loaded", "No world is currently loaded");
		}
		double r = MathHelper.clamp(radius, 4.0, MAX_RADIUS);
		List<SamplePoint> samples = collectSamples(client, focus, r, purpose);
		if (samples.isEmpty()) {
			throw new BridgeUnavailableException("no_samples", "No relevant geometry found around the focus");
		}

		double[] pitches = {45.0, 55.0, 65.0};
		double[] distanceScales = {1.0, 1.5, 2.0};
		int yawSteps = 16;

		CameraPose bestPose = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		double bestVisibleWeight = 0.0;
		double totalWeight = 0.0;
		for (SamplePoint sample : samples) {
			totalWeight += sample.weight();
		}
		int candidates = 0;
		for (double pitchDeg : pitches) {
			double pitch = Math.toRadians(pitchDeg);
			for (double scale : distanceScales) {
				double distance = MathHelper.clamp(r * scale, MIN_DISTANCE, MAX_DISTANCE);
				double dy = distance * Math.sin(pitch);
				double horizontal = distance * Math.cos(pitch);
				for (int i = 0; i < yawSteps; i++) {
					double yawDeg = i * (360.0 / yawSteps);
					double yaw = Math.toRadians(yawDeg);
					// Camera yaw convention: 0 = looking south (+Z), 90 = west (-X).
					// Place the camera opposite the look direction so it faces the focus.
					double cx = focus.x + Math.sin(yaw) * horizontal;
					double cz = focus.z - Math.cos(yaw) * horizontal;
					double cy = focus.y + dy;
					CameraPose candidate = new CameraPose(cx, cy, cz, (float) yawDeg, (float) pitchDeg);
					candidates++;
					PoseScore scored = scorePose(client, candidate, samples, distance, r);
					if (scored.score() > bestScore) {
						bestScore = scored.score();
						bestPose = candidate;
						bestVisibleWeight = scored.visibleWeight();
					}
				}
			}
		}

		// Interior candidates: when the focus is enclosed (cave, room, tunnel),
		// every exterior ring pose is buried in solid geometry. Try air cells
		// inside the volume looking back at the focus instead.
		if (bestScore <= 0.0) {
			for (CameraPose candidate : interiorCandidates(client, focus, r)) {
				candidates++;
				PoseScore scored = scorePose(client, candidate, samples, r, r);
				if (scored.score() > bestScore) {
					bestScore = scored.score();
					bestPose = candidate;
					bestVisibleWeight = scored.visibleWeight();
				}
			}
		}

		int visibleSamples = totalWeight > 0
			? (int) Math.round(samples.size() * bestVisibleWeight / totalWeight)
			: 0;
		return new FrameResult(bestPose, candidates, samples.size(), visibleSamples, bestScore);
	}

	/**
	 * Air cells inside the focus volume, aimed back at the focus. Covers
	 * tunnels and rooms where no exterior vantage exists.
	 */
	private List<CameraPose> interiorCandidates(MinecraftClient client, Vec3d focus, double radius) {
		List<CameraPose> candidates = new ArrayList<>();
		int r = (int) Math.ceil(Math.min(radius, 12));
		BlockPos origin = BlockPos.ofFloored(focus);
		for (int dx = -r; dx <= r; dx += 2) {
			for (int dz = -r; dz <= r; dz += 2) {
				for (int dy = -r; dy <= r; dy += 2) {
					BlockPos pos = origin.add(dx, dy, dz);
					if (pos.getSquaredDistance(origin) > r * r) {
						continue;
					}
					if (!client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty()) {
						continue;
					}
					Vec3d eye = Vec3d.ofCenter(pos);
					Vec3d delta = focus.subtract(eye);
					double horizontal = Math.hypot(delta.x, delta.z);
					double distance = delta.length();
					// Too close: the frame is just the player's head. Too far in
					// a tunnel is fine — the view still reads.
					if (distance < 2.5) {
						continue;
					}
					float yaw = (float) (Math.toDegrees(Math.atan2(-delta.x, delta.z)));
					float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
					candidates.add(new CameraPose(eye.x, eye.y, eye.z, yaw, MathHelper.clamp(pitch, -90.0F, 90.0F)));
				}
			}
		}
		return candidates;
	}

	private record SamplePoint(Vec3d pos, double weight) {
	}

	private record PoseScore(double score, double visibleWeight) {
	}

	private List<SamplePoint> collectSamples(MinecraftClient client, Vec3d focus, double radius, String purpose) {
		List<SamplePoint> samples = new ArrayList<>();
		boolean nav = "navigation".equals(purpose) || "surroundings".equals(purpose);
		boolean threats = "threats".equals(purpose);
		boolean inspect = "inspect".equals(purpose) || "structure".equals(purpose);

		int r = (int) Math.ceil(radius);
		BlockPos origin = BlockPos.ofFloored(focus);
		int step = radius > 16 ? 2 : 1;
		int vertical = Math.min(r, 8);
		for (int dx = -r; dx <= r; dx += step) {
			for (int dz = -r; dz <= r; dz += step) {
				for (int dy = -vertical; dy <= vertical; dy++) {
					if (samples.size() >= MAX_SAMPLE_POINTS) {
						break;
					}
					BlockPos pos = origin.add(dx, dy, dz);
					var state = client.world.getBlockState(pos);
					if (state.isAir()) {
						continue;
					}
					if (!client.world.getBlockState(pos.up()).isAir()) {
						continue;
					}
					// Walkable floor heuristic: collidable top with two air above.
					boolean walkable = !state.getCollisionShape(client.world, pos).isEmpty()
						&& client.world.getBlockState(pos.up(2)).isAir();
					double weight = 1.0;
					if (nav && walkable) {
						weight = 2.0;
					}
					else if (inspect) {
						weight = 1.5;
					}
					samples.add(new SamplePoint(Vec3d.ofCenter(pos, 1.0), weight));
				}
			}
		}

		// The focus itself is the most important sample: when framing "self",
		// the player must be visible or the shot is useless.
		samples.add(new SamplePoint(focus, 8.0));

		Box entityBox = Box.of(focus, radius * 2, 16, radius * 2);
		for (Entity entity : client.world.getEntities()) {
			if (entity == client.player || !entityBox.contains(entity.getPos())) {
				continue;
			}
			samples.add(new SamplePoint(entity.getEyePos(), threats ? 4.0 : 2.0));
		}
		return samples;
	}

	private PoseScore scorePose(
		MinecraftClient client,
		CameraPose candidate,
		List<SamplePoint> samples,
		double distance,
		double radius
	) {
		Vec3d eye = new Vec3d(candidate.x(), candidate.y(), candidate.z());
		// Hard penalty: camera inside any collidable geometry.
		BlockPos eyeBlock = BlockPos.ofFloored(eye);
		double penalty = client.world.getBlockState(eyeBlock).getCollisionShape(client.world, eyeBlock).isEmpty() ? 0.0 : 1.0;
		// Mild preference for closer shots.
		penalty += 0.05 * (distance / radius - 1.0);
		double totalWeight = 0.0;
		double visibleWeight = 0.0;
		// Camera forward vector (MC convention: yaw 0 = +Z, pitch + = down).
		double yawRad = Math.toRadians(candidate.yaw());
		double pitchRad = Math.toRadians(candidate.pitch());
		Vec3d forward = new Vec3d(
			-Math.sin(yawRad) * Math.cos(pitchRad),
			-Math.sin(pitchRad),
			Math.cos(yawRad) * Math.cos(pitchRad));
		// Samples outside the view cone do not count even if unoccluded.
		double minDot = Math.cos(Math.toRadians(45.0));
		for (SamplePoint sample : samples) {
			totalWeight += sample.weight();
			Vec3d target = sample.pos();
			double targetDistance = eye.distanceTo(target);
			Vec3d direction = target.subtract(eye).normalize();
			if (forward.dotProduct(direction) < minDot) {
				continue;
			}
			// Raycast slightly short of the target so the target block itself
			// does not count as an occluder. VISUAL shape type means foliage
			// and other non-opaque blocks do not count as occluders either —
			// they are fadeable, not blocking.
			Vec3d end = eye.add(direction.multiply(Math.max(0.0, targetDistance - 0.35)));
			BlockHitResult hit = client.world.raycast(new RaycastContext(
				eye, end, RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE,
				net.minecraft.block.ShapeContext.absent()));
			if (hit.getType() == HitResult.Type.MISS) {
				visibleWeight += sample.weight();
			}
		}
		return new PoseScore((totalWeight > 0 ? visibleWeight / totalWeight : 0.0) - penalty, visibleWeight);
	}

	private record PendingCapture(int framesRemaining, CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> future) {
		PendingCapture tick() {
			return new PendingCapture(framesRemaining - 1, future);
		}
	}
}
