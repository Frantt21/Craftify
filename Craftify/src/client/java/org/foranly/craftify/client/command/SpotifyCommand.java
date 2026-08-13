package org.foranly.craftify.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.foranly.craftify.client.spotify.SpotifyProcess;
import org.foranly.craftify.client.spotify.SpotifyTracker;

/**
 * Comando {@code /craftify spotify}: verifica que el mod detecte correctamente el
 * proceso de Spotify y lea el título de su ventana en el sistema operativo actual.
 */
public final class SpotifyCommand {

    private SpotifyCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("craftify")
                .then(ClientCommands.literal("spotify")
                        .executes(SpotifyCommand::executeSpotify))
                .then(ClientCommands.literal("send")
                        .then(ClientCommands.literal("on")
                                .executes(ctx -> setSending(ctx, false)))
                        .then(ClientCommands.literal("off")
                                .executes(ctx -> setSending(ctx, true)))
                        .then(ClientCommands.literal("toggle")
                                .executes(ctx -> setSending(ctx, !SpotifyTracker.isPaused())))));
    }

    private static int setSending(CommandContext<FabricClientCommandSource> context, boolean paused) {
        SpotifyTracker.setPaused(paused);
        FabricClientCommandSource source = context.getSource();
        if (paused) {
            source.sendFeedback(Component.literal("[Craftify] Envío de paquetes pausado.")
                    .withStyle(ChatFormatting.YELLOW));
            source.sendFeedback(Component.literal("[Craftify] Usa /craftify send on para reanudar.")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            source.sendFeedback(Component.literal("[Craftify] Envío de paquetes reanudado.")
                    .withStyle(ChatFormatting.GREEN));
            source.sendFeedback(Component.literal("[Craftify] El estado actual de Spotify se enviará en la próxima lectura.")
                    .withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    private static int executeSpotify(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        SpotifyProcess.Os os = SpotifyProcess.currentOs();

        source.sendFeedback(Component.literal("[Craftify] Sistema operativo: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(os.name()).withStyle(ChatFormatting.YELLOW)));

        if (os == SpotifyProcess.Os.UNSUPPORTED) {
            source.sendError(Component.literal("[Craftify] Este sistema operativo no está soportado.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        boolean running = SpotifyProcess.isRunning(os);
        source.sendFeedback(Component.literal("[Craftify] Proceso de Spotify (" + os.executable() + "): ")
                .withStyle(ChatFormatting.GOLD)
                .append(running
                        ? Component.literal("corriendo").withStyle(ChatFormatting.GREEN)
                        : Component.literal("no encontrado").withStyle(ChatFormatting.RED)));

        if (running) {
            String title = SpotifyProcess.readTitle(os);
            if (title == null) {
                source.sendFeedback(Component.literal("[Craftify] Estado: ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("sin canción activa (no_track)").withStyle(ChatFormatting.YELLOW)));
                sendNoTrackHint(source, os);
            } else {
                source.sendFeedback(Component.literal("[Craftify] Estado: ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("reproduciendo (playing)").withStyle(ChatFormatting.GREEN)));
                source.sendFeedback(Component.literal("[Craftify] Título actual: ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(title).withStyle(ChatFormatting.WHITE)));
            }
        } else {
            source.sendFeedback(Component.literal("[Craftify] Estado: ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("Spotify cerrado (closed)").withStyle(ChatFormatting.RED)));
        }

        source.sendFeedback(Component.literal("[Craftify] Envío de paquetes: ")
                .withStyle(ChatFormatting.GOLD)
                .append(SpotifyTracker.isRunning()
                        ? (SpotifyTracker.isPaused()
                                ? Component.literal("pausado (usa /craftify send on)").withStyle(ChatFormatting.YELLOW)
                                : Component.literal("activo (detección en tiempo real de cambios de canción)").withStyle(ChatFormatting.GREEN))
                        : Component.literal("inactivo (solo envía dentro de un mundo)").withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    /** Pista de qué hacer cuando el título no se pudo leer, según el sistema operativo. */
    private static void sendNoTrackHint(FabricClientCommandSource source, SpotifyProcess.Os os) {
        Component hint = switch (os) {
            case MACOS -> Component.literal("[Craftify] Para leer el título: acepta el aviso \"controlar Spotify\" "
                    + "cuando aparezca (una vez), o concede Grabación de Pantalla.")
                    .withStyle(ChatFormatting.GRAY);
            case LINUX -> Component.literal("[Craftify] Sin título: instala playerctl (p. ej. sudo apt install playerctl) "
                    + "o xdotool, y reinicia el juego.")
                    .withStyle(ChatFormatting.GRAY);
            default -> null;
        };
        if (hint != null) {
            source.sendFeedback(hint);
        }
    }
}
