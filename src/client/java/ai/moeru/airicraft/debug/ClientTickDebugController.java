package ai.moeru.airicraft.debug;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ClientTickDebugController {
	private Phase phase = Phase.RUNNING;
	private long clientTickId;
	private long captureSequence;
	private long pauseEpoch;
	private String debugSessionId;
	private boolean capturePlayerActions;
	private PendingCapture pendingCapture;
	private ClientTickCapture currentCapture;

	public synchronized CompletableFuture<ClientTickCapture> pause() {
		return pause(false);
	}

	public synchronized CompletableFuture<ClientTickCapture> pause(boolean nextCapturePlayerActions) {
		if (phase == Phase.PAUSED && currentCapture != null) {
			return CompletableFuture.completedFuture(currentCapture);
		}
		requirePhase(Phase.RUNNING, "debug_busy", "The client tick debugger is busy");
		debugSessionId = UUID.randomUUID().toString();
		capturePlayerActions = nextCapturePlayerActions;
		captureSequence = 0L;
		pauseEpoch = 0L;
		currentCapture = null;
		CompletableFuture<ClientTickCapture> future = new CompletableFuture<>();
		pendingCapture = PendingCapture.command(future);
		phase = Phase.PAUSE_REQUESTED;
		return future;
	}

	public synchronized boolean capturesPlayerActions() {
		return capturePlayerActions;
	}

	public synchronized CompletableFuture<ClientTickCapture> step(String requestedSessionId, long requestedPauseEpoch) {
		requirePausedAuthority(requestedSessionId, requestedPauseEpoch);
		CompletableFuture<ClientTickCapture> future = new CompletableFuture<>();
		currentCapture = null;
		pendingCapture = PendingCapture.command(future);
		phase = Phase.STEP_ARMED;
		return future;
	}

	public synchronized void continueRunning(String requestedSessionId, long requestedPauseEpoch) {
		requirePausedAuthority(requestedSessionId, requestedPauseEpoch);
		phase = Phase.RUNNING;
		debugSessionId = null;
		pauseEpoch = 0L;
		captureSequence = 0L;
		capturePlayerActions = false;
		currentCapture = null;
		pendingCapture = null;
	}

	public synchronized boolean allowVanillaTick(boolean vanillaAllowsTick) {
		if (!vanillaAllowsTick) {
			return false;
		}
		return switch (phase) {
			case RUNNING, PAUSE_TICK_RUNNING, STEP_ARMED, STEP_RUNNING -> true;
			case PAUSE_REQUESTED, WAITING_FOR_FRAME, PAUSED -> false;
		};
	}

	/** Admit the final client tick only after its authoritative server boundary completes. */
	public synchronized boolean beginServerAlignedTick(ServerTickDebugController.DebugStatus server) {
		if (phase == Phase.RUNNING) {
			return true;
		}
		if ((phase == Phase.PAUSE_REQUESTED || phase == Phase.STEP_ARMED)
			&& server.paused()
			&& Objects.equals(debugSessionId, server.debugSessionId())
			&& server.pauseEpoch() == pauseEpoch + 1L) {
			onClientTickStarted();
			return true;
		}
		return false;
	}

	public synchronized void onClientTickStarted() {
		if (phase == Phase.PAUSE_REQUESTED) {
			phase = Phase.PAUSE_TICK_RUNNING;
		}
		else if (phase == Phase.STEP_ARMED) {
			phase = Phase.STEP_RUNNING;
		}
	}

	public synchronized Optional<CaptureIntent> onClientTickCompleted() {
		clientTickId++;
		if (phase != Phase.PAUSE_TICK_RUNNING && phase != Phase.STEP_RUNNING) {
			return Optional.empty();
		}
		return Optional.of(reserveCapture());
	}

	public synchronized Optional<CaptureIntent> onRenderedFrameBoundary() {
		if (phase != Phase.PAUSE_REQUESTED && phase != Phase.STEP_ARMED) {
			return Optional.empty();
		}
		return Optional.of(reserveCapture());
	}

	public synchronized void attachSnapshot(CaptureIntent intent, ClientTickSnapshot snapshot) {
		PendingCapture pending = requirePending(intent);
		if (!Objects.equals(intent.snapshotId(), snapshot.snapshotId())) {
			throw new DebugStateException("snapshot_mismatch", "The captured snapshot does not match the pending client tick");
		}
		pendingCapture = pending.withSnapshot(snapshot);
	}

	public synchronized void completeFrame(CaptureIntent intent, ClientTickFrame frame) {
		PendingCapture pending = requirePending(intent);
		if (pending.snapshot() == null) {
			throw new DebugStateException("snapshot_missing", "The client tick snapshot is not attached");
		}
		pauseEpoch++;
		ClientTickCapture capture = new ClientTickCapture(
			debugSessionId,
			pauseEpoch,
			pending.snapshot(),
			Objects.requireNonNull(frame, "frame")
		);
		currentCapture = capture;
		pendingCapture = null;
		phase = Phase.PAUSED;
		pending.future().complete(capture);
	}

	public synchronized void failFrame(CaptureIntent intent, String code, String message) {
		PendingCapture pending = requirePending(intent);
		if (pending.snapshot() == null) {
			throw new DebugStateException("snapshot_missing", "The client tick snapshot is not attached");
		}
		completeFrame(intent, ClientTickFrame.failed(code, message));
	}

	public synchronized ClientTickSnapshot requireCurrentSnapshot(String requestedSnapshotId) {
		if (phase != Phase.PAUSED || currentCapture == null) {
			throw new DebugStateException("snapshot_not_paused", "A world snapshot is available only while client ticks are paused");
		}
		ClientTickSnapshot snapshot = currentCapture.snapshot();
		if (requestedSnapshotId == null || !requestedSnapshotId.equals(snapshot.snapshotId())) {
			throw new DebugStateException("stale_snapshot", "The world snapshot handle is stale");
		}
		return snapshot;
	}

	public synchronized DebugStatus status() {
		return new DebugStatus(
			phase,
			debugSessionId,
			pauseEpoch,
			clientTickId,
			currentCapture == null ? null : currentCapture.snapshot().snapshotId(),
			currentCapture == null ? null : currentCapture.frame().status()
		);
	}

	public synchronized void reset(String code, String message) {
		PendingCapture pending = pendingCapture;
		phase = Phase.RUNNING;
		debugSessionId = null;
		pauseEpoch = 0L;
		captureSequence = 0L;
		capturePlayerActions = false;
		pendingCapture = null;
		currentCapture = null;
		if (pending != null) {
			pending.future().completeExceptionally(new DebugStateException(code, message));
		}
	}

	private CaptureIntent reserveCapture() {
		PendingCapture pending = pendingCapture;
		if (pending == null || pending.future() == null) {
			throw new DebugStateException("debug_state_invalid", "No debug command is waiting for a captured frame");
		}
		captureSequence++;
		String captureId = debugSessionId + ":capture:" + captureSequence;
		String snapshotId = debugSessionId + ":tick:" + clientTickId;
		CaptureIntent intent = new CaptureIntent(debugSessionId, captureId, snapshotId, clientTickId);
		pendingCapture = pending.withIntent(intent);
		phase = Phase.WAITING_FOR_FRAME;
		return intent;
	}

	private PendingCapture requirePending(CaptureIntent intent) {
		PendingCapture pending = pendingCapture;
		if (
			phase != Phase.WAITING_FOR_FRAME
				|| pending == null
				|| pending.intent() == null
				|| intent == null
				|| !pending.intent().captureId().equals(intent.captureId())
		) {
			throw new DebugStateException("stale_capture", "The rendered frame does not match the pending client tick");
		}
		return pending;
	}

	private void requirePausedAuthority(String requestedSessionId, long requestedPauseEpoch) {
		requirePhase(Phase.PAUSED, "debug_not_paused", "Client ticks are not paused");
		if (requestedSessionId == null || !requestedSessionId.equals(debugSessionId)) {
			throw new DebugStateException("stale_debug_session", "The debug session identifier is stale");
		}
		if (requestedPauseEpoch != pauseEpoch) {
			throw new DebugStateException("stale_pause_epoch", "The pause epoch is stale");
		}
	}

	private void requirePhase(Phase required, String code, String message) {
		if (phase != required) {
			throw new DebugStateException(code, message);
		}
	}

	public enum Phase {
		RUNNING,
		PAUSE_REQUESTED,
		PAUSE_TICK_RUNNING,
		WAITING_FOR_FRAME,
		PAUSED,
		STEP_ARMED,
		STEP_RUNNING
	}

	public record CaptureIntent(
		String debugSessionId,
		String captureId,
		String snapshotId,
		long clientTickId
	) {
	}

	public record ClientTickSnapshot(
		int schemaVersion,
		String debugSessionId,
		String captureId,
		String snapshotId,
		long clientTickId,
		long capturedAtMs,
		String dimensionId,
		long worldTime,
		long timeOfDay,
		ClientTickPlayerSnapshot player,
		ClientTickPlayerActionsSnapshot playerActions,
		long plannerGeneration,
		String plannerPhase
	) {
		public ClientTickSnapshot {
			if (schemaVersion < 1) {
				throw new IllegalArgumentException("schemaVersion must be positive");
			}
		}
	}

	public record ClientTickFrame(
		String status,
		String format,
		int width,
		int height,
		int sourceWidth,
		int sourceHeight,
		long capturedAtMs,
		byte[] imageBytes,
		String errorCode,
		String message
	) {
		public ClientTickFrame {
			imageBytes = imageBytes == null ? new byte[0] : imageBytes.clone();
		}

		@Override
		public byte[] imageBytes() {
			return imageBytes.clone();
		}

		public static ClientTickFrame captured(
			String format,
			int width,
			int height,
			int sourceWidth,
			int sourceHeight,
			long capturedAtMs,
			byte[] imageBytes
		) {
			return new ClientTickFrame(
				"CAPTURED",
				format,
				width,
				height,
				sourceWidth,
				sourceHeight,
				capturedAtMs,
				imageBytes,
				null,
				null
			);
		}

		public static ClientTickFrame failed(String code, String message) {
			return new ClientTickFrame("FAILED", null, 0, 0, 0, 0, System.currentTimeMillis(), new byte[0], code, message);
		}
	}

	public record ClientTickCapture(
		String debugSessionId,
		long pauseEpoch,
		ClientTickSnapshot snapshot,
		ClientTickFrame frame
	) {
	}

	public record DebugStatus(
		Phase phase,
		String debugSessionId,
		long pauseEpoch,
		long clientTickId,
		String snapshotId,
		String frameStatus
	) {
		public boolean paused() {
			return phase == Phase.PAUSED;
		}
	}

	public static final class DebugStateException extends RuntimeException {
		private final String code;

		public DebugStateException(String code, String message) {
			super(message);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}

	private record PendingCapture(
		CompletableFuture<ClientTickCapture> future,
		CaptureIntent intent,
		ClientTickSnapshot snapshot
	) {
		private static PendingCapture command(CompletableFuture<ClientTickCapture> future) {
			return new PendingCapture(future, null, null);
		}

		private PendingCapture withIntent(CaptureIntent value) {
			return new PendingCapture(future, value, snapshot);
		}

		private PendingCapture withSnapshot(ClientTickSnapshot value) {
			return new PendingCapture(future, intent, value);
		}
	}
}
