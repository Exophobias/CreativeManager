package fr.k0bus.creativemanager.commands.cm;

import fr.k0bus.creativemanager.CreativeManager;
import fr.k0bus.creativemanager.commands.Commands;
import fr.k0bus.creativemanager.config.ConfigFiles;
import fr.k0bus.creativemanager.config.ConfigMigrator;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.conversations.Conversable;

public class InfosSubCommands extends Commands {

  public InfosSubCommands(CreativeManager instance) {
    super(instance, "creativemanager.admin", true);
  }

  @Override
  protected void run(CommandSender sender, String[] args) {
    if (sender instanceof Conversable) {
      sender.sendMessage(CreativeManager.getTag() + "§r");
      sender.sendMessage(
          CreativeManager.getTag()
              + "§§l§7CM DEBUG §8>>§r §6Send me this when asking me some help");
      sender.sendMessage(CreativeManager.getTag() + "§r");
      sender.sendMessage(
          CreativeManager.getTag()
              + "§§l§7CM DEBUG §8>>§r §6"
              + plugin.getDescription().getName()
              + " version §7:§b "
              + plugin.getDescription().getVersion());
      sender.sendMessage(
          CreativeManager.getTag()
              + "§§l§7CM DEBUG §8>>§r §6Server version §7:§b "
              + Bukkit.getVersion());
      sender.sendMessage(
          CreativeManager.getTag()
              + "§§l§7CM DEBUG §8>>§r §6MC version §7:§b "
              + Bukkit.getBukkitVersion());
      ConfigFiles.Batch status = plugin.getConfigStatus();
      if (status != null) {
        ConfigMigrator.Result main = status.config();
        String installed =
            main.compatible()
                ? "v" + main.loadedVersion()
                : main.sourceVersion() < 0 ? "unknown" : "v" + main.sourceVersion();
        sender.sendMessage(
            CreativeManager.getTag()
                + "§§l§7CM DEBUG §8>>§r §6Config schema §7:§b "
                + "installed "
                + installed
                + ", supported v"
                + ConfigMigrator.CURRENT_VERSION
                + ", state "
                + main.state().name().toLowerCase());
        if (!plugin.isLatestConfigAttemptActive()) {
          String blockedResource = status.firstBlockedResource();
          ConfigMigrator.Result blocked =
              blockedResource == null ? null : status.results().get(blockedResource);
          String blockedInstalled =
              blocked == null || blocked.sourceVersion() < 0
                  ? "unknown"
                  : "v" + blocked.sourceVersion();
          String blockedState = blocked == null ? "error" : blocked.state().name().toLowerCase();
          sender.sendMessage(
              CreativeManager.getTag()
                  + "§§l§7CM DEBUG §8>>§r §6Blocked resource §7:§e "
                  + (blockedResource == null ? "configuration" : blockedResource)
                  + ", installed "
                  + blockedInstalled
                  + ", state "
                  + blockedState
                  + "; previous known-good runtime retained");
        }
      }
      sender.sendMessage(CreativeManager.getTag() + "§r");
    }
  }
}
