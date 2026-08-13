package org.foranly.craftify.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Muestra el nametag del propio jugador en tercera persona (F5).
 *
 * <p>Vanilla lo oculta: {@code LivingEntityRenderer#shouldShowName} devuelve {@code false}
 * cuando la entidad coincide con {@link Minecraft#getCameraEntity()} (el jugador local),
 * que es el caso tanto en primera como en tercera persona. Este mixin permite mostrarlo
 * cuando la cámara está en tercera persona y se está mirando al propio jugador.
 *
 * <p>Funciona con el nombre normal y también con el custom name que ponga el plugin
 * (canal {@code craftify:title}) vía {@code setCustomName}.
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
        // Solo el jugador local, mirándose a sí mismo (F5, sin espectar a otro) y con la
        // cámara en tercera persona. Se conserva el chequeo de HUD oculto (F1) de vanilla.
        if (entity == minecraft.player
                && entity == minecraft.getCameraEntity()
                && !minecraft.options.getCameraType().isFirstPerson()
                && !minecraft.gui.hud.isHidden()) {
            cir.setReturnValue(true);
        }
    }
}
