package ai.moeru.airicraft.dashboard;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.AgentConfigLoader;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticEnvironmentTest {
	@Test
	void identifiesProvidersWithoutCopyingCredentialsOrEndpointPaths() {
		var config = AgentConfigLoader.fromMapStrict(Map.of("providerBaseUrl", "https://user:PRIVATE@example.org/PRIVATE?key=PRIVATE",
			"apiKey", "PRIVATE", "model", "test-model", "visionModel", "vision-model"), AgentConfig.defaults());
		String json = new Gson().toJson(DiagnosticEnvironment.providers(config.llm()));
		assertTrue(json.contains("example.org"));
		assertTrue(json.contains("test-model"));
		assertTrue(json.contains("vision-model"));
		assertFalse(json.contains("PRIVATE"));
	}

	@Test
	void identifiesTheActiveCodexModelAndHandlesMalformedProviderUrls() {
		var config = AgentConfigLoader.fromMapStrict(Map.of("plannerBackend", "codex-app-server",
			"codexAppServer", Map.of("model", "codex-test-model"), "providerBaseUrl", "not a uri PRIVATE"), AgentConfig.defaults());
		String json = new Gson().toJson(DiagnosticEnvironment.providers(config.llm()));
		assertTrue(json.contains("codex-test-model"));
		assertTrue(json.contains("codex-app-server"));
		assertFalse(json.contains("PRIVATE"));
	}
}
