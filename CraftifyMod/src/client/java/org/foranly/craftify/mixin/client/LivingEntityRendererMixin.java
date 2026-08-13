package org.foranly.craftify.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shows the local player's own nametag in third person (F5).
 *
 * <p>Vanilla hides it: {@code LivingEntityRenderer#shouldShowName} returns {@code false}
 * when the entity matches {@link Minecraft#getCameraEntity()} (the local player), which is
 * the case both in first and third person. This mixin allows showing it when the camera is
 * in third person and looking at the player themselves.
 *
 * <p>It works with the normal name and also with the custom name set by the plugin
 * ({@code craftify:title} channel) via {@code setCustomName}.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Inject(
            method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void craftify$showOwnNameInThirdPerson(LivingEntity entity, double distanceToCameraSq,
                                                   CallbackInfoReturnable<Boolean> cir) {
        Minecraft minecraft = Minecraft.getInstance();
        // Only the local player, looking at themselves (F5, not spectating someone else)
        // with the camera in third person. Vanilla's hidden-HUD (F1) check is preserved.
        if (entity == minecraft.player
                && entity == minecraft.getCameraEntity()
                && !minecraft.options.getCameraType().isFirstPerson()
                && !minecraft.gui.hud.isHidden()) {
            cir.setReturnValue(true);
        }
    }
}
