package fr.k0bus.creativemanager.commands.cm;

import fr.k0bus.creativemanager.CreativeManager;
import fr.k0bus.creativemanager.commands.Commands;
import fr.k0bus.k0buscore.utils.StringUtils;
import org.bukkit.command.CommandSender;

public class ReloadSubCommands extends Commands {

  public ReloadSubCommands(CreativeManager instance) {
    super(instance, "creativemanager.admin", false);
  }

  @Override
  protected void run(CommandSender sender, String[] args) {
    if (plugin.reloadConfigManager()) {
      sender.sendMessage(
          CreativeManager.getTag() + StringUtils.translateColor("&5Configuration reloaded !"));
    } else {
      sender.sendMessage(
          CreativeManager.getTag()
              + StringUtils.translateColor(
                  "&cConfiguration reload blocked; the previous settings remain active."));
    }
  }
}
