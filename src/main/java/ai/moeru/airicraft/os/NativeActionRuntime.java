package ai.moeru.airicraft.os;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Identified native admissions; all methods run on the owning client thread. */
public final class NativeActionRuntime {
	private static final int RETAINED_RECEIPTS = 256;
	private static final int RETAINED_OBSERVATIONS = 256;
	private static final long LEASE_NANOS = 5_000_000_000L;
	private final Port port;
	private final LongSupplier nanoTime;
	private final String sessionId = UUID.randomUUID().toString();
	private final ArrayDeque<Event> events = new ArrayDeque<>();
	private long eventSequence;
	private String epoch = UUID.randomUUID().toString();
	private World world;
	private final Map<String, Observation> observations = new LinkedHashMap<>();
	private final Map<Id, Entry> requests = new LinkedHashMap<>();
	private long captureSequence;
	private long effectRevision;
	private long leaseGeneration;
	private long admissionSequence;
	private long lastHeartbeat;
	private Lease lease;
	private Entry active;
	private Entry context;

	public NativeActionRuntime(Port port, LongSupplier nanoTime) {
		this.port = Objects.requireNonNull(port);
		this.nanoTime = Objects.requireNonNull(nanoTime);
	}

	public Observation observe() {
		return observe(new JsonObject());
	}
	public Observation observe(JsonObject query) {
		expireLease();
		var observation = new Observation(sessionId, epoch, epoch + "/" + ++captureSequence, captureSequence, nanoTime.getAsLong(), effectRevision, world, port.observe(query));
		observations.put(observation.captureId(), observation);
		while (observations.size() > RETAINED_OBSERVATIONS) observations.remove(observations.keySet().iterator().next());
		return observation;
	}

	public Lease acquire(String hostId, String expectedEpoch) {
		expireLease();
		requireAvailablePlayer();
		if (!epoch.equals(expectedEpoch)) throw new Rejected("stale_epoch");
		if ((active != null || context != null) && lease == null) throw new Rejected("unresolved_release");
		if (lease != null) {
			if (lease.hostId().equals(hostId)) return lease;
			throw new Rejected("player_owned");
		}
		lease = new Lease(epoch, ++leaseGeneration, hostId);
		admissionSequence = 0;
		lastHeartbeat = nanoTime.getAsLong();
		recordEvent("lease_acquired", "", lease, null);
		return lease;
	}

	public Lease heartbeat(Lease expected) {
		expireLease();
		if (!Objects.equals(lease, expected) || lease == null) throw new Rejected("stale_fence");
		lastHeartbeat = nanoTime.getAsLong();
		if (active != null) active.permit.heartbeat(lastHeartbeat);
		if (context != null) context.permit.heartbeat(lastHeartbeat);
		publishAvailability();
		return lease;
	}

	public Receipt submit(Request request) {
		expireLease();
		if (lease == null || !lease.epoch().equals(request.id().epoch()) || lease.generation() != request.id().generation())
			throw new Rejected("stale_fence");
		if (!request.payloadHash().equals(fingerprint(request.captureId(), request.operation(), request.arguments())))
			throw new Rejected("payload_hash_mismatch");
		Entry existing = requests.get(request.id());
		if (existing != null) {
			if (!existing.payloadHash.equals(request.payloadHash())) throw new Rejected("request_conflict");
			return existing.receipt;
		}
		if (request.id().sequence() < 1) throw new Rejected("invalid_sequence");
		if (request.id().sequence() <= admissionSequence) throw new Rejected("outcome_unknown");
		admissionSequence = request.id().sequence();
		var permit = new EffectPermit(nanoTime);
		Operation operation;
		try {
			requireAvailablePlayer();
			if (active != null) throw new Rejected("player_owned");
			if (!port.operations().contains(request.operation())) throw new Rejected("operation_not_granted");
			Observation basis = observations.get(request.captureId());
			if (basis == null) throw new Rejected("observation_unknown");
			if (basis.effectRevision() != effectRevision || nanoTime.getAsLong() - basis.capturedAtNanos() >= 2_000_000_000L)
				throw new Rejected("observation_stale");
			var arguments = request.arguments();
			if (context != null) {
				var supplied = arguments.remove("contextId");
				if (supplied == null || !supplied.isJsonPrimitive() || !supplied.getAsJsonPrimitive().isString()
					|| !context.contextId.equals(supplied.getAsString())) throw new Rejected("context_required");
				if (!contextReady(context)) throw new Rejected("context_not_ready");
				operation = ((ContextOperation) context.operation).prepare(request.operation(), arguments, basis.facts(), permit);
				if (operation instanceof ContextOperation) throw new Rejected("nested_context");
			} else {
				if (arguments.has("contextId")) throw new Rejected("context_unknown");
				operation = port.prepare(request.operation(), arguments, basis.facts(), permit);
			}
		} catch (Rejected rejected) {
			return retainRejection(request, rejected.code(), permit);
		} catch (IllegalArgumentException | IllegalStateException invalid) {
			return retainRejection(request, "invalid_request", permit);
		}
		String contextId = operation instanceof ContextOperation
			? request.id().epoch() + "/" + request.id().generation() + "/" + request.id().sequence()
			: context == null ? null : context.contextId;
		var receipt = new Receipt(request.id(), State.ACCEPTED, false, "accepted", "",
			new Basis(1, request.operation(), request.captureId(), request.payloadHash()), contextId == null ? Map.of() : Map.of("contextId", contextId));
		var entry = new Entry(request.payloadHash(), operation, receipt, permit);
		entry.contextId = contextId;
		if (operation instanceof ContextOperation) context = entry;
		else active = entry;
		requests.put(request.id(), entry);
		recordEvent("receipt", "", lease, receipt);
		return receipt;
	}

	/** Consuming an admission sequence must leave a queryable disposition, even when preparation rejects it. */
	private Receipt retainRejection(Request request, String reason, EffectPermit permit) {
		var receipt = new Receipt(request.id(), State.FAILED, true, "admission_rejected", reason,
			new Basis(1, request.operation(), request.captureId(), request.payloadHash()),
			Map.of("admitted", false, "accountingComplete", true,
				"releaseEvidence", Map.of("verified", true, "source", "admission_not_started")));
		requests.put(request.id(), new Entry(request.payloadHash(), null, receipt, permit));
		recordEvent("receipt", reason, lease, receipt);
		trimReceipts();
		return receipt;
	}

	private void trimReceipts() {
		var iterator = requests.entrySet().iterator();
		while (requests.size() > RETAINED_RECEIPTS + (active == null ? 0 : 1) + (context == null ? 0 : 1) && iterator.hasNext()) {
			var entry = iterator.next().getValue();
			if (entry != active && entry != context) iterator.remove();
		}
	}

	public void release(Lease expected) {
		expireLease();
		if (lease == null || !lease.equals(expected)) throw new Rejected("stale_fence");
		revoke("host_released");
	}

	/** Called synchronously before lifecycle, reflex or operator control changes. Cleanup remains owned. */
	public void interrupt(String reason) {
		if (reason == null || reason.isBlank()) throw new IllegalArgumentException("An interruption needs a reason");
		revoke(reason);
	}

	public boolean blocksOrdinaryActions() {
		expireLease();
		return lease != null || active != null || context != null;
	}

	public Authority authority() {
		expireLease();
		return new Authority(sessionId, epoch, leaseGeneration, lease, active == null ? null : active.receipt,
			context == null ? null : context.receipt, admissionSequence);
	}

	public Receipt inspect(Id id) {
		expireLease();
		Entry entry = requests.get(id);
		if (entry == null) throw new Rejected("outcome_unknown");
		return entry.receipt;
	}

	public History history(long sinceSeqNo, int limit) {
		expireLease();
		if (sinceSeqNo < 0 || sinceSeqNo > eventSequence || limit < 1 || limit > 32) throw new Rejected("invalid_history_cursor");
		long oldest = events.isEmpty() ? eventSequence + 1 : events.getFirst().seqNo();
		var page = events.stream().filter(event -> event.seqNo() > sinceSeqNo).limit(limit).toList();
		return new History(oldest, eventSequence, page.isEmpty() ? sinceSeqNo : page.getLast().seqNo(),
			sinceSeqNo < oldest - 1, page);
	}

	public Receipt cancel(Lease expected, Id id) {
		expireLease();
		if (lease == null || !lease.equals(expected) || !id.epoch().equals(lease.epoch()) || id.generation() != lease.generation())
			throw new Rejected("stale_fence");
		Entry entry = requests.get(id);
		if (entry == null) throw new Rejected("outcome_unknown");
		requestCancellation(entry, "cancelled", false);
		return entry.receipt;
	}

	public void tick() {
		expireLease();
		// A context cannot enter/exit while one of its operations still owns cleanup.
		if (active != null) tickEntry(active);
		else if (context != null) tickEntry(context);
		publishAvailability();
	}

	private boolean contextReady(Entry entry) {
		return entry.cancelReason.isEmpty() && ((ContextOperation) entry.operation).ready();
	}

	private Map<String, Object> effects(Entry entry, Map<String, Object> effects) {
		if (entry.contextId == null) return effects;
		var result = new LinkedHashMap<>(effects);
		result.put("contextId", entry.contextId);
		if (entry == context) {
			result.put("contextReady", contextReady(entry));
			result.put("completedOperations", entry.completedOperations);
			if (entry.cleanupFailure != null) result.put("cleanupFailure", entry.cleanupFailure);
		}
		return result;
	}

	private void tickEntry(Entry entry) {
		boolean cancelling = !entry.cancelReason.isEmpty();
		Permission permission = world.reflexActive() || world.controllerBusy() ? Permission.OBSERVE : cancelling ? Permission.CLEANUP : Permission.RUN;
		entry.permit.update(permission, lastHeartbeat);
		// An idle, ready visit performs no effects; its fresh capture remains usable for the next operation.
		if (permission != Permission.OBSERVE && (entry != context || !contextReady(entry))) effectRevision++;
		Progress progress;
		try {
			progress = entry.operation.tick(permission);
		} catch (RuntimeException exception) {
			if (entry == context && permission == Permission.CLEANUP) {
				entry.failed = true;
				if (entry.cleanupFailure == null) entry.cleanupFailure = "executor_exception";
			}
			requestCancellation(entry, "executor_exception", true);
			return;
		}
		if (entry == context && cancelling && progress.state() == State.FAILED
			&& !progress.released() && !progress.reason().equals("effect_fenced")) {
			entry.failed = true;
			if (entry.cleanupFailure == null) entry.cleanupFailure = progress.reason().isEmpty() ? "context_cleanup_failed" : progress.reason();
		}
		if (!cancelling && (progress.state() == State.FAILED || progress.state() == State.CANCELLED)) {
			requestCancellation(entry, progress.reason().isEmpty() ? "executor_stopped" : progress.reason(), progress.state() == State.FAILED);
			cancelling = true;
		}
		State state = cancelling ? (progress.released() ? (entry.failed ? State.FAILED : State.CANCELLED) : State.RECONCILING) : progress.state();
		if (!progress.released() && state == State.SUCCEEDED) state = State.RECONCILING;
		updateReceipt(entry, new Receipt(entry.receipt.id(), state, progress.released(), progress.released() ? "released" : progress.phase(),
			cancelling ? entry.cancelReason : progress.reason(), entry.receipt.basis(), effects(entry, progress.effects())));
		if (progress.released()) {
			entry.permit.update(Permission.OBSERVE, lastHeartbeat);
			entry.operation = null;
			// Retention is ordered by settlement; a long-lived active request may predate rejected requests.
			requests.remove(entry.receipt.id());
			requests.put(entry.receipt.id(), entry);
			if (entry == context) context = null;
			else {
				active = null;
				if (context != null) {
					context.completedOperations++;
					if (state == State.FAILED || !Boolean.TRUE.equals(progress.effects().get("accountingComplete")))
						requestCancellation(context, "context_operation_failed", true);
					var before = context.receipt;
					updateReceipt(context, new Receipt(before.id(), before.state(), false, before.phase(), before.reason(), before.basis(), effects(context, before.effects())));
				}
			}
			trimReceipts();
		}
	}

	private void requestCancellation(Entry entry, String reason, boolean failed) {
		if (entry.receipt.released()) return;
		entry.permit.update(Permission.OBSERVE, lastHeartbeat);
		boolean first = entry.cancelReason.isEmpty();
		entry.failed |= failed;
		if (first) {
			entry.cancelReason = reason;
		}
		var before = entry.receipt;
		updateReceipt(entry, new Receipt(before.id(), State.RECONCILING, false, first ? "cancel_requested" : before.phase(),
			entry.cancelReason, before.basis(), effects(entry, before.effects())));
		if (entry == context && active != null) requestCancellation(active, reason, failed);
	}

	private void expireLease() {
		World current = port.world();
		if (world != null && (!world.worldId().equals(current.worldId())
			|| !world.dimension().equals(current.dimension()) || !world.loadId().equals(current.loadId()))) {
			epoch = UUID.randomUUID().toString();
			observations.clear();
			revoke("world_changed");
		}
		world = current;
		if (current.reflexActive()) revoke("reflex_takeover");
		if (!current.alive()) revoke("player_unavailable");
		if (current.controllerBusy()) revoke("external_controller");
		if (lease != null && nanoTime.getAsLong() - lastHeartbeat >= LEASE_NANOS) revoke("lease_expired");
		publishAvailability();
	}

	private void requireAvailablePlayer() {
		if (!world.alive()) throw new Rejected("player_unavailable");
		if (world.reflexActive()) throw new Rejected("reflex_active");
		if (world.controllerBusy()) throw new Rejected("controller_busy");
	}

	private void revoke(String reason) {
		Lease revoked = lease;
		lease = null;
		if (revoked != null) recordEvent("lease_revoked", reason, revoked, active == null ? null : active.receipt);
		if (context != null) requestCancellation(context, reason, false);
		if (active != null) requestCancellation(active, reason, false);
		publishAvailability();
	}

	private void updateReceipt(Entry entry, Receipt receipt) {
		if (receipt.equals(entry.receipt)) return;
		entry.receipt = receipt;
		recordEvent("receipt", "", lease, receipt);
	}
	private void recordEvent(String type, String reason, Lease owner, Receipt receipt) {
		events.addLast(new Event(++eventSequence, epoch, Long.toString(nanoTime.getAsLong()), type, reason, owner, receipt));
		if (events.size() > 512) events.removeFirst();
		publishAvailability();
	}
	private void publishAvailability() {
		if (world != null) port.availability(new AvailabilityGate(world, lease, active == null && context == null, lastHeartbeat));
	}

	public record AvailabilityGate(World world, Lease lease, boolean idle, long heartbeatAtNanos) {}

	public interface Port {
		World world();
		/** Client-thread authority notification for passive native availability evidence. */
		default void availability(AvailabilityGate gate) {}
		Set<String> operations();
		JsonObject observe();
		default JsonObject observe(JsonObject query) {
			if (!query.keySet().isEmpty()) throw new Rejected("observation_query_unsupported");
			return observe();
		}
		/** Validate without effects. The first effect must wait until the admitted operation is ticked. */
		Operation prepare(String operation, JsonObject arguments, JsonObject observation, EffectPermit permit);
	}

	public interface Operation {
		Progress tick(Permission permission);
	}

	/** Retained setup owns a separate admission until cleanup, across independently identified operations. */
	public interface ContextOperation extends Operation {
		boolean ready();
		Operation prepare(String operation, JsonObject arguments, JsonObject observation, EffectPermit permit);
	}

	/** Effect threads recheck this permit at the actual mutation, not merely when it was queued. */
	public static final class EffectPermit {
		private final LongSupplier clock;
		private Permission permission = Permission.OBSERVE;
		private long lastHeartbeat;
		private EffectPermit(LongSupplier clock) { this.clock = clock; }
		private synchronized void update(Permission permission, long lastHeartbeat) {
			this.permission = permission; this.lastHeartbeat = lastHeartbeat;
		}
		private synchronized void heartbeat(long heartbeat) { lastHeartbeat = heartbeat; }
		public synchronized boolean allows(Permission requested) {
			return requested != Permission.OBSERVE && permission == requested
				&& (requested == Permission.CLEANUP || clock.getAsLong() - lastHeartbeat < LEASE_NANOS);
		}
		public synchronized void perform(Permission requested, Runnable effect) {
			if (!allows(requested)) throw new Rejected("effect_fenced");
			effect.run();
		}
	}

	public enum Permission { RUN, CLEANUP, OBSERVE }
	public enum State { ACCEPTED, RUNNING, RECONCILING, SUCCEEDED, FAILED, CANCELLED }
	public record World(String worldId, String dimension, String loadId, boolean alive, boolean controllerBusy, boolean reflexActive) {}
	public record Lease(String epoch, long generation, String hostId) {}
	public record Authority(String sessionId, String epoch, long generation, Lease lease, Receipt active, Receipt context, long admissionSequence) {}
	public record Id(String epoch, long generation, long sequence) {}
	public record Event(long seqNo, String epoch, String capturedAtNanos, String type, String reason, Lease lease, Receipt receipt) {}
	public record History(long oldestSeqNo, long latestSeqNo, long nextSeqNo, boolean gap, List<Event> events) {}
	public record Observation(String sessionId, String epoch, String captureId, long captureSequence, long capturedAtNanos, long effectRevision, World world, JsonObject facts) {
		public Observation { facts = facts.deepCopy(); }
		@Override public JsonObject facts() { return facts.deepCopy(); }
	}
	public record Basis(int schemaVersion, String operation, String captureId, String payloadHash) {}
	public record Receipt(Id id, State state, boolean released, String phase, String reason, Basis basis, Map<String, Object> effects) {
		public Receipt { effects = Map.copyOf(effects); }
	}
	public record Progress(State state, boolean released, String phase, String reason, Map<String, Object> effects) {
		public Progress { effects = Map.copyOf(effects); }
		public static Progress running(String phase, Map<String, Object> effects) {
			return new Progress(State.RUNNING, false, phase, "", effects);
		}
		public static Progress success(Map<String, Object> effects) {
			return new Progress(State.SUCCEEDED, true, "released", "", effects);
		}
	}
	public record Request(Id id, String captureId, String operation, JsonObject arguments, String payloadHash) {
		public Request { arguments = arguments.deepCopy(); }
		@Override public JsonObject arguments() { return arguments.deepCopy(); }
		public static Request create(Lease lease, long sequence, String captureId, String operation, JsonObject arguments) {
			return new Request(new Id(lease.epoch(), lease.generation(), sequence), captureId, operation, arguments,
				fingerprint(captureId, operation, arguments));
		}
	}
	public static final class Rejected extends IllegalArgumentException {
		private final String code;
		public Rejected(String code) { super(code); this.code = code; }
		public String code() { return code; }
	}

	public static String fingerprint(String captureId, String operation, JsonObject arguments) {
		var payload = new JsonObject();
		payload.addProperty("captureId", captureId);
		payload.addProperty("operation", operation);
		payload.add("arguments", arguments);
		byte[] bytes = new GsonBuilder().disableHtmlEscaping().create().toJson(sorted(payload)).getBytes(StandardCharsets.UTF_8);
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static JsonElement sorted(JsonElement value) {
		if (value.isJsonObject()) {
			var result = new JsonObject();
			for (String key : new TreeSet<>(value.getAsJsonObject().keySet())) result.add(key, sorted(value.getAsJsonObject().get(key)));
			return result;
		}
		if (value.isJsonArray()) {
			var result = new JsonArray();
			for (JsonElement item : value.getAsJsonArray()) result.add(sorted(item));
			return result;
		}
		return value.deepCopy();
	}

	private static final class Entry {
		private final String payloadHash;
		private Operation operation;
		private Receipt receipt;
		private String cancelReason = "";
		private boolean failed;
		private final EffectPermit permit;
		private String contextId;
		private int completedOperations;
		private String cleanupFailure;
		private Entry(String payloadHash, Operation operation, Receipt receipt, EffectPermit permit) {
			this.payloadHash = payloadHash;
			this.operation = operation;
			this.receipt = receipt;
			this.permit = permit;
		}
	}
}
