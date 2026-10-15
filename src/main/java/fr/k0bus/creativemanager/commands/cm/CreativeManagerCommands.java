package fr.k0bus.creativemanager.commands.cm;

import fr.k0bus.creativemanager.CreativeManager;
import fr.k0bus.creativemanager.commands.Commands;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/** Main command class. */
public class CreativeManagerCommands extends Commands {

  public CreativeManagerCommands(CreativeManager instance) {
    super(instance);
    registerCommands("reload", new ReloadSubCommands(getPlugin()));
    registerCommands("settings", new SettingsSubCommands(getPlugin()));
    registerCommands("infos", new InfosSubCommands(getPlugin()));
    registerCommands("items", new ItemsSubCommands(getPlugin()));

  }
  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
      help(sender, label);
      return true;
    }
    if (!getSubCommands().containsKey(args[0].toLowerCase(java.util.Locale.ROOT))) {
      sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
      help(sender, label);
      return true;
    }
    Commands selected = getSubCommands().get(args[0].toLowerCase(java.util.Locale.ROOT));
    if (selected.permissions != null && !sender.hasPermission(selected.permissions)) {
      sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
      return true;
    }
    String[] normalized = args.clone();
    normalized[0] = args[0].toLowerCase(java.util.Locale.ROOT);
    return super.onCommand(sender, command, label, normalized);
  }

  public void help(CommandSender sender, String label) {
    List<String> entries = new ArrayList<>();
    getSubCommands().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
        .filter(entry -> entry.getValue().permissions == null
            || sender.hasPermission(entry.getValue().permissions))
        .forEach(entry -> entries.add(entry.getKey()));
    if (entries.isEmpty()) {
      sender.sendMessage(ChatColor.RED + "You do not have permission to use these commands.");
      return;
    }
    sender.sendMessage(ChatColor.DARK_GRAY + "  ---------- " + ChatColor.GREEN
        + "CreativeManager Staff" + ChatColor.DARK_GRAY + " ----------");
    for (String entry : entries) {
      String description = switch (entry) {
        case "reload" -> "reload operator configuration";
        case "settings" -> "open protection settings";
        case "infos" -> "inspect creative protection settings";
        default -> "inspect the held item's creative tags";
      };
      sender.sendMessage(ChatColor.GREEN + "/" + label + " " + entry
          + ChatColor.DARK_GRAY + " - " + ChatColor.GRAY + description);
    }
    sender.sendMessage(ChatColor.DARK_GRAY + "  ----------------------------------------");
  }
}
