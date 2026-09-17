package ai.moeru.airicraft.playtest;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.ClientRuntimeController;
import ai.moeru.airicraft.agent.recording.AutomaticPlaytestRecording;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.WorldSavePath;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Client-thread owner of one opt-in recording and its report/pause boundary. */
public final class AutomaticPlaytestRuntime {
	public static boolean enabled() { return Boolean.getBoolean("airicraft.automaticPlaytest"); }
	private enum State { IDLE, RECORDING, REPORT_PENDING, PAUSING, CAPTURE_READY, FINISHED, FAILED }
	private final ClientRuntimeController controller;
	private final Path root;
	private State state = State.IDLE;
	private AutomaticPlaytestRecording recording;
	private boolean resultCommitted;
	private String error = "";
	private String pendingDescription = "";
	private long sessionEpoch;

	public AutomaticPlaytestRuntime(ClientRuntimeController controller, Path root) {
		this.controller = controller;
		this.root = root;
	}

	public void onClientTick(MinecraftClient client) {
		if (!enabled() || client.world == null || client.getServer() == null) return;
		try {
			if (state == State.IDLE) {
				Map<String, Object> context = Map.of(
					"clock", ai.moeru.airicraft.debug.ServerTickDebugRuntime.tickAnchor(),
					"worldPath", client.getServer().getSavePath(WorldSavePath.ROOT).toString(),
					"dimension", client.world.getRegistryKey().getValue().toString());
				String runId = System.getProperty("airicraft.automaticPlaytestId");
				recording = runId == null ? new AutomaticPlaytestRecording(root, context) : new AutomaticPlaytestRecording(root, runId, context);
				state = State.RECORDING;
			}
			if (state == State.REPORT_PENDING && resultCommitted) { pause(client); return; }
			if (state == State.RECORDING) recording.recordTick(controller.agentRuntime(), controller.liveRecording());
		}
		catch (IOException exception) {
			fail(exception);
		}
	}

	public String report(String description) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!enabled()) return "TOOL_ERROR: something_wrong: automatic_playtest_disabled";
		if (client.world == null || client.getServer() == null)
			return "TOOL_ERROR: something_wrong: local_singleplayer_required";
		if (state == State.IDLE) onClientTick(client);
		if (state == State.CAPTURE_READY) {
			return "Tool result for something_wrong: reportId=" + recording.id()
				+ " state=CAPTURE_READY outputDir=" + recording.pendingDirectory() + ". This playtest was already reported; the launcher will finalize its Recorder Play.";
		}
		if (state == State.FINISHED) return "TOOL_ERROR: something_wrong: playtest_already_finished";
		if (state == State.RECORDING || state == State.FAILED) {
			pendingDescription = description;
			if (recording != null) {
				try { recording.report(description, controller.agentRuntime().tickCount()); }
				catch (IOException exception) { fail(exception); }
			}
			state = State.REPORT_PENDING;
		}
		return "Tool result for something_wrong: reportId=" + (recording == null ? "unrecorded" : recording.id())
			+ " state=" + state + " outputDir=" + (recording == null ? root : recording.incidentDirectory())
			+ ". Report accepted; this playtest will pause. The launcher will close the client to finalize its Recorder Play and publish the complete incident."
			+ (error.isEmpty() ? "" : " Recording error: " + error);

	}

	/** Called only after the tool receipt is recorded; never wait for a frozen planner tick. */
	public void resultCommitted() { resultCommitted = true; }

	private void pause(MinecraftClient client) {
		state = State.PAUSING;
		long reportSessionEpoch = sessionEpoch;
		try {
			var trace = controller.clientTickDebugRuntime().traceStatus();
			if (trace.active()) controller.clientTickDebugRuntime().stopTrace(trace.traceId());
			controller.clientTickDebugRuntime().pause(client, false).whenComplete((capture, failure) -> client.execute(() -> {
				if (sessionEpoch != reportSessionEpoch) return;
				if (failure != null) { fail(failure); return; }
				try {
					if (recording == null) throw new IOException("Recording could not be started: " + error);
					recording.report(pendingDescription, controller.agentRuntime().tickCount());
					recording.finish(controller.agentRuntime(), controller.liveRecording(), Map.of(
						"debugSessionId", capture.debugSessionId(), "pauseEpoch", capture.pauseEpoch(),
						"snapshot", capture.snapshot(), "frameStatus", capture.frame().status()), capture.frame().imageBytes());
					state = State.CAPTURE_READY;
					error = "";
					Airicraft.LOGGER.info("Automatic playtest capture ready for Recorder Play finalization: {}", recording.pendingDirectory());
				}
				catch (IOException | RuntimeException exception) { fail(exception); }
			}));
		}
		catch (RuntimeException exception) { fail(exception); }
	}

	public boolean freezing() { return state == State.PAUSING; }
	public boolean captureReady() { return state == State.CAPTURE_READY; }

	/** Complete the paused world checkpoint before allowing normal disconnect/Recorder Play finalization. */
	public java.util.concurrent.CompletableFuture<Void> prepareShutdown(MinecraftClient client) {
		var server = client.getServer();
		if (!captureReady() || server == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
		var activeRecording = recording;
		return server.submit(() -> {
			try {
				var clock = ai.moeru.airicraft.debug.ServerTickDebugRuntime.controller().status();
				if (!clock.paused()) throw new IllegalStateException("World checkpoint requires the report pause");
				server.getPlayerManager().saveAllPlayerData();
				server.save(false, true, true);
				activeRecording.saveWorldCheckpoint(server.getSavePath(WorldSavePath.ROOT), Map.of(
					"serverTickId", clock.serverTickId(),
					"worldTime", server.getOverworld().getTime(), "timeOfDay", server.getOverworld().getTimeOfDay(),
					"capturedWhilePaused", true));
			}
			catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
		});
	}

	public Map<String, Object> statusPayload() {
		return Map.of("enabled", enabled(), "state", state.name(), "error", error,
			"outputDir", recording == null ? "" : recording.pendingDirectory().toString());
	}

	public void worldLeft(String reason) {
		// This mode has one run per process. Keep its pause through disconnect/server save.
		if (captureReady() || state == State.FINISHED || recording == null) return;
		sessionEpoch++;
		try {
			recording.finish(controller.agentRuntime(), controller.liveRecording(), reason);
			state = State.FINISHED;
		}
		catch (IOException | RuntimeException exception) { fail(exception); }
	}

	private void fail(Throwable failure) {
		error = failure.toString();
		state = State.FAILED;
		Airicraft.LOGGER.error("Automatic playtest recording failed; incomplete files remain in .in-progress", failure);
	}
}
