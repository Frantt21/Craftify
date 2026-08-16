package org.foranly.craftify.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Small JSON config stored at {@code config/craftify.json} (the Fabric config dir),
 * persisting the client-side settings across sessions: lyrics overlay toggle, sharing
 * with other players and the overlay position.
 */
public final class CraftifyConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("craftify.json");

    private boolean lyricsEnabled = true;
    private boolean lyricsShared;
    private String lyricsPosition = "BOTTOM_LEFT";
    /** Overlay text scale (0.5 = half size, 2.0 = double). */
    private double lyricsScale = 1.0;
    /** Overlay opacity in percent (0-100). */
    private int lyricsOpacity = 100;
    /** Overlay text color as a hex RGB string ("FFFFFF" = white). */
    private String lyricsColor = "FFFFFF";

    private static CraftifyConfig instance;

    private CraftifyConfig() {
    }

    /** Loads (once) or returns the cached config. */
    public static CraftifyConfig instance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static CraftifyConfig load() {
        if (Files.exists(FILE)) {
            try {
                CraftifyConfig loaded = GSON.fromJson(Files.readString(FILE), CraftifyConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (IOException | RuntimeException e) {
                // Fall back to defaults on any read/parse problem.
            }
        }
        return new CraftifyConfig();
    }

    /** Writes the current values to disk (best-effort; never throws). */
    public void save() {
        try {
            if (FILE.getParent() != null) {
                Files.createDirectories(FILE.getParent());
            }
            Files.writeString(FILE, GSON.toJson(this));
        } catch (IOException e) {
            // Best effort: losing the config is not fatal.
        }
    }

    public boolean isLyricsEnabled() {
        return lyricsEnabled;
    }

    public void setLyricsEnabled(boolean value) {
        this.lyricsEnabled = value;
        save();
    }

    public boolean isLyricsShared() {
        return lyricsShared;
    }

    public void setLyricsShared(boolean value) {
        this.lyricsShared = value;
        save();
    }

    public String getLyricsPosition() {
        return lyricsPosition;
    }

    public void setLyricsPosition(String value) {
        this.lyricsPosition = value;
        save();
    }

    public double getLyricsScale() {
        return lyricsScale;
    }

    public void setLyricsScale(double value) {
        this.lyricsScale = value;
        save();
    }

    public int getLyricsOpacity() {
        return lyricsOpacity;
    }

    public void setLyricsOpacity(int value) {
        this.lyricsOpacity = Math.max(0, Math.min(100, value));
        save();
    }

    public String getLyricsColor() {
        return lyricsColor;
    }

    public void setLyricsColor(String value) {
        this.lyricsColor = value == null ? "FFFFFF" : value;
        save();
    }
}
