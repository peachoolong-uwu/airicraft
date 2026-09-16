package ai.moeru.airicraft.os;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** In-mod native effect reconciliation. Disk waits cannot block the lease heartbeat thread. */
public final class NativeEffects implements AutoCloseable {
	private final NativeAccess nativeAccess;
	private final EffectJournal journal;
	private final String worldId, host = UUID.randomUUID().toString();
	private final LongSupplier millis;
	private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("airicraft-os-lease").factory());
	private volatile JsonObject lease;
	private volatile String leaseFailure;
	private volatile boolean stopping;
	private CompletableFuture<JsonObject> renewal;
	private JsonObject active, contextIntent, contextReceipt, last, releasedLease;
	private boolean releasePending;
	private String contextKey;
	private boolean closingContext;
	private long sequence, contextStart, contextThrough;
	private int contextOperations, nativeContextOperations;
	public NativeEffects(NativeAccess nativeAccess, EffectJournal journal, String worldId, LongSupplier millis) {
		this.nativeAccess = nativeAccess; this.journal = journal; this.worldId = worldId; this.millis = millis;
	}
	public void start() throws Exception {
		if (lease != null || stopping) throw new IllegalStateException("native_host_already_started");
		var response = observe(); checkWorld(response.getAsJsonObject("frame"));
		for (var intent : journal.unfinished()) recover(intent);
		if (!journal.unfinished().isEmpty()) throw new IllegalStateException("reconciliation_required");
		lease = call("os_lease", OsJson.obj("action", "acquire", "hostId", host, "epoch", response.getAsJsonObject("frame").get("epoch"))).getAsJsonObject("lease");
		heartbeat.scheduleAtFixedRate(this::renew, 1, 1, TimeUnit.SECONDS);
	}
	public JsonObject observe() throws Exception { return observe(new JsonObject()); }
	public JsonObject observe(JsonObject query) throws Exception {
		long started = millis.getAsLong(); var response = call("os_observe", query); long received = millis.getAsLong();
		var frame = response.getAsJsonObject("frame");
		double nativeAge = frame.get("captureAgeMillis").getAsDouble();
		if (!Double.isFinite(nativeAge) || nativeAge < 0 || received < started) throw new IllegalStateException("invalid_native_observation");
		frame.addProperty("receivedAtHostMillis", received); frame.addProperty("captureAgeUpperBoundMillis", nativeAge + received - started);
		if (contextIntent != null) {
			if (!contextIntent.getAsJsonObject("id").get("epoch").equals(frame.get("epoch")) || frame.get("serverTick").isJsonNull()) throw new IllegalStateException("invalid_context_clock");
			long through = OsJson.number(frame, "serverTick", 0);
			if (through < contextThrough) throw new IllegalStateException("context_clock_regressed"); contextThrough = through;
		}
		return response;
	}
	public JsonObject execute(String operation, Function<JsonObject, JsonObject> prepare, String definition, String invocation) throws Exception {
		return submit(operation, prepare, definition, invocation, null);
	}
	public JsonObject retain(String key, Function<JsonObject, JsonObject> prepare) throws Exception {
		return submit("retain_container", prepare, "os:context", "os:context", key);
	}
	private JsonObject submit(String operation, Function<JsonObject, JsonObject> prepare, String definition, String invocation, String retaining) throws Exception {
		if (stopping || leaseFailure != null) throw new IllegalStateException(leaseFailure == null ? "native_stopping" : leaseFailure);
		if (lease == null || active != null || retaining != null && contextIntent != null) throw new IllegalStateException("native_unavailable");
		var response = observe(); var frame = response.getAsJsonObject("frame"); checkWorld(frame);
		if (!canDispatch(response.getAsJsonObject("authority")) || age(frame) >= 2000) throw new IllegalStateException("stale_observation");
		if (contextIntent != null && (Math.max(contextOperations, nativeContextOperations) >= WorkScheduler.CONTEXT_OPERATIONS || contextThrough - contextStart >= WorkScheduler.CONTEXT_TICKS)) throw new IllegalStateException("context_budget");
		if (retaining != null && frame.get("serverTick").isJsonNull()) throw new IllegalStateException("context_clock_unavailable");
		var arguments = OsJson.object(OsJson.copy(prepare.apply(frame)));
		if (contextIntent != null) arguments.add("contextId", contextReceipt.getAsJsonObject("effects").get("contextId"));
		var id = OsJson.obj("epoch", lease.get("epoch"), "generation", lease.get("generation"), "sequence", ++sequence);
		String capture = OsJson.text(frame, "captureId");
		String hash = NativeActionRuntime.fingerprint(capture, operation, arguments);
		var request = OsJson.obj("schemaVersion", 1, "id", id, "captureId", capture, "operation", operation, "arguments", arguments, "payloadHash", hash);
		var intent = OsJson.obj("id", id, "request", request, "payloadHash", hash, "definition", definition, "invocation", invocation, "context", retaining);
		if (retaining != null) {
			contextIntent = intent; contextKey = retaining; contextOperations = 0; nativeContextOperations = 0; closingContext = false;
			contextStart = contextThrough = frame.get("serverTick").getAsLong();
		} else active = intent;
		journal.record(intent);
		JsonObject receipt;
		try { receipt = call("os_submit", request).getAsJsonObject("receipt"); }
		catch (Exception lost) { receipt = recover(intent); if (receipt == null) throw new IllegalStateException("reconciliation_required", lost); }
		return accept(intent, receipt, retaining != null);
	}
	public boolean canDispatch(JsonObject authority) {
		if (stopping || leaseFailure != null || lease == null || active != null || !lease.equals(authority.get("lease")) || !authority.get("active").isJsonNull()) return false;
		if (contextIntent == null) return authority.get("context").isJsonNull();
		var context = context();
		if (context == null || !context.phase().equals("ready") || authority.get("context").isJsonNull()) return false;
		var nativeContext = authority.getAsJsonObject("context");
		return contextIntent.get("id").equals(nativeContext.get("id")) && OsJson.string(nativeContext, "state", "").equals("RUNNING") && OsJson.bool(nativeContext.getAsJsonObject("effects"), "contextReady", false);
	}
	public WorkScheduler.Context context() {
		if (contextIntent == null) return null;
		String phase = closingContext ? "exiting" : contextReceipt == null || OsJson.text(contextReceipt, "state").equals("ACCEPTED") ? "entering"
			: OsJson.text(contextReceipt, "state").equals("RUNNING") && OsJson.bool(contextReceipt.getAsJsonObject("effects"), "contextReady", false) ? "ready" : "unresolved";
		return new WorkScheduler.Context(contextKey, phase, Math.max(contextOperations, nativeContextOperations), contextThrough - contextStart);
	}
	public JsonObject poll() throws Exception {
		if (active == null) return last == null ? null : last.deepCopy();
		var receipt = recover(active); if (receipt == null) throw new IllegalStateException("reconciliation_required");
		return accept(active, receipt, false);
	}
	public JsonObject cancel() throws Exception {
		if (active == null) return last;
		try { return accept(active, call("os_cancel", OsJson.obj("lease", lease, "id", active.get("id"))).getAsJsonObject("receipt"), false); }
		catch (Exception lost) { return poll(); }
	}
	public JsonObject pollContext(boolean close) throws Exception {
		if (contextIntent == null) return null;
		if (close) closingContext = true;
		JsonObject receipt = close && lease != null ? call("os_cancel", OsJson.obj("lease", lease, "id", contextIntent.get("id"))).getAsJsonObject("receipt") : recover(contextIntent);
		if (receipt == null) throw new IllegalStateException("context_reconciliation_required");
		return accept(contextIntent, receipt, true);
	}
	public boolean unresolved() { return releasePending || active != null || contextIntent != null || !journal.unfinished().isEmpty(); }
	public boolean stop() throws Exception {
		stopping = true; heartbeat.shutdownNow();
		var owned = lease;
		if (owned != null) {
			releasedLease = owned; releasePending = true;
			try { call("os_lease", OsJson.obj("action", "release", "lease", owned)); lease = null; }
			catch (Exception failure) {
				var authority = observe().getAsJsonObject("authority");
				if (authority.get("lease").isJsonNull() || !authority.get("lease").equals(owned)) lease = null; else throw failure;
			}
		}
		if (active != null) poll();
		if (contextIntent != null) { closingContext = true; pollContext(false); }
		for (var intent : journal.unfinished()) recover(intent);
		if (releasePending) {
			var authority = observe().getAsJsonObject("authority");
			releasePending = ownedByReleasedLease(authority.get("active")) || ownedByReleasedLease(authority.get("context"));
		}
		return lease == null && !unresolved();
	}
	private boolean ownedByReleasedLease(com.google.gson.JsonElement action) {
		if (action == null || action.isJsonNull()) return false;
		var id = action.getAsJsonObject().getAsJsonObject("id");
		return id != null && id.get("epoch").equals(releasedLease.get("epoch")) && id.get("generation").equals(releasedLease.get("generation"));
	}
	public JsonObject state() { return OsJson.obj("lease", lease, "active", active == null ? null : active.get("id"), "context", context(), "unresolved", unresolved(), "failure", leaseFailure, "stopping", stopping); }
	private JsonObject accept(JsonObject intent, JsonObject receipt, boolean context) throws IOException {
		account(intent, receipt);
		if (context) {
			if (released(receipt)) { contextIntent = null; contextReceipt = null; contextKey = null; }
			else {
				var effects = receipt.getAsJsonObject("effects"); OsJson.text(effects, "contextId");
				if (contextReceipt != null && !contextReceipt.getAsJsonObject("effects").get("contextId").equals(effects.get("contextId"))) throw new IllegalStateException("context_identity_changed");
				contextReceipt = receipt.deepCopy(); nativeContextOperations = Math.max(nativeContextOperations, OsConfiguration.integer(effects, "completedOperations", 0, Integer.MAX_VALUE));
			}
		} else {
			last = receipt.deepCopy();
			if (released(receipt)) { active = null; if (contextIntent != null && !OsJson.string(receipt, "phase", "").equals("admission_rejected")) contextOperations++; }
		}
		return receipt.deepCopy();
	}
	private JsonObject recover(JsonObject intent) throws Exception {
		var response = raw("os_inspect", OsJson.obj("id", intent.get("id")));
		if (OsJson.text(response, "status").equals("ok")) { var receipt = response.getAsJsonObject("receipt"); account(intent, receipt); return receipt; }
		var authority = response.getAsJsonObject("authority"); var id = intent.getAsJsonObject("id");
		if (OsJson.string(response, "code", "").equals("outcome_unknown") && authority.get("epoch").equals(id.get("epoch")) && authority.get("generation").equals(id.get("generation")) &&
			authority.get("lease").isJsonNull() && authority.get("active").isJsonNull() && authority.get("context").isJsonNull() && OsJson.number(authority, "admissionSequence", 0) < OsJson.number(id, "sequence", 0)) {
			var receipt = OsJson.obj("id", id, "released", true, "accountingComplete", true, "disposition", "not_admitted", "proof", authority);
			journal.settle(id, receipt); return receipt;
		}
		return null;
	}
	private void account(JsonObject intent, JsonObject receipt) throws IOException {
		if (receipt.has("disposition") && OsJson.string(receipt, "disposition", "").equals("not_admitted")) return;
		var request = intent.getAsJsonObject("request"); var basis = receipt.getAsJsonObject("basis");
		if (!intent.get("id").equals(receipt.get("id")) || basis == null || !intent.get("payloadHash").equals(basis.get("payloadHash")) || !request.get("operation").equals(basis.get("operation")) || !request.get("captureId").equals(basis.get("captureId"))) throw new IllegalStateException("invalid_native_receipt");
		String state = OsJson.text(receipt, "state");
		if (!Set.of("ACCEPTED", "RUNNING", "RECONCILING", "SUCCEEDED", "FAILED", "CANCELLED").contains(state) || OsJson.bool(receipt, "released", false) && Set.of("ACCEPTED", "RUNNING", "RECONCILING").contains(state)) throw new IllegalStateException("invalid_native_receipt");
		journal.settle(intent.getAsJsonObject("id"), OsJson.obj("released", released(receipt), "accountingComplete", OsJson.bool(receipt.getAsJsonObject("effects"), "accountingComplete", false), "native", receipt));
	}
	public static boolean released(JsonObject receipt) {
		if (receipt == null || !OsJson.bool(receipt, "released", false)) return false;
		if (receipt.has("disposition")) return OsJson.bool(receipt, "accountingComplete", false);
		var effects = receipt.getAsJsonObject("effects");
		return effects != null && OsJson.bool(effects, "accountingComplete", false) && effects.has("releaseEvidence") && OsJson.bool(effects.getAsJsonObject("releaseEvidence"), "verified", false);
	}
	private synchronized void renew() {
		if (stopping || lease == null || leaseFailure != null || renewal != null && !renewal.isDone()) return;
		renewal = nativeAccess.call("os_lease", OsJson.obj("action", "heartbeat", "lease", lease)).orTimeout(3, TimeUnit.SECONDS);
		renewal.whenComplete((response, error) -> { if (error != null || !OsJson.text(response, "status").equals("ok")) leaseFailure = "native_lease_lost"; });
	}
	private JsonObject call(String name, JsonObject args) throws Exception {
		var response = raw(name, args); if (!OsJson.text(response, "status").equals("ok")) throw new IllegalStateException(OsJson.string(response, "code", "invalid_native_response")); return response;
	}
	private JsonObject raw(String name, JsonObject args) throws Exception {
		var pending = nativeAccess.call(name, OsJson.object(OsJson.copy(args)));
		try { return OsJson.object(OsJson.copy(pending.get(3, TimeUnit.SECONDS), 524_288, 24, 16_384)); }
		finally { if (!pending.isDone()) pending.cancel(false); }
	}
	private void checkWorld(JsonObject frame) {
		var world = frame.getAsJsonObject("world");
		if (world == null || !OsJson.bool(world, "alive", false) || !Objects.equals(OsJson.text(world, "worldId"), worldId) || OsJson.bool(world, "controllerBusy", true) || OsJson.bool(world, "reflexActive", true)) throw new IllegalStateException("world_not_ready");
	}
	public double age(JsonObject frame) { return frame.get("captureAgeUpperBoundMillis").getAsDouble() + millis.getAsLong() - frame.get("receivedAtHostMillis").getAsLong(); }
	@Override public void close() { stopping = true; heartbeat.shutdownNow(); try { stop(); } catch (Exception ignored) { /* Durable intents retain unresolved cleanup for the next run. */ } }
}
