package org.foranly.craftifyplugin.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.foranly.craftifyplugin.CraftifyPlugin;

/**
 * {@code /craftifyplugin reload} — reloads the plugin configuration and re-applies the
 * current Spotify states to all online players.
 */
public final class CraftifyCommand implements CommandExecutor {

    /** Permission required to run this command. */
    public static final String RELOAD_PERMISSION = "craftifyplugin.reload";

    private final CraftifyPlugin plugin;

    public CraftifyCommand(CraftifyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !"reload".equalsIgnoreCase(args[0])) {
            sender.sendMessage(Component.text("Usage: /craftifyplugin reload", NamedTextColor.GRAY));
            return true;
        }

        if (!sender.hasPermission(RELOAD_PERMISSION)) {
            sender.sendMessage(Component.text("You don't have permission to reload CraftifyPlugin.", NamedTextColor.RED));
            return true;
        }

        plugin.reloadPlugin();
        sender.sendMessage(Component.text("CraftifyPlugin reloaded.", NamedTextColor.GREEN));
        return true;
    }
}
