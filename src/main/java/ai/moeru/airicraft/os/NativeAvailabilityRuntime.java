package ai.moeru.airicraft.os;

import net.minecraft.server.MinecraftServer;

/** A read-only driver observer. Samplers run on the server thread, outside the registry monitor. */
public final class NativeAvailabilityRuntime {
	private static Registration registration;
	private NativeAvailabilityRuntime() {}

	public interface Sampler { String capture(MinecraftServer server); }
	private record Registration(MinecraftServer server, String owner, Sampler sampler, NativeAvailabilityClock clock) {}
	public static synchronized void register(MinecraftServer server, String owner, Sampler sampler) {
		if (registration == null || registration.server != server || !registration.owner.equals(owner))
			registration = new Registration(server, owner, sampler, new NativeAvailabilityClock());
	}
	public static void invalidate(MinecraftServer server, String owner) {
		var current = selected(server, owner);
		if (current != null) current.clock.invalidate();
	}
	public static NativeAvailabilityClock.Snapshot snapshot(MinecraftServer server, String owner) {
		var current = selected(server, owner);
		return current == null ? null : current.clock.snapshot();
	}
	public static synchronized void unregister(MinecraftServer server, String owner) {
		if (selected(server, owner) != null) registration = null;
	}
	public static synchronized void stop(MinecraftServer server) {
		if (registration != null && registration.server == server) registration = null;
	}
	private static synchronized Registration selected(MinecraftServer server, String owner) {
		return registration != null && registration.server == server && (owner == null || registration.owner.equals(owner)) ? registration : null;
	}
	public static void begin(MinecraftServer server) {
		var current = selected(server, null);
		if (current != null) current.clock.begin(capture(current));
	}
	public static void complete(MinecraftServer server) {
		var current = selected(server, null);
		if (current != null) current.clock.complete(server.getTickManager().shouldTick() && !server.isPaused(), capture(current));
	}
	private static String capture(Registration current) {
		try { return current.sampler.capture(current.server); }
		catch (RuntimeException unavailable) { return null; }
	}
}
