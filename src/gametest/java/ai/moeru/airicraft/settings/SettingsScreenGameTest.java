package ai.moeru.airicraft.settings;

import ai.moeru.airicraft.AiricraftClient;
import me.shedaniel.clothconfig2.api.AbstractConfigEntry;
import me.shedaniel.clothconfig2.gui.entries.StringListEntry;
import me.shedaniel.clothconfig2.gui.entries.SubCategoryListEntry;
import me.shedaniel.clothconfig2.gui.entries.TextFieldListEntry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.stream.Stream;

/** Run explicitly with -Pairicraft.settingsSmoke=true runClientGameTest. */
public final class SettingsScreenGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try {
			var path = FabricLoader.getInstance().getConfigDir().resolve("airicraft/agent.yml");
			String original = Files.readString(path);
			var runtime = context.computeOnClient(client -> AiricraftClient.runtimeController().agentRuntime());
			context.runOnClient(client -> {
				client.options.getGuiScale().setValue(1);
				client.onResolutionChanged();
			});
			context.setScreen(TitleScreen::new);
			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.takeScreenshot("airicraft-settings-connection");
			context.runOnClient(client -> ((AiricraftSettingsScreen) client.currentScreen).saveAll(true));
			context.waitForScreen(TitleScreen.class);
			context.runOnClient(client -> {
				if (AiricraftClient.runtimeController().agentRuntime() != runtime) throw new AssertionError("Unchanged save reloaded the agent");
			});
			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.runOnClient(client -> {
				var screen = (AiricraftSettingsScreen) client.currentScreen;
				var range = (TextFieldListEntry<?>) entry(screen, "Chat range (blocks)");
				range.setValue("-2");
				screen.saveAll(true);
				if (client.currentScreen != screen) throw new AssertionError("Invalid range was accepted");
				if (AiricraftClient.runtimeController().agentRuntime() != runtime) throw new AssertionError("Invalid input reloaded the agent");
				range.setValue("-1");
			});
			context.runOnClient(client -> {
				var screen = (AiricraftSettingsScreen) client.currentScreen;
				field(screen, "API key").setValue("fake-ui-test-key");
			});
			context.takeScreenshot("airicraft-settings-masked-key");
			context.clickScreenButton("gui.cancel");
			context.waitForScreen(TitleScreen.class);
			if (!Files.readString(path).equals(original)) throw new AssertionError("Cancel wrote settings");
			context.runOnClient(client -> {
				if (AiricraftClient.runtimeController().agentRuntime() != runtime) throw new AssertionError("Cancel reloaded the agent");
			});

			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.runOnClient(client -> field((AiricraftSettingsScreen) client.currentScreen, "Model").setValue("settings-smoke-model"));
			context.waitTick();
			context.clickScreenButton("airicraft.settings.save");
			context.waitForScreen(TitleScreen.class);
			if (!Files.readString(path).contains("settings-smoke-model")) throw new AssertionError("Save did not persist the model");
			context.runOnClient(client -> {
				if (AiricraftClient.runtimeController().agentRuntime() == runtime) throw new AssertionError("Save did not reload the agent");
			});
			context.clickScreenButton("button.airicraft.settings");
			context.waitForScreen(AiricraftSettingsScreen.class);
			context.runOnClient(client -> {
				if (!field((AiricraftSettingsScreen) client.currentScreen, "Model").getValue().equals("settings-smoke-model")) {
					throw new AssertionError("Reopened screen did not load saved model");
				}
			});
			context.takeScreenshot("airicraft-settings-saved");
			for (int tab = 1; tab < 4; tab++) {
				int selected = tab;
				context.runOnClient(client -> {
					var screen = (AiricraftSettingsScreen) client.currentScreen;
					screen.selectedCategoryIndex = selected;
					client.setScreen(screen);
				});
				context.takeScreenshot("airicraft-settings-tab-" + tab);
			}
			context.clickScreenButton("gui.cancel");
			context.waitForScreen(TitleScreen.class);
			Files.writeString(path, original);
			if (FabricLoader.getInstance().isModLoaded("modmenu")) {
				context.runOnClient(client -> client.setScreen(ModMenuProbe.open(client.currentScreen)));
				context.waitForScreen(AiricraftSettingsScreen.class);
				context.takeScreenshot("airicraft-settings-modmenu");
				context.clickScreenButton("gui.cancel");
				context.waitForScreen(TitleScreen.class);
			}
			try (var world = context.worldBuilder().create()) {
				context.waitFor(client -> client.currentScreen == null, 20);
				context.getInput().pressKey(net.minecraft.client.option.KeyBinding.byId("key.airicraft.settings"));
				context.waitFor(client -> client.currentScreen instanceof AiricraftSettingsScreen, 10);
				context.takeScreenshot("airicraft-settings-in-world");
				context.clickScreenButton("gui.cancel");
				context.waitForScreen(null);
				context.runOnClient(client -> client.player.networkHandler.sendChatCommand("airicraft config"));
				context.waitForScreen(AiricraftSettingsScreen.class);
				context.clickScreenButton("gui.cancel");
				context.waitForScreen(null);
				context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
				context.waitForScreen(GameMenuScreen.class);
				context.clickScreenButton("button.airicraft.settings");
				context.waitForScreen(AiricraftSettingsScreen.class);
				context.clickScreenButton("gui.cancel");
				context.waitForScreen(GameMenuScreen.class);
			}
		} catch (Exception exception) { throw new AssertionError(exception); }
	}

	private static StringListEntry field(AiricraftSettingsScreen screen, String label) {
		return (StringListEntry) entry(screen, label);
	}

	private static AbstractConfigEntry<?> entry(AiricraftSettingsScreen screen, String label) {
		return screen.getCategorizedEntries().values().stream().flatMap(java.util.Collection::stream)
			.flatMap(SettingsScreenGameTest::flatten).filter(entry -> entry.getFieldName().getString().equals(label))
			.findFirst().orElseThrow();
	}

	private static Stream<AbstractConfigEntry<?>> flatten(AbstractConfigEntry<?> entry) {
		if (entry instanceof SubCategoryListEntry category) return category.getValue().stream().flatMap(SettingsScreenGameTest::flatten);
		return Stream.of(entry);
	}

	private static final class ModMenuProbe {
		private static net.minecraft.client.gui.screen.Screen open(net.minecraft.client.gui.screen.Screen parent) {
			return com.terraformersmc.modmenu.ModMenu.getConfigScreen("airicraft", parent);
		}
	}
}
