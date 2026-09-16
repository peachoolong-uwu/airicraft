package ai.moeru.airicraft.os;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import java.util.List;
import java.util.UUID;

/** One bounded observer owned by the current local driver. Tick hooks reveal progress, not world facts. */
public final class NativeProgressRuntime {
	private static MinecraftServer server;
	private static String owner;
	private static NativeProgressClocks clocks;

	private NativeProgressRuntime() {}

	public static synchronized NativeProgressClocks.Snapshot capture(MinecraftServer current, String observer, List<NativeProgressClocks.Scope> scopes) {
		if (current != server || !observer.equals(owner)) {
			server = current; owner = observer; clocks = new NativeProgressClocks();
		}
		clocks.configure(scopes);
		return clocks.snapshot();
	}
	public static synchronized void begin(MinecraftServer current) {
		if (current == server && clocks != null) clocks.begin();
	}
	public static synchronized void complete(MinecraftServer current) {
		if (current == server && clocks != null) clocks.complete(current.getTickManager().shouldTick() && !current.isPaused());
	}
	public static synchronized void chunk(ServerWorld world, int x, int z) {
		if (world.getServer() == server && clocks != null && !clocks.empty()) clocks.chunk(world.getRegistryKey().getValue().toString(), x, z);
	}
	public static synchronized void entity(ServerWorld world, UUID entityId) {
		if (world.getServer() == server && clocks != null && !clocks.empty()) clocks.entity(world.getRegistryKey().getValue().toString(), entityId);
	}
	public static synchronized void stop(MinecraftServer current) {
		if (current == server) { server = null; owner = null; clocks = null; }
	}
}
