package org.foranly.craftify.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.foranly.craftify.client.lyrics.LyricsManager;

/**
 * Options screen opened with F10 (or {@code /craftify lyrics}): toggles the local lyrics
 * overlay and whether the current lyric line is shared with other players (the server
 * renders it as a hologram).
 */
public final class LyricsOptionsScreen extends Screen {

    private Button overlayButton;
    private Button shareButton;

    public LyricsOptionsScreen() {
        super(Component.literal("Craftify - Lyrics"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 32;

        this.overlayButton = Button.builder(overlayLabel(), button -> {
                    LyricsManager lyrics = LyricsManager.instance();
                    lyrics.setEnabled(!lyrics.isEnabled());
                    button.setMessage(overlayLabel());
                })
                .bounds(centerX - 100, y, 200, 20)
                .build();

        this.shareButton = Button.builder(shareLabel(), button -> {
                    LyricsManager lyrics = LyricsManager.instance();
                    lyrics.setShared(!lyrics.isShared());
                    button.setMessage(shareLabel());
                })
                .bounds(centerX - 100, y + 24, 200, 20)
                .build();

        Button doneButton = Button.builder(Component.literal("Done"), button -> this.onClose())
                .bounds(centerX - 100, y + 48, 200, 20)
                .build();

        this.addRenderableWidget(this.overlayButton);
        this.addRenderableWidget(this.shareButton);
        this.addRenderableWidget(doneButton);
    }

    private Component overlayLabel() {
        return Component.literal("Lyrics overlay: " + (LyricsManager.instance().isEnabled() ? "ON" : "OFF"));
    }

    private Component shareLabel() {
        return Component.literal("Share lyrics with others: " + (LyricsManager.instance().isShared() ? "ON" : "OFF"));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(null);
    }
}
