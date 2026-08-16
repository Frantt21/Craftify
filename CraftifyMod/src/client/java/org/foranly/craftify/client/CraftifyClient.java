package org.foranly.craftify.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import org.foranly.craftify.client.command.SpotifyCommand;
import org.foranly.craftify.client.gui.LyricsOptionsScreen;
import org.foranly.craftify.client.lyrics.LyricsManager;
import org.foranly.craftify.client.network.LyricsLinePayload;
import org.foranly.craftify.client.network.SpotifyTitlePayload;
import org.foranly.craftify.client.spotify.SpotifyTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CraftifyClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("craftify");

    /** F10 opens the lyrics options screen. */
    private static final KeyMapping LYRICS_KEY = new KeyMapping(
            "key.craftify.lyrics", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
            org.lwjgl.glfw.GLFW.GLFW_KEY_F10, KeyMapping.Category.MISC);

    @Override
    public void onInitializeClient() {
        SpotifyTitlePayload.register();
        LyricsLinePayload.register();
        // Registers the lyrics HUD element (LRCLib overlay, toggle: F10 / /craftify lyrics).
        LyricsManager.instance();
        // F10 opens the lyrics options screen (in-game only).
        KeyMappingHelper.registerKeyMapping(LYRICS_KEY);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (LYRICS_KEY.consumeClick() && client.player != null && client.gui.screen() == null) {
                client.setScreenAndShow(new LyricsOptionsScreen());
            }
        });
        // Helps verifying in the game log which jar version was loaded.
        LOGGER.info("Craftify client initialized (mixins: LivingEntityRendererMixin, lyrics overlay, F10 menu)");

        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, _) -> SpotifyCommand.register(dispatcher));

        ClientPlayConnectionEvents.JOIN.register((_, _, _) -> SpotifyTracker.start());
        ClientPlayConnectionEvents.DISCONNECT.register((_, _) -> SpotifyTracker.stop());
    }
}
