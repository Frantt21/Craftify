package org.foranly.craftify.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.foranly.craftify.client.command.SpotifyCommand;
import org.foranly.craftify.client.network.SpotifyTitlePayload;
import org.foranly.craftify.client.spotify.SpotifyTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CraftifyClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("craftify");

    @Override
    public void onInitializeClient() {
        SpotifyTitlePayload.register();
        // Helps verifying in the game log which jar version was loaded.
        LOGGER.info("Craftify client initialized (mixins: LivingEntityRendererMixin)");

        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, _) -> SpotifyCommand.register(dispatcher));

        ClientPlayConnectionEvents.JOIN.register((_, _, _) -> SpotifyTracker.start());
        ClientPlayConnectionEvents.DISCONNECT.register((_, _) -> SpotifyTracker.stop());
    }
}
