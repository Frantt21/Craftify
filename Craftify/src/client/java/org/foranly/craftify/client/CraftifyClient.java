package org.foranly.craftify.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.foranly.craftify.client.command.SpotifyCommand;
import org.foranly.craftify.client.network.SpotifyTitlePayload;
import org.foranly.craftify.client.spotify.SpotifyTracker;

public class CraftifyClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SpotifyTitlePayload.register();

        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, _) -> SpotifyCommand.register(dispatcher));

        ClientPlayConnectionEvents.JOIN.register((_, _, _) -> SpotifyTracker.start());
        ClientPlayConnectionEvents.DISCONNECT.register((_, _) -> SpotifyTracker.stop());
    }
}
