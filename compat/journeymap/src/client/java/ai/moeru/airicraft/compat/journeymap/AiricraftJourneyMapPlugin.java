package ai.moeru.airicraft.compat.journeymap;

import ai.moeru.airicraft.agent.integration.map.MapIntegrationBridge;
import ai.moeru.airicraft.agent.memory.LocationMemoryBridge;
import journeymap.api.v2.client.event.MappingEvent;
import journeymap.api.v2.common.event.ClientEventRegistry;
import journeymap.api.v2.common.event.CommonEventRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.JourneyMapPlugin;

import java.util.List;

@JourneyMapPlugin(apiVersion = IClientAPI.API_VERSION)
public final class AiricraftJourneyMapPlugin implements IClientPlugin {
	private static final String MOD_ID = "airicraft";

	private IClientAPI jmAPI;
	private volatile Object mappedWorld;

	@Override
	public String getModId() {
		return MOD_ID;
	}

	@Override
	public void initialize(IClientAPI jmClientApi) {
		this.jmAPI = jmClientApi;
		MapIntegrationBridge.setProviders(List.of(new JourneyMapIntegrationProvider(jmAPI)));
		LocationMemoryBridge.registerJourneyMap(new JourneyMapLocationMemoryProvider(jmAPI, () -> {
			var world = MinecraftClient.getInstance().world;
			return world != null && world == mappedWorld;
		}));
		ClientEventRegistry.MAPPING_EVENT.subscribe(MOD_ID, event -> {
			var client = MinecraftClient.getInstance();
			var world = client.world;
			client.execute(() -> {
				if (client.world != world) return;
				if (event.getStage() == MappingEvent.Stage.MAPPING_STARTED) {
					if (world != null && world.getRegistryKey().equals(event.dimension)) mappedWorld = world;
				}
				else if (world == null || world.getRegistryKey().equals(event.dimension)) mappedWorld = null;
				LocationMemoryBridge.changed();
			});
		});
		CommonEventRegistry.WAYPOINT_EVENT.subscribe(MOD_ID, event -> LocationMemoryBridge.changed());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			mappedWorld = null;
			LocationMemoryBridge.changed();
		});
	}
}
