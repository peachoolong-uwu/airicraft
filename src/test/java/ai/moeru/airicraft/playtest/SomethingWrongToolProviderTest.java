package ai.moeru.airicraft.playtest;

import ai.moeru.airicraft.agent.llm.PlannerToolCatalog;
import ai.moeru.airicraft.agent.llm.PlannerToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SomethingWrongToolProviderTest {
	@Test void reportIsVisibleWithoutDiscoveryAndCommitIsSeparateFromAcceptance() {
		var reported = new AtomicReference<String>();
		var committed = new AtomicInteger();
		var provider = new SomethingWrongToolProvider(description -> {
			reported.set(description);
			return "Tool result for something_wrong: accepted";
		}, committed::incrementAndGet, Runnable::run);
		var registry = PlannerToolRegistry.of(provider);
		assertTrue(registry.activeOpenAiTool("something_wrong").isPresent());
		assertFalse(provider.isReadTool("something_wrong"));
		JsonObject args = new JsonObject();
		args.addProperty("description", "Task says succeeded, but the requested item is absent. Work @r17.");
		var call = PlannerToolCatalog.parseToolCall("something_wrong", args, registry);
		assertTrue(provider.execute(call).join().contains("accepted"));
		assertEquals(args.get("description").getAsString(), reported.get());
		assertEquals(0, committed.get(), "Pausing before receipt commitment loses the reporting call");
		registry.afterResultCommitted("something_wrong");
		assertEquals(1, committed.get());
	}

	@Test void rejectsMalformedReportsBeforeInvokingTheSink() {
		var provider = new SomethingWrongToolProvider(description -> { fail("Invalid report reached the sink"); return ""; }, () -> {}, Runnable::run);
		for (String json : new String[]{"{}", "{\"description\":\" \"}", "{\"description\":4}", "{\"description\":null}", "{\"description\":\"bug\",\"path\":\"/tmp/other\"}"}) {
			assertThrows(JsonParseException.class, () -> provider.validateArguments("something_wrong", com.google.gson.JsonParser.parseString(json).getAsJsonObject()));
		}
		var tooLong = new JsonObject();
		tooLong.addProperty("description", "x".repeat(8193));
		assertThrows(JsonParseException.class, () -> provider.validateArguments("something_wrong", tooLong));
	}
}
