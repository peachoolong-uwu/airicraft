package ai.moeru.airicraft.os;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** One ledger for observed stock, protected floors, claims and verified shared production. */
public final class ResourceLedger {
	public record Observation(String epoch, String revision, Map<String, Integer> stocks, Map<String, Integer> assets,
		Map<String, Integer> capacities, Set<String> targets) {
		public Observation { stocks = amounts(stocks, 0); assets = amounts(assets, 0); capacities = amounts(capacities, 0); targets = Set.copyOf(targets); }
	}
	public record Bundle(Map<String, Integer> inputs, Map<String, Integer> assets, Map<String, Integer> capacities, Set<String> targets) {
		public Bundle { inputs = amounts(inputs, 1); assets = amounts(assets, 1); capacities = amounts(capacities, 1); targets = Set.copyOf(targets); }
	}
	public record Stock(boolean known, int quantity, int surplus, Map<String, Integer> allocated) {}
	public record Target(String consumer, String resource, int quantity, int priority, long order) {}
	public record Delivery(long id, String consumer, String resource, int quantity, Set<String> methods, String epoch, int credited, String status, int allocated) {
		public int missing() { return status.equals("pending") ? Math.max(0, quantity - credited - allocated) : 0; }
	}
	private record Claim(String consumer, Bundle bundle, String epoch, boolean reconciling) {}
	private final Map<String, Target> targets = new LinkedHashMap<>();
	private final Map<String, Claim> claims = new LinkedHashMap<>();
	private final Map<Long, Delivery> deliveries = new LinkedHashMap<>();
	private final Map<String, Supply> supplies = new LinkedHashMap<>();
	private Observation observed;
	private boolean invalid = true;
	private long sequence, order, allocationRevision;
	public String epoch() { return observed == null ? null : observed.epoch; }
	public boolean needsObservation() { return invalid; }
	public long allocationRevision() { return allocationRevision; }
	public void invalidate() { if (!invalid) allocationRevision++; invalid = true; }
	public void observe(Observation next) {
		if (observed != null && observed.revision.equals(next.revision)) {
			if (!observed.equals(next)) throw new IllegalArgumentException("observation_conflict");
			return;
		}
		if (observed == null || invalid || !observed.epoch.equals(next.epoch)) allocationRevision++;
		observed = next; invalid = false;
		for (var entry : List.copyOf(claims.entrySet())) {
			var claim = entry.getValue(); var bundle = claim.bundle;
			boolean lost = !claim.epoch.equals(next.epoch) || !next.targets.containsAll(bundle.targets)
				|| bundle.inputs.keySet().stream().anyMatch(key -> next.stocks.getOrDefault(key, -1) < claimed(key).values().stream().mapToInt(Integer::intValue).sum())
				|| bundle.assets.entrySet().stream().anyMatch(asset -> next.assets.getOrDefault(asset.getKey(), -1) < asset.getValue())
				|| bundle.capacities.keySet().stream().anyMatch(key -> next.capacities.getOrDefault(key, -1) < claims.values().stream().mapToInt(other -> other.bundle.capacities.getOrDefault(key, 0)).sum());
			if (lost && !claim.reconciling) { claims.put(entry.getKey(), new Claim(claim.consumer, bundle, claim.epoch, true)); allocationRevision++; }
		}
	}
	public void target(String consumer, String resource, int quantity, int priority) {
		if (quantity < 0 || priority < 0) throw new IllegalArgumentException("invalid_target");
		String key = consumer + "\n" + resource; var previous = targets.get(key);
		if (quantity == 0) { if (targets.remove(key) != null) allocationRevision++; return; }
		if (previous == null && targets.size() >= 1024) throw new IllegalStateException("target_capacity");
		if (previous != null && previous.quantity == quantity && previous.priority == priority) return;
		targets.put(key, new Target(consumer, resource, quantity, priority, previous == null ? ++order : previous.order)); allocationRevision++;
	}
	public List<Target> targets() { return List.copyOf(targets.values()); }
	public void withdrawConsumer(String consumer) {
		if (targets.values().removeIf(target -> target.consumer.equals(consumer))) allocationRevision++;
	}
	public Stock stock(String resource) {
		if (observed == null || !observed.stocks.containsKey(resource)) return new Stock(false, 0, 0, Map.of());
		int quantity = observed.stocks.get(resource), available = quantity;
		var claimed = claimed(resource); var floors = new HashMap<String, Target>();
		targets.values().stream().filter(target -> target.resource.equals(resource)).forEach(target -> floors.put(target.consumer, target));
		var consumers = new TreeSet<>(claimed.keySet()); consumers.addAll(floors.keySet());
		var allocated = new LinkedHashMap<String, Integer>();
		for (var consumer : consumers) { int count = Math.min(available, claimed.getOrDefault(consumer, 0)); allocated.put(consumer, count); available -= count; }
		var ordered = new ArrayList<>(consumers);
		ordered.sort(Comparator.<String>comparingInt(id -> floors.containsKey(id) ? -floors.get(id).priority : 0)
			.thenComparingLong(id -> floors.containsKey(id) ? floors.get(id).order : 0).thenComparing(Comparator.naturalOrder()));
		for (var consumer : ordered) {
			int desired = Math.max(floors.containsKey(consumer) ? floors.get(consumer).quantity : 0, claimed.getOrDefault(consumer, 0));
			int added = Math.min(available, Math.max(0, desired - allocated.get(consumer)));
			allocated.merge(consumer, added, Integer::sum); available -= added;
		}
		return new Stock(true, quantity, available, Map.copyOf(allocated));
	}
	public void assess(String consumer, Bundle bundle, String revision) {
		if (observed == null || invalid || !observed.revision.equals(revision)) throw new IllegalStateException("stale_observation");
		if (claims.size() >= 32) throw new IllegalStateException("claim_capacity");
		for (var claim : claims.values()) if (claim.reconciling && (overlap(bundle.inputs.keySet(), claim.bundle.inputs.keySet()) || overlap(bundle.assets.keySet(), claim.bundle.assets.keySet()) || overlap(bundle.capacities.keySet(), claim.bundle.capacities.keySet()) || overlap(bundle.targets, claim.bundle.targets))) throw new IllegalStateException("resource_reconciling");
		for (var input : bundle.inputs.entrySet()) {
			var stock = stock(input.getKey()); int own = stock.allocated.getOrDefault(consumer, 0) - claimed(input.getKey()).getOrDefault(consumer, 0);
			if (!stock.known || input.getValue() > own + stock.surplus) throw new IllegalStateException("resource_unavailable");
		}
		for (var asset : bundle.assets.entrySet()) if (observed.assets.getOrDefault(asset.getKey(), -1) < asset.getValue() || claims.values().stream().anyMatch(claim -> claim.bundle.assets.containsKey(asset.getKey()))) throw new IllegalStateException("asset_unavailable");
		for (var capacity : bundle.capacities.entrySet()) if (observed.capacities.getOrDefault(capacity.getKey(), -1) - claims.values().stream().mapToInt(claim -> claim.bundle.capacities.getOrDefault(capacity.getKey(), 0)).sum() < capacity.getValue()) throw new IllegalStateException("capacity_unavailable");
		for (String target : bundle.targets) if (!observed.targets.contains(target) || claims.values().stream().anyMatch(claim -> claim.bundle.targets.contains(target))) throw new IllegalStateException("target_unavailable");
	}
	public void reserve(String id, String consumer, Bundle bundle, String revision) {
		if (claims.containsKey(id)) throw new IllegalStateException("claim_exists");
		assess(consumer, bundle, revision); claims.put(id, new Claim(consumer, bundle, epoch(), false)); allocationRevision++;
	}
	public boolean settle(String id, boolean released, boolean accounted, Map<String, Integer> consumed) {
		var claim = claims.get(id); if (claim == null) throw new IllegalArgumentException("claim_unknown");
		for (var entry : consumed.entrySet()) if (entry.getValue() < 0 || entry.getValue() > claim.bundle.inputs.getOrDefault(entry.getKey(), -1)) throw new IllegalArgumentException("invalid_consumption_evidence");
		allocationRevision++;
		if (!released || !accounted) { claims.put(id, new Claim(claim.consumer, claim.bundle, claim.epoch, true)); return false; }
		claims.remove(id); invalid = true; return true;
	}
	public long demand(String consumer, String resource, int quantity, Set<String> methods) {
		if (observed == null) throw new IllegalStateException("stale_observation");
		if (quantity < 1 || methods.isEmpty() || methods.size() > 32) throw new IllegalArgumentException("invalid_demand");
		if (deliveries.size() >= 256) throw new IllegalStateException("demand_capacity");
		long id = ++sequence; deliveries.put(id, new Delivery(id, consumer, resource, quantity, Set.copyOf(methods), epoch(), 0, "pending", 0)); allocationRevision++; return id;
	}
	public Delivery delivery(long id) {
		var value = deliveries.get(id); if (value == null) throw new IllegalArgumentException("demand_unknown");
		int allocated = supplies.values().stream().filter(supply -> !supply.settled).mapToInt(supply -> supply.allocations.getOrDefault(id, 0) - supply.credits.getOrDefault(id, 0)).sum();
		return new Delivery(id, value.consumer, value.resource, value.quantity, value.methods, value.epoch, value.credited, value.status, allocated);
	}
	public List<Delivery> deliveries() { return deliveries.keySet().stream().map(this::delivery).toList(); }
	public void cancelDelivery(long id) {
		var value = delivery(id); putDelivery(value, value.credited, "cancelled");
		for (var supply : supplies.values()) if (supply.allocations.containsKey(id)) supply.allocations.put(id, supply.credits.getOrDefault(id, 0));
		allocationRevision++;
	}
	public void closeDelivery(long id) {
		var value = delivery(id);
		if (value.status.equals("pending") || supplies.values().stream().anyMatch(supply -> !supply.settled && supply.allocations.containsKey(id))) throw new IllegalStateException("demand_unsettled");
		deliveries.remove(id); allocationRevision++;
	}
	public void beginSupply(String id, String resource, String method, int expected, Map<Long, Integer> allocations) {
		if (supplies.containsKey(id) || supplies.size() >= 32 || expected < 1 || expected > 64 || allocations.isEmpty() || allocations.size() > 44) throw new IllegalArgumentException("invalid_supply");
		var supply = new Supply(resource, method, epoch(), expected);
		for (var entry : allocations.entrySet()) addAllocation(supply, entry.getKey(), entry.getValue());
		supplies.put(id, supply); allocationRevision++;
	}
	public void joinSupply(String id, long demand, int quantity) {
		var supply = supply(id); addAllocation(supply, demand, quantity); distribute(supply); allocationRevision++;
	}
	public int supplyRoom(String id) {
		var supply = supply(id);
		return supply.settled || supply.allocations.size() >= 44 ? 0 : supply.expected - supply.allocations.values().stream().mapToInt(Integer::intValue).sum();
	}
	public void credit(String id, String effect, int quantity, boolean accounted) {
		var supply = supply(id);
		if (quantity < supply.produced || quantity > supply.expected || supply.settled && quantity != supply.produced || supply.effect != null && !supply.effect.equals(effect)) throw new IllegalArgumentException("invalid_output_evidence");
		if (!accounted) return;
		if (quantity > supply.produced) invalidate();
		supply.effect = effect; supply.produced = quantity; distribute(supply);
	}
	public void settleSupply(String id, boolean released, boolean accounted) {
		if (!released || !accounted) return;
		supply(id).settled = true; allocationRevision++;
	}
	public void closeSupply(String id) { if (!supply(id).settled) throw new IllegalStateException("supply_unsettled"); supplies.remove(id); allocationRevision++; }
	private void addAllocation(Supply supply, long id, int quantity) {
		var delivery = delivery(id);
		if (supply.settled || supply.allocations.containsKey(id) || supply.allocations.size() >= 44 || !delivery.status.equals("pending") || !delivery.epoch.equals(supply.epoch) || !delivery.epoch.equals(epoch()) || !delivery.resource.equals(supply.resource) || !delivery.methods.contains(supply.method)) throw new IllegalStateException("supply_inapplicable");
		if (quantity < 1 || quantity > delivery.missing() || quantity > supply.expected - supply.allocations.values().stream().mapToInt(Integer::intValue).sum()) throw new IllegalStateException("supply_capacity_unavailable");
		supply.allocations.put(id, quantity);
	}
	private void distribute(Supply supply) {
		int available = supply.produced - supply.credits.values().stream().mapToInt(Integer::intValue).sum();
		for (var allocation : supply.allocations.entrySet()) {
			var delivery = delivery(allocation.getKey()); if (!delivery.status.equals("pending")) continue;
			int credit = Math.min(available, Math.min(allocation.getValue() - supply.credits.getOrDefault(delivery.id, 0), delivery.quantity - delivery.credited));
			if (credit > 0) {
				supply.credits.merge(delivery.id, credit, Integer::sum); available -= credit;
				putDelivery(delivery, delivery.credited + credit, delivery.credited + credit == delivery.quantity ? "fulfilled" : "pending"); allocationRevision++;
			}
		}
	}
	private void putDelivery(Delivery value, int credited, String status) { deliveries.put(value.id, new Delivery(value.id, value.consumer, value.resource, value.quantity, value.methods, value.epoch, credited, status, 0)); }
	private Supply supply(String id) { var value = supplies.get(id); if (value == null) throw new IllegalArgumentException("supply_unknown"); return value; }
	private Map<String, Integer> claimed(String resource) {
		var totals = new HashMap<String, Integer>(); claims.values().forEach(claim -> totals.merge(claim.consumer, claim.bundle.inputs.getOrDefault(resource, 0), Integer::sum)); return totals;
	}
	private static boolean overlap(Set<String> a, Set<String> b) { return a.stream().anyMatch(b::contains); }
	private static Map<String, Integer> amounts(Map<String, Integer> values, int minimum) {
		if (values.size() > 256 || values.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank() || entry.getKey().length() > 256 || entry.getValue() == null || entry.getValue() < minimum)) throw new IllegalArgumentException("invalid_amounts");
		return Map.copyOf(values);
	}
	private static final class Supply {
		final String resource, method, epoch; final int expected; final Map<Long, Integer> allocations = new LinkedHashMap<>(), credits = new LinkedHashMap<>();
		String effect; int produced; boolean settled;
		Supply(String resource, String method, String epoch, int expected) { this.resource = resource; this.method = method; this.epoch = epoch; this.expected = expected; }
	}
}
