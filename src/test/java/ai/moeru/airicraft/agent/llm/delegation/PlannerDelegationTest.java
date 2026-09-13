package ai.moeru.airicraft.agent.llm.delegation;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import ai.moeru.airicraft.agent.dialogue.DialogueRuntime;
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.llm.*;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

class PlannerDelegationTest {
	@Test void returnRequiresCurrentIdentityAndIdleWorkAndCarriesObservedEvidence() {
		var handoff = new PlannerDelegation();
		var future = handoff.delegate("Smelt charcoal", "Two charcoal in inventory", "Known furnace nearby");
		assertFalse(future.isDone());
		handoff.start(Map.of("coal", 1), 10);
		assertThrows(IllegalStateException.class, () -> handoff.requestReturn("old-id", "success", "Done", true));
		assertThrows(IllegalStateException.class, () -> handoff.requestReturn(handoff.id(), "success", "Done", false));
		handoff.recordToolExchange(call("smelt_items", "{}"), "accepted processId=1", false);
		handoff.recordEvent(11, "smelting.output_ready", Map.of("count", 2));
		handoff.requestReturn(handoff.id(), "success", "I made charcoal", true);
		assertFalse(future.isDone(), "return must wait for the thinking turn to stop");
		handoff.finish(Map.of("charcoal", 2));
		assertTrue(future.join().contains("smelting.output_ready"));
		assertTrue(future.join().contains("accepted processId=1"));
		assertTrue(future.join().contains("plannerReportedOutcome"));
		assertTrue(future.join().contains("finalFacts"));
		assertFalse(handoff.active());
		assertThrows(IllegalStateException.class, () -> handoff.requestReturn("old-id", "success", "Done", true));
	}

	@Test void resetReleasesWaitingControllerAndRejectsLateReturn() {
		var handoff = new PlannerDelegation();
		var future = handoff.delegate("Task", "Done", "Context");
		handoff.start(Map.of(), 0);
		String old = handoff.id();
		handoff.reset("world changed");
		assertTrue(future.join().contains("world changed"));
		assertThrows(IllegalStateException.class, () -> handoff.requestReturn(old, "success", "Done", true));
		assertFalse(handoff.active());
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
	void realOrchestratorsTransferToolsTwiceAndPreserveBothHistoriesAcrossAPlainYield(boolean hazard) throws Exception {
		var handoff = new PlannerDelegation();
		var dialogueRef = new AtomicReference<DialogueRuntime>();
		var controllerRef = new AtomicReference<PlannerOrchestrator>();
		var writes = new AtomicInteger();
		var work = new PlannerToolProvider() {
			public String id() { return "work"; }
			public boolean handles(String name) { return name.equals("do_work"); }
			public boolean isReadTool(String name) { return false; }
			public List<Map<String, Object>> openAiTools() { return List.of(PlannerToolCatalog.toolForProvider("do_work", "Perform work", Map.of("type", "object", "properties", Map.of()), List.of())); }
			public CompletableFuture<String> execute(PlannerToolCall call) {
				assertTrue(handoff.active(), "only the delegated owner may perform these actions");
				if (hazard && writes.get() == 0) dialogueRef.get().updateSafetyContext(1L, null, false);
				return CompletableFuture.completedFuture("observed charcoal=" + writes.incrementAndGet());
			}
		};
		var controllerTools = PlannerToolRegistry.of(new PlannerDelegationToolProvider(PlannerDelegationToolProvider.Role.CONTROLLER,
			handoff, Runnable::run, () -> controllerRef.get().delegationContext(), () -> dialogueRef.get().delegationWorkIdle()), work);
		var thinkingTools = PlannerToolRegistry.of(new PlannerDelegationToolProvider(PlannerDelegationToolProvider.Role.THINKING,
			handoff, Runnable::run, () -> "", () -> dialogueRef.get().delegationWorkIdle()), work);
		controllerTools.freezeToolPrefix();
		thinkingTools.freezeToolPrefix();
		var controllerBackend = new Backend(n -> n < 2
			? response(call("delegate_task", "{\"task\":\"Produce charcoal\",\"successCriteria\":\"Observe one more charcoal\"}"))
			: new PlannerResponse("Both delegated tasks finished.", List.of(), null));
		var thinkingBackend = new Backend(n -> switch (n) {
			case 0 -> new PlannerResponse("I will inspect the task.", List.of(), null);
			case 1, 3 -> response(call("do_work", "{}"));
			case 2, 4 -> response(call("return_control", "{\"delegationId\":\"" + handoff.id() + "\",\"status\":\"success\",\"outcome\":\"Work done\"}"));
			default -> throw new AssertionError("Thinking planner made an extra call after returning control");
		});
		var controller = orchestrator(controllerBackend, controllerTools, PlannerLifecycleListener.NO_OP);
		var thinking = orchestrator(thinkingBackend, thinkingTools, new PlannerLifecycleListener() {
			@Override public void onToolExchange(PlannerToolCall call, String result, boolean image) { handoff.recordToolExchange(call, result, image); }
		});
		controllerRef.set(controller);
		var dialogue = new DialogueRuntime(controller, 8, Clock.systemUTC());
		dialogueRef.set(dialogue);
		dialogue.configureDelegation(thinking, handoff);
		var session = new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, 1);
		var events = new SemanticEventBuffer(128);
		try {
			dialogue.onPlayerChat("Alice", "Do two complex tasks", 1, session, "Alice", Optional.empty(), TaskSnapshot.idle(), null, events);
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			for (long tick = 2; System.nanoTime() < deadline && controllerBackend.calls.size() < 3; tick++) {
				dialogue.poll(tick, events, session, Optional.empty(), TaskSnapshot.idle(), null);
				if (hazard && writes.get() == 1 && !handoff.active() && !controller.hasInFlight() && controllerBackend.calls.size() == 1)
					dialogue.onPlayerChat("Alice", "Continue with fresh safety evidence", tick, session, "Alice", Optional.empty(), TaskSnapshot.idle(), null, events);
				dialogue.continuePlannerGoal(tick, true, session, "Alice", Optional.empty(), TaskSnapshot.idle(), null, events);
				dialogue.pendingReplyReady(tick).ifPresent(reply -> dialogue.recordSentReply(reply, true));
				Thread.sleep(1);
			}
			assertEquals(2, writes.get());
			assertEquals(3, controllerBackend.calls.size());
			assertEquals(5, thinkingBackend.calls.size());
			assertFalse(handoff.active());
			assertTrue(controllerBackend.calls.get(1).messages().toString().contains("observed charcoal=1"));
			assertTrue(controllerBackend.calls.get(2).messages().toString().contains("observed charcoal=2"));
			assertPrefix(controllerBackend.calls.get(0), controllerBackend.calls.get(1));
			assertPrefix(thinkingBackend.calls.get(2), thinkingBackend.calls.get(3));
			assertTrue(thinkingBackend.calls.get(0).messages().toString().contains("controllerContext"));
			assertTrue(thinkingBackend.calls.get(0).messages().toString().contains("Do two complex tasks"), thinkingBackend.calls.get(0).messages().toString());
		} finally { dialogue.shutdown(); }
	}

	private static void assertPrefix(LlmConversation a, LlmConversation b) {
		assertTrue(b.messages().size() >= a.messages().size());
		assertEquals(OpenAiCompatibleChatClient.canonicalRequestMessages(a),
			OpenAiCompatibleChatClient.canonicalRequestMessages(LlmConversation.of(b.messages().subList(0, a.messages().size()))));
	}
	private static PlannerToolCall call(String name, String args) { return new PlannerToolCall(UUID.randomUUID().toString(), name, JsonParser.parseString(args).getAsJsonObject(), null, null); }
	private static PlannerResponse response(PlannerToolCall call) { return PlannerResponse.toolCalls(List.of(call), null); }
	private static PlannerOrchestrator orchestrator(LlmBackend backend, PlannerToolRegistry registry, PlannerLifecycleListener listener) {
		var config = AgentConfig.LlmConfig.defaults();
		return new PlannerOrchestrator(new PlannerExecutor(backend), new PlannerCompactionService(new OpenAiCompatibleChatClient(config, registry)),
			new PlannerContextAggregator(Clock.systemUTC(), 65536, 128, PlannerVisionMode.EXTERNAL_SUMMARY, registry),
			CurrentViewVisionTool.disabled(), CurrentInventoryTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY, "low", 1, 0, 0, 0,
			Clock.systemUTC(), NoopObservability.INSTANCE, listener, new AgentDebugRecorder(), PlannerActionToolExecutor.DISABLED,
			PlannerToolNarrationSink.NO_OP, registry, PlannerToolExecutionObserver.NO_OP);
	}
	private static final class Backend implements LlmBackend {
		final List<LlmConversation> calls = new CopyOnWriteArrayList<>();
		private final IntFunction<PlannerResponse> responses;
		Backend(IntFunction<PlannerResponse> responses) { this.responses = responses; }
		public LlmCallResult<PlannerResponse> generate(LlmConversation conversation) {
			int index = calls.size(); calls.add(conversation);
			return LlmCallResult.of(responses.apply(index), LlmUsageSnapshot.unknown(), 200, "test");
		}
		public boolean isConfigured() { return true; }
		public void injectMockResponse(PlannerResponse response) { throw new UnsupportedOperationException(); }
		public void injectTimeout() { throw new UnsupportedOperationException(); }
	}
}
