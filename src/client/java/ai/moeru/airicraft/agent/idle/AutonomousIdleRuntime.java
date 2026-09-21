package ai.moeru.airicraft.agent.idle;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.job.ActiveJob;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexSnapshot;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexState;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskType;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.Map;

/**
 * Rule-based autonomous idle behavior. When the agent has no active job,
 * no reflex hold, and no planner goal, it queues small resource-gathering
 * tasks so idle time is spent productively. Unlike IdleIdeaScheduler this
 * does not require an LLM planner — it submits tasks directly.
 */
public final class AutonomousIdleRuntime {
	private static final int WOOD_TARGET = 16;
	private static final int COBBLE_TARGET = 32;
	private static final int COAL_TARGET = 16;

	private final AgentConfig.AutonomousIdleConfig config;
	private long idleSinceTick = -1L;
	private long lastTaskTick = -1L;
	private String lastJobId;

	public AutonomousIdleRuntime(AgentConfig.AutonomousIdleConfig config) {
		this.config = config == null ? AgentConfig.AutonomousIdleConfig.defaults() : config;
	}

	public void reset() {
		idleSinceTick = -1L;
		lastTaskTick = -1L;
		lastJobId = null;
	}

	/**
	 * @return a TaskSpec to submit, or null if the agent should stay idle.
	 */
	public TaskSpec tick(
		MinecraftClient client,
		SessionSnapshot session,
		ActiveJob job,
		SurvivalReflexSnapshot reflex,
		WorldEvidence evidence,
		long tickCount
	) {
		if (!config.enabled()) {
			idleSinceTick = -1L;
			return null;
		}
		if (!session.worldLoaded() || !session.companionActuationAllowed()) {
			idleSinceTick = -1L;
			return null;
		}
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null || !player.isAlive()) {
			idleSinceTick = -1L;
			return null;
		}
		if (reflex != null && reflex.state() != SurvivalReflexState.IDLE) {
			idleSinceTick = -1L;
			return null;
		}
		boolean jobIdle = job == null || job.isIdle() || job.status().terminal();
		if (!jobIdle) {
			idleSinceTick = -1L;
			lastJobId = job.jobId();
			return null;
		}
		long cooldownTicks = config.taskCooldownSeconds() * 20L;
		if (lastJobId != null && job != null && lastJobId.equals(job.jobId()) && job.status().terminal()) {
			if (tickCount - lastTaskTick < cooldownTicks) {
				return null;
			}
		}
		if (idleSinceTick < 0L) {
			idleSinceTick = tickCount;
		}
		long idleDelayTicks = config.idleDelaySeconds() * 20L;
		if (tickCount - idleSinceTick < idleDelayTicks) {
			return null;
		}
		if (tickCount - lastTaskTick < cooldownTicks) {
			return null;
		}
		TaskSpec spec = pickTask(evidence);
		if (spec != null) {
			lastTaskTick = tickCount;
			lastJobId = null;
		}
		return spec;
	}

	private static TaskSpec pickTask(WorldEvidence evidence) {
		Map<TaskResourceKind, Integer> counts = evidence == null ? Map.of() : evidence.inventoryCounts();
		int wood = counts.getOrDefault(TaskResourceKind.WOOD_LOGS, 0);
		int cobble = counts.getOrDefault(TaskResourceKind.COBBLESTONE, 0);
		int coal = counts.getOrDefault(TaskResourceKind.COAL, 0);
		if (wood < WOOD_TARGET) {
			return new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, WOOD_TARGET - wood);
		}
		if (cobble < COBBLE_TARGET) {
			return new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.COBBLESTONE, COBBLE_TARGET - cobble);
		}
		if (coal < COAL_TARGET) {
			return new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.COAL, COAL_TARGET - coal);
		}
		return null;
	}
}
