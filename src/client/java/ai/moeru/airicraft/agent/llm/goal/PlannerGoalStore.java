package ai.moeru.airicraft.agent.llm.goal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** One planner objective per local world. Action jobs have a separate lifecycle. */
public final class PlannerGoalStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Supplier<Path> worldDirectory;
	private Path loadedWorld;
	private volatile Goal goal;
	private volatile String error;

	public PlannerGoalStore(Supplier<Path> worldDirectory) {
		this.worldDirectory = worldDirectory;
	}

	/** Called on the client thread; reads disk only when the world changes. */
	public void refreshWorld() {
		Path world = worldDirectory.get();
		if (Objects.equals(world, loadedWorld)) return;
		loadedWorld = world;
		goal = null;
		error = null;
		if (world == null) return;
		try {
			Path file = file();
			if (!Files.exists(file)) return;
			Document document = GSON.fromJson(Files.readString(file), Document.class);
			if (document == null || document.version() != 1 || document.goal() == null) {
				throw new IllegalArgumentException("invalid planner goal document");
			}
			goal = document.goal();
		}
		catch (IOException | RuntimeException exception) {
			error = "planner_goal_load_failed: " + exception.getMessage();
		}
	}

	public Goal snapshot() { return goal; }
	public boolean active() { return goal != null && goal.status() == Status.ACTIVE; }
	public String context() {
		return error != null ? error : goal == null ? "No planner goal." : GSON.toJson(goal);
	}

	public Goal set(String objective) throws IOException {
		requireWorld();
		if (active()) throw new IllegalArgumentException("goal_active: change or finish the current goal first");
		return save(new Goal(UUID.randomUUID().toString(), objective, Status.ACTIVE, ""));
	}

	public Goal change(String id, String objective, String reason) throws IOException {
		requireActive(id);
		// New identity makes an outcome for the previous objective inapplicable.
		return save(new Goal(UUID.randomUUID().toString(), objective, Status.ACTIVE, checked(reason, "reason")));
	}

	public Goal finish(String id, Status status, String outcome) throws IOException {
		requireActive(id);
		if (status == null || status == Status.ACTIVE) throw new IllegalArgumentException("outcome must be success or give_up");
		return save(new Goal(id, goal.objective(), status, checked(outcome, "outcome")));
	}

	private void requireWorld() {
		refreshWorld();
		if (loadedWorld == null) throw new IllegalStateException("planner_goal_requires_local_world_save");
		if (error != null) throw new IllegalStateException(error);
	}

	private void requireActive(String id) {
		requireWorld();
		if (!active() || !goal.id().equals(id)) throw new IllegalArgumentException("stale_or_inactive_goal: inspect current planner goal");
	}

	private Path file() { return loadedWorld.resolve("airicraft/planner-goal.json"); }

	private Goal save(Goal replacement) throws IOException {
		Path file = file();
		Files.createDirectories(file.getParent());
		Path temporary = Files.createTempFile(file.getParent(), "planner-goal-", ".tmp");
		try {
			Files.writeString(temporary, GSON.toJson(new Document(1, replacement)) + "\n");
			Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			goal = replacement;
			return replacement;
		}
		finally { Files.deleteIfExists(temporary); }
	}

	public static String checked(String value, String field) {
		if (value == null || value.isBlank() || value.length() > 2048) {
			throw new IllegalArgumentException(field + " must be 1..2048 characters");
		}
		return value.trim();
	}

	public enum Status { ACTIVE, SUCCEEDED, GIVEN_UP }
	public record Goal(String id, String objective, Status status, String outcome) {
		public Goal {
			id = checked(id, "id");
			objective = checked(objective, "objective");
			Objects.requireNonNull(status, "status");
			if (outcome == null || outcome.length() > 2048 || (status != Status.ACTIVE && outcome.isBlank())) {
				throw new IllegalArgumentException("invalid outcome");
			}
		}
	}
	private record Document(int version, Goal goal) {}
}
