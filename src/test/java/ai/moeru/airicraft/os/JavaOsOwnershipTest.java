package ai.moeru.airicraft.os;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class JavaOsOwnershipTest {
	@Test void aNewConsumerCanUseCapacityReleasedByACancelledSubscriber() {
		var ledger = new ResourceLedger();
		ledger.observe(new ResourceLedger.Observation("epoch", "capture", Map.of("wheat", 0), Map.of(), Map.of(), Set.of()));
		long a = ledger.demand("a", "wheat", 2, Set.of("harvest")), b = ledger.demand("b", "wheat", 3, Set.of("harvest"));
		ledger.beginSupply("shared", "wheat", "harvest", 5, Map.of(a, 2, b, 3));
		ledger.cancelDelivery(a);
		long c = ledger.demand("c", "wheat", 2, Set.of("harvest")); ledger.joinSupply("shared", c, ledger.supplyRoom("shared"));
		ledger.credit("shared", "receipt", 5, true); ledger.settleSupply("shared", true, true);
		assertEquals(0, ledger.delivery(a).credited()); assertEquals(3, ledger.delivery(b).credited()); assertEquals(2, ledger.delivery(c).credited());
		assertThrows(IllegalArgumentException.class, () -> ledger.credit("shared", "other-receipt", 5, true));
	}
	@Test void returningParentWaitsForOwnedChildrenAndPhysicalCleanup() {
		var tree = new InvocationTree();
		String root = tree.install("root", Set.of("chest"), new JsonContract(OsJson.json(true)), false);
		var child = tree.spawn(root, "child", Set.of("chest"), new JsonContract(OsJson.json(true)), false);
		tree.attach("activity", Set.of(child.id()));
		tree.returned(root, OsJson.json("done"));
		tree.returned(child.id(), OsJson.json(42));
		assertNull(tree.outcome(root));
		tree.release("activity", false, true);
		assertNull(tree.outcome(root));
		tree.release("activity", true, true);
		assertEquals("success", tree.outcome(root).get("status").getAsString());
		assertEquals(1, tree.size());
	}
	@Test void withdrawingOneSubscriberDoesNotCancelSharedWork() {
		var tree = new InvocationTree();
		var contract = new JsonContract(OsJson.json(true));
		String a = tree.install("a", Set.of(), contract, false), b = tree.install("b", Set.of(), contract, false);
		tree.attach("shared", Set.of(a, b));
		tree.cancel(a, "stop");
		assertFalse(tree.stopActivity()); assertNotNull(tree.outcome(a));
		tree.cancel(b, "stop");
		assertTrue(tree.stopActivity()); assertNull(tree.outcome(b));
		tree.release("shared", true, true);
		assertEquals("cancelled", tree.outcome(b).get("status").getAsString());
	}
	@Test void protectedStockCannotBeConsumedByAnotherRoot() {
		var ledger = new ResourceLedger();
		ledger.observe(new ResourceLedger.Observation("epoch", "capture", Map.of("wheat", 10), Map.of(), Map.of("bag", 64), Set.of("chest")));
		ledger.target("sheep", "wheat", 8, 1);
		var tooMuch = new ResourceLedger.Bundle(Map.of("wheat", 3), Map.of(), Map.of("bag", 3), Set.of("chest"));
		assertThrows(IllegalStateException.class, () -> ledger.reserve("bread", "baker", tooMuch, "capture"));
		var allowed = new ResourceLedger.Bundle(Map.of("wheat", 2), Map.of(), Map.of("bag", 2), Set.of("chest"));
		ledger.reserve("bread", "baker", allowed, "capture");
		assertFalse(ledger.settle("bread", false, true, Map.of("wheat", 1)));
		assertTrue(ledger.settle("bread", true, true, Map.of("wheat", 1)));
		assertTrue(ledger.needsObservation());
	}
	@Test void productionIsCreditedOnceAndNeverCreatesObservedStock() {
		var ledger = new ResourceLedger();
		ledger.observe(new ResourceLedger.Observation("epoch", "capture", Map.of("wheat", 0), Map.of(), Map.of(), Set.of()));
		long a = ledger.demand("a", "wheat", 2, Set.of("harvest")), b = ledger.demand("b", "wheat", 3, Set.of("harvest"));
		ledger.beginSupply("supply", "wheat", "harvest", 5, Map.of(a, 2, b, 3));
		ledger.credit("supply", "receipt", 5, true);
		ledger.credit("supply", "receipt", 5, true);
		assertEquals(2, ledger.delivery(a).credited()); assertEquals(3, ledger.delivery(b).credited());
		assertEquals(0, ledger.stock("wheat").quantity());
	}
}
