package ai.moeru.airicraft.os;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NativeProgressClocksTest {
	private static final String DIMENSION = "minecraft:overworld";
	private static final UUID SHEEP = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Test
	void onlyCompletedNativeTicksWithEveryRequiredSourceAdvanceAScope() {
		var clocks = new NativeProgressClocks();
		clocks.configure(scopes("[{\"scope\":\"farm\",\"chunks\":[{\"x\":1,\"z\":2},{\"x\":2,\"z\":2}]},{\"scope\":\"sheep\",\"entities\":[\"" + SHEEP + "\"]}]"));
		assertNull(clocks.snapshot().scopes().getFirst().eligibleTicks());
		clocks.begin();
		clocks.chunk(DIMENSION, 1, 2); clocks.chunk(DIMENSION, 1, 2);
		clocks.entity(DIMENSION, SHEEP);
		assertNull(clocks.snapshot().scopes().getFirst().eligibleTicks());
		clocks.complete(true);
		assertEquals(0L, clocks.snapshot().scopes().get(0).eligibleTicks());
		assertEquals(1L, clocks.snapshot().scopes().get(1).eligibleTicks());
		clocks.begin();
		clocks.chunk(DIMENSION, 1, 2); clocks.chunk(DIMENSION, 2, 2);
		clocks.complete(true);
		assertEquals(1L, clocks.snapshot().scopes().get(0).eligibleTicks());
		assertEquals(1L, clocks.snapshot().scopes().get(1).eligibleTicks());
		assertEquals(2L, clocks.snapshot().throughTick());
	}

	@Test
	void pausedTicksMissingUpdatesAndForeignDimensionsDoNotManufactureProgress() {
		var clocks = new NativeProgressClocks();
		clocks.configure(scopes("[{\"scope\":\"farm\",\"chunks\":[{\"x\":1,\"z\":2}]}]"));
		clocks.begin(); clocks.chunk(DIMENSION, 1, 2); clocks.complete(false);
		assertEquals(0L, clocks.snapshot().throughTick());
		assertEquals(0L, clocks.snapshot().scopes().getFirst().eligibleTicks());
		clocks.begin(); clocks.chunk("minecraft:the_nether", 1, 2); clocks.complete(true);
		clocks.begin(); clocks.complete(true);
		assertEquals(0L, clocks.snapshot().scopes().getFirst().eligibleTicks());
		assertFalse(clocks.snapshot().scopes().getFirst().lastTickEligible());
	}

	@Test
	void registrationPreservesUnchangedClocksButNeverCreditsAPartialTickToANewBinding() {
		var clocks = new NativeProgressClocks();
		var original = scopes("[{\"scope\":\"farm\",\"chunks\":[{\"x\":1,\"z\":2}]}]");
		clocks.configure(original);
		clocks.begin(); clocks.chunk(DIMENSION, 1, 2); clocks.complete(true);
		String identity = clocks.snapshot().scopes().getFirst().clockId();
		clocks.begin(); clocks.configure(original); clocks.chunk(DIMENSION, 1, 2); clocks.complete(true);
		assertEquals(identity, clocks.snapshot().scopes().getFirst().clockId());
		assertEquals(2L, clocks.snapshot().scopes().getFirst().eligibleTicks());
		clocks.begin();
		clocks.configure(scopes("[{\"scope\":\"farm\",\"chunks\":[{\"x\":3,\"z\":2}]}]"));
		clocks.chunk(DIMENSION, 3, 2); clocks.complete(true);
		assertNotEquals(identity, clocks.snapshot().scopes().getFirst().clockId());
		assertNull(clocks.snapshot().scopes().getFirst().eligibleTicks());
		clocks.begin(); clocks.chunk(DIMENSION, 3, 2); clocks.complete(true);
		assertEquals(1L, clocks.snapshot().scopes().getFirst().eligibleTicks());
	}

	@Test
	void invalidAndOversizedConfigurationRejectsAtomically() {
		var clocks = new NativeProgressClocks();
		var valid = scopes("[{\"scope\":\"farm\",\"chunks\":[{\"x\":1,\"z\":2}]}]");
		clocks.configure(valid);
		var before = clocks.snapshot();
		assertThrows(NativeActionRuntime.Rejected.class, () -> clocks.configure(List.of(valid.getFirst(), valid.getFirst())));
		assertEquals(before, clocks.snapshot());
		assertThrows(NativeActionRuntime.Rejected.class, () -> scopes("[{\"scope\":\"farm\",\"chunks\":[{\"x\":1.1,\"z\":2}]}]"));
		assertThrows(NativeActionRuntime.Rejected.class, () -> scopes("[{\"scope\":\"farm\",\"entities\":[\"bad-uuid\"]}]"));
		assertThrows(NativeActionRuntime.Rejected.class, () -> scopes("[{\"scope\":\"farm\",\"chunks\":[],\"entities\":[]}]"));
		assertThrows(NativeActionRuntime.Rejected.class, () -> scopes("[{\"scope\":\"farm\",\"chunks\":[{\"x\":1,\"z\":2},{\"x\":1,\"z\":2}]}]"));
		clocks.configure(List.of());
		assertTrue(clocks.snapshot().scopes().isEmpty());
	}

	@Test
	void anUnfinishedTickBreaksTheClockIdentityInsteadOfCreditingItsPartialUpdates() {
		var clocks = new NativeProgressClocks();
		clocks.configure(scopes("[{\"scope\":\"sheep\",\"entities\":[\"" + SHEEP + "\"]}]"));
		clocks.begin(); clocks.entity(DIMENSION, SHEEP); clocks.complete(true);
		var before = clocks.snapshot();
		clocks.begin(); clocks.entity(DIMENSION, SHEEP);
		clocks.begin(); clocks.complete(true);
		var after = clocks.snapshot();
		assertNotEquals(before.clockSession(), after.clockSession());
		assertNotEquals(before.scopes().getFirst().clockId(), after.scopes().getFirst().clockId());
		assertEquals(0L, after.scopes().getFirst().eligibleTicks());
		assertEquals(1L, after.throughTick());
	}

	@Test
	void registrationIsBoundedAndEquivalentSourceOrderKeepsTheClock() {
		var clocks = new NativeProgressClocks();
		var declarations = new com.google.gson.JsonArray();
		for (int i = 0; i < 32; i++) declarations.add(JsonParser.parseString("{\"scope\":\"area-" + i + "\",\"chunks\":[{\"x\":0,\"z\":0},{\"x\":1,\"z\":0},{\"x\":2,\"z\":0},{\"x\":3,\"z\":0}]}"));
		clocks.configure(NativeProgressClocks.parse(declarations, DIMENSION));
		var before = clocks.snapshot();
		assertEquals(32, before.scopes().size());
		var excessive = declarations.deepCopy();
		excessive.get(0).getAsJsonObject().getAsJsonArray("chunks").add(JsonParser.parseString("{\"x\":4,\"z\":0}"));
		assertThrows(NativeActionRuntime.Rejected.class, () -> clocks.configure(NativeProgressClocks.parse(excessive, DIMENSION)));
		assertEquals(before, clocks.snapshot());
		declarations.get(0).getAsJsonObject().add("chunks", JsonParser.parseString("[{\"x\":3,\"z\":0},{\"x\":2,\"z\":0},{\"x\":1,\"z\":0},{\"x\":0,\"z\":0}]"));
		clocks.configure(NativeProgressClocks.parse(declarations, DIMENSION));
		assertEquals(before, clocks.snapshot());
	}

	private static List<NativeProgressClocks.Scope> scopes(String json) {
		return NativeProgressClocks.parse(JsonParser.parseString(json).getAsJsonArray(), DIMENSION);
	}
}
