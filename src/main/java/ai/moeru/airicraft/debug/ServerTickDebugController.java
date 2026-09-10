package ai.moeru.airicraft.debug;

import java.util.Objects;

/**
 * The authoritative pause/step state for a logical Minecraft server.
 *
 * <p>This controller deliberately contains no client or rendering types. The server tick mixin
 * is its only interpreter: a paused controller means {@code MinecraftServer.tick} is not called.
 */
public final class ServerTickDebugController {
	private Phase phase = Phase.RUNNING;
	private String debugSessionId;
	private long pauseEpoch;
	private long serverTickId;

	public synchronized void pause(String requestedDebugSessionId) {
		if (phase == Phase.PAUSED && Objects.equals(debugSessionId, requestedDebugSessionId)) return;
		requirePhase(Phase.RUNNING, "debug_busy", "The server tick debugger is busy");
		debugSessionId = requireSessionId(requestedDebugSessionId);
		pauseEpoch = 0L;
		phase = Phase.PAUSE_REQUESTED;
	}

	public synchronized void step(String requestedSessionId, long requestedPauseEpoch) {
		requirePausedAuthority(requestedSessionId, requestedPauseEpoch);
		phase = Phase.STEP_ARMED;
	}

	public synchronized void continueRunning(String requestedSessionId, long requestedPauseEpoch) {
		requirePausedAuthority(requestedSessionId, requestedPauseEpoch);
		phase = Phase.RUNNING;
		debugSessionId = null;
		pauseEpoch = 0L;
	}

	/** Called at the head of {@code MinecraftServer.tick}. */
	public synchronized boolean beginServerTick() {
		return switch (phase) {
			case RUNNING -> true;
			case PAUSE_REQUESTED -> {
				phase = Phase.PAUSE_TICK_RUNNING;
				yield true;
			}
			case STEP_ARMED -> {
				phase = Phase.STEP_RUNNING;
				yield true;
			}
			case PAUSE_TICK_RUNNING, STEP_RUNNING -> throw new DebugStateException(
				"debug_state_invalid", "A server tick began while the previous debug tick was still running"
			);
			case PAUSED -> false;
		};
	}

	/** Called at the tail of every server tick which the controller allowed. */
	public synchronized void completeServerTick() {
		serverTickId++;
		if (phase == Phase.PAUSE_TICK_RUNNING || phase == Phase.STEP_RUNNING) {
			pauseEpoch++;
			phase = Phase.PAUSED;
		}
	}

	public synchronized DebugStatus status() {
		return new DebugStatus(phase, debugSessionId, pauseEpoch, serverTickId);
	}

	public synchronized void reset() {
		phase = Phase.RUNNING;
		debugSessionId = null;
		pauseEpoch = 0L;
	}

	private void requirePausedAuthority(String requestedSessionId, long requestedPauseEpoch) {
		requirePhase(Phase.PAUSED, "debug_not_paused", "Server ticks are not paused");
		if (!Objects.equals(debugSessionId, requestedSessionId)) {
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

	private static String requireSessionId(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("debugSessionId must not be blank");
		}
		return value;
	}

	public enum Phase {
		RUNNING,
		PAUSE_REQUESTED,
		PAUSE_TICK_RUNNING,
		PAUSED,
		STEP_ARMED,
		STEP_RUNNING
	}

	public record DebugStatus(Phase phase, String debugSessionId, long pauseEpoch, long serverTickId) {
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
}
