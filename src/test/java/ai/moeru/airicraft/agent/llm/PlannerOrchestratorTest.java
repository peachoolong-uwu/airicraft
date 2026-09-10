package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import ai.moeru.airicraft.agent.debug.ConversationSourcesDebugSnapshot;
import ai.moeru.airicraft.agent.debug.LlmFlightRecorder;
import ai.moeru.airicraft.agent.dialogue.DialogueTurn;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.FlightRecordingObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import ai.moeru.airicraft.agent.recording.PlannerCallJournal;
import ai.moeru.airicraft.agent.recording.PlannerCallRecordV1;
import ai.moeru.airicraft.agent.events.EventPolicyChanges;
import ai.moeru.airicraft.agent.events.EventPolicyMatch;
import ai.moeru.airicraft.agent.events.EventPolicyRuleUpsert;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import ai.moeru.airicraft.agent.goals.GoalType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ai.moeru.airicraft.agent.session.SessionMode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerOrchestratorTest {
	@Test
	void plannerOffRecordsAndDiscardsInputWithoutLeakingItIntoTheNextTurn() {
		RecordingBackend backend = new RecordingBackend();
		LlmFlightRecorder flightRecorder = new LlmFlightRecorder();
		AgentObservability observability = new FlightRecordingObservability(NoopObservability.INSTANCE, flightRecorder);
		AgentConfig.LlmConfig config = AgentConfig.LlmConfig.defaults();
		Clock clock = Clock.systemUTC();
		PlannerToolRegistry toolRegistry = PlannerToolRegistry.empty();
		toolRegistry.activateAllForTesting();
		PlannerCallJournal plannerCallJournal = new PlannerCallJournal(
			clock,
			() -> 100L,
			"test-provider",
			"test-model",
			toolRegistry::openAiTools
		);
		PlannerOrchestrator orchestrator = new PlannerOrchestrator(
			new PlannerExecutor(backend, observability),
			new PlannerCompactionService(new OpenAiCompatibleChatClient(config, observability, toolRegistry), observability),
			new PlannerContextAggregator(clock, config.plannerCompactionTriggerTokens(), config.plannerPendingSemanticEventCap(), PlannerVisionMode.EXTERNAL_SUMMARY, toolRegistry),
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			config.visionImageDetail(),
			config.plannerSessionMaxConcurrentAttempts(),
			config.plannerSessionCoalesceStepMillis(),
			config.plannerSessionCoalesceMinMillis(),
			config.plannerSessionCoalesceMaxMillis(),
			clock,
			observability,
			CompositePlannerLifecycleListener.of(plannerCallJournal),
			new AgentDebugRecorder(),
			PlannerActionToolExecutor.DISABLED,
			PlannerToolNarrationSink.NO_OP,
			toolRegistry,
			PlannerToolExecutionObserver.NO_OP
		);

		orchestrator.submit(request("before pause", 1L));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, noActionResponse());
		assertNotNull(awaitResult(orchestrator));

		orchestrator.setEnabled(false);
		assertFalse(orchestrator.submit(request("discard during pause", 2L)));
		assertFalse(orchestrator.hasInFlight());
		assertEquals(1, backend.callCount());
		assertNull(orchestrator.poll());

		var flightRecord = flightRecorder.query(null).records().getFirst();
		assertEquals("COMPLETED", flightRecord.status());
		assertTrue(flightRecord.requestBody().contains("discard during pause"));
		assertTrue(flightRecord.requestBody().contains("\"tools\""));
		assertEquals("PLANNER OFF", flightRecord.rawResponseBody());
		PlannerCallRecordV1 discardedRecord = plannerCallJournal.snapshot().getLast();
		assertEquals("PLANNER OFF", discardedRecord.outcome().assistantContent().getAsString());
		assertNull(discardedRecord.timeline().applied());

		orchestrator.setEnabled(true);
		orchestrator.submit(request("after pause", 3L));
		backend.awaitCalls(2, Duration.ofSeconds(1));
		String resumedConversation = conversationText(backend.conversation(1));
		assertTrue(resumedConversation.contains("before pause"));
		assertTrue(resumedConversation.contains("after pause"));
		assertFalse(resumedConversation.contains("discard during pause"));
		backend.succeed(1, noActionResponse());
		assertNotNull(awaitResult(orchestrator));
	}

	@Test
	void disablingPlannerSupersedesAnAlreadyInFlightTurn() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.submit(request("in flight at pause", 1L));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		orchestrator.setEnabled(false);
		assertFalse(orchestrator.hasInFlight());

		backend.succeed(0, noActionResponse());
		assertNull(orchestrator.poll());

		orchestrator.setEnabled(true);
		orchestrator.submit(request("after pause", 2L));
		backend.awaitCalls(2, Duration.ofSeconds(1));
		String resumedConversation = conversationText(backend.conversation(1));
		assertTrue(resumedConversation.contains("after pause"));
		assertFalse(resumedConversation.contains("in flight at pause"));
	}

	@Test
	void returnsImmediatePlannerResponseWhenNoToolIsRequested() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		backend.injectMockResponse(new PlannerResponse(
			"Sure, I'll follow you.",
			new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "Alice")
		));
		PlannerOrchestrator orchestrator = newOrchestrator(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);

		orchestrator.submit(baseRequest(null));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertNotNull(result);
		assertTrue(result.succeeded());
		assertEquals("Sure, I'll follow you.", result.response().replyText());
	}

	@Test
	void invalidChatMessagePlanRetriesOnceWithFormatReminder() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);

		orchestrator.submit(baseRequest(null));
		backend.awaitCalls(1, Duration.ofSeconds(1));

		backend.succeed(0, new PlannerResponse(
			List.of(new PlannerChatMessage("x".repeat(PlannerChatContract.MAX_MESSAGE_LENGTH + 1), 0)),
			new PlannerIntent("reply_only", null, null),
			null
		));
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));

		assertTrue(conversationText(backend.conversation(1)).contains("CHAT MESSAGE FORMAT REMINDER"));

		backend.succeed(1, new PlannerResponse(
			List.of(new PlannerChatMessage("Short now.", 0)),
			new PlannerIntent("reply_only", null, null),
			null
		));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertTrue(result.succeeded());
		assertEquals("Short now.", result.response().replyText());
		assertEquals(2, result.attempt());
	}

	@Test
	void secondInvalidChatMessagePlanIsContractedAndAccepted() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);
		String invalid = "# " + "x".repeat(PlannerChatContract.MAX_MESSAGE_LENGTH + 40);

		orchestrator.submit(baseRequest(null));
		backend.awaitCalls(1, Duration.ofSeconds(1));

		backend.succeed(0, new PlannerResponse(
			List.of(new PlannerChatMessage(invalid, PlannerChatContract.MAX_DELAY_TICKS + 20)),
			new PlannerIntent("reply_only", null, null),
			null
		));
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));

		backend.succeed(1, new PlannerResponse(
			List.of(new PlannerChatMessage(invalid, PlannerChatContract.MAX_DELAY_TICKS + 20)),
			new PlannerIntent("reply_only", null, null),
			null
		));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertTrue(result.succeeded());
		assertEquals(PlannerChatContract.MAX_MESSAGE_LENGTH, result.response().chatMessages().getFirst().text().length());
		assertFalse(result.response().chatMessages().getFirst().text().startsWith("#"));
		assertEquals(PlannerChatContract.MAX_DELAY_TICKS, result.response().chatMessages().getFirst().delayTicks());
		assertEquals(2, result.attempt());
	}

	@Test
	void singleToolCallFeedsVisionDescriptionBackIntoPlanner() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		backend.injectMockResponse(new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new PlannerToolRequest("take_a_look", "Describe the scene.")
		));
		backend.injectMockResponse(new PlannerResponse(
			"I see a forested hill ahead.",
			new PlannerIntent("reply_only", null, null)
		));
		StubVisionTool visionTool = new StubVisionTool(
			true,
			CompletableFuture.completedFuture(capturedScreenshot()),
			CompletableFuture.completedFuture(new VisionDescription(
				"A birch forest hill under open sky.",
				"gpt-4.1-mini",
				1L
			))
		);
		PlannerOrchestrator orchestrator = newOrchestrator(backend, visionTool, PlannerVisionMode.EXTERNAL_SUMMARY);

		orchestrator.submit(baseRequest(null));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertNotNull(result);
		assertTrue(result.succeeded());
		assertEquals("I see a forested hill ahead.", result.response().replyText());
		assertEquals("A birch forest hill under open sky.", result.request().toolResult());
		assertEquals(1, visionTool.captureRequestCount());
		assertEquals(1, visionTool.descriptionRequestCount());
	}

	@Test
	void singleToolCallFeedsInventoryInspectionBackIntoPlanner() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		backend.injectMockResponse(new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new PlannerToolRequest("inspect_inventory", null)
		));
		backend.injectMockResponse(new PlannerResponse(
			"You have 5 jungle logs.",
			new PlannerIntent("reply_only", null, null)
		));
		StubInventoryTool inventoryTool = new StubInventoryTool(
			"Tool result for inspect_inventory: itemCounts={minecraft:jungle_log=5}",
			"unused"
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			inventoryTool,
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.submit(baseRequest(null));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertNotNull(result);
		assertTrue(result.succeeded());
		assertEquals("You have 5 jungle logs.", result.response().replyText());
		assertEquals("Tool result for inspect_inventory: itemCounts={minecraft:jungle_log=5}", result.request().toolResult());
		assertEquals(1, inventoryTool.inventoryRequestCount());
		assertEquals(0, inventoryTool.craftablesRequestCount());
	}

	@Test
	void singleToolCallFeedsNearbyEntityInspectionBackIntoPlanner() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		backend.injectMockResponse(new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new PlannerToolRequest("inspect_nearby_entities", null)
		));
		backend.injectMockResponse(new PlannerResponse(
			"There is a sheep nearby.",
			new PlannerIntent("reply_only", null, null)
		));
		StubInventoryTool inventoryTool = new StubInventoryTool(
			"unused",
			"unused",
			"Tool result for inspect_nearby_entities: nearbyRadius=32.0, entityCount=1, entities=[{uuid=sheep-1, name=Sheep, entityTypeId=minecraft:sheep, distance=3.0, alive=true, health=8.0, pos=1,64,1}]"
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			inventoryTool,
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.submit(baseRequest(null));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertNotNull(result);
		assertTrue(result.succeeded());
		assertEquals("There is a sheep nearby.", result.response().replyText());
		assertEquals(
			"Tool result for inspect_nearby_entities: nearbyRadius=32.0, entityCount=1, entities=[{uuid=sheep-1, name=Sheep, entityTypeId=minecraft:sheep, distance=3.0, alive=true, health=8.0, pos=1,64,1}]",
			result.request().toolResult()
		);
		assertEquals(0, inventoryTool.inventoryRequestCount());
		assertEquals(0, inventoryTool.craftablesRequestCount());
		assertEquals(1, inventoryTool.nearbyEntitiesRequestCount());
	}

	@Test
	void freshInWorldPlannerSubmissionIncludesInventoryBootstrap() {
		RecordingBackend backend = new RecordingBackend();
		StubInventoryTool inventoryTool = new StubInventoryTool(
			"Tool result for inspect_inventory: itemCounts={minecraft:red_dye=1}",
			"unused"
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			inventoryTool,
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.submit(inWorldRequestAt(10L, 1_000L, "Alice", "@agent give me the red dye"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		LlmConversation conversation = backend.conversation(0);

		assertInventoryBootstrap(conversation, "minecraft:red_dye=1");
		assertTrue(terminalPrompt(conversation).contains("@agent give me the red dye"));

		backend.succeed(0, replyOnly(""));
		PlannerExecutionResult result = awaitResult(orchestrator);
		assertTrue(result.succeeded());
		assertEquals(1, inventoryTool.inventoryRequestCount());
		assertEquals(0, inventoryTool.craftablesRequestCount());
	}

	@Test
	void inventoryBootstrapRunsOnlyOncePerPlannerLifecycle() {
		RecordingBackend backend = new RecordingBackend();
		StubInventoryTool inventoryTool = new StubInventoryTool(
			"Tool result for inspect_inventory: itemCounts={minecraft:red_dye=1}",
			"unused"
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			inventoryTool,
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.submit(inWorldRequestAt(10L, 1_000L, "Alice", "@agent status"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		assertEquals(1, inventoryBootstrapCount(backend.conversation(0)));
		backend.succeed(0, replyOnly(""));
		assertTrue(awaitResult(orchestrator).succeeded());

		orchestrator.submit(inWorldRequestAt(20L, 2_000L, "Alice", "@agent status again"));
		backend.awaitCalls(2, Duration.ofSeconds(1));

		assertEquals(0, inventoryBootstrapCount(backend.conversation(1)));
		assertEquals(1, inventoryTool.inventoryRequestCount());
		backend.succeed(1, replyOnly(""));
		assertTrue(awaitResult(orchestrator).succeeded());
	}

	@Test
	void resetRearmsInventoryBootstrap() {
		RecordingBackend backend = new RecordingBackend();
		StubInventoryTool inventoryTool = new StubInventoryTool(
			"Tool result for inspect_inventory: itemCounts={minecraft:red_dye=1}",
			"unused"
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			inventoryTool,
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.submit(inWorldRequestAt(10L, 1_000L, "Alice", "@agent status"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, replyOnly(""));
		assertTrue(awaitResult(orchestrator).succeeded());

		orchestrator.reset();
		orchestrator.submit(inWorldRequestAt(20L, 2_000L, "Alice", "@agent status again"));
		backend.awaitCalls(2, Duration.ofSeconds(1));

		assertEquals(1, inventoryBootstrapCount(backend.conversation(1)));
		assertEquals(2, inventoryTool.inventoryRequestCount());
		backend.succeed(1, replyOnly(""));
		assertTrue(awaitResult(orchestrator).succeeded());
	}

	@Test
	void outOfWorldSubmissionDoesNotConsumeInventoryBootstrap() {
		RecordingBackend backend = new RecordingBackend();
		StubInventoryTool inventoryTool = new StubInventoryTool(
			"Tool result for inspect_inventory: itemCounts={minecraft:red_dye=1}",
			"unused"
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			inventoryTool,
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent status"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		assertEquals(0, inventoryBootstrapCount(backend.conversation(0)));
		assertEquals(0, inventoryTool.inventoryRequestCount());
		backend.succeed(0, replyOnly(""));
		assertTrue(awaitResult(orchestrator).succeeded());

		orchestrator.submit(inWorldRequestAt(20L, 2_000L, "Alice", "@agent status again"));
		backend.awaitCalls(2, Duration.ofSeconds(1));

		assertInventoryBootstrap(backend.conversation(1), "minecraft:red_dye=1");
		assertEquals(1, inventoryTool.inventoryRequestCount());
		backend.succeed(1, replyOnly(""));
		assertTrue(awaitResult(orchestrator).succeeded());
	}

	@Test
	void singleToolCallFeedsRecipeInspectionBackIntoPlanner() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		backend.injectMockResponse(new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new PlannerToolRequest("check_craftables", null)
		));
		backend.injectMockResponse(new PlannerResponse(
			"You can craft jungle planks.",
			new PlannerIntent("reply_only", null, null)
		));
		StubInventoryTool inventoryTool = new StubInventoryTool(
			"unused",
			"Tool result for check_craftables: availableCrafts=Available 2x2 crafts: [From {1*jungle_log} to 4*jungle_planks]: jungle_log_to_jungle_planks"
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			inventoryTool,
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.submit(baseRequest(null));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertNotNull(result);
		assertTrue(result.succeeded());
		assertEquals("You can craft jungle planks.", result.response().replyText());
		assertEquals(
			"Tool result for check_craftables: availableCrafts=Available 2x2 crafts: [From {1*jungle_log} to 4*jungle_planks]: jungle_log_to_jungle_planks",
			result.request().toolResult()
		);
		assertEquals(0, inventoryTool.inventoryRequestCount());
		assertEquals(1, inventoryTool.craftablesRequestCount());
	}

	@Test
	void providerToolCallsRouteThroughProvider() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		JsonObject args = new JsonObject();
		args.addProperty("query", "oak planks");
		args.addProperty("mode", "output");
		backend.injectMockResponse(new PlannerResponse(
			"",
			new PlannerToolCall("call_search", "search_recipes", args, null, null),
			null
		));
		backend.injectMockResponse(new PlannerResponse(
			"Oak planks have a recipe.",
			new PlannerIntent("reply_only", null, null)
		));
		RecordingPlannerToolProvider provider = new RecordingPlannerToolProvider();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			PlannerToolRegistry.of(provider)
		);

		orchestrator.submit(baseRequest(null));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertTrue(result.succeeded());
		assertEquals("Oak planks have a recipe.", result.response().replyText());
		assertEquals("Tool result for search_recipes: query=oak planks", result.request().toolResult());
		assertEquals(List.of("oak planks"), provider.queries());
	}

	@Test
	void externalToolCallUsesProviderWithoutStagedDiscovery() {
		RecordingPlannerToolProvider provider = new RecordingPlannerToolProvider();
		PlannerToolRegistry registry = PlannerToolRegistry.of(provider);
		PlannerOrchestrator orchestrator = newOrchestrator(
			new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults()),
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			registry
		);
		JsonObject arguments = new JsonObject();
		arguments.addProperty("query", "iron pickaxe");

		ExternalPlannerToolResult result = orchestrator.executeExternalTool("search_recipes", arguments).join();

		assertEquals("search_recipes", result.toolName());
		assertEquals("Tool result for search_recipes: query=iron pickaxe", result.text());
		assertFalse(result.hasImage());
		assertEquals(List.of("iron pickaxe"), provider.queries());
	}

	@Test
	void externalVisionToolReturnsRawImageWithoutInternalVisionSummary() {
		StubVisionTool visionTool = new StubVisionTool(
			true,
			CompletableFuture.completedFuture(capturedScreenshot()),
			CompletableFuture.failedFuture(new AssertionError("External Codex should receive the image directly"))
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults()),
			visionTool,
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		ExternalPlannerToolResult result = orchestrator.executeExternalTool("take_a_look", new JsonObject()).join();

		assertTrue(result.hasImage());
		assertEquals("image/png", result.imageAttachment().mimeType());
		assertArrayEquals(new byte[]{1, 2, 3}, result.imageAttachment().imageBytes());
		assertEquals(1, visionTool.captureRequestCount());
		assertEquals(0, visionTool.descriptionRequestCount());
	}

	@Test
	void providerImageToolUsesExternalVisionSummaryWhenPlannerDoesNotAcceptNativeImages() {
		RecordingBackend backend = new RecordingBackend();
		JsonObject args = new JsonObject();
		args.addProperty("kind", "minimap");
		PlannerToolCall toolCall = new PlannerToolCall("call_map", "take_map_look", args, null, null);
		ImagePlannerToolProvider provider = new ImagePlannerToolProvider();
		StubVisionTool visionTool = new StubVisionTool(
			true,
			CompletableFuture.failedFuture(new AssertionError("Provider image tools should not capture first-person view")),
			CompletableFuture.completedFuture(new VisionDescription(
				"Player marker is centered near a lake, with forest to the north.",
				"gpt-4.1-mini",
				1L
			))
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			visionTool,
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			PlannerToolRegistry.of(provider)
		);

		orchestrator.submit(baseRequest(null));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse("", toolCall, null));
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));

		LlmConversation followUp = backend.conversation(1);
		assertFalse(followUp.messages().stream().anyMatch(LlmChatMessage::hasImageAttachment));
		assertTrue(terminalPrompt(followUp).contains("Player marker is centered near a lake"));
		assertEquals(0, visionTool.captureRequestCount());
		assertEquals(1, visionTool.descriptionRequestCount());

		backend.succeed(1, replyOnly("The player marker is near the lake."));
		PlannerExecutionResult result = awaitResult(orchestrator);
		assertTrue(result.succeeded());
		assertTrue(result.request().toolResult().contains("Player marker is centered near a lake"));
	}

	@Test
	void actionToolCallsRouteThroughExecutorWithNarration() {
		JsonObject followArgs = new JsonObject();
		followArgs.addProperty("targetPlayer", "Alice");
		assertActionToolRoute("follow_player", followArgs);

		JsonObject collectArgs = new JsonObject();
		collectArgs.addProperty("resourceKind", "WOOD_LOGS");
		collectArgs.addProperty("quantity", 4);
		assertActionToolRoute("collect_resource", collectArgs);

		JsonObject craftArgs = new JsonObject();
		craftArgs.addProperty("recipeId", "minecraft:oak_planks");
		craftArgs.addProperty("times", 1);
		assertActionToolRoute("craft_recipe", craftArgs);

		JsonObject attackArgs = new JsonObject();
		attackArgs.addProperty("entityTypeId", "minecraft:sheep");
		assertActionToolRoute("attack_entity", attackArgs);

		JsonObject useArgs = new JsonObject();
		useArgs.addProperty("name", "Dinner");
		useArgs.addProperty("itemId", "minecraft:shears");
		assertActionToolRoute("use_entity", useArgs);

		JsonObject cancelArgs = new JsonObject();
		cancelArgs.addProperty("reason", "user changed plan");
		assertActionToolRoute("cancel_task", cancelArgs);

		JsonObject policyArgs = new JsonObject();
		policyArgs.addProperty("clearAll", true);
		policyArgs.add("removeRuleIds", JsonParser.parseString("[]").getAsJsonArray());
		policyArgs.add("upserts", JsonParser.parseString("[]").getAsJsonArray());
		assertActionToolRoute("update_event_policy", policyArgs);
	}

	@Test
	void toolRequestIgnoresStrayReplyTextWhenIntentIsNone() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		backend.injectMockResponse(new PlannerResponse(
			"I dont see anything yet, where are you?",
			new PlannerIntent("none", null, null),
			new PlannerToolRequest("take_a_look", "Describe the scene.")
		));
		backend.injectMockResponse(new PlannerResponse(
			"I can see a beach and ocean nearby.",
			new PlannerIntent("reply_only", null, null)
		));
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			new StubVisionTool(
				true,
				CompletableFuture.completedFuture(capturedScreenshot()),
				CompletableFuture.completedFuture(new VisionDescription(
					"A sandy beach next to the ocean under open sky.",
					"gpt-4.1-mini",
					1L
				))
			),
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.submit(baseRequest(null));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertNotNull(result);
		assertTrue(result.succeeded());
		assertEquals("I can see a beach and ocean nearby.", result.response().replyText());
		assertEquals("A sandy beach next to the ocean under open sky.", result.request().toolResult());
	}

	@Test
	void toolRequestIgnoresReplyOnlyIntentAndContinuesToolFlow() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		backend.injectMockResponse(new PlannerResponse(
			"I need to look around first!",
			new PlannerIntent("reply_only", null, null),
			new PlannerToolRequest("take_a_look", "Describe the scene.")
		));
		backend.injectMockResponse(new PlannerResponse(
			"I can see a forested hill ahead.",
			new PlannerIntent("reply_only", null, null)
		));
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			new StubVisionTool(
				true,
				CompletableFuture.completedFuture(capturedScreenshot()),
				CompletableFuture.completedFuture(new VisionDescription(
					"A birch forest hill under open sky.",
					"gpt-4.1-mini",
					1L
				))
			),
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.submit(baseRequest(null));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertNotNull(result);
		assertTrue(result.succeeded());
		assertEquals("I can see a forested hill ahead.", result.response().replyText());
		assertEquals("A birch forest hill under open sky.", result.request().toolResult());
	}

	@Test
	void toolFailureFallsBackToSyntheticUnavailableMarker() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		backend.injectMockResponse(new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new PlannerToolRequest("take_a_look", "Describe the scene.")
		));
		backend.injectMockResponse(new PlannerResponse(
			"I can't see clearly right now.",
			new PlannerIntent("acknowledge_failure", null, null)
		));
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			new StubVisionTool(
				true,
				CompletableFuture.failedFuture(new BridgeUnavailableException("capture_timeout", "Screenshot capture timed out")),
				CompletableFuture.completedFuture(new VisionDescription("unused", "gpt-4.1-mini", 1L))
			),
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.submit(baseRequest(null));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertNotNull(result);
		assertTrue(result.succeeded());
		assertEquals("VISION_UNAVAILABLE: capture_timeout", result.request().toolResult());
	}

	@Test
	void nativeVisionModeFeedsScreenshotBackIntoPlannerWithoutExternalSummary() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		backend.injectMockResponse(new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new PlannerToolRequest("take_a_look", null)
		));
		backend.injectMockResponse(new PlannerResponse(
			"I can see the hill clearly now.",
			new PlannerIntent("reply_only", null, null)
		));
		StubVisionTool visionTool = new StubVisionTool(
			false,
			CompletableFuture.completedFuture(capturedScreenshot()),
			CompletableFuture.failedFuture(new AssertionError("External summary should not be requested"))
		);
		PlannerOrchestrator orchestrator = newOrchestrator(backend, visionTool, PlannerVisionMode.NATIVE_TOOL_IMAGE);

		orchestrator.submit(baseRequest(null));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertNotNull(result);
		assertTrue(result.succeeded());
		assertEquals("I can see the hill clearly now.", result.response().replyText());
		assertEquals("Tool result for take_a_look: current first-person view attached.", result.request().toolResult());
		assertEquals(1, visionTool.captureRequestCount());
		assertEquals(0, visionTool.descriptionRequestCount());
	}

	@Test
	void nativeVisionModePassesTargetedTakeALookRequest() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		JsonObject args = new JsonObject();
		args.addProperty("direction", "west");
		backend.injectMockResponse(new PlannerResponse(
			"",
			new PlannerToolCall("call_look_west", "take_a_look", args, null, null),
			null
		));
		backend.injectMockResponse(new PlannerResponse(
			"I looked west.",
			new PlannerIntent("reply_only", null, null)
		));
		StubVisionTool visionTool = new StubVisionTool(
			false,
			CompletableFuture.completedFuture(capturedScreenshot()),
			CompletableFuture.failedFuture(new AssertionError("External summary should not be requested")),
			List.of("lookTarget=direction direction=west")
		);
		PlannerOrchestrator orchestrator = newOrchestrator(backend, visionTool, PlannerVisionMode.NATIVE_TOOL_IMAGE);

		orchestrator.submit(baseRequest(null));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertNotNull(result);
		assertTrue(result.succeeded());
		assertEquals(ViewCaptureRequest.TargetType.DIRECTION, visionTool.captureRequests().get(0).targetType());
		assertEquals("west", visionTool.captureRequests().get(0).direction());
		assertTrue(result.request().toolResult().contains("Tool result for take_a_look: current first-person view attached."));
		assertTrue(result.request().toolResult().contains("lookTarget=direction direction=west"));
	}

	@Test
	void externalVisionModeAppendsTargetedTakeALookWarning() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		JsonObject args = new JsonObject();
		args.addProperty("x", 10);
		args.addProperty("y", 64);
		args.addProperty("z", -5);
		args.addProperty("prompt", "Check whether this block is visible.");
		backend.injectMockResponse(new PlannerResponse(
			"",
			new PlannerToolCall("call_look_block", "take_a_look", args, null, null),
			null
		));
		backend.injectMockResponse(new PlannerResponse(
			"That block is blocked from view.",
			new PlannerIntent("reply_only", null, null)
		));
		StubVisionTool visionTool = new StubVisionTool(
			true,
			CompletableFuture.completedFuture(capturedScreenshot()),
			CompletableFuture.completedFuture(new VisionDescription(
				"I see a wall.",
				"gpt-4.1-mini",
				1L
			)),
			List.of(
				"lookTarget=block x=10 y=64 z=-5",
				"LOOK_WARNING: target_block_los_blocked blockingBlockId=minecraft:stone blockingPos=9,64,-5"
			)
		);
		PlannerOrchestrator orchestrator = newOrchestrator(backend, visionTool, PlannerVisionMode.EXTERNAL_SUMMARY);

		orchestrator.submit(baseRequest(null));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertNotNull(result);
		assertTrue(result.succeeded());
		assertEquals(ViewCaptureRequest.TargetType.BLOCK, visionTool.captureRequests().get(0).targetType());
		assertTrue(result.request().toolResult().contains("I see a wall."));
		assertTrue(result.request().toolResult().contains("lookTarget=block x=10 y=64 z=-5"));
		assertTrue(result.request().toolResult().contains("LOOK_WARNING: target_block_los_blocked"));
	}

	@Test
	void boundedToolPlanRejectsExcessiveToolRequests() {
		int toolRequestCountLimit = 20;

		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		for (int index = 0; index <= toolRequestCountLimit + 2; index++) {
			backend.injectMockResponse(new PlannerResponse(
				"",
				new PlannerIntent("none", null, null),
				new PlannerToolRequest("take_a_look", "Describe the scene " + index + ".")
			));
		}
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			new StubVisionTool(
				true,
				CompletableFuture.completedFuture(capturedScreenshot()),
				CompletableFuture.completedFuture(new VisionDescription(
					"A birch forest hill under open sky.",
					"gpt-4.1-mini",
					1L
				))
			),
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.submit(baseRequest(null));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertNotNull(result);
		assertEquals(LlmFailureType.PARSE_ERROR, result.failureType());
		assertTrue(result.failureMessage().contains("too many tools"));
		assertEquals(3, result.attempt());
		assertNull(result.response());
	}

	@Test
	void boundedTwoToolPlanCanRequestInventoryAndRecipesInOneGoal() {
		RecordingBackend backend = new RecordingBackend();
		StubInventoryTool inventoryTool = new StubInventoryTool(
			"Tool result for inspect_inventory: itemCounts={minecraft:jungle_log=5}",
			"Tool result for check_craftables: availableCrafts=Available 2x2 crafts: [From {1*jungle_log} to 4*jungle_planks]: jungle_log_to_jungle_planks"
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			inventoryTool,
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.submit(baseRequest(null));
		backend.awaitCalls(1, Duration.ofSeconds(1));

		backend.succeed(
			0,
			new PlannerResponse(
				"",
				new PlannerIntent("none", null, null),
				new PlannerToolRequest("inspect_inventory", null)
			)
		);
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));

		backend.succeed(
			1,
			new PlannerResponse(
				"",
				new PlannerIntent("none", null, null),
				new PlannerToolRequest("check_craftables", null)
			)
		);
		awaitBackendCallCount(orchestrator, backend, 3, Duration.ofSeconds(1));

		backend.succeed(2, replyOnly("You can craft jungle planks."));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertNotNull(result);
		assertTrue(result.succeeded());
		assertEquals("You can craft jungle planks.", result.response().replyText());
		assertEquals(1, inventoryTool.inventoryRequestCount());
		assertEquals(1, inventoryTool.craftablesRequestCount());
	}

	@Test
	void debugCompactionCompletesWithoutPlannerRequest() throws Exception {
		try (CompactionTestServer server = CompactionTestServer.start()) {
			AgentConfig.LlmConfig config = new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			);
			PlannerOrchestrator orchestrator = newCompactionOrchestrator(config);
			orchestrator.recordAssistantTurn(new DialogueTurn("agent", "On it.", 10L, 1_000L));

			assertTrue(orchestrator.startDebugCompaction());
			awaitDebugCompaction(orchestrator);

			PlannerOrchestratorDebugSnapshot snapshot = orchestrator.debugSnapshot();
			assertTrue(snapshot.lastCompactionResult().succeeded());
			assertEquals("follow Alice", snapshot.context().activeCheckpoint().activeGoal());
			assertEquals(1, server.requestCount());
		}
	}

	@Test
	void debugCompactionParsesThinkingWrappedPayload() throws Exception {
		try (CompactionTestServer server = CompactionTestServer.start("""
			{
			  "choices": [
			    {
			      "message": {
			        "content": [
			          {
			            "type": "reasoning",
			            "text": "Summarize important session facts.",
			            "thought": true,
			            "thought_signature": "sig-123"
			          },
			          {
			            "type": "text",
			            "text": "{\\"time_anchor\\":\\"Tuesday afternoon\\",\\"session_state\\":\\"in world\\",\\"active_goal\\":\\"follow Alice\\",\\"active_commitments\\":[\\"follow Alice\\"],\\"durable_facts\\":[\\"Alice is nearby\\"],\\"relevant_people\\":[\\"Alice\\"],\\"open_loops\\":[\\"keep following\\"],\\"recent_timeline\\":[\\"Alice asked for follow\\"],\\"forgettable_noise\\":[]}"
			          }
			        ]
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 2048,
			    "completion_tokens": 128,
			    "total_tokens": 2176
			  }
			}
			""")) {
			AgentConfig.LlmConfig config = new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			);
			PlannerOrchestrator orchestrator = newCompactionOrchestrator(config);

			assertTrue(orchestrator.startDebugCompaction());
			CompactionExecutionResult result = awaitCompaction(orchestrator);

			assertNotNull(result);
			assertTrue(result.succeeded());
			assertEquals("Tuesday afternoon", result.checkpoint().timeAnchor());
		}
	}

	@Test
	void firstSubmitStartsImmediatelyWithoutCoalesce() {
		RecordingBackend backend = new RecordingBackend();
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			3,
			clock
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "A"));
		backend.awaitCalls(1, Duration.ofSeconds(1));

		PlannerOrchestratorDebugSnapshot snapshot = orchestrator.debugSnapshot();
		assertFalse(snapshot.coalescePending());
		assertEquals(-1L, snapshot.coalesceReadyAtMs());
		assertEquals(0L, snapshot.coalesceWindowMs());
	}

	@Test
	void supersedesUnfinishedPlannerRequestsWithoutParallelBackendCalls() {
		RecordingBackend backend = new RecordingBackend();
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			3,
			clock
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "A"));
		backend.awaitCalls(1, Duration.ofSeconds(1));

		orchestrator.submit(requestAt(11L, 1_100L, "Alice", "B"));
		assertEquals(1, backend.callCount());
		assertEquals(List.of(1L), backend.discardedGenerations());
		PlannerOrchestratorDebugSnapshot firstCoalesce = orchestrator.debugSnapshot();
		assertTrue(firstCoalesce.coalescePending());
		assertEquals(1_010L, firstCoalesce.coalesceReadyAtMs());
		assertEquals(10L, firstCoalesce.coalesceWindowMs());

		clock.advanceMillis(9L);
		assertNull(orchestrator.poll());
		assertEquals(1, backend.callCount());

		clock.advanceMillis(1L);
		assertNull(orchestrator.poll());
		assertEquals(1, backend.callCount());

		orchestrator.submit(requestAt(12L, 1_200L, "Alice", "C"));
		assertEquals(1, backend.callCount());
		PlannerOrchestratorDebugSnapshot secondCoalesce = orchestrator.debugSnapshot();
		assertTrue(secondCoalesce.coalescePending());
		assertEquals(20L, secondCoalesce.coalesceWindowMs());

		clock.advanceMillis(19L);
		assertNull(orchestrator.poll());
		assertEquals(1, backend.callCount());

		clock.advanceMillis(1L);
		assertNull(orchestrator.poll());
		assertEquals(1, backend.callCount());

		assertPromptContains(backend.conversation(0), "[chat][Alice] A");

		backend.succeed(0, replyOnly("old A"));
		backend.awaitCompletions(1, Duration.ofSeconds(1));
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));

		assertPromptContains(backend.conversation(1), "[chat][Alice] A", "[chat][Alice] B", "[chat][Alice] C");

		backend.succeed(1, replyOnly("latest ABC"));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertEquals("latest ABC", result.response().replyText());
		assertEquals(2L, result.generation());
		assertEquals(3, result.request().triggerBatch().size());
		assertEquals(1L, orchestrator.debugSnapshot().supersededCount());
		assertEquals(List.of(2L), backend.acceptedGenerations());
	}

	@Test
	void autonomousEventsQueueWithoutSupersedingOrStarvingActiveGeneration() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "A"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		orchestrator.submit(autonomousRequestAt(11L, 1_100L, "damage B", "damage"));
		orchestrator.submit(autonomousRequestAt(12L, 1_200L, "damage C", "damage"));
		orchestrator.submit(autonomousRequestAt(13L, 1_300L, "surface restored", "survival-resolved"));

		assertEquals(1, backend.callCount());
		assertEquals(1L, orchestrator.debugSnapshot().activeGeneration());
		assertEquals(0L, orchestrator.debugSnapshot().supersededCount());

		backend.succeed(0, replyOnly("finished A"));
		PlannerExecutionResult first = awaitResult(orchestrator);
		assertEquals(1L, first.generation());
		assertEquals("finished A", first.response().replyText());
		assertEquals(0L, orchestrator.debugSnapshot().supersededCount());

		orchestrator.recordAssistantTurn(new DialogueTurn("agent", first.response().replyText(), 14L, 1_400L));
		orchestrator.onAcceptedReplyRecorded();
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));

		LlmConversation queued = backend.conversation(1);
		assertPromptContains(queued, "damage C", "surface restored");
		assertFalse(conversationText(queued).contains("damage B"));
		assertEquals(0L, orchestrator.debugSnapshot().supersededCount());

		backend.succeed(1, replyOnly("handled safety episode"));
		PlannerExecutionResult second = awaitResult(orchestrator);
		assertEquals(2L, second.generation());
		assertEquals(2, second.request().triggerBatch().size());
	}

	@Test
	void reflexEpochRejectsOldPlannerToolsAndLaunchesConsolidatedSafeTurn() {
		RecordingBackend backend = new RecordingBackend();
		ArrayList<String> invokedTools = new ArrayList<>();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			3,
			10,
			10,
			100,
			128,
			Clock.systemUTC(),
			toolCall -> {
				invokedTools.add(toolCall.name());
				return CompletableFuture.completedFuture("Tool result for " + toolCall.name() + ": ok");
			},
			PlannerToolNarrationSink.NO_OP
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "continue mining").withSafetyContext(0L, null));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		orchestrator.updateSafetyContext(1L, "hold-1", true);
		backend.succeed(0, PlannerResponse.toolCalls(List.of(
			new PlannerToolCall("old_cancel", PlannerToolCatalog.CANCEL_TASK, new JsonObject(), null, null)
		), null));

		List<StalePlannerRejection> rejections = awaitStaleRejections(orchestrator);
		assertEquals(1, rejections.size());
		assertEquals(0L, rejections.getFirst().requestSafetyEpoch());
		assertEquals(1L, rejections.getFirst().currentSafetyEpoch());
		assertTrue(invokedTools.isEmpty());
		assertEquals(1, backend.callCount());

		orchestrator.updateSafetyContext(1L, "hold-1", false);
		orchestrator.submit(autonomousRequestAt(20L, 2_000L, "reflex resolved holdId=hold-1", "survival-resolved")
			.withSafetyContext(1L, "hold-1"));
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
		assertPromptContains(backend.conversation(1), "continue mining", "reflex resolved holdId=hold-1");

		backend.succeed(1, replyOnly("safe decision"));
		PlannerExecutionResult result = awaitResult(orchestrator);
		assertEquals(2L, result.generation());
		assertEquals(1L, result.request().safetyEpoch());
		assertEquals("hold-1", result.request().safetyHoldId());
	}

	@Test
	void operatorHoldReleaseRejectsInFlightPlannerReplyFromThatHold() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.updateSafetyContext(1L, "hold-1", false);
		orchestrator.submit(autonomousRequestAt(20L, 2_000L, "reflex resolved holdId=hold-1", "survival-resolved")
			.withSafetyContext(1L, "hold-1"));
		backend.awaitCalls(1, Duration.ofSeconds(1));

		orchestrator.updateSafetyContext(1L, null, false);
		backend.succeed(0, replyOnly("cancel the interrupted task"));

		List<StalePlannerRejection> rejections = awaitStaleRejections(orchestrator);
		assertEquals(1, rejections.size());
		assertEquals("hold-1", rejections.getFirst().requestHoldId());
		assertNull(rejections.getFirst().currentHoldId());
		assertEquals(0L, orchestrator.debugSnapshot().supersededCount());
	}

	@Test
	void plannerToolThatReleasesCurrentHoldRebasesItsFollowUp() {
		RecordingBackend backend = new RecordingBackend();
		AtomicReference<PlannerOrchestrator> orchestratorRef = new AtomicReference<>();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			3,
			10,
			10,
			100,
			128,
			Clock.systemUTC(),
			toolCall -> {
				orchestratorRef.get().updateSafetyContext(1L, null, false);
				return CompletableFuture.completedFuture("Tool result for cancel_task: ok");
			},
			PlannerToolNarrationSink.NO_OP
		);
		orchestratorRef.set(orchestrator);
		orchestrator.updateSafetyContext(1L, "hold-1", false);
		orchestrator.submit(autonomousRequestAt(20L, 2_000L, "reflex resolved holdId=hold-1", "survival-resolved")
			.withSafetyContext(1L, "hold-1"));
		backend.awaitCalls(1, Duration.ofSeconds(1));

		backend.succeed(0, PlannerResponse.toolCalls(List.of(
			new PlannerToolCall("cancel", PlannerToolCatalog.CANCEL_TASK, new JsonObject(), null, null)
		), null));
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));

		backend.succeed(1, replyOnly("Cancelled after the safety episode."));
		PlannerExecutionResult result = awaitResult(orchestrator);
		assertEquals(1L, result.request().safetyEpoch());
		assertNull(result.request().safetyHoldId());
		assertEquals("Cancelled after the safety episode.", result.response().replyText());
		assertTrue(orchestrator.drainStalePlannerRejections().isEmpty());
	}

	@Test
	void coalesceWindowResetsFromLatestTriggerAndBatchesQueuedTriggersOnce() {
		RecordingBackend backend = new RecordingBackend();
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			3,
			clock
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "A"));
		backend.awaitCalls(1, Duration.ofSeconds(1));

		orchestrator.submit(requestAt(11L, 1_100L, "Alice", "B"));
		PlannerOrchestratorDebugSnapshot firstWindow = orchestrator.debugSnapshot();
		assertTrue(firstWindow.coalescePending());
		assertEquals(1_010L, firstWindow.coalesceReadyAtMs());
		assertEquals(10L, firstWindow.coalesceWindowMs());

		clock.advanceMillis(5L);
		orchestrator.submit(requestAt(12L, 1_200L, "Alice", "C"));
		PlannerOrchestratorDebugSnapshot resetWindow = orchestrator.debugSnapshot();
		assertTrue(resetWindow.coalescePending());
		assertEquals(20L, resetWindow.coalesceWindowMs());
		assertEquals(1_025L, resetWindow.coalesceReadyAtMs());

		clock.advanceMillis(19L);
		assertNull(orchestrator.poll());
		assertEquals(1, backend.callCount());

		clock.advanceMillis(1L);
		assertNull(orchestrator.poll());
		assertEquals(1, backend.callCount());

		backend.succeed(0, replyOnly("old A"));
		backend.awaitCompletions(1, Duration.ofSeconds(1));
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
		assertPromptContains(backend.conversation(1), "[chat][Alice] A", "[chat][Alice] B", "[chat][Alice] C");

		backend.succeed(1, replyOnly("latest ABC"));

		PlannerExecutionResult result = awaitResult(orchestrator);
		assertEquals("latest ABC", result.response().replyText());
		assertEquals(2L, result.generation());
		assertEquals(3, result.request().triggerBatch().size());
	}

	@Test
	void coalesceWindowClampsAtConfiguredMaximum() {
		RecordingBackend backend = new RecordingBackend();
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			3,
			clock
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "A"));
		backend.awaitCalls(1, Duration.ofSeconds(1));

		for (int index = 0; index < 15; index++) {
			clock.advanceMillis(1L);
			orchestrator.submit(requestAt(11L + index, 1_100L + index, "Alice", "T" + index));
		}

		PlannerOrchestratorDebugSnapshot snapshot = orchestrator.debugSnapshot();
		assertTrue(snapshot.coalescePending());
		assertEquals(100L, snapshot.coalesceWindowMs());
		assertEquals(clock.instant().toEpochMilli() + 100L, snapshot.coalesceReadyAtMs());

		clock.advanceMillis(99L);
		assertNull(orchestrator.poll());
		assertEquals(1, backend.callCount());

		clock.advanceMillis(1L);
		assertNull(orchestrator.poll());
		assertEquals(1, backend.callCount());

		backend.succeed(0, replyOnly("old A"));
		backend.awaitCompletions(1, Duration.ofSeconds(1));
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
	}

	@Test
	void completedGenerationWinsOverLaterSubmitUntilAcceptedReplyIsRecorded() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "A"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, replyOnly("reply A"));
		backend.awaitCompletions(1, Duration.ofSeconds(1));

		orchestrator.submit(requestAt(11L, 1_100L, "Alice", "B"));
		assertEquals(1, backend.callCount());

		PlannerExecutionResult first = awaitResult(orchestrator);
		assertEquals("reply A", first.response().replyText());
		assertEquals(1L, first.generation());
		assertEquals(1, first.request().triggerBatch().size());
		assertEquals("A", first.request().triggerBatch().triggers().getFirst().text());

		orchestrator.recordAssistantTurn(new DialogueTurn("agent", "reply A", 20L, 2_000L));
		orchestrator.onAcceptedReplyRecorded();
		backend.awaitCalls(2, Duration.ofSeconds(1));

		String secondPrompt = terminalPrompt(backend.conversation(1));
		assertTrue(secondPrompt.contains("[chat][Alice] B"));
		assertFalse(secondPrompt.contains("[chat][Alice] A"));

		backend.succeed(1, replyOnly("reply B"));
		PlannerExecutionResult second = awaitResult(orchestrator);
		assertEquals("reply B", second.response().replyText());
		assertEquals(2L, second.generation());
	}

	@Test
	void timeoutRetriesOnceUsingTheSameFrozenSnapshot() {
		RecordingBackend backend = new RecordingBackend();
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			3,
			clock
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "retry please"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		String firstPrompt = terminalPrompt(backend.conversation(0));

		backend.fail(0, LlmFailureType.TIMEOUT, "Injected timeout");
		awaitRetryPending(orchestrator, Duration.ofSeconds(1));

		clock.advanceMillis(250L);
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));

		assertEquals(firstPrompt, terminalPrompt(backend.conversation(1)));

		backend.succeed(1, replyOnly("retried"));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertEquals("retried", result.response().replyText());
		assertEquals(1L, result.generation());
		assertEquals(2, result.attempt());
	}

	@Test
	void conversationSnapshotTracksOutboundMessagesAndAssistantReplyCard() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "A"));
		backend.awaitCalls(1, Duration.ofSeconds(1));

		PlannerConversationDebugSnapshot submitted = orchestrator.conversationDebugSnapshot();
		assertEquals(1L, submitted.generation());
		assertEquals("PLANNER_REQUEST", submitted.phase());
		assertEquals(1, submitted.attempt());
		assertTrue(submitted.messages().stream().anyMatch(message -> message.kind() == PlannerConversationDebugKind.SYSTEM));
		PlannerConversationDebugMessage terminalMessage = lastConversationMessage(submitted);
		assertEquals(PlannerConversationDebugKind.USER_TURN, terminalMessage.kind());
		assertTrue(terminalMessage.text().contains("[chat][Alice] A"));

		backend.succeed(0, replyOnly("reply A"));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertEquals("reply A", result.response().replyText());
		PlannerConversationDebugSnapshot canonicalAfterReply = orchestrator.conversationDebugSnapshot();
		assertEquals(PlannerConversationDebugKind.USER_TURN, lastConversationMessage(canonicalAfterReply).kind());
		assertNull(findConversationMessage(canonicalAfterReply, PlannerConversationDebugKind.ASSISTANT_TURN, "reply A"));

		PlannerConversationDebugMessage replyCard = lastConversationMessage(orchestrator.projectedConversationDebugSnapshot());
		assertEquals(PlannerConversationDebugKind.ASSISTANT_TURN, replyCard.kind());
		assertEquals("reply A", replyCard.text());
	}

	@Test
	void conversationDebugSnapshotReturnsCanonicalSubmittedPromptAfterReplyOnlyCompletion() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "A"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, replyOnly("reply A"));
		PlannerExecutionResult result = awaitResult(orchestrator);
		assertTrue(result.succeeded());

		PlannerConversationDebugSnapshot canonical = orchestrator.conversationDebugSnapshot();
		assertEquals(1L, canonical.generation());
		assertEquals("PLANNER_REQUEST", canonical.phase());
		assertTrue(canonical.messages().stream().anyMatch(message -> message.kind() == PlannerConversationDebugKind.SYSTEM));
		assertEquals(PlannerConversationDebugKind.USER_TURN, lastConversationMessage(canonical).kind());
		assertTrue(lastConversationMessage(canonical).text().contains("[chat][Alice] A"));
		assertNull(findConversationMessage(canonical, PlannerConversationDebugKind.ASSISTANT_TURN, "reply A"));

		PlannerConversationDebugSnapshot projected = orchestrator.projectedConversationDebugSnapshot();
		assertNotNull(findConversationMessage(projected, PlannerConversationDebugKind.ASSISTANT_TURN, "reply A"));
	}

	@Test
	void conversationSourcesMatchJournalProjectedSnapshots() {
		RecordingBackend backend = new RecordingBackend();
		AgentDebugRecorder debugRecorder = new AgentDebugRecorder();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			debugRecorder
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "A"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, replyOnly("reply A"));
		PlannerExecutionResult result = awaitResult(orchestrator);
		assertTrue(result.succeeded());

		PlannerConversationDebugSnapshot canonical = orchestrator.conversationDebugSnapshot();
		PlannerConversationDebugSnapshot projected = orchestrator.projectedConversationDebugSnapshot();
		ConversationSourcesDebugSnapshot sources = debugRecorder.conversationSourcesSnapshot();
		assertEquals(canonical, sources.canonicalConversation());
		assertEquals(projected, sources.projectedConversation());
		assertEquals(canonical.messages().size(), sources.canonicalMessageCount());
		assertEquals(projected.messages().size(), sources.projectedMessageCount());
		assertEquals(1, sources.canonicalUserTurnCount());
		assertEquals(1, sources.projectedUserTurnCount());
		assertNotNull(findConversationMessage(projected, PlannerConversationDebugKind.ASSISTANT_TURN, "reply A"));
	}

	@Test
	void conversationSnapshotShowsGoalSetOutcomeWhenReplyTextIsBlank() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent follow me"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse(
			"",
			new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "Alice")
		));

		PlannerExecutionResult result = awaitResult(orchestrator);
		assertTrue(result.succeeded());

		PlannerConversationDebugMessage outcomeCard = lastConversationMessage(orchestrator.projectedConversationDebugSnapshot());
		assertEquals(PlannerConversationDebugKind.TASK, outcomeCard.kind());
		assertTrue(outcomeCard.text().contains("Goal call: FOLLOW_PLAYER -> Alice."));
	}

	@Test
	void conversationSnapshotShowsReplyAndOperationCardsForGoalAndEventFilters() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent follow me but mute system spam"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse(
			"On it.",
			new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "Alice"),
			null,
			new EventPolicyChanges(
				true,
				List.of("old-noise-rule"),
				List.of(new EventPolicyRuleUpsert(
					"mute-system-server",
					"ignore",
					new EventPolicyMatch("social.system_message", null, "server", null, null, null, null),
					"system chatter"
				))
			)
		));

		PlannerExecutionResult result = awaitResult(orchestrator);
		assertTrue(result.succeeded());

		PlannerConversationDebugSnapshot snapshot = orchestrator.projectedConversationDebugSnapshot();
		assertNotNull(findConversationMessage(snapshot, PlannerConversationDebugKind.ASSISTANT_TURN, "On it."));
		assertNotNull(findConversationMessage(snapshot, PlannerConversationDebugKind.TASK, "Goal call: FOLLOW_PLAYER -> Alice."));
		assertNotNull(findConversationMessage(snapshot, PlannerConversationDebugKind.TASK, "Event filter: clear all rules."));
		assertNotNull(findConversationMessage(snapshot, PlannerConversationDebugKind.TASK, "Event filter: remove old-noise-rule."));
		assertNotNull(findConversationMessage(snapshot, PlannerConversationDebugKind.TASK, "Event filter: upsert mute-system-server -> IGNORE on social.system_message [speaker=server]."));
	}

	@Test
	void conversationSnapshotKeepsPreviousOperationCardsAcrossLaterPlannerSubmits() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent follow me"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse(
			"",
			new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "Alice")
		));
		PlannerExecutionResult firstResult = awaitResult(orchestrator);
		assertTrue(firstResult.succeeded());
		assertNotNull(findConversationMessage(
			orchestrator.projectedConversationDebugSnapshot(),
			PlannerConversationDebugKind.TASK,
			"Goal call: FOLLOW_PLAYER -> Alice."
		));

		orchestrator.submit(requestAt(11L, 1_100L, "Alice", "status?"));
		backend.awaitCalls(2, Duration.ofSeconds(1));

		PlannerConversationDebugSnapshot submitted = orchestrator.projectedConversationDebugSnapshot();
		assertNotNull(findConversationMessage(submitted, PlannerConversationDebugKind.TASK, "Goal call: FOLLOW_PLAYER -> Alice."));
		assertNotNull(findConversationMessage(submitted, PlannerConversationDebugKind.USER_TURN, "[chat][Alice] status?"));
	}

	@Test
	void conversationSnapshotKeepsOperationCardsWhenLaterPromptHasManyNotices() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent ignore noisy system messages"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse(
			"",
			new PlannerIntent("reply_only", null, null),
			null,
			new EventPolicyChanges(
				false,
				List.of(),
				List.of(new EventPolicyRuleUpsert(
					"mute-system-server",
					"ignore",
					new EventPolicyMatch("social.system_message", null, "server", null, null, null, null),
					"noise"
				))
			)
		));
		PlannerExecutionResult firstResult = awaitResult(orchestrator);
		assertTrue(firstResult.succeeded());

		List<SemanticEvent> noisyEvents = new ArrayList<>();
		for (int index = 0; index < 64; index++) {
			noisyEvents.add(new SemanticEvent(
				index + 1L,
				200L + index,
				2_000L + index,
				"pickup.item_picked_up",
				Map.of("actor", "self", "itemId", "minecraft:item_" + index, "count", 1)
			));
		}
		orchestrator.recordEvents(
			new SemanticEventQueryResult(1L, 64L, false, noisyEvents),
			new PlannerRequestSeed(20L, 2_100L, SessionMode.OUT_OF_WORLD, "Alice", null)
		);
		orchestrator.submit(requestAt(21L, 2_100L, "Alice", "status?"));
		backend.awaitCalls(2, Duration.ofSeconds(1));

		PlannerConversationDebugSnapshot submitted = orchestrator.projectedConversationDebugSnapshot();
		assertNotNull(findConversationMessage(submitted, PlannerConversationDebugKind.TASK, "Event filter: upsert mute-system-server -> IGNORE on social.system_message [speaker=server]."));
		assertNotNull(findConversationMessage(submitted, PlannerConversationDebugKind.USER_TURN, "[chat][Alice] status?"));
	}

	@Test
	void conversationSnapshotShowsCoalescedSemanticNoticesInOutboundPrompt() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);

		orchestrator.recordEvents(new SemanticEventQueryResult(
			1L,
			3L,
			false,
			List.of(
				new SemanticEvent(1L, 100L, 1_000L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:dirt", "count", 1)),
				new SemanticEvent(2L, 101L, 1_010L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:dirt", "count", 1)),
				new SemanticEvent(3L, 102L, 1_020L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:dirt", "count", 1))
			)
		), 1_020L);
		orchestrator.submit(requestAt(10L, 1_020L, "Alice", "A"));
		backend.awaitCalls(1, Duration.ofSeconds(1));

		PlannerConversationDebugSnapshot submitted = orchestrator.conversationDebugSnapshot();
		List<PlannerConversationDebugMessage> dirtNotices = submitted.messages().stream()
			.filter(message -> message.kind() == PlannerConversationDebugKind.NOTICE)
			.filter(message -> message.text().contains("minecraft:dirt"))
			.toList();
		assertEquals(1, dirtNotices.size());
		assertTrue(dirtNotices.getFirst().text().contains("3x minecraft:dirt"));
	}

	@Test
	void conversationSnapshotCoalescesRepeatedPickupNoticesAcrossMultipleRecordCalls() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);

		orchestrator.recordEvents(new SemanticEventQueryResult(
			1L,
			1L,
			false,
			List.of(new SemanticEvent(1L, 100L, 1_000L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:sunflower", "count", 1)))
		), 1_000L);
		orchestrator.recordEvents(new SemanticEventQueryResult(
			2L,
			2L,
			false,
			List.of(new SemanticEvent(2L, 101L, 1_010L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:sunflower", "count", 1)))
		), 1_010L);
		orchestrator.recordEvents(new SemanticEventQueryResult(
			3L,
			3L,
			false,
			List.of(new SemanticEvent(3L, 102L, 1_020L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:wheat_seeds", "count", 1)))
		), 1_020L);
		orchestrator.recordEvents(new SemanticEventQueryResult(
			4L,
			4L,
			false,
			List.of(new SemanticEvent(4L, 103L, 1_030L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:sunflower", "count", 1)))
		), 1_030L);

		orchestrator.submit(requestAt(10L, 1_030L, "Alice", "A"));
		backend.awaitCalls(1, Duration.ofSeconds(1));

		PlannerConversationDebugSnapshot submitted = orchestrator.conversationDebugSnapshot();
		List<PlannerConversationDebugMessage> pickupNotices = submitted.messages().stream()
			.filter(message -> message.kind() == PlannerConversationDebugKind.NOTICE)
			.filter(message -> message.text().contains("picked up"))
			.toList();
		assertEquals(2, pickupNotices.size());
		assertTrue(pickupNotices.get(0).text().contains("3x minecraft:sunflower"));
		assertTrue(pickupNotices.get(1).text().contains("1x minecraft:wheat_seeds"));
	}

	@Test
	void pendingSemanticOverflowAutoSubmitsFlushWithoutPersistingSyntheticPrompt() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			3,
			10,
			10,
			100,
			2,
			Clock.systemDefaultZone()
		);

		PlannerRequestSeed seed = new PlannerRequestSeed(10L, 1_000L, SessionMode.OUT_OF_WORLD, "Alice", null);
		orchestrator.recordEvents(new SemanticEventQueryResult(
			1L,
			2L,
			false,
			List.of(
				new SemanticEvent(1L, 100L, 1_000L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:dirt", "count", 1)),
				new SemanticEvent(2L, 101L, 1_010L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:dirt", "count", 1))
			)
		), seed);
		backend.awaitCalls(1, Duration.ofSeconds(1));

		PlannerConversationDebugSnapshot submitted = orchestrator.conversationDebugSnapshot();
		assertEquals(PlannerConversationDebugKind.TASK, lastConversationMessage(submitted).kind());
		assertTrue(lastConversationMessage(submitted).text().contains("Pending semantic context reached capacity"));
		assertTrue(submitted.messages().stream().anyMatch(message ->
			message.kind() == PlannerConversationDebugKind.NOTICE && message.text().contains("2x minecraft:dirt")
		));

		backend.succeed(0, replyOnly("noted"));
		PlannerExecutionResult result = awaitResult(orchestrator);

		assertEquals("noted", result.response().replyText());
		assertNull(result.request().triggerBatch());

		orchestrator.recordAssistantTurn(new DialogueTurn("agent", "noted", 11L, 1_100L));
		orchestrator.onAcceptedReplyRecorded();
		orchestrator.submit(requestAt(12L, 1_200L, "Alice", "status?"));
		backend.awaitCalls(2, Duration.ofSeconds(1));

		String secondPrompt = terminalPrompt(backend.conversation(1));
		assertFalse(secondPrompt.contains("Pending semantic context reached capacity"));
	}

	@Test
	void toolRequestAddsTaskCardWhileWaitingForToolResult() {
		RecordingBackend backend = new RecordingBackend();
		StubVisionTool visionTool = new StubVisionTool(
			true,
			new CompletableFuture<>(),
			CompletableFuture.failedFuture(new AssertionError("External summary should not be requested"))
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			visionTool,
			PlannerVisionMode.NATIVE_TOOL_IMAGE
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent what do you see?"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new PlannerToolRequest("take_a_look", null)
		));
		backend.awaitCompletions(1, Duration.ofSeconds(1));

		assertNull(awaitNullPoll(orchestrator));
		PlannerConversationDebugMessage taskCard = lastConversationMessage(orchestrator.projectedConversationDebugSnapshot());
		assertEquals(PlannerConversationDebugKind.TASK, taskCard.kind());
		assertTrue(taskCard.text().contains("Tool call: take_a_look"));
	}

	@Test
	void toolFollowUpConversationShowsCanonicalToolCallAndToolResults() {
		RecordingBackend backend = new RecordingBackend();
		StubVisionTool visionTool = new StubVisionTool(
			true,
			CompletableFuture.completedFuture(capturedScreenshot()),
			CompletableFuture.failedFuture(new AssertionError("External summary should not be requested"))
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			visionTool,
			PlannerVisionMode.NATIVE_TOOL_IMAGE
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent what do you see?"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new PlannerToolRequest("take_a_look", null)
		));
		backend.awaitCompletions(1, Duration.ofSeconds(1));

		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
		PlannerConversationDebugSnapshot followUp = orchestrator.conversationDebugSnapshot();
		assertEquals(1L, followUp.generation());
		assertEquals("TOOL_FOLLOW_UP", followUp.phase());
		PlannerConversationDebugMessage toolCallMessage = findConversationMessage(followUp, PlannerConversationDebugKind.ASSISTANT_TURN, "Tool call: take_a_look");
		assertNotNull(toolCallMessage);
		assertEquals("assistant", toolCallMessage.role());
		PlannerConversationDebugMessage toolResultMessage = findConversationMessage(followUp, PlannerConversationDebugKind.TOOL_RESULT, "current first-person view attached");
		assertNotNull(toolResultMessage);
		PlannerConversationDebugMessage imageMessage = followUp.messages().stream()
			.filter(message -> message.kind() == PlannerConversationDebugKind.TOOL_RESULT)
			.filter(PlannerConversationDebugMessage::hasImageAttachment)
			.findFirst()
			.orElseThrow();
		assertTrue(imageMessage.text().contains("current first-person view attached"));

		PlannerConversationDebugSnapshot projected = orchestrator.projectedConversationDebugSnapshot();
		PlannerConversationDebugMessage toolCallCard = findConversationMessage(projected, PlannerConversationDebugKind.TASK, "Tool call: take_a_look");
		assertNotNull(toolCallCard);
	}

	@Test
	void conversationSnapshotShowsAcceptedToolFollowUpReplyBeforeNextSubmit() {
		RecordingBackend backend = new RecordingBackend();
		StubVisionTool visionTool = new StubVisionTool(
			true,
			CompletableFuture.completedFuture(capturedScreenshot()),
			CompletableFuture.failedFuture(new AssertionError("External summary should not be requested"))
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			visionTool,
			PlannerVisionMode.NATIVE_TOOL_IMAGE
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent what do you see?"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new PlannerToolRequest("take_a_look", null)
		));
		backend.awaitCompletions(1, Duration.ofSeconds(1));
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
		backend.succeed(1, replyOnly("I see snow."));
		PlannerExecutionResult result = awaitResult(orchestrator);

		orchestrator.recordAssistantTurn(new DialogueTurn("agent", result.response().replyText(), 11L, 1_100L));
		orchestrator.onAcceptedReplyRecorded();

		PlannerConversationDebugSnapshot snapshot = orchestrator.projectedConversationDebugSnapshot();
		assertNotNull(findConversationMessage(snapshot, PlannerConversationDebugKind.ASSISTANT_TURN, "I see snow."));
		assertEquals("TOOL_FOLLOW_UP", snapshot.phase());
	}

	@Test
	void acceptedToolExchangeRehydratesIntoNextPlannerPrompt() {
		RecordingBackend backend = new RecordingBackend();
		StubInventoryTool inventoryTool = new StubInventoryTool(
			"unused",
			"Tool result for check_craftables: availableCrafts=Available 2x2 crafts: [From {1*birch_wood} to 4*birch_planks]: birch_wood_to_birch_planks"
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			inventoryTool,
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent what can I craft?"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new PlannerToolRequest("check_craftables", null),
			null,
			JsonParser.parseString("""
				[
				  {
				    "type": "text",
				    "text": "{\\"replyText\\":\\"\\",\\"intent\\":{\\"type\\":\\"none\\"},\\"toolRequest\\":{\\"type\\":\\"check_craftables\\",\\"prompt\\":null}}"
				  }
				]
				""")
		));

		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
		backend.succeed(1, replyOnly("You can craft birch planks."));
		PlannerExecutionResult firstResult = awaitResult(orchestrator);
		assertTrue(firstResult.succeeded());

		orchestrator.recordAssistantTurn(new DialogueTurn("agent", firstResult.response().replyText(), 11L, 1_100L));
		orchestrator.onAcceptedReplyRecorded();

		orchestrator.submit(requestAt(20L, 2_000L, "Alice", "@agent craft them"));
		awaitBackendCallCount(orchestrator, backend, 3, Duration.ofSeconds(1));

		LlmConversation secondPrompt = backend.conversation(2);
		LlmChatMessage replayedToolRequest = secondPrompt.messages().stream()
			.filter(message -> "assistant".equals(message.role()) && message.hasToolCalls())
			.filter(message -> message.toolCalls().stream().anyMatch(toolCall -> "check_craftables".equals(toolCall.name())))
			.findFirst()
			.orElseThrow();
		assertNotNull(replayedToolRequest);

		LlmChatMessage replayedToolResult = secondPrompt.messages().stream()
			.filter(message -> message.kind() == LlmMessageKind.TOOL_RESULT)
			.findFirst()
			.orElseThrow();
		assertTrue(replayedToolResult.content().contains("birch_wood_to_birch_planks"));
	}

	@Test
	void multipleReadToolCallsExecuteAndReturnAllResultsInOneFollowUp() {
		RecordingBackend backend = new RecordingBackend();
		StubInventoryTool inventoryTool = new StubInventoryTool(
			"Tool result for inspect_inventory: itemCounts={minecraft:oak_log=3}",
			"Tool result for check_craftables: availableCrafts=oak_planks"
		);
		RecordingLifecycleListener lifecycleListener = new RecordingLifecycleListener();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			inventoryTool,
			PlannerVisionMode.EXTERNAL_SUMMARY,
			lifecycleListener
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent inspect and craft"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, PlannerResponse.toolCalls(List.of(
			new PlannerToolCall("call_inv", "inspect_inventory", new JsonObject(), null, null),
			new PlannerToolCall("call_craftables", "check_craftables", new JsonObject(), null, null)
		), null));

		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
		assertEquals(1, inventoryTool.inventoryRequestCount());
		assertEquals(1, inventoryTool.craftablesRequestCount());

		LlmConversation followUp = backend.conversation(1);
		LlmChatMessage replayedToolCalls = followUp.messages().stream()
			.filter(message -> "assistant".equals(message.role()) && message.hasToolCalls())
			.filter(message -> message.toolCalls().stream().anyMatch(toolCall -> "inspect_inventory".equals(toolCall.name())))
			.findFirst()
			.orElseThrow();
		assertEquals(2, replayedToolCalls.toolCalls().size());
		assertEquals("inspect_inventory", replayedToolCalls.toolCalls().get(0).name());
		assertEquals("check_craftables", replayedToolCalls.toolCalls().get(1).name());
		assertTrue(followUp.messages().stream()
			.anyMatch(message -> "tool".equals(message.role())
				&& "call_inv".equals(message.toolCallId())
				&& message.content().contains("minecraft:oak_log=3")));
		assertTrue(followUp.messages().stream()
			.anyMatch(message -> "tool".equals(message.role())
				&& "call_craftables".equals(message.toolCallId())
				&& message.content().contains("oak_planks")));
		assertEquals(2, lifecycleListener.completedToolResults().size());
		assertTrue(lifecycleListener.completedToolResults().get(0).contains("inspect_inventory"));
		assertTrue(lifecycleListener.completedToolResults().get(1).contains("check_craftables"));
		PlannerConversationDebugMessage taskCard = lastConversationMessage(orchestrator.projectedConversationDebugSnapshot());
		assertTrue(taskCard.text().contains("Tool calls: inspect_inventory,check_craftables"));
	}

	@Test
	void multipleRegisteredProviderReadsExecuteInOrderWithoutRepair() {
		RecordingBackend backend = new RecordingBackend();
		BatchReadProvider provider = new BatchReadProvider();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			PlannerToolRegistry.of(provider)
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent inspect recipes"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, PlannerResponse.toolCalls(List.of(
			new PlannerToolCall("call_search", "search_recipes", new JsonObject(), null, null),
			new PlannerToolCall("call_uses", "find_recipe_uses", new JsonObject(), null, null)
		), null));

		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
		assertEquals(List.of("search_recipes", "find_recipe_uses"), provider.executedTools());
		LlmConversation followUp = backend.conversation(1);
		assertTrue(followUp.messages().stream()
			.anyMatch(message -> "tool".equals(message.role())
				&& "call_search".equals(message.toolCallId())
				&& message.content().contains("recipes")));
		assertTrue(followUp.messages().stream()
			.anyMatch(message -> "tool".equals(message.role())
				&& "call_uses".equals(message.toolCallId())
				&& message.content().contains("uses")));

		backend.succeed(1, replyOnly("Recipe data is ready."));
		assertTrue(awaitResult(orchestrator).succeeded());
	}

	@Test
	void inspectWorldCanBatchWithTextReadTools() {
		RecordingBackend backend = new RecordingBackend();
		StubInventoryTool inventoryTool = new StubInventoryTool(
			"Tool result for inspect_inventory: itemCounts={minecraft:wheat_seeds=4}",
			"unused"
		);
		PlannerToolRegistry toolRegistry = PlannerToolRegistry.of(new CurrentWorldQueryToolProvider(arguments ->
			CompletableFuture.completedFuture("Tool result for inspect_world: mode=find_placement_sites returned=1 sites=[{targetPos=1,64,1}]")
		));
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			inventoryTool,
			PlannerVisionMode.EXTERNAL_SUMMARY,
			toolRegistry
		);

		JsonObject worldArgs = new JsonObject();
		worldArgs.addProperty("mode", "find_placement_sites");
		worldArgs.addProperty("scope", "self");
		worldArgs.addProperty("targetMaterial", "air");
		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent find farm spots"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, PlannerResponse.toolCalls(List.of(
			new PlannerToolCall("call_world", PlannerToolCatalog.INSPECT_WORLD, worldArgs, null, null),
			new PlannerToolCall("call_inv", "inspect_inventory", new JsonObject(), null, null)
		), null));

		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));

		LlmConversation followUp = backend.conversation(1);
		assertTrue(followUp.messages().stream()
			.anyMatch(message -> "tool".equals(message.role())
				&& "call_world".equals(message.toolCallId())
				&& message.content().contains("targetPos=1,64,1")));
		assertTrue(followUp.messages().stream()
			.anyMatch(message -> "tool".equals(message.role())
				&& "call_inv".equals(message.toolCallId())
				&& message.content().contains("minecraft:wheat_seeds=4")));
		PlannerConversationDebugMessage taskCard = lastConversationMessage(orchestrator.projectedConversationDebugSnapshot());
		assertTrue(taskCard.text().contains("Tool calls: inspect_world,inspect_inventory"));
	}

	@Test
	void multipleActionToolCallsAreRejectedBeforeDispatch() {
		RecordingBackend backend = new RecordingBackend();
		ArrayList<String> invokedTools = new ArrayList<>();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			3,
			10,
			10,
			100,
			128,
			Clock.systemUTC(),
			toolCall -> {
				invokedTools.add(toolCall.name());
				return CompletableFuture.completedFuture("Tool result for " + toolCall.name() + ": ok");
			},
			PlannerToolNarrationSink.NO_OP
		);

		JsonObject navigateArgs = new JsonObject();
		navigateArgs.addProperty("x", 1);
		navigateArgs.addProperty("y", 64);
		navigateArgs.addProperty("z", 2);
		navigateArgs.addProperty("exactY", false);
		JsonObject craftArgs = new JsonObject();
		craftArgs.addProperty("recipeId", "minecraft:oak_planks");
		craftArgs.addProperty("times", 1);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent move and craft"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, PlannerResponse.toolCalls(List.of(
			new PlannerToolCall("call_nav", PlannerToolCatalog.NAVIGATE_TO, navigateArgs, null, null),
			new PlannerToolCall("call_craft", PlannerToolCatalog.CRAFT_RECIPE, craftArgs, null, null)
		), null));
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
		assertTrue(
			conversationText(backend.conversation(1)).contains("TOOL CALL FORMAT REMINDER"),
			conversationText(backend.conversation(1))
		);
		backend.succeed(1, PlannerResponse.toolCalls(List.of(
			new PlannerToolCall("call_nav_retry", PlannerToolCatalog.NAVIGATE_TO, navigateArgs, null, null),
			new PlannerToolCall("call_craft_retry", PlannerToolCatalog.CRAFT_RECIPE, craftArgs, null, null)
		), null));
		awaitBackendCallCount(orchestrator, backend, 3, Duration.ofSeconds(1));
		assertTrue(
			conversationText(backend.conversation(2)).contains("TOOL CALL FORMAT REMINDER"),
			conversationText(backend.conversation(2))
		);
		backend.succeed(2, PlannerResponse.toolCalls(List.of(
			new PlannerToolCall("call_nav_second_retry", PlannerToolCatalog.NAVIGATE_TO, navigateArgs, null, null),
			new PlannerToolCall("call_craft_second_retry", PlannerToolCatalog.CRAFT_RECIPE, craftArgs, null, null)
		), null));

		PlannerExecutionResult result = awaitResult(orchestrator);
		assertFalse(result.succeeded());
		assertEquals(LlmFailureType.PARSE_ERROR, result.failureType());
		assertTrue(result.failureMessage().contains("only read-only tools can be batched"));
		assertEquals(3, result.attempt());
		assertTrue(invokedTools.isEmpty());
	}

	@Test
	void parseRepairIncludesCurrentSchemaForRejectedToolArguments() {
		RecordingBackend backend = new RecordingBackend();
		PlannerToolRegistry registry = PlannerToolRegistry.empty();
		registry.discoverTools("observation", 4);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			registry,
			PlannerActionToolExecutor.DISABLED
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent inspect here"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.fail(
			0,
			LlmFailureType.PARSE_ERROR,
			"Failed to parse Codex planner response: Invalid inspect_world tool arguments: Unsupported inspect_world mode: block"
		);
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));

		String repairPrompt = conversationText(backend.conversation(1));
		assertTrue(repairPrompt.contains("CURRENT SCHEMA FOR THE REJECTED TOOL"), repairPrompt);
		assertTrue(repairPrompt.contains("inspect_world"), repairPrompt);
		assertTrue(repairPrompt.contains("inspect_area"), repairPrompt);
		assertTrue(repairPrompt.contains("find_placement_sites"), repairPrompt);
		assertTrue(repairPrompt.contains("\"center\""), repairPrompt);

		backend.succeed(1, replyOnly("I checked the schema."));
		assertTrue(awaitResult(orchestrator).succeeded());
	}

	@Test
	void consecutiveToolFollowUpKeepsPriorToolExchangeInPrompt() {
		RecordingBackend backend = new RecordingBackend();
		StubInventoryTool inventoryTool = new StubInventoryTool(
			"unused",
			"Tool result for check_craftables: availableCrafts=Available 2x2 crafts: [From {1*stick,1*charcoal} to 4*torch]: torch"
		);
		RecordingPlannerToolProvider provider = new RecordingPlannerToolProvider();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			inventoryTool,
			PlannerVisionMode.EXTERNAL_SUMMARY,
			PlannerToolRegistry.of(provider)
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent make torches"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new PlannerToolRequest("check_craftables", null)
		));

		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
		JsonObject args = new JsonObject();
		args.addProperty("query", "torch");
		backend.succeed(1, new PlannerResponse(
			"",
			new PlannerToolCall("call_search", "search_recipes", args, null, null),
			null
		));

		awaitBackendCallCount(orchestrator, backend, 3, Duration.ofSeconds(1));
		LlmConversation secondFollowUp = backend.conversation(2);
		assertTrue(secondFollowUp.messages().stream()
			.anyMatch(message -> "assistant".equals(message.role())
				&& message.hasToolCalls()
				&& message.toolCalls().stream().anyMatch(toolCall -> "check_craftables".equals(toolCall.name()))));
		assertTrue(secondFollowUp.messages().stream()
			.anyMatch(message -> message.kind() == LlmMessageKind.TOOL_RESULT && message.content().contains("Available 2x2 crafts")));
		assertTrue(secondFollowUp.messages().stream()
			.anyMatch(message -> "assistant".equals(message.role())
				&& message.hasToolCalls()
				&& message.toolCalls().stream().anyMatch(toolCall -> "search_recipes".equals(toolCall.name()))));
		assertTrue(secondFollowUp.messages().stream()
			.anyMatch(message -> message.kind() == LlmMessageKind.TOOL_RESULT && message.content().contains("query=torch")));
		assertEquals(List.of("torch"), provider.queries());
	}

	@Test
	void multipleReadToolCallsExecuteInOrderAndReplayIntoFollowUp() {
		RecordingBackend backend = new RecordingBackend();
		StubInventoryTool inventoryTool = new StubInventoryTool(
			"Tool result for inspect_inventory: itemCounts={minecraft:oak_log=2}",
			"Tool result for check_craftables: availableCrafts=Available 2x2 crafts: oak_planks"
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			inventoryTool,
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		JsonObject inventoryArgs = new JsonObject();
		inventoryArgs.addProperty("prompt", "Count items.");
		JsonObject craftablesArgs = new JsonObject();
		craftablesArgs.addProperty("prompt", "List crafts.");
		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent what can I make?"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse("", List.of(
			new PlannerToolCall("call_inventory", "inspect_inventory", inventoryArgs, null, null),
			new PlannerToolCall("call_craftables", "check_craftables", craftablesArgs, null, null)
		), null));

		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));

		assertEquals(1, inventoryTool.inventoryRequestCount());
		assertEquals(1, inventoryTool.craftablesRequestCount());
		LlmConversation followUp = backend.conversation(1);
		LlmChatMessage replayedToolCall = followUp.messages().stream()
			.filter(message -> "assistant".equals(message.role()) && message.hasToolCalls())
			.filter(message -> message.toolCalls().size() == 2)
			.findFirst()
			.orElseThrow();
		assertEquals(List.of("inspect_inventory", "check_craftables"), replayedToolCall.toolCalls().stream()
			.map(PlannerToolCall::name)
			.toList());
		List<LlmChatMessage> toolResults = followUp.messages().stream()
			.filter(message -> "tool".equals(message.role()))
			.toList();
		assertEquals(2, toolResults.size());
		assertEquals("call_inventory", toolResults.get(0).toolCallId());
		assertTrue(toolResults.get(0).content().contains("itemCounts"));
		assertEquals("call_craftables", toolResults.get(1).toolCallId());
		assertTrue(toolResults.get(1).content().contains("oak_planks"));
	}

	@Test
	void multipleToolCallBatchAllowsRegisteredVisualReadBeforeExecution() {
		RecordingBackend backend = new RecordingBackend();
		StubInventoryTool inventoryTool = new StubInventoryTool(
			"Tool result for inspect_inventory: itemCounts={minecraft:oak_log=2}",
			"unused"
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			inventoryTool,
			PlannerVisionMode.EXTERNAL_SUMMARY
		);

		JsonObject inventoryArgs = new JsonObject();
		inventoryArgs.addProperty("prompt", "Count items.");
		JsonObject lookArgs = new JsonObject();
		lookArgs.addProperty("prompt", "Look around.");
		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent inspect everything"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse("", List.of(
			new PlannerToolCall("call_inventory", "inspect_inventory", inventoryArgs, null, null),
			new PlannerToolCall("call_look", "take_a_look", lookArgs, null, null)
		), null));
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
		assertEquals(1, inventoryTool.inventoryRequestCount());
		assertTrue(conversationText(backend.conversation(1)).contains("VISION_UNAVAILABLE"));
		backend.succeed(1, replyOnly("I inspected the available state."));

		PlannerExecutionResult result = awaitResult(orchestrator);

		assertTrue(result.succeeded());
	}

	@Test
	void undiscoveredSpecialistToolIsRejectedBeforeTheActionExecutor() {
		RecordingBackend backend = new RecordingBackend();
		PlannerToolRegistry registry = PlannerToolRegistry.empty();
		ArrayList<String> invokedTools = new ArrayList<>();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			registry,
			toolCall -> {
				invokedTools.add(toolCall.name());
				return CompletableFuture.completedFuture("Tool result for " + toolCall.name() + ": ok");
			}
		);
		JsonObject arguments = new JsonObject();
		arguments.addProperty("x", 4);
		arguments.addProperty("y", 64);
		arguments.addProperty("z", 8);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent go there"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse("", new PlannerToolCall(
			"call_hidden",
			PlannerToolCatalog.NAVIGATE_TO,
			arguments,
			null,
			null
		), null));
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
		backend.succeed(1, new PlannerResponse("", new PlannerToolCall(
			"call_hidden_retry",
			PlannerToolCatalog.NAVIGATE_TO,
			arguments,
			null,
			null
		), null));
		awaitBackendCallCount(orchestrator, backend, 3, Duration.ofSeconds(1));
		backend.succeed(2, new PlannerResponse("", new PlannerToolCall(
			"call_hidden_final",
			PlannerToolCatalog.NAVIGATE_TO,
			arguments,
			null,
			null
		), null));

		PlannerExecutionResult result = awaitResult(orchestrator);

		assertFalse(result.succeeded());
		assertTrue(result.failureMessage().contains("tool_not_discovered: navigate_to"));
		assertTrue(invokedTools.isEmpty());
		assertTrue(backend.acceptedGenerations().isEmpty());
	}

	@Test
	void discoveryActivatesSpecialistSchemaForTheNextPlannerRequestAndExecution() {
		RecordingBackend backend = new RecordingBackend();
		PlannerToolRegistry registry = PlannerToolRegistry.empty();
		ArrayList<String> invokedTools = new ArrayList<>();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			registry,
			toolCall -> {
				invokedTools.add(toolCall.name());
				return CompletableFuture.completedFuture("Tool result for " + toolCall.name() + ": ok");
			}
		);
		JsonObject discoverArguments = new JsonObject();
		discoverArguments.addProperty("query", "navigation");

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent go there"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		assertFalse(conversationText(backend.conversation(0)).contains("navigate_to"));
		backend.succeed(0, new PlannerResponse("", new PlannerToolCall(
			"call_discover",
			PlannerToolCatalog.DISCOVER_TOOLS,
			discoverArguments,
			null,
			null
		), null));
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
		assertEquals(List.of(1L), backend.acceptedGenerations());
		assertEquals(0, orchestrator.debugSnapshot().context().queuedTriggerCount());

		LlmConversation followUp = backend.conversation(1);
		assertTrue(conversationText(followUp).contains("navigate_to"));
		assertTrue(conversationText(followUp).contains("Tool result for discover_tools"));
		assertTrue(registry.isActiveTool(PlannerToolCatalog.NAVIGATE_TO));

		JsonObject navigateArguments = new JsonObject();
		navigateArguments.addProperty("x", 4);
		navigateArguments.addProperty("y", 64);
		navigateArguments.addProperty("z", 8);
		backend.succeed(1, new PlannerResponse("", new PlannerToolCall(
			"call_navigate",
			PlannerToolCatalog.NAVIGATE_TO,
			navigateArguments,
			null,
			null
		), null));
		awaitInvokedToolCount(orchestrator, invokedTools, 1, Duration.ofSeconds(1));
		awaitBackendCallCount(orchestrator, backend, 3, Duration.ofSeconds(1));
		backend.succeed(2, replyOnly("On my way."));

		PlannerExecutionResult result = awaitResult(orchestrator);

		assertTrue(result.succeeded());
		assertEquals(List.of(PlannerToolCatalog.NAVIGATE_TO), invokedTools);
	}

	@Test
	void discoveryCannotBatchWithAReadTool() {
		RecordingBackend backend = new RecordingBackend();
		PlannerToolRegistry registry = PlannerToolRegistry.empty();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			registry
		);

		JsonObject discoverArguments = new JsonObject();
		discoverArguments.addProperty("query", "navigation");
		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent inspect and discover"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, PlannerResponse.toolCalls(List.of(
			new PlannerToolCall("call_discover", PlannerToolCatalog.DISCOVER_TOOLS, discoverArguments, null, null),
			new PlannerToolCall("call_inventory", PlannerToolCatalog.INSPECT_INVENTORY, new JsonObject(), null, null)
		), null));

		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
		assertTrue(conversationText(backend.conversation(1)).contains("only read-only tools can be batched"));

		backend.succeed(1, replyOnly("I will use one tool at a time."));
		assertTrue(awaitResult(orchestrator).succeeded());
	}

	@Test
	void activeSafetyHoldTemporarilyExposesTheResumeControlOnTheCoreSurface() {
		PlannerToolRegistry registry = PlannerToolRegistry.empty();
		PlannerOrchestrator orchestrator = newOrchestrator(
			new RecordingBackend(),
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			registry,
			PlannerActionToolExecutor.DISABLED
		);

		orchestrator.updateSafetyContext(1L, "hold-1", true);
		assertTrue(registry.isActiveTool(PlannerToolCatalog.RESUME_TASK));

		orchestrator.updateSafetyContext(2L, null, false);
		assertFalse(registry.isActiveTool(PlannerToolCatalog.RESUME_TASK));
	}

	@Test
	void plannerRequestAndToolFollowUpUseTurnContextAsSpanParent() {
		RecordingBackend backend = new RecordingBackend();
		RecordingObservability observability = new RecordingObservability();
		StubInventoryTool inventoryTool = new StubInventoryTool(
			"Tool result for inspect_inventory: itemCounts={minecraft:charcoal=3}",
			"unused"
		);
		PlannerOrchestrator orchestrator = newObservedOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			inventoryTool,
			PlannerVisionMode.EXTERNAL_SUMMARY,
			observability
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent check inventory"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new PlannerToolRequest("inspect_inventory", null)
		));

		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
		assertEquals(
			List.of(AgentObservability.PLANNER_REQUEST_SPAN_NAME, AgentObservability.FOLLOW_UP_SPAN_NAME),
			observability.childNames()
		);
		assertEquals(
			java.util.Arrays.asList(
				"session:OUT_OF_WORLD:sender=Alice:tick=10",
				"session:OUT_OF_WORLD:sender=Alice:tick=10"
			),
			observability.childParentThreadIds()
		);

		backend.succeed(1, replyOnly("Inventory checked."));
		assertTrue(awaitResult(orchestrator).succeeded());
	}

	@Test
	void toolFollowUpConversationReplaysRawAssistantContentBeforeToolResult() {
		RecordingBackend backend = new RecordingBackend();
		StubVisionTool visionTool = new StubVisionTool(
			true,
			CompletableFuture.completedFuture(capturedScreenshot()),
			CompletableFuture.failedFuture(new AssertionError("External summary should not be requested"))
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			visionTool,
			PlannerVisionMode.NATIVE_TOOL_IMAGE
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent what do you see?"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new PlannerToolRequest("take_a_look", null),
			null,
			JsonParser.parseString("""
				[
				  {
				    "type": "reasoning",
				    "text": "Need to inspect the screenshot first.",
				    "thought": true,
				    "thought_signature": "sig-123"
				  },
				  {
				    "type": "text",
				    "text": "{\\"replyText\\":\\"\\",\\"intent\\":{\\"type\\":\\"none\\"},\\"toolRequest\\":{\\"type\\":\\"take_a_look\\",\\"prompt\\":null}}"
				  }
				]
				""")
		));

			awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
			LlmConversation followUpConversation = backend.conversation(1);
			LlmChatMessage replayedToolCall = followUpConversation.messages().get(followUpConversation.messages().size() - 3);
			LlmChatMessage replayedToolResult = followUpConversation.messages().get(followUpConversation.messages().size() - 2);
			LlmChatMessage replayedImageResult = followUpConversation.messages().get(followUpConversation.messages().size() - 1);
			assertEquals("assistant", replayedToolCall.role());
			assertTrue(replayedToolCall.hasToolCalls());
			assertEquals("take_a_look", replayedToolCall.toolCalls().get(0).name());
			assertEquals("tool", replayedToolResult.role());
			assertTrue(replayedToolResult.content().contains("current first-person view attached"));
			assertEquals("user", replayedImageResult.role());
			assertTrue(replayedImageResult.hasImageAttachment());
	}

	@Test
	void failureAppendsFailureCardToVisibleConversation() {
		RecordingBackend backend = new RecordingBackend();
		PlannerOrchestrator orchestrator = newOrchestrator(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "A"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.fail(0, LlmFailureType.PROVIDER_ERROR, "Injected provider error");

		PlannerExecutionResult result = awaitResult(orchestrator);
		assertEquals(LlmFailureType.PROVIDER_ERROR, result.failureType());

		PlannerConversationDebugMessage failureCard = lastConversationMessage(orchestrator.projectedConversationDebugSnapshot());
		assertEquals(PlannerConversationDebugKind.FAILURE, failureCard.kind());
		assertTrue(failureCard.text().contains("PROVIDER_ERROR"));
		assertTrue(failureCard.text().contains("Injected provider error"));
	}

	@Test
	void resetAndShutdownClearVisibleConversationSnapshot() {
		RecordingBackend resetBackend = new RecordingBackend();
		PlannerOrchestrator resetOrchestrator = newOrchestrator(
			resetBackend,
			CurrentViewVisionTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY
		);
		resetOrchestrator.submit(requestAt(10L, 1_000L, "Alice", "A"));
		resetBackend.awaitCalls(1, Duration.ofSeconds(1));
		resetBackend.succeed(0, replyOnly("reply A"));
		awaitResult(resetOrchestrator);
		assertFalse(resetOrchestrator.conversationDebugSnapshot().isEmpty());
		assertFalse(resetOrchestrator.projectedConversationDebugSnapshot().isEmpty());
		resetOrchestrator.reset();
		assertTrue(resetOrchestrator.conversationDebugSnapshot().isEmpty());
		assertTrue(resetOrchestrator.projectedConversationDebugSnapshot().isEmpty());

		RecordingBackend shutdownBackend = new RecordingBackend();
		PlannerOrchestrator shutdownOrchestrator = newOrchestrator(
			shutdownBackend,
			CurrentViewVisionTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY
		);
		shutdownOrchestrator.submit(requestAt(11L, 1_100L, "Alice", "B"));
		shutdownBackend.awaitCalls(1, Duration.ofSeconds(1));
		shutdownBackend.succeed(0, replyOnly("reply B"));
		awaitResult(shutdownOrchestrator);
		assertFalse(shutdownOrchestrator.conversationDebugSnapshot().isEmpty());
		assertFalse(shutdownOrchestrator.projectedConversationDebugSnapshot().isEmpty());
		shutdownOrchestrator.shutdown();
		assertTrue(shutdownOrchestrator.conversationDebugSnapshot().isEmpty());
		assertTrue(shutdownOrchestrator.projectedConversationDebugSnapshot().isEmpty());
	}

	@Test
	void supersededToolExecutionDoesNotFeedOldFollowUpBackIntoPlanner() {
		RecordingBackend backend = new RecordingBackend();
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		StubVisionTool visionTool = new StubVisionTool(
			true,
			new CompletableFuture<>(),
			CompletableFuture.failedFuture(new AssertionError("External summary should not be requested"))
		);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			visionTool,
			PlannerVisionMode.NATIVE_TOOL_IMAGE,
			3,
			clock
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent what do you see?"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new PlannerToolRequest("take_a_look", null)
		));
		backend.awaitCompletions(1, Duration.ofSeconds(1));

		awaitVisionCaptureRequestCount(orchestrator, visionTool, 1, Duration.ofSeconds(1));

		orchestrator.submit(requestAt(11L, 1_100L, "Alice", "B"));
		assertEquals(1, backend.callCount());
		PlannerOrchestratorDebugSnapshot snapshot = orchestrator.debugSnapshot();
		assertTrue(snapshot.coalescePending());
		assertEquals(0L, snapshot.coalesceWindowMs());

		visionTool.captureFuture().complete(capturedScreenshot());
		assertNull(awaitNullPoll(orchestrator));
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));

		backend.succeed(1, replyOnly("reply B"));
		PlannerExecutionResult result = awaitResult(orchestrator);
		assertEquals("reply B", result.response().replyText());
		assertEquals(2L, result.generation());
	}

	@Test
	void pendingActionToolIsJournaledBeforeQueuedTriggersStart() {
		RecordingBackend backend = new RecordingBackend();
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		CompletableFuture<String> actionResult = new CompletableFuture<>();
		ArrayList<String> invokedTools = new ArrayList<>();
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			3,
			10,
			10,
			100,
			128,
			clock,
			toolCall -> {
				invokedTools.add(toolCall.name());
				return actionResult;
			},
			PlannerToolNarrationSink.NO_OP
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent craft sticks"));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		JsonObject craftArgs = new JsonObject();
		craftArgs.addProperty("recipeId", "spruce_planks_x2_to_stick");
		craftArgs.addProperty("times", 1);
		backend.succeed(0, new PlannerResponse(
			"",
			new PlannerToolCall("call_craft", PlannerToolCatalog.CRAFT_RECIPE, craftArgs, "Crafting sticks.", null),
			null
		));
		awaitInvokedToolCount(orchestrator, invokedTools, 1, Duration.ofSeconds(1));

		orchestrator.submit(requestAt(11L, 1_100L, "self", "Recent context updates require one combined response."));
		assertEquals(1, backend.callCount());
		assertEquals(0L, orchestrator.debugSnapshot().supersededCount());

		actionResult.complete("Tool result for craft_recipe: completed recipeId=spruce_planks_x2_to_stick times=1 state=SUCCEEDED");
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));
		LlmConversation toolFollowUp = backend.conversation(1);
		assertTrue(toolFollowUp.messages().stream()
			.anyMatch(message -> "assistant".equals(message.role())
				&& message.hasToolCalls()
				&& message.toolCalls().stream().anyMatch(toolCall -> PlannerToolCatalog.CRAFT_RECIPE.equals(toolCall.name()))));
		assertTrue(toolFollowUp.messages().stream()
			.anyMatch(message -> "tool".equals(message.role())
				&& "call_craft".equals(message.toolCallId())
				&& message.content().contains("spruce_planks_x2_to_stick")));

		backend.succeed(1, replyOnly("Crafted sticks."));
		PlannerExecutionResult actionResultReply = awaitResult(orchestrator);
		assertTrue(actionResultReply.succeeded());
		assertEquals(1L, actionResultReply.generation());

		orchestrator.recordAssistantTurn(new DialogueTurn("agent", actionResultReply.response().replyText(), 12L, 1_200L));
		orchestrator.onAcceptedReplyRecorded();
		awaitBackendCallCount(orchestrator, backend, 3, Duration.ofSeconds(1));

		LlmConversation queuedPrompt = backend.conversation(2);
		assertTrue(queuedPrompt.messages().stream()
			.anyMatch(message -> "assistant".equals(message.role())
				&& message.hasToolCalls()
				&& message.toolCalls().stream().anyMatch(toolCall -> PlannerToolCatalog.CRAFT_RECIPE.equals(toolCall.name()))));
		assertTrue(queuedPrompt.messages().stream()
			.anyMatch(message -> "tool".equals(message.role())
				&& "call_craft".equals(message.toolCallId())
				&& message.content().contains("spruce_planks_x2_to_stick")));
		assertPromptContains(queuedPrompt, "Recent context updates require one combined response.");
	}

	private static void assertActionToolRoute(String toolName, JsonObject arguments) {
		RecordingBackend backend = new RecordingBackend();
		ArrayList<String> invokedTools = new ArrayList<>();
		ArrayList<String> narrations = new ArrayList<>();
		String narration = "Narrating " + toolName;
		arguments.addProperty("narration", narration);
		PlannerOrchestrator orchestrator = newOrchestrator(
			backend,
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			PlannerVisionMode.EXTERNAL_SUMMARY,
			3,
			10,
			10,
			100,
			128,
			Clock.systemUTC(),
			toolCall -> {
				invokedTools.add(toolCall.name());
				return CompletableFuture.completedFuture("Tool result for " + toolCall.name() + ": ok");
			},
			toolCall -> narrations.add(toolCall.narration())
		);

		orchestrator.submit(requestAt(10L, 1_000L, "Alice", "@agent " + toolName));
		backend.awaitCalls(1, Duration.ofSeconds(1));
		backend.succeed(0, new PlannerResponse("", new PlannerToolCall("call_" + toolName, toolName, arguments, narration, null), null));
		awaitBackendCallCount(orchestrator, backend, 2, Duration.ofSeconds(1));

		assertEquals(List.of(toolName), invokedTools);
		assertEquals(List.of(narration), narrations);
		LlmConversation followUp = backend.conversation(1);
		LlmChatMessage replayedToolCall = followUp.messages().get(followUp.messages().size() - 2);
		LlmChatMessage replayedToolResult = followUp.messages().get(followUp.messages().size() - 1);
		assertEquals("assistant", replayedToolCall.role());
		assertEquals(toolName, replayedToolCall.toolCalls().get(0).name());
		assertEquals("tool", replayedToolResult.role());
		assertTrue(replayedToolResult.content().contains("Tool result for " + toolName + ": ok"));

		backend.succeed(1, replyOnly("Done."));
		PlannerExecutionResult result = awaitResult(orchestrator);
		assertTrue(result.succeeded());
		assertEquals("Done.", result.response().replyText());
	}

	private static PlannerRequest baseRequest(String toolResult) {
		return new PlannerRequest(
			10L,
			1_000L,
			SessionMode.OUT_OF_WORLD,
			"Alice",
			null,
			null,
			null,
			"Alice",
			"@agent what do you see?",
			toolResult
		);
	}

	private static PlannerRequest requestAt(long tick, long timestampMs, String sender, String message) {
		return new PlannerRequest(
			tick,
			timestampMs,
			SessionMode.OUT_OF_WORLD,
			"Alice",
			null,
			sender,
			message,
			null
		);
	}

	private static PlannerRequest autonomousRequestAt(
		long tick,
		long timestampMs,
		String message,
		String coalescingKey
	) {
		return new PlannerRequest(
			tick,
			timestampMs,
			SessionMode.OUT_OF_WORLD,
			"Alice",
			null,
			null,
			null,
			PlannerTriggerBatch.of(List.of(
				PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "survival_runtime", message, tick, timestampMs, coalescingKey)
			)),
			null
		);
	}

	private static PlannerRequest inWorldRequestAt(long tick, long timestampMs, String sender, String message) {
		return new PlannerRequest(
			tick,
			timestampMs,
			SessionMode.SINGLEPLAYER_LOCAL,
			"Alice",
			null,
			sender,
			message,
			null
		);
	}

	private static PlannerResponse replyOnly(String text) {
		return new PlannerResponse(text, new PlannerIntent("reply_only", null, null));
	}

	private static PlannerOrchestrator newCompactionOrchestrator(AgentConfig.LlmConfig config) {
		Clock clock = Clock.systemDefaultZone();
		return new PlannerOrchestrator(
			new PlannerExecutor(new OpenAiCompatibleLlmBackend(config)),
			new PlannerCompactionService(new OpenAiCompatibleChatClient(config)),
			new PlannerContextAggregator(clock, config.plannerCompactionTriggerTokens(), config.plannerVisionMode()),
			CurrentViewVisionTool.disabled(),
			CurrentInventoryTool.disabled(),
			config.plannerVisionMode(),
			config.visionImageDetail(),
			config.plannerSessionMaxConcurrentAttempts(),
			config.plannerSessionCoalesceStepMillis(),
			config.plannerSessionCoalesceMinMillis(),
			config.plannerSessionCoalesceMaxMillis(),
			clock,
			NoopObservability.INSTANCE,
			PlannerLifecycleListener.NO_OP,
			new AgentDebugRecorder(),
			PlannerActionToolExecutor.DISABLED,
			PlannerToolNarrationSink.NO_OP,
			PlannerToolRegistry.empty(),
			PlannerToolExecutionObserver.NO_OP
		);
	}

	private static PlannerOrchestrator newOrchestrator(LlmBackend backend, CurrentViewVisionTool visionTool, PlannerVisionMode visionMode) {
		return newOrchestrator(backend, visionTool, CurrentInventoryTool.disabled(), visionMode);
	}

	private static PlannerOrchestrator newOrchestrator(
		LlmBackend backend,
		CurrentViewVisionTool visionTool,
		CurrentInventoryTool inventoryTool,
		PlannerVisionMode visionMode
	) {
		return newOrchestrator(backend, visionTool, inventoryTool, visionMode, 3, Clock.systemDefaultZone());
	}

	private static PlannerOrchestrator newOrchestrator(
		LlmBackend backend,
		CurrentViewVisionTool visionTool,
		CurrentInventoryTool inventoryTool,
		PlannerVisionMode visionMode,
		PlannerLifecycleListener lifecycleListener
	) {
		AgentConfig.LlmConfig config = AgentConfig.LlmConfig.defaults();
		Clock clock = Clock.systemDefaultZone();
		PlannerToolRegistry toolRegistry = PlannerToolRegistry.empty();
		toolRegistry.activateAllForTesting();
		return new PlannerOrchestrator(
			new PlannerExecutor(backend),
			new PlannerCompactionService(new OpenAiCompatibleChatClient(config, toolRegistry)),
			new PlannerContextAggregator(clock, config.plannerCompactionTriggerTokens(), config.plannerPendingSemanticEventCap(), visionMode, toolRegistry),
			visionTool,
			inventoryTool,
			visionMode,
			config.visionImageDetail(),
			config.plannerSessionMaxConcurrentAttempts(),
			config.plannerSessionCoalesceStepMillis(),
			config.plannerSessionCoalesceMinMillis(),
			config.plannerSessionCoalesceMaxMillis(),
			clock,
			NoopObservability.INSTANCE,
			lifecycleListener,
			new AgentDebugRecorder(),
			PlannerActionToolExecutor.DISABLED,
			PlannerToolNarrationSink.NO_OP,
			toolRegistry,
			PlannerToolExecutionObserver.NO_OP
		);
	}

	private static PlannerOrchestrator newOrchestrator(
		LlmBackend backend,
		CurrentViewVisionTool visionTool,
		CurrentInventoryTool inventoryTool,
		PlannerVisionMode visionMode,
		AgentDebugRecorder debugRecorder
	) {
		AgentConfig.LlmConfig config = AgentConfig.LlmConfig.defaults();
		Clock clock = Clock.systemDefaultZone();
		PlannerToolRegistry toolRegistry = PlannerToolRegistry.empty();
		toolRegistry.activateAllForTesting();
		return new PlannerOrchestrator(
			new PlannerExecutor(backend),
			new PlannerCompactionService(new OpenAiCompatibleChatClient(config, toolRegistry)),
			new PlannerContextAggregator(clock, config.plannerCompactionTriggerTokens(), config.plannerPendingSemanticEventCap(), visionMode, toolRegistry),
			visionTool,
			inventoryTool,
			visionMode,
			config.visionImageDetail(),
			config.plannerSessionMaxConcurrentAttempts(),
			config.plannerSessionCoalesceStepMillis(),
			config.plannerSessionCoalesceMinMillis(),
			config.plannerSessionCoalesceMaxMillis(),
			clock,
			NoopObservability.INSTANCE,
			PlannerLifecycleListener.NO_OP,
			debugRecorder,
			PlannerActionToolExecutor.DISABLED,
			PlannerToolNarrationSink.NO_OP,
			toolRegistry,
			PlannerToolExecutionObserver.NO_OP
		);
	}

	private static PlannerOrchestrator newOrchestrator(
		LlmBackend backend,
		CurrentViewVisionTool visionTool,
		CurrentInventoryTool inventoryTool,
		PlannerVisionMode visionMode,
		PlannerToolRegistry toolRegistry
	) {
		toolRegistry.activateAllForTesting();
		return newOrchestrator(
			backend,
			visionTool,
			inventoryTool,
			visionMode,
			toolRegistry,
			PlannerActionToolExecutor.DISABLED
		);
	}

	private static PlannerOrchestrator newOrchestrator(
		LlmBackend backend,
		CurrentViewVisionTool visionTool,
		CurrentInventoryTool inventoryTool,
		PlannerVisionMode visionMode,
		PlannerToolRegistry toolRegistry,
		PlannerActionToolExecutor actionToolExecutor
	) {
		AgentConfig.LlmConfig config = AgentConfig.LlmConfig.defaults();
		Clock clock = Clock.systemDefaultZone();
		return new PlannerOrchestrator(
			new PlannerExecutor(backend),
			new PlannerCompactionService(new OpenAiCompatibleChatClient(config, toolRegistry)),
			new PlannerContextAggregator(clock, config.plannerCompactionTriggerTokens(), config.plannerPendingSemanticEventCap(), visionMode, toolRegistry),
			visionTool,
			inventoryTool,
			visionMode,
			config.visionImageDetail(),
			config.plannerSessionMaxConcurrentAttempts(),
			config.plannerSessionCoalesceStepMillis(),
			config.plannerSessionCoalesceMinMillis(),
			config.plannerSessionCoalesceMaxMillis(),
			clock,
			NoopObservability.INSTANCE,
			PlannerLifecycleListener.NO_OP,
			new AgentDebugRecorder(),
			actionToolExecutor,
			PlannerToolNarrationSink.NO_OP,
			toolRegistry,
			PlannerToolExecutionObserver.NO_OP
		);
	}

	private static PlannerOrchestrator newObservedOrchestrator(
		LlmBackend backend,
		CurrentViewVisionTool visionTool,
		CurrentInventoryTool inventoryTool,
		PlannerVisionMode visionMode,
		AgentObservability observability
	) {
		AgentConfig.LlmConfig config = AgentConfig.LlmConfig.defaults();
		Clock clock = Clock.systemDefaultZone();
		PlannerToolRegistry toolRegistry = PlannerToolRegistry.empty();
		toolRegistry.activateAllForTesting();
		return new PlannerOrchestrator(
			new PlannerExecutor(backend, observability),
			new PlannerCompactionService(new OpenAiCompatibleChatClient(config, observability, toolRegistry), observability),
			new PlannerContextAggregator(clock, config.plannerCompactionTriggerTokens(), config.plannerPendingSemanticEventCap(), visionMode, toolRegistry),
			visionTool,
			inventoryTool,
			visionMode,
			config.visionImageDetail(),
			config.plannerSessionMaxConcurrentAttempts(),
			config.plannerSessionCoalesceStepMillis(),
			config.plannerSessionCoalesceMinMillis(),
			config.plannerSessionCoalesceMaxMillis(),
			clock,
			observability,
			PlannerLifecycleListener.NO_OP,
			new AgentDebugRecorder(),
			PlannerActionToolExecutor.DISABLED,
			PlannerToolNarrationSink.NO_OP,
			toolRegistry,
			PlannerToolExecutionObserver.NO_OP
		);
	}

	private static PlannerOrchestrator newOrchestrator(
		LlmBackend backend,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode,
		int plannerSessionMaxConcurrentAttempts,
		Clock clock
	) {
		return newOrchestrator(
			backend,
			visionTool,
			CurrentInventoryTool.disabled(),
			visionMode,
			plannerSessionMaxConcurrentAttempts,
			clock
		);
	}

	private static PlannerOrchestrator newOrchestrator(
		LlmBackend backend,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode,
		int plannerSessionMaxConcurrentAttempts,
		int plannerSessionCoalesceStepMillis,
		int plannerSessionCoalesceMinMillis,
		int plannerSessionCoalesceMaxMillis,
		int plannerPendingSemanticEventCap,
		Clock clock
	) {
		return newOrchestrator(
			backend,
			visionTool,
			CurrentInventoryTool.disabled(),
			visionMode,
			plannerSessionMaxConcurrentAttempts,
			plannerSessionCoalesceStepMillis,
			plannerSessionCoalesceMinMillis,
			plannerSessionCoalesceMaxMillis,
			plannerPendingSemanticEventCap,
			clock
		);
	}

	private static PlannerOrchestrator newOrchestrator(
		LlmBackend backend,
		CurrentViewVisionTool visionTool,
		CurrentInventoryTool inventoryTool,
		PlannerVisionMode visionMode,
		int plannerSessionMaxConcurrentAttempts,
		Clock clock
	) {
		AgentConfig.LlmConfig config = AgentConfig.LlmConfig.defaults();
		return newOrchestrator(
			backend,
			visionTool,
			inventoryTool,
			visionMode,
			plannerSessionMaxConcurrentAttempts,
			config.plannerSessionCoalesceStepMillis(),
			config.plannerSessionCoalesceMinMillis(),
			config.plannerSessionCoalesceMaxMillis(),
			config.plannerPendingSemanticEventCap(),
			clock
		);
	}

	private static PlannerOrchestrator newOrchestrator(
		LlmBackend backend,
		CurrentViewVisionTool visionTool,
		CurrentInventoryTool inventoryTool,
		PlannerVisionMode visionMode,
		int plannerSessionMaxConcurrentAttempts,
		int plannerSessionCoalesceStepMillis,
		int plannerSessionCoalesceMinMillis,
		int plannerSessionCoalesceMaxMillis,
		int plannerPendingSemanticEventCap,
		Clock clock
	) {
		return newOrchestrator(
			backend,
			visionTool,
			inventoryTool,
			visionMode,
			plannerSessionMaxConcurrentAttempts,
			plannerSessionCoalesceStepMillis,
			plannerSessionCoalesceMinMillis,
			plannerSessionCoalesceMaxMillis,
			plannerPendingSemanticEventCap,
			clock,
			PlannerActionToolExecutor.DISABLED,
			PlannerToolNarrationSink.NO_OP
		);
	}

	private static PlannerOrchestrator newOrchestrator(
		LlmBackend backend,
		CurrentViewVisionTool visionTool,
		CurrentInventoryTool inventoryTool,
		PlannerVisionMode visionMode,
		int plannerSessionMaxConcurrentAttempts,
		int plannerSessionCoalesceStepMillis,
		int plannerSessionCoalesceMinMillis,
		int plannerSessionCoalesceMaxMillis,
		int plannerPendingSemanticEventCap,
		Clock clock,
		PlannerActionToolExecutor actionToolExecutor,
		PlannerToolNarrationSink narrationSink
	) {
		AgentConfig.LlmConfig config = AgentConfig.LlmConfig.defaults();
		PlannerToolRegistry toolRegistry = PlannerToolRegistry.empty();
		toolRegistry.activateAllForTesting();
		return new PlannerOrchestrator(
			new PlannerExecutor(backend),
			new PlannerCompactionService(new OpenAiCompatibleChatClient(config, toolRegistry)),
			new PlannerContextAggregator(clock, config.plannerCompactionTriggerTokens(), plannerPendingSemanticEventCap, visionMode, toolRegistry),
			visionTool,
			inventoryTool,
			visionMode,
			config.visionImageDetail(),
			plannerSessionMaxConcurrentAttempts,
			plannerSessionCoalesceStepMillis,
			plannerSessionCoalesceMinMillis,
			plannerSessionCoalesceMaxMillis,
			clock,
			NoopObservability.INSTANCE,
			PlannerLifecycleListener.NO_OP,
			new AgentDebugRecorder(),
			actionToolExecutor,
			narrationSink,
			toolRegistry,
			PlannerToolExecutionObserver.NO_OP
		);
	}

	private static PlannerExecutionResult awaitResult(PlannerOrchestrator orchestrator) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
		while (Instant.now().isBefore(deadline)) {
			PlannerExecutionResult result = orchestrator.poll();
			if (result != null) {
				return result;
			}
			try {
				Thread.sleep(10L);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting", exception);
			}
		}
		throw new AssertionError("Timed out waiting for planner result");
	}

	private static PlannerRequest request(String message, long tick) {
		return new PlannerRequest(tick, tick * 1_000L, SessionMode.OUT_OF_WORLD, null, null, "Alice", message, null);
	}

	private static PlannerResponse noActionResponse() {
		return new PlannerResponse("", new PlannerIntent("none", null, null));
	}

	private static List<StalePlannerRejection> awaitStaleRejections(PlannerOrchestrator orchestrator) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
		while (Instant.now().isBefore(deadline)) {
			orchestrator.poll();
			List<StalePlannerRejection> rejections = orchestrator.drainStalePlannerRejections();
			if (!rejections.isEmpty()) {
				return rejections;
			}
			try {
				Thread.sleep(10L);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting for stale planner rejection", exception);
			}
		}
		throw new AssertionError("Timed out waiting for stale planner rejection");
	}

	private static void awaitVisionCaptureRequestCount(
		PlannerOrchestrator orchestrator,
		StubVisionTool visionTool,
		int expectedCount,
		Duration timeout
	) {
		Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			orchestrator.poll();
			if (visionTool.captureRequestCount() >= expectedCount) {
				return;
			}
			try {
				Thread.sleep(10L);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting for vision capture request count", exception);
			}
		}
		throw new AssertionError(
			"Timed out waiting for vision capture request count "
				+ expectedCount
				+ ", actual="
				+ visionTool.captureRequestCount()
				+ ", snapshot="
				+ orchestrator.debugSnapshot()
		);
	}

	private static CompactionExecutionResult awaitCompaction(PlannerOrchestrator orchestrator) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(1));
		while (Instant.now().isBefore(deadline)) {
			CompactionExecutionResult result = orchestrator.pollDebugCompaction();
			if (result != null) {
				return result;
			}
			try {
				Thread.sleep(10L);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting", exception);
			}
		}
		throw new AssertionError("Timed out waiting for compaction result");
	}

	private static PlannerExecutionResult awaitNullPoll(PlannerOrchestrator orchestrator) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(1));
		while (Instant.now().isBefore(deadline)) {
			PlannerExecutionResult result = orchestrator.poll();
			if (result == null) {
				return null;
			}
		}
		throw new AssertionError("Expected orchestrator.poll() to return null");
	}

	private static void awaitBackendCallCount(
		PlannerOrchestrator orchestrator,
		RecordingBackend backend,
		int expectedCount,
		Duration timeout
	) {
		Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			orchestrator.poll();
			if (backend.callCount() >= expectedCount) {
				return;
			}
			try {
				Thread.sleep(10L);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting for backend call count", exception);
			}
		}
		throw new AssertionError(
			"Timed out waiting for backend call count "
				+ expectedCount
				+ ", actual="
				+ backend.callCount()
				+ ", snapshot="
				+ orchestrator.debugSnapshot()
		);
	}

	private static void awaitInvokedToolCount(
		PlannerOrchestrator orchestrator,
		List<String> invokedTools,
		int expectedCount,
		Duration timeout
	) {
		Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			orchestrator.poll();
			if (invokedTools.size() >= expectedCount) {
				return;
			}
			try {
				Thread.sleep(10L);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting for invoked tool count", exception);
			}
		}
		throw new AssertionError("Timed out waiting for invoked tool count " + expectedCount + ", actual=" + invokedTools.size());
	}

	private static void awaitRetryPending(PlannerOrchestrator orchestrator, Duration timeout) {
		Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			orchestrator.poll();
			PlannerOrchestratorDebugSnapshot snapshot = orchestrator.debugSnapshot();
			if (snapshot.retryPending()) {
				return;
			}
			try {
				Thread.sleep(10L);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting for retry scheduling", exception);
			}
		}
		throw new AssertionError("Timed out waiting for retry scheduling. snapshot=" + orchestrator.debugSnapshot());
	}

	private static void awaitDebugCompaction(PlannerOrchestrator orchestrator) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
		while (Instant.now().isBefore(deadline)) {
			orchestrator.poll();
			PlannerOrchestratorDebugSnapshot snapshot = orchestrator.debugSnapshot();
			if (!snapshot.compactionInFlight() && snapshot.lastCompactionResult() != null) {
				return;
			}
			try {
				Thread.sleep(10L);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting", exception);
			}
		}
		throw new AssertionError("Timed out waiting for debug compaction");
	}

	private static FirstPersonScreenshotService.CapturedScreenshot capturedScreenshot() {
		return new FirstPersonScreenshotService.CapturedScreenshot("png", 854, 480, 1920, 1080, 1L, new byte[]{1, 2, 3});
	}

	private static final class BatchReadProvider implements PlannerToolProvider {
		private final List<String> executedTools = new ArrayList<>();

		@Override
		public String id() {
			return "batch_reads";
		}

		@Override
		public List<Map<String, Object>> openAiTools() {
			return List.of(
				PlannerToolCatalog.toolForProvider("search_recipes", "Search recipes.", Map.of(), List.of()),
				PlannerToolCatalog.toolForProvider("find_recipe_uses", "Find recipe uses.", Map.of(), List.of())
			);
		}

		@Override
		public boolean handles(String toolName) {
			String normalized = PlannerToolCatalog.normalizeName(toolName);
			return "search_recipes".equals(normalized) || "find_recipe_uses".equals(normalized);
		}

		@Override
		public boolean isReadTool(String toolName) {
			return handles(toolName);
		}

		@Override
		public CompletableFuture<String> execute(PlannerToolCall toolCall) {
			executedTools.add(toolCall.name());
			return CompletableFuture.completedFuture("Tool result for " + toolCall.name() + ": ok");
		}

		private List<String> executedTools() {
			return List.copyOf(executedTools);
		}
	}

	private static final class RecordingPlannerToolProvider implements PlannerToolProvider {
		private final List<String> queries = new ArrayList<>();

		@Override
		public String id() {
			return "recording";
		}

		@Override
		public List<Map<String, Object>> openAiTools() {
			return List.of(PlannerToolCatalog.toolForProvider(
				"search_recipes",
				"Search recipe-viewer recipes.",
				PlannerToolCatalog.propertiesForProvider(
					PlannerToolCatalog.propForProvider("query", PlannerToolCatalog.stringForProvider("Recipe query."))
				),
				List.of("query")
			));
		}

		@Override
		public String promptInstructions() {
			return "Use search_recipes for recipe-viewer searches.";
		}

		@Override
		public boolean handles(String toolName) {
			return "search_recipes".equals(PlannerToolCatalog.normalizeName(toolName));
		}

		@Override
		public CompletableFuture<String> execute(PlannerToolCall toolCall) {
			String query = toolCall.arguments().get("query").getAsString();
			queries.add(query);
			return CompletableFuture.completedFuture("Tool result for search_recipes: query=" + query);
		}

		private List<String> queries() {
			return List.copyOf(queries);
		}
	}

	private static final class ImagePlannerToolProvider implements PlannerToolProvider {
		@Override
		public String id() {
			return "image";
		}

		@Override
		public List<Map<String, Object>> openAiTools() {
			return List.of(PlannerToolCatalog.toolForProvider(
				"take_map_look",
				"Read a map image.",
				PlannerToolCatalog.propertiesForProvider(
					PlannerToolCatalog.propForProvider("kind", PlannerToolCatalog.stringForProvider("Map image kind."))
				),
				List.of("kind")
			));
		}

		@Override
		public boolean handles(String toolName) {
			return "take_map_look".equals(PlannerToolCatalog.normalizeName(toolName));
		}

		@Override
		public CompletableFuture<String> execute(PlannerToolCall toolCall) {
			return CompletableFuture.completedFuture("unused");
		}

		@Override
		public CompletableFuture<PlannerProviderToolResult> executeResult(PlannerToolCall toolCall) {
			return CompletableFuture.completedFuture(PlannerProviderToolResult.image(
				"Tool result for take_map_look: provider=journeymap, kind=minimap, image attached.",
				new LlmImageAttachment("image/png", new byte[]{1, 2, 3}, "low")
			));
		}
	}

	private static final class StubVisionTool implements CurrentViewVisionTool {
		private final boolean configured;
		private final CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> captureFuture;
		private final CompletableFuture<VisionDescription> descriptionFuture;
		private final List<String> metadataLines;
		private final List<ViewCaptureRequest> captureRequests = new ArrayList<>();
		private int captureRequestCount;
		private int descriptionRequestCount;

		private StubVisionTool(
			boolean configured,
			CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> captureFuture,
			CompletableFuture<VisionDescription> descriptionFuture
		) {
			this(configured, captureFuture, descriptionFuture, List.of());
		}

		private StubVisionTool(
			boolean configured,
			CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> captureFuture,
			CompletableFuture<VisionDescription> descriptionFuture,
			List<String> metadataLines
		) {
			this.configured = configured;
			this.captureFuture = captureFuture;
			this.descriptionFuture = descriptionFuture;
			this.metadataLines = List.copyOf(metadataLines);
		}

		@Override
		public boolean isConfigured() {
			return configured;
		}

		@Override
		public CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> requestCapture() {
			return requestCapture(ViewCaptureRequest.current()).thenApply(ViewCaptureResult::screenshot);
		}

		@Override
		public CompletableFuture<ViewCaptureResult> requestCapture(ViewCaptureRequest request) {
			captureRequestCount++;
			captureRequests.add(request);
			return captureFuture.thenApply(screenshot -> new ViewCaptureResult(screenshot, metadataLines));
		}

		@Override
		public CompletableFuture<VisionDescription> requestDescription(FirstPersonScreenshotService.CapturedScreenshot screenshot, String prompt) {
			descriptionRequestCount++;
			return descriptionFuture;
		}

		private int captureRequestCount() {
			return captureRequestCount;
		}

		private int descriptionRequestCount() {
			return descriptionRequestCount;
		}

		private List<ViewCaptureRequest> captureRequests() {
			return List.copyOf(captureRequests);
		}

		private CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> captureFuture() {
			return captureFuture;
		}
	}

	private static final class StubInventoryTool implements CurrentInventoryTool {
		private final String inventoryResult;
		private final String craftablesResult;
		private final String nearbyEntitiesResult;
		private int inventoryRequestCount;
		private int craftablesRequestCount;
		private int nearbyEntitiesRequestCount;

		private StubInventoryTool(String inventoryResult, String craftablesResult) {
			this(inventoryResult, craftablesResult, "unused");
		}

		private StubInventoryTool(String inventoryResult, String craftablesResult, String nearbyEntitiesResult) {
			this.inventoryResult = inventoryResult;
			this.craftablesResult = craftablesResult;
			this.nearbyEntitiesResult = nearbyEntitiesResult;
		}

		@Override
		public CompletableFuture<String> inspectInventory(String prompt) {
			inventoryRequestCount++;
			return CompletableFuture.completedFuture(inventoryResult);
		}

		@Override
		public CompletableFuture<String> checkCraftables(String prompt) {
			craftablesRequestCount++;
			return CompletableFuture.completedFuture(craftablesResult);
		}

		@Override
		public CompletableFuture<String> inspectNearbyEntities(com.google.gson.JsonObject arguments) {
			nearbyEntitiesRequestCount++;
			return CompletableFuture.completedFuture(nearbyEntitiesResult);
		}

		private int inventoryRequestCount() {
			return inventoryRequestCount;
		}

		private int craftablesRequestCount() {
			return craftablesRequestCount;
		}

		private int nearbyEntitiesRequestCount() {
			return nearbyEntitiesRequestCount;
		}
	}

	private static final class RecordingObservability implements AgentObservability {
		private static final ContextKey<String> THREAD_ID_KEY = ContextKey.named("test-thread-id");

		private final List<String> childParentThreadIds = new ArrayList<>();
		private final List<String> childNames = new ArrayList<>();

		@Override
		public synchronized Context startTurnSpan(PlannerRequest request, String threadId) {
			return Context.root().with(THREAD_ID_KEY, threadId);
		}

		@Override
		public synchronized Context startChildSpan(String name, Context parent) {
			Context safeParent = parent == null ? Context.root() : parent;
			childNames.add(name);
			childParentThreadIds.add(safeParent.get(THREAD_ID_KEY));
			return safeParent;
		}

		private synchronized List<String> childNames() {
			return List.copyOf(childNames);
		}

		private synchronized List<String> childParentThreadIds() {
			return new ArrayList<>(childParentThreadIds);
		}

		@Override
		public void setSpanAttribute(Context context, String key, String value) {
		}

		@Override
		public void setSpanAttribute(Context context, String key, boolean value) {
		}

		@Override
		public void setSpanAttribute(Context context, String key, long value) {
		}

		@Override
		public void recordImageCapture(Context context, FirstPersonScreenshotService.CapturedScreenshot capture) {
		}

		@Override
		public void recordLlmRequest(
			Context context,
			String providerName,
			URI endpoint,
			String model,
			long timeoutMillis,
			LlmConversation conversation,
			String requestBody
		) {
		}

		@Override
		public void recordFailedLlmInput(Context context, LlmConversation conversation, String requestBody) {
		}

		@Override
		public void recordLlmRequest(
			Context context,
			String providerName,
			URI endpoint,
			String model,
			long timeoutMillis,
			VisionRequest request,
			String imageDetail,
			String requestBody
		) {
		}

		@Override
		public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, PlannerResponse plannerResponse) {
		}

		@Override
		public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, String rawResponseBody) {
		}

		@Override
		public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, CompactionCheckpoint checkpoint) {
		}

		@Override
		public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, VisionDescription visionDescription) {
		}

		@Override
		public void recordFailure(Context context, String failureType, String message, Throwable throwable) {
		}

		@Override
		public void endSpan(Context context) {
		}

		@Override
		public void shutdown() {
		}
	}

	private static final class RecordingBackend implements LlmBackend {
		private final List<LlmConversation> conversations = new ArrayList<>();
		private final List<CompletableFuture<LlmCallResult<PlannerResponse>>> responses = new ArrayList<>();
		private final List<Long> acceptedGenerations = new ArrayList<>();
		private final List<Long> discardedGenerations = new ArrayList<>();
		private int completedCallCount;

		@Override
		public LlmCallResult<PlannerResponse> generate(LlmConversation conversation) throws LlmBackendException {
			CompletableFuture<LlmCallResult<PlannerResponse>> future = new CompletableFuture<>();
			synchronized (this) {
				conversations.add(conversation);
				responses.add(future);
				notifyAll();
			}
			try {
				return future.join();
			}
			catch (CompletionException exception) {
				Throwable cause = exception.getCause();
				if (cause instanceof LlmBackendException backendException) {
					throw backendException;
				}
				throw exception;
			}
			finally {
				synchronized (this) {
					completedCallCount++;
					notifyAll();
				}
			}
		}

		@Override
		public LlmCallResult<PlannerResponse> generate(PlannerBackendRequest request) throws LlmBackendException {
			return generate(request.conversation());
		}

		@Override
		public synchronized void acceptGeneration(long generation) {
			acceptedGenerations.add(generation);
		}

		@Override
		public synchronized void discardGeneration(long generation) {
			discardedGenerations.add(generation);
		}

		@Override
		public void injectMockResponse(PlannerResponse response) {
			throw new UnsupportedOperationException("Use succeed(index, response) in tests");
		}

		@Override
		public void injectTimeout() {
			throw new UnsupportedOperationException("Use fail(index, TIMEOUT, ...) in tests");
		}

		@Override
		public boolean isConfigured() {
			return true;
		}

		private synchronized void awaitCalls(int expectedCount, Duration timeout) {
			long deadline = System.nanoTime() + timeout.toNanos();
			while (conversations.size() < expectedCount) {
				long remainingNanos = deadline - System.nanoTime();
				if (remainingNanos <= 0L) {
					throw new AssertionError("Timed out waiting for backend calls. expected=" + expectedCount + " actual=" + conversations.size());
				}
				long waitMillis = Math.max(1L, remainingNanos / 1_000_000L);
				try {
					wait(waitMillis);
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError("Interrupted while waiting for backend calls", exception);
				}
			}
		}

		private synchronized int callCount() {
			return conversations.size();
		}

		private synchronized List<Long> acceptedGenerations() {
			return List.copyOf(acceptedGenerations);
		}

		private synchronized List<Long> discardedGenerations() {
			return List.copyOf(discardedGenerations);
		}

		private synchronized void awaitCompletions(int expectedCount, Duration timeout) {
			long deadline = System.nanoTime() + timeout.toNanos();
			while (completedCallCount < expectedCount) {
				long remainingNanos = deadline - System.nanoTime();
				if (remainingNanos <= 0L) {
					throw new AssertionError(
						"Timed out waiting for backend completions. expected=" + expectedCount + " actual=" + completedCallCount
					);
				}
				long waitMillis = Math.max(1L, remainingNanos / 1_000_000L);
				try {
					wait(waitMillis);
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError("Interrupted while waiting for backend completions", exception);
				}
			}
		}

		private synchronized LlmConversation conversation(int index) {
			return conversations.get(index);
		}

		private synchronized void succeed(int index, PlannerResponse response) {
			responses.get(index).complete(LlmCallResult.of(response, LlmUsageSnapshot.unknown()));
		}

		private synchronized void fail(int index, LlmFailureType failureType, String message) {
			responses.get(index).completeExceptionally(new LlmBackendException(failureType, message));
		}
	}

	private static final class RecordingLifecycleListener implements PlannerLifecycleListener {
		private final List<String> completedToolResults = new ArrayList<>();

		@Override
		public synchronized void onToolCompleted(long generation, String toolResult, boolean imageAttached) {
			completedToolResults.add(toolResult);
		}

		private synchronized List<String> completedToolResults() {
			return List.copyOf(completedToolResults);
		}
	}

	private static final class MutableClock extends Clock {
		private Instant instant;
		private final ZoneId zoneId;

		private MutableClock(Instant instant, ZoneId zoneId) {
			this.instant = instant;
			this.zoneId = zoneId;
		}

		@Override
		public ZoneId getZone() {
			return zoneId;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return new MutableClock(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant;
		}

		private void advanceMillis(long millis) {
			instant = instant.plusMillis(millis);
		}
	}

	private static final class CompactionTestServer implements AutoCloseable {
		private final HttpServer server;
		private int requestCount;

		private CompactionTestServer(HttpServer server) {
			this.server = server;
		}

		private static CompactionTestServer start() throws IOException {
			return start("""
				{
				  "choices": [
				    {
				      "message": {
				        "content": "{\\"time_anchor\\":\\"Tuesday afternoon\\",\\"session_state\\":\\"in world\\",\\"active_goal\\":\\"follow Alice\\",\\"active_commitments\\":[\\"follow Alice\\"],\\"durable_facts\\":[\\"Alice is nearby\\"],\\"relevant_people\\":[\\"Alice\\"],\\"open_loops\\":[\\"keep following\\"],\\"recent_timeline\\":[\\"Alice asked for follow\\"],\\"forgettable_noise\\":[]}"
				      }
				    }
				  ],
				  "usage": {
				    "prompt_tokens": 2048,
				    "completion_tokens": 128,
				    "total_tokens": 2176
				  }
				}
				""");
		}

		private static CompactionTestServer start(String responseBody) throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			CompactionTestServer holder = new CompactionTestServer(server);
			server.setExecutor(Executors.newCachedThreadPool());
			server.createContext("/chat/completions", exchange -> holder.handle(exchange, responseBody));
			server.start();
			return holder;
		}

		private void handle(HttpExchange exchange, String responseBody) throws IOException {
			requestCount++;
			writeResponse(exchange, 200, responseBody);
		}

		private int port() {
			return server.getAddress().getPort();
		}

		private int requestCount() {
			return requestCount;
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}

	private static void writeResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private static void assertPromptContains(LlmConversation conversation, String... expectedFragments) {
		String prompt = terminalPrompt(conversation);
		for (String expectedFragment : expectedFragments) {
			assertTrue(prompt.contains(expectedFragment), () -> "Prompt missing fragment: " + expectedFragment + "\nPrompt was:\n" + prompt);
		}
	}

	private static PlannerConversationDebugMessage lastConversationMessage(PlannerConversationDebugSnapshot snapshot) {
		assertFalse(snapshot.messages().isEmpty(), "Expected visible conversation messages");
		return snapshot.messages().get(snapshot.messages().size() - 1);
	}

	private static PlannerConversationDebugMessage findConversationMessage(
		PlannerConversationDebugSnapshot snapshot,
		PlannerConversationDebugKind kind,
		String textFragment
	) {
		return snapshot.messages().stream()
			.filter(message -> kind == null || message.kind() == kind)
			.filter(message -> textFragment == null || message.text().contains(textFragment))
			.findFirst()
			.orElse(null);
	}

	private static void assertInventoryBootstrap(LlmConversation conversation, String expectedInventoryFragment) {
		assertTrue(conversation.messages().size() >= 3, "Expected bootstrap messages after system prompt");
		LlmChatMessage toolCallMessage = conversation.messages().get(1);
		LlmChatMessage toolResultMessage = conversation.messages().get(2);
		assertEquals("assistant", toolCallMessage.role());
		assertTrue(toolCallMessage.hasToolCalls());
		assertEquals("bootstrap_inspect_inventory", toolCallMessage.toolCalls().get(0).id());
		assertEquals("inspect_inventory", toolCallMessage.toolCalls().get(0).name());
		assertEquals("startup inventory context", toolCallMessage.toolCalls().get(0).arguments().get("prompt").getAsString());
		assertEquals("tool", toolResultMessage.role());
		assertEquals("bootstrap_inspect_inventory", toolResultMessage.toolCallId());
		assertTrue(toolResultMessage.content().contains(expectedInventoryFragment), () -> "Unexpected bootstrap inventory result: " + toolResultMessage.content());
	}

	private static long inventoryBootstrapCount(LlmConversation conversation) {
		return conversation.messages().stream()
			.filter(message -> "assistant".equals(message.role()))
			.filter(LlmChatMessage::hasToolCalls)
			.flatMap(message -> message.toolCalls().stream())
			.filter(toolCall -> "bootstrap_inspect_inventory".equals(toolCall.id()))
			.count();
	}

	private static String terminalPrompt(LlmConversation conversation) {
		return conversation.messages().get(conversation.messages().size() - 1).content();
	}

	private static String conversationText(LlmConversation conversation) {
		return conversation.messages().stream()
			.map(LlmChatMessage::content)
			.reduce("", (left, right) -> left + "\n" + right);
	}
}
