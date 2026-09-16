package ai.moeru.airicraft.os;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** Java owns feasibility, resource procurement, context reuse and admission. */
final class WorkService {
	private final InvocationTree tree;
	private final ResourceLedger ledger;
	private final NativeEffects effects;
	private final OsConfiguration config;
	final NativeViews views;
	private final LongSupplier millis;
	private final WorkScheduler scheduler = new WorkScheduler();
	private final Map<String, Work> requests = new LinkedHashMap<>();
	private final Map<Long, String> demandOwners = new LinkedHashMap<>();
	private final Map<String, Long> deferred = new LinkedHashMap<>();
	private Work active;
	private long sequence, generation, lastRefresh, witnessTick;
	private JsonObject witness;
	private Set<String> witnessOffers = Set.of();
	private long witnessRevision, witnessGeneration;
	private double witnessExpires;
	private String fault;
	WorkService(InvocationTree tree, ResourceLedger ledger, NativeEffects effects, OsConfiguration config, NativeViews views, LongSupplier millis) {
		this.tree = tree; this.ledger = ledger; this.effects = effects; this.config = config; this.views = views; this.millis = millis;
	}
	void refresh() throws Exception {
		var response = effects.observe(OsJson.obj("progressScopes", config.progressScopes()));
		var context = effects.context();
		views.publish(response, active == null && (context == null || context.phase().equals("ready")), ledger); lastRefresh = millis.getAsLong();
	}
	long generation() { return generation; }
	String capture() { var response = views.response(); return response == null ? null : OsJson.text(response.getAsJsonObject("frame"), "captureId"); }
	String request(String owner, JsonObject effect) {
		var record = declaration(owner, effect, "work:" + ++sequence, false); requests.put(record.id, record); return record.id;
	}
	void offers(String owner, JsonArray values, long authoredGeneration, String capture) {
		if (authoredGeneration != generation || !Objects.equals(capture, capture())) return;
		if (values.size() > 32) throw new IllegalArgumentException("work_offer_limit");
		var desired = new HashSet<String>();
		for (var value : values) {
			var effect = SkillEffects.effect(value); String id = "offer:" + owner + ":" + OsJson.digest(effect);
			if (!desired.add(id)) continue;
			var previous = requests.get(id);
			if (previous == null || previous.phase.equals("finished")) requests.put(id, declaration(owner, effect, id, true));
			requests.get(id).authoredGeneration = generation; requests.get(id).capture = capture;
		}
		requests.values().removeIf(record -> record.recurring && record.owner.equals(owner) && !desired.contains(record.id) && record != active);
	}
	JsonObject take(String owner, String id) {
		var work = requests.get(id);
		if (work == null || !work.owner.equals(owner) || work.recurring) throw new IllegalArgumentException("work_unknown");
		if (!work.phase.equals("finished")) return OsJson.obj("status", "pending");
		requests.remove(id); return work.result.deepCopy();
	}
	JsonObject target(String owner, String resource, int quantity) {
		var spec = resource(owner, resource); ledger.target(tree.execution(owner).root(), spec.key(), quantity, spec.priority());
		return OsJson.obj("status", "registered", "resource", resource, "quantity", quantity);
	}
	long demand(String owner, String resource, int quantity, Set<String> requested) {
		var spec = resource(owner, resource); var methods = new HashSet<String>();
		for (String method : requested.isEmpty() ? spec.methods() : requested) {
			if (!spec.methods().contains(method)) throw new IllegalArgumentException("supply_method_unavailable");
			try { tree.authorize(owner, operation(method).grant()); methods.add(method); }
			catch (IllegalStateException denied) { if (!requested.isEmpty()) throw denied; }
		}
		if (demandOwners.values().stream().filter(owner::equals).count() >= 32) throw new IllegalStateException("invocation_demand_capacity");
		long id = ledger.demand(tree.execution(owner).root(), spec.key(), quantity, methods); demandOwners.put(id, owner);
		if (active != null && active.supplyStarted && !tree.stopActivity() && spec.key().equals(active.supplyResource) && methods.contains(active.operationName)) {
			int share = Math.min(quantity, ledger.supplyRoom(active.id));
			if (share > 0) { ledger.joinSupply(active.id, id, share); tree.subscribe(active.id, owner); active.owners.add(owner); active.deliveries.put(id, share); }
		}
		return id;
	}
	JsonObject takeDemand(String owner, long id) {
		if (!owner.equals(demandOwners.get(id))) throw new IllegalArgumentException("demand_unknown");
		var delivery = ledger.delivery(id);
		if (delivery.status().equals("pending") || delivery.allocated() > 0) return OsJson.obj("status", "pending");
		try { ledger.closeDelivery(id); } catch (IllegalStateException unsettled) { return OsJson.obj("status", "pending"); }
		demandOwners.remove(id); return OsJson.obj("status", delivery.status(), "quantity", delivery.quantity(), "credited", delivery.credited(), "epoch", delivery.epoch());
	}
	void tick(boolean authorsPending) throws Exception {
		sweep();
		if (active != null) { complete(tree.stopActivity() ? effects.cancel() : effects.poll()); return; }
		var context = effects.context();
		if (context != null && !context.phase().equals("ready")) {
			reconcileContext(false);
			invalidate(); return;
		}
		if (fault != null) { effects.stop(); return; }
		if (!views.current() || millis.getAsLong() - lastRefresh >= 500) refresh();
		var response = views.response(); if (response == null) return;
		if (effects.context() != null && !effects.canDispatch(response.getAsJsonObject("authority"))) {
			reconcileContext(false); invalidate(); return;
		}
		refreshSupplies();
		var frame = response.getAsJsonObject("frame"); var offers = new ArrayList<WorkScheduler.Offer>();
		for (var record : requests.values()) {
			if (!record.phase.equals("queued")) continue;
			boolean ready = false;
			try {
				if (deferred.getOrDefault(deferKey(record), 0L) > millis.getAsLong()) throw new IllegalStateException("retry_deferred");
				tree.authorize(record.owner, record.operation.grant());
				var prepared = record.operation.prepare(frame, record.arguments);
				ledger.assess(tree.execution(record.owner).root(), prepared.bundle(), OsJson.text(frame, "captureId"));
				if (record.supplyResource != null && !prepared.destination().equals(record.supplyResource)) throw new IllegalArgumentException("supply_output_mismatch");
				ready = !record.recurring || record.authoredGeneration == generation && Objects.equals(record.capture, OsJson.text(frame, "captureId"));
				record.reason = ready ? null : "awaiting_offers";
			} catch (IllegalArgumentException invalid) { record.result = OsJson.obj("status", "rejected", "reason", OsJson.reason(invalid)); record.phase = "finished"; defer(record); }
			catch (IllegalStateException blocked) { record.reason = OsJson.reason(blocked); }
			var roots = record.owners.stream().filter(tree::running).map(owner -> tree.execution(owner).root()).collect(Collectors.toSet());
			if (!roots.isEmpty()) offers.add(new WorkScheduler.Offer(record.id, roots, "land", record.priority, record.operation.context(), ready));
		}
		scheduler.update(Set.copyOf(tree.roots()), offers); progress(offers);
		var decision = scheduler.choose(effects.canDispatch(response.getAsJsonObject("authority")), effects.context());
		if (decision.kind().equals("close_context")) {
			if (decision.reason().equals("no_feasible_work") && authorsPending) return;
			reconcileContext(true); invalidate(); return;
		}
		if (!decision.kind().equals("select")) return;
		var selected = requests.get(decision.offerId());
		if (selected.operation.context() != null && effects.context() == null) {
			effects.retain(selected.operation.context(), fresh -> {
				tree.authorize(selected.owner, selected.operation.grant()); var prepared = selected.operation.prepare(fresh, selected.arguments);
				ledger.observe(prepared.observation()); ledger.assess(tree.execution(selected.owner).root(), prepared.bundle(), prepared.observation().revision());
				return selected.operation.contextArguments(fresh);
			}); invalidate(); return;
		}
		selected.selection = offers.stream().filter(offer -> offer.id().equals(selected.id)).findFirst().orElseThrow();
		admit(selected);
	}
	private void reconcileContext(boolean close) throws Exception {
		var receipt = effects.pollContext(close);
		if (receipt != null && (OsJson.string(receipt, "state", "").equals("FAILED") || receipt.has("effects") && receipt.getAsJsonObject("effects").has("cleanupFailure"))) throw new IllegalStateException("native_context_failed");
	}
	private void admit(Work work) throws Exception {
		try {
			var receipt = effects.execute("transfer_container", frame -> {
				tree.authorize(work.owner, work.operation.grant());
				for (String owner : work.owners) tree.authorize(owner, work.operation.grant());
				work.prepared = work.operation.prepare(frame, work.arguments); ledger.observe(work.prepared.observation());
				if (work.supplyResource != null && !work.prepared.destination().equals(work.supplyResource)) throw new IllegalArgumentException("supply_output_mismatch");
				ledger.reserve(work.id, tree.execution(work.owner).root(), work.prepared.bundle(), work.prepared.observation().revision()); work.claimed = true;
				if (work.supplyResource != null) {
					for (var target : work.targetShares.entrySet()) {
						long id = ledger.demand(target.getKey(), work.supplyResource, target.getValue(), Set.of(work.operationName)); work.internalDemands.add(id); work.deliveries.put(id, target.getValue());
					}
					ledger.beginSupply(work.id, work.supplyResource, work.operationName, work.prepared.quantity(), work.deliveries); work.supplyStarted = true;
				}
				tree.attach(work.id, work.owners); work.attached = true; active = work; work.phase = "active";
				return work.prepared.arguments();
			}, tree.execution(work.owner).definition(), work.owner);
			if (!receipt.has("disposition") && !OsJson.string(receipt, "phase", "").equals("admission_rejected")) scheduler.served(work.selection);
			invalidate(); complete(receipt);
		} catch (Exception error) {
			if (active != null && effects.state().get("active") != null && !effects.state().get("active").isJsonNull()) { fault = OsJson.reason(error); effects.stop(); throw error; }
			if (OsJson.reason(error).equals("context_budget") && !work.claimed) { work.reason = "context_budget"; invalidate(); return; }
			rollback(work); work.result = OsJson.obj("status", "rejected", "reason", OsJson.reason(error)); work.phase = "finished"; defer(work); invalidate();
		}
	}
	private void complete(JsonObject receipt) {
		if (active == null || receipt == null) return;
		var work = active; var accounting = work.operation.account(work.prepared, receipt);
		if (work.supplyStarted) {
			ledger.credit(work.id, EffectJournal.key(receipt.getAsJsonObject("id")), accounting.produced().getOrDefault(work.supplyResource, 0), accounting.complete());
			ledger.settleSupply(work.id, accounting.released(), accounting.complete());
		}
		if (!ledger.settle(work.id, accounting.released(), accounting.complete(), accounting.consumed())) return;
		if (work.supplyStarted) ledger.closeSupply(work.id);
		for (long id : work.internalDemands) { if (ledger.delivery(id).status().equals("pending")) ledger.cancelDelivery(id); ledger.closeDelivery(id); }
		String status = receipt.has("disposition") || OsJson.string(receipt, "phase", "").equals("admission_rejected") ? "rejected" : switch (OsJson.string(receipt, "state", "")) { case "SUCCEEDED" -> "success"; case "CANCELLED" -> "cancelled"; default -> "failure"; };
		work.result = OsJson.obj("status", status, "id", work.id, "receipt", receipt, "consumed", accounting.consumed(), "produced", accounting.produced());
		work.phase = "finished"; active = null; tree.release(work.id, true, true);
		if (!status.equals("success") || work.supplyResource != null && accounting.produced().getOrDefault(work.supplyResource, 0) < work.prepared.quantity()) defer(work);
		invalidate();
	}
	private void rollback(Work work) {
		if (work.supplyStarted) { ledger.settleSupply(work.id, true, true); ledger.closeSupply(work.id); }
		if (work.claimed) ledger.settle(work.id, true, true, Map.of());
		for (long id : work.internalDemands) { ledger.cancelDelivery(id); ledger.closeDelivery(id); }
		if (work.attached) tree.release(work.id, true, true); if (active == work) active = null;
	}
	private void sweep() {
		requests.values().removeIf(work -> work != active && (!tree.running(work.owner) || work.supplyResource != null));
		deferred.entrySet().removeIf(entry -> entry.getValue() <= millis.getAsLong());
		for (var target : ledger.targets()) if (!tree.contains(target.consumer()) || !Set.of("running", "closing").contains(tree.execution(target.consumer()).phase())) ledger.withdrawConsumer(target.consumer());
		for (var entry : List.copyOf(demandOwners.entrySet())) if (!tree.running(entry.getValue())) {
			ledger.cancelDelivery(entry.getKey());
			try { ledger.closeDelivery(entry.getKey()); demandOwners.remove(entry.getKey()); } catch (IllegalStateException pending) { /* Native cleanup still owns its allocation. */ }
		}
	}
	private void refreshSupplies() {
		if (ledger.needsObservation()) return;
		for (var ruleEntry : config.supplies.entrySet()) {
			var rule = ruleEntry.getValue(); var resource = config.resources.get(rule.resource()); var needs = new ArrayList<Need>();
			for (var delivery : ledger.deliveries()) {
				String owner = demandOwners.get(delivery.id());
				if (owner != null && tree.running(owner) && delivery.resource().equals(resource.key()) && delivery.methods().contains(rule.operation()) && delivery.missing() > 0) needs.add(new Need(owner, delivery.consumer(), delivery.id(), delivery.missing()));
			}
			for (var target : ledger.targets()) if (target.resource().equals(resource.key()) && tree.running(target.consumer())) {
				try { tree.authorize(target.consumer(), operation(rule.operation()).grant()); } catch (IllegalStateException denied) { continue; }
				var stock = ledger.stock(resource.key()); if (!stock.known()) continue;
				int committed = ledger.deliveries().stream().filter(delivery -> delivery.consumer().equals(target.consumer()) && delivery.resource().equals(resource.key()) && delivery.methods().contains(rule.operation()) && delivery.status().equals("pending")).mapToInt(delivery -> delivery.quantity() - delivery.credited()).sum();
				int shortage = Math.max(0, target.quantity() - stock.allocated().getOrDefault(target.consumer(), 0) - committed);
				if (shortage > 0) needs.add(new Need(target.consumer(), target.consumer(), null, shortage));
			}
			for (String anchor : needs.stream().map(Need::consumer).distinct().toList()) {
				var ordered = needs.stream().sorted(java.util.Comparator.comparing(need -> !need.consumer.equals(anchor))).toList();
				int maximum = feasibleSupply(rule, anchor, ordered.stream().mapToLong(Need::quantity).sum());
				var allocations = new LinkedHashMap<Long, Integer>(); var targetShares = new LinkedHashMap<String, Integer>(); var owners = new HashSet<String>(); int quantity = 0; String owner = null;
				for (var need : ordered) {
					if (quantity == maximum || allocations.size() >= 32) break;
					int share = Math.min(need.quantity, maximum - quantity); quantity += share; owners.add(need.owner); if (owner == null) owner = need.owner;
					if (need.delivery == null) targetShares.put(need.consumer, share); else allocations.put(need.delivery, share);
				}
				if (quantity == 0) continue;
				var args = rule.arguments(); args.addProperty("quantity", quantity);
				String id = "supply:" + OsJson.digest(OsJson.obj("rule", ruleEntry.getKey(), "anchor", anchor, "deliveries", allocations, "targets", targetShares, "quantity", quantity));
				var work = new Work(id, owner, rule.operation(), operation(rule.operation()), args, false); work.owners.addAll(owners); work.supplyResource = resource.key(); work.deliveries.putAll(allocations); work.targetShares.putAll(targetShares); requests.put(id, work);
				work.priority = Math.max(work.priority, resource.priority());
			}
		}
	}
	private int feasibleSupply(OsConfiguration.Supply rule, String consumer, long missing) {
		var response = views.response(); if (response == null) return 0;
		var frame = response.getAsJsonObject("frame");
		for (int quantity = (int) Math.min(rule.maximum(), missing); quantity > 0; quantity--) {
			try {
				var arguments = rule.arguments(); arguments.addProperty("quantity", quantity);
				var prepared = operation(rule.operation()).prepare(frame, arguments);
				ledger.assess(consumer, prepared.bundle(), OsJson.text(frame, "captureId"));
				return quantity;
			} catch (IllegalArgumentException invalid) { return 0; }
			catch (IllegalStateException unavailable) { /* A smaller batch may fit stock, protected floors or destination capacity. */ }
		}
		return 0;
	}
	private void progress(List<WorkScheduler.Offer> offers) {
		var proof = views.availability();
		if (proof == null) { witness = null; return; }
		long through = OsJson.number(proof, "throughTick", 0); if (through < witnessTick) throw new IllegalStateException("eligibility_clock_regressed");
		var ids = offers.stream().filter(WorkScheduler.Offer::ready).map(WorkScheduler.Offer::id).collect(Collectors.toSet());
		boolean covered = witness != null && millis.getAsLong() < witnessExpires && Objects.equals(witness.get("clockId"), proof.get("clockId")) && Objects.equals(witness.get("stamp"), proof.get("stamp")) && witnessRevision == ledger.allocationRevision() && witnessGeneration == generation;
		Set<String> eligible = covered ? offers.stream().filter(offer -> ids.contains(offer.id()) && witnessOffers.contains(offer.id())).flatMap(offer -> offer.roots().stream()).collect(Collectors.toSet()) : Set.of();
		scheduler.advance(covered ? Math.max(witnessTick, OsJson.number(proof, "fromTick", through)) : through, through, eligible, covered);
		witness = proof; witnessOffers = ids; witnessTick = through; witnessRevision = ledger.allocationRevision(); witnessGeneration = generation;
		var frame = views.response().getAsJsonObject("frame");
		witnessExpires = frame.get("receivedAtHostMillis").getAsLong() + 2000 - frame.get("captureAgeUpperBoundMillis").getAsDouble();
	}
	private Work declaration(String owner, JsonObject effect, String id, boolean recurring) {
		if (requests.size() >= 1024) throw new IllegalStateException("work_capacity");
		String name = OsJson.text(effect, "operation"); var operation = operation(name); tree.authorize(owner, operation.grant());
		if (!Objects.equals(OsJson.string(effect, "context", null), operation.context())) throw new IllegalArgumentException("invalid_work_context");
		return new Work(id, owner, name, operation, OsJson.object(effect.get("arguments")), recurring);
	}
	private ContainerOperation operation(String name) { var operation = config.operations.get(name); if (operation == null) throw new IllegalArgumentException("operation_unknown"); return operation; }
	private OsConfiguration.Resource resource(String owner, String name) { var resource = config.resources.get(name); if (resource == null) throw new IllegalArgumentException("resource_unknown"); tree.authorize(owner, resource.grant()); return resource; }
	private String deferKey(Work record) { return tree.contains(record.owner) ? tree.execution(record.owner).root() + ":" + record.operationName : record.owner + ":" + record.operationName; }
	private void defer(Work record) { if (record.recurring || record.supplyResource != null) deferred.put(deferKey(record), millis.getAsLong() + 5000); }
	private void invalidate() { generation++; witness = null; views.invalidate(); }
	boolean active() { return active != null; }
	boolean stopped() throws Exception { if (active != null) complete(effects.poll()); return effects.stop() && active == null; }
	JsonObject state() {
		var queued = new JsonArray(); requests.values().stream().limit(32).forEach(work -> queued.add(OsJson.obj("id", work.id, "owner", work.owner, "operation", work.operationName, "phase", work.phase, "reason", work.reason)));
		return OsJson.obj("active", active == null ? null : active.id, "requests", requests.size(), "queue", queued, "generation", generation, "ages", scheduler.ages(), "fault", fault);
	}
	private record Need(String owner, String consumer, Long delivery, int quantity) {}
	private static final class Work {
		final String id, owner, operationName; final ContainerOperation operation; final JsonObject arguments; final boolean recurring;
		final Set<String> owners = new HashSet<>(); final Map<Long, Integer> deliveries = new LinkedHashMap<>(); final Map<String, Integer> targetShares = new LinkedHashMap<>(); final List<Long> internalDemands = new ArrayList<>();
		String phase = "queued", reason, capture, supplyResource; long authoredGeneration; boolean claimed, attached, supplyStarted; int priority;
		ContainerOperation.Prepared prepared; WorkScheduler.Offer selection; JsonObject result;
		Work(String id, String owner, String operationName, ContainerOperation operation, JsonObject arguments, boolean recurring) {
			this.id = id; this.owner = owner; this.operationName = operationName; this.operation = operation; this.priority = operation.priority(); this.arguments = arguments.deepCopy(); this.recurring = recurring; owners.add(owner);
		}
	}
}
