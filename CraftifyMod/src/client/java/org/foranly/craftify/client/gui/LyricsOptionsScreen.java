package org.foranly.craftify.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.foranly.craftify.client.lyrics.LyricsManager;
import org.foranly.craftify.client.lyrics.LyricsPosition;

/**
 * Lyrics submenu (F10 -> "Lyrics options" or {@code /craftify lyrics}): toggles the local
 * lyrics overlay, whether the current line is shared with other players (the server renders
 * it as a hologram) and the overlay position on the screen.
 */
public final class LyricsOptionsScreen extends Screen {

    private Button overlayButton;
    private Button shareButton;
    private final List<Button> positionButtons = new ArrayList<>();

    public LyricsOptionsScreen() {
        super(Component.literal("Craftify - Lyrics"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 88;

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
                .bounds(centerX - 100, y + 26, 200, 20)
                .build();

        Button appearanceButton = Button.builder(Component.literal("Appearance (size / opacity / color)"),
                        button -> Minecraft.getInstance().gui.setScreen(new LyricsAppearanceScreen(this)))
                .bounds(centerX - 100, y + 52, 200, 20)
                .build();

        // Position picker: a 3x3 grid of small buttons (TL TC TR / ML MC MR / BL BC BR),
        // flush against the chosen edge. The selected one is highlighted.
        LyricsPosition current = LyricsManager.instance().position();
        int gridY = y + 82;
        int gridX = centerX - 33;
        for (LyricsPosition position : LyricsPosition.values()) {
            int col = position.ordinal() % 3;
            int row = position.ordinal() / 3;
            Button positionButton = Button.builder(positionLabel(position), button -> {
                        LyricsManager.instance().setPosition(position);
                        refreshPositionButtons();
                    })
                    .bounds(gridX + col * 33, gridY + row * 22, 31, 20)
                    .tooltip(Tooltip.create(Component.literal("Position: " + position.displayName())))
                    .build();
            this.positionButtons.add(positionButton);
            this.addRenderableWidget(positionButton);
        }

        Button backButton = Button.builder(Component.literal("Back"),
                        button -> Minecraft.getInstance().gui.setScreen(new CraftifyMenuScreen()))
                .bounds(centerX - 100, gridY + 66, 200, 20)
                .build();

        this.addRenderableWidget(this.overlayButton);
        this.addRenderableWidget(this.shareButton);
        this.addRenderableWidget(appearanceButton);
        this.addRenderableWidget(backButton);
    }

    private Component overlayLabel() {
        return Component.literal("Lyrics overlay: " + (LyricsManager.instance().isEnabled() ? "ON" : "OFF"));
    }

    private Component shareLabel() {
        return Component.literal("Share lyrics: " + (LyricsManager.instance().isShared() ? "ON" : "OFF"));
    }

    private Component positionLabel(LyricsPosition position) {
        boolean selected = LyricsManager.instance().position() == position;
        return Component.literal(selected ? "> " + position.shortName() : position.shortName())
                .withStyle(selected ? ChatFormatting.YELLOW : ChatFormatting.GRAY);
    }

    private void refreshPositionButtons() {
        for (Button button : positionButtons) {
            int index = positionButtons.indexOf(button);
            if (index >= 0 && index < LyricsPosition.values().length) {
                button.setMessage(positionLabel(LyricsPosition.values()[index]));
            }
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(null);
    }
}
