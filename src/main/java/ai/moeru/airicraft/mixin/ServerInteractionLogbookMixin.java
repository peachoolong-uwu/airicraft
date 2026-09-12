package ai.moeru.airicraft.mixin;

import ai.moeru.airicraft.memory.InteractionLogbookRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerInteractionManager.class)
public class ServerInteractionLogbookMixin {
	@Unique private ScreenHandler airicraft$previous;
	@Inject(method = "interactBlock", at = @At("HEAD"))
	private void airicraft$before(ServerPlayerEntity player, World world, ItemStack stack, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
		airicraft$previous = player.currentScreenHandler;
	}
	@Inject(method = "interactBlock", at = @At("RETURN"))
	private void airicraft$after(ServerPlayerEntity player, World world, ItemStack stack, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
		if (player.currentScreenHandler != airicraft$previous)
			InteractionLogbookRecorder.opened(player, hit.getBlockPos(), player.currentScreenHandler);
		airicraft$previous = null;
	}
}
