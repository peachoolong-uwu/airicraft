package ai.moeru.airicraft.os;

import ai.moeru.airicraft.agent.NativeContainerActions;
import ai.moeru.airicraft.agent.NativeDriverService;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Only the external Minecraft inventory/client loop is simulated. */
final class OsMinecraftFixture implements AutoCloseable {
	final ScheduledExecutorService client = Executors.newSingleThreadScheduledExecutor();
	final Chest chest = new Chest();
	final NativeActionRuntime nativeRuntime = new NativeActionRuntime(new NativeContainerActions(chest, System::nanoTime), System::nanoTime);
	long ticks;
	final NativeDriverService driver = new NativeDriverService(nativeRuntime, System::nanoTime, () -> ticks, () -> ticks);
	OsMinecraftFixture() { client.scheduleAtFixedRate(() -> { ticks++; driver.tick(); }, 0, 10, TimeUnit.MILLISECONDS); }
	CompletableFuture<JsonObject> call(String name, JsonObject args) {
		return CompletableFuture.supplyAsync(() -> driver.execute(name, args), client);
	}
	int carried() throws Exception { return client.submit(() -> chest.carried).get(); }
	@Override public void close() { client.shutdownNow(); }
	static final class Chest implements NativeContainerActions.Access {
		int stored = 20, carried, cursor, visits, closures, pickups;
		boolean open = true;
		@Override public NativeActionRuntime.World world() { return new NativeActionRuntime.World("save", "minecraft:overworld", "load", true, false, false); }
		@Override public NativeContainerActions.Window capture() {
			return new NativeContainerActions.Window(open, open ? "window-a" : "", open ? 7 : 0,
				List.of(slot(0, true, stored), slot(27, false, carried)), slot(-1, false, cursor));
		}
		private NativeContainerActions.Slot slot(int id, boolean container, int count) { return new NativeContainerActions.Slot(id, container, count == 0 ? "" : "minecraft:wheat", count == 0 ? "" : "plain", count, 64); }
		@Override public List<NativeContainerActions.Slot> inventory() { return java.util.stream.IntStream.range(0, 36).mapToObj(index -> slot(index, false, index == 0 ? carried : 0)).toList(); }
		@Override public void retainWindow(String id) { visits++; }
		@Override public CompletableFuture<NativeContainerActions.Window> confirm(String id) { return CompletableFuture.completedFuture(capture()); }
		@Override public CompletableFuture<NativeContainerActions.Window> click(NativeContainerActions.Window expected, int slot, int button, NativeActionRuntime.EffectPermit permit, NativeActionRuntime.Permission permission) {
			permit.perform(permission, () -> {
				if (slot == 0) { if (cursor == 0 && stored > 0) pickups++; int previous = stored; stored = cursor; cursor = previous; }
				else if (button == 1 && cursor > 0) { carried++; cursor--; }
				else throw new IllegalStateException("unexpected_click");
			});
			return CompletableFuture.completedFuture(capture());
		}
		@Override public CompletableFuture<NativeContainerActions.Window> close(NativeContainerActions.Window expected, NativeActionRuntime.EffectPermit permit, NativeActionRuntime.Permission permission) {
			permit.perform(permission, () -> { if (cursor != 0) throw new IllegalStateException("cursor_not_empty"); open = false; closures++; });
			return CompletableFuture.completedFuture(capture());
		}
		@Override public boolean controlsReleased() { return true; }
	}
}
