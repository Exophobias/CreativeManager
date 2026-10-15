package fr.k0bus.creativemanager.commands.cm;

import fr.k0bus.creativemanager.commands.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

public class CreativeManagerCommandTab implements TabCompleter {

  final Commands commands;

  public CreativeManagerCommandTab(Commands commands) {
    this.commands = commands;
  }

  @Override
  public List<String> onTabComplete(
      @NotNull CommandSender sender,
      @NotNull Command command,
      @NotNull String alias,
      @NotNull String[] args) {
    List<String> cmdList = new ArrayList<>();
    if (args.length == 1)
      for (Map.Entry<String, Commands> entry : commands.getSubCommands().entrySet()) {
        if ((entry.getValue().permissions == null || sender.hasPermission(entry.getValue().permissions))
            && entry.getKey().startsWith(args[0].toLowerCase(java.util.Locale.ROOT)))
          cmdList.add(entry.getKey());
      }
    if (args.length == 1 && "help".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) cmdList.add("help");
    return cmdList;
  }
}
