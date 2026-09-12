package ai.moeru.airicraft.mixin;

import ai.moeru.airicraft.memory.InteractionLogbookRecorder;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenHandler.class)
public class ScreenHandlerLogbookMixin {
	@Unique private InteractionLogbookRecorder.Click airicraft$before;
	@Inject(method = "onSlotClick", at = @At("HEAD"))
	private void airicraft$before(int slot, int button, SlotActionType type, PlayerEntity player, CallbackInfo ci) {
		if (player instanceof ServerPlayerEntity serverPlayer)
			airicraft$before = InteractionLogbookRecorder.beforeClick(serverPlayer, (ScreenHandler) (Object) this, slot);
	}
	@Inject(method = "onSlotClick", at = @At("RETURN"))
	private void airicraft$after(int slot, int button, SlotActionType type, PlayerEntity player, CallbackInfo ci) {
		if (player instanceof ServerPlayerEntity serverPlayer && airicraft$before != null) {
			InteractionLogbookRecorder.afterClick(serverPlayer, (ScreenHandler) (Object) this, airicraft$before);
			airicraft$before = null;
		}
	}
}
