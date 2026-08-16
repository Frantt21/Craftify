package org.foranly.craftify.client.gui;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.foranly.craftify.client.lyrics.LyricsManager;

/**
 * Lyrics appearance submenu (F10 -> "Lyrics options" -> "Appearance"): adjusts the size of
 * the overlay text (0.5x-3.0x), its opacity (10-100%) and the text color (preset palette
 * or a custom hex value). Every change is applied live and persisted to
 * {@code config/craftify.json}.
 */
public final class LyricsAppearanceScreen extends Screen {

    private static final int COLOR_BLACK = 0x000000;
    private static final int COLOR_DARK_GRAY = 0x555555;
    private static final int COLOR_GRAY = 0xAAAAAA;
    private static final int COLOR_WHITE = 0xFFFFFF;
    private static final int COLOR_YELLOW = 0xFFFF55;
    private static final int COLOR_GOLD = 0xFFAA00;
    private static final int COLOR_GREEN = 0x55FF55;
    private static final int COLOR_AQUA = 0x55FFFF;
    private static final int COLOR_RED = 0xFF5555;
    private static final int COLOR_PINK = 0xFF55FF;

    private final Screen parent;
    private EditBox colorBox;

    public LyricsAppearanceScreen(Screen parent) {
        super(Component.literal("Craftify - Lyrics Appearance"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 62;

        this.addRenderableWidget(new SliderButton(centerX - 100, y, 200, 20, 0.5, 3.0,
                "Size",
                () -> LyricsManager.instance().scale(),
                value -> LyricsManager.instance().setScale(value),
                value -> String.format(Locale.ROOT, "%.0f%%", value * 100)));

        this.addRenderableWidget(new SliderButton(centerX - 100, y + 26, 200, 20, 10, 100,
                "Opacity",
                () -> LyricsManager.instance().opacity(),
                value -> LyricsManager.instance().setOpacity((int) Math.round(value)),
                value -> Math.round(value) + "%"));

        // Color palette: one row of preset swatches.
        int swatchY = y + 56;
        int swatchWidth = (200 - 7 * 2) / 8;
        int[] colors = {COLOR_WHITE, COLOR_YELLOW, COLOR_GOLD, COLOR_GREEN,
                COLOR_AQUA, COLOR_PINK, COLOR_RED, COLOR_GRAY};
        String[] names = {"White", "Yellow", "Gold", "Green", "Aqua", "Pink", "Red", "Gray"};
        for (int i = 0; i < colors.length; i++) {
            int color = colors[i];
            final int swatchColor = color;
            this.addRenderableWidget(Button.builder(
                            Component.literal(names[i]).withStyle(style -> style.withColor(TextColor.fromRgb(color))),
                            button -> {
                                LyricsManager.instance().setTextColor(swatchColor);
                                refreshColorUi();
                            })
                    .bounds(centerX - 100 + i * (swatchWidth + 2), swatchY, swatchWidth, 20)
                    .build());
        }

        // Custom hex input (#RRGGBB).
        this.colorBox = new EditBox(Minecraft.getInstance().font, centerX - 100, swatchY + 26, 150, 20,
                Component.literal("#RRGGBB"));
        this.colorBox.setMaxLength(9);
        this.colorBox.setValue(colorHex(LyricsManager.instance().textColor()));
        this.addRenderableWidget(this.colorBox);
        this.addRenderableWidget(Button.builder(Component.literal("Apply"),
                        button -> applyCustomColor())
                .bounds(centerX + 56, swatchY + 26, 44, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"),
                        button -> Minecraft.getInstance().gui.setScreen(parent))
                .bounds(centerX - 100, swatchY + 52, 200, 20)
                .build());
    }

    private void applyCustomColor() {
        int rgb = parseHex(this.colorBox.getValue());
        if (rgb >= 0) {
            LyricsManager.instance().setTextColor(rgb);
            refreshColorUi();
        } else {
            this.colorBox.setValue(colorHex(LyricsManager.instance().textColor()));
        }
    }

    /** Re-syncs the hex box with the current color (after a swatch or custom apply). */
    private void refreshColorUi() {
        this.colorBox.setValue(colorHex(LyricsManager.instance().textColor()));
    }

    private static int parseHex(String value) {
        if (value == null) {
            return -1;
        }
        String cleaned = value.strip();
        if (cleaned.startsWith("#")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.length() != 6) {
            return -1;
        }
        try {
            return 0xFF000000 | Integer.parseInt(cleaned, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String colorHex(int argb) {
        return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    /** A slider that applies its value to the lyrics manager live and shows a formatted label. */
    private static final class SliderButton extends AbstractSliderButton {

        private final double min;
        private final double max;
        private final String label;
        private final java.util.function.DoubleSupplier getter;
        private final java.util.function.DoubleConsumer setter;
        private final java.util.function.DoubleFunction<String> formatter;

        private SliderButton(int x, int y, int width, int height, double min, double max, String label,
                             java.util.function.DoubleSupplier getter,
                             java.util.function.DoubleConsumer setter,
                             java.util.function.DoubleFunction<String> formatter) {
            super(x, y, width, height, Component.empty(),
                    (getter.getAsDouble() - min) / (max - min));
            this.min = min;
            this.max = max;
            this.label = label;
            this.getter = getter;
            this.setter = setter;
            this.formatter = formatter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double value = min + this.value * (max - min);
            setMessage(Component.literal(label + ": " + formatter.apply(value)));
        }

        @Override
        protected void applyValue() {
            double value = min + this.value * (max - min);
            setter.accept(value);
        }
    }
}
