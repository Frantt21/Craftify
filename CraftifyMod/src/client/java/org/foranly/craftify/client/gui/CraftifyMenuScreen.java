package org.foranly.craftify.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.foranly.craftify.client.command.SpotifyCommand;
import org.foranly.craftify.client.spotify.SpotifyTracker;

/**
 * General Craftify menu (F10 or {@code /craftify menu}): the entry point of the mod's UI.
 * Shows general options (checking Spotify, packet sending) and links to the submenus for
 * the specific features (lyrics options, lyrics search).
 */
public final class CraftifyMenuScreen extends Screen {

    private Button sendingButton;

    public CraftifyMenuScreen() {
        super(Component.literal("Craftify"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 66;

        // General options
        this.sendingButton = Button.builder(sendingLabel(), button -> {
                    SpotifyTracker.setPaused(!SpotifyTracker.isPaused());
                    button.setMessage(sendingLabel());
                })
                .bounds(centerX - 100, y, 200, 20)
                .tooltip(Tooltip.create(Component.literal("Pause/resume sending Spotify state packets to the server")))
                .build();

        SpriteIconButton checkButton = iconButton(
                "Check Spotify",
                "icon/info", 15, 15,
                centerX - 100, y + 26, 200, 20,
                button -> runSpotifyCheck(),
                "Reads the Spotify process and prints the full status to the chat");

        // Submenu entries
        SpriteIconButton lyricsButton = iconButton(
                "Lyrics options",
                "icon/music_notes", 15, 15,
                centerX - 100, y + 52, 200, 20,
                button -> openScreen(new LyricsOptionsScreen()),
                "Lyrics overlay, sharing with other players and position");

        SpriteIconButton searchButton = iconButton(
                "Lyrics search",
                "icon/search", 12, 12,
                centerX - 100, y + 78, 200, 20,
                button -> openScreen(new LyricsSearchScreen()),
                "Search LRCLib for a song and inspect the candidates");

        Button doneButton = Button.builder(Component.literal("Done"), button -> this.onClose())
                .bounds(centerX - 100, y + 104, 200, 20)
                .build();

        this.addRenderableWidget(this.sendingButton);
        this.addRenderableWidget(checkButton);
        this.addRenderableWidget(lyricsButton);
        this.addRenderableWidget(searchButton);
        this.addRenderableWidget(doneButton);
    }

    /** A text + icon button using a vanilla GUI sprite, positioned afterwards. */
    private SpriteIconButton iconButton(String text, String sprite, int spriteW, int spriteH,
                                        int x, int y, int width, int height,
                                        Button.OnPress onPress, String tooltip) {
        SpriteIconButton button = SpriteIconButton.builder(Component.literal(text), onPress, false)
                .size(width, height)
                .sprite(Identifier.withDefaultNamespace(sprite), spriteW, spriteH)
                .build();
        button.setX(x);
        button.setY(y);
        button.setTooltip(Tooltip.create(Component.literal(tooltip)));
        return button;
    }

    private Component sendingLabel() {
        return Component.literal("Packet sending: " + (SpotifyTracker.isPaused() ? "OFF" : "ON"));
    }

    /**
     * Opens a submenu without forcing an immediate frame render (avoids the flicker that
     * {@code setScreenAndShow} causes with the screen fade-in).
     */
    private static void openScreen(Screen screen) {
        Minecraft.getInstance().gui.setScreen(screen);
    }

    /**
     * Runs the Spotify check off the render thread (the snapshot may take up to a second
     * on macOS) and prints the result to the chat on the main thread.
     */
    private void runSpotifyCheck() {
        CompletableFuture.runAsync(() -> {
            List<Component> messages = new ArrayList<>();
            SpotifyCommand.printSpotifyStatus(messages::add, messages::add);
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().player != null) {
                    for (Component message : messages) {
                        Minecraft.getInstance().player.sendSystemMessage(message);
                    }
                }
            });
        });
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(null);
    }
}
