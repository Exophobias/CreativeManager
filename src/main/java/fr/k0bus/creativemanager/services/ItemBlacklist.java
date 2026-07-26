package fr.k0bus.creativemanager.services;

import fr.k0bus.creativemanager.CreativeManager;
import fr.k0bus.creativemanager.utils.CMUtils;
import fr.k0bus.creativemanager.utils.SearchUtils;
import fr.k0bus.k0buscore.utils.StringUtils;
import java.util.HashMap;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class ItemBlacklist {

  /**
   * Strips blacklisted items from the player's inventory.
   *
   * <p>Must be called from the main thread: it mutates live {@link ItemStack} mirrors.
   *
   * @param player the player to check.
   */
  public static void check(Player player) {
    if (player.hasPermission("creativemanager.bypass.blacklist.get")) return;
    List<String> blacklist = CreativeManager.getSettings().getGetBL();
    // An empty whitelist still means "nothing is allowed", so only short-circuit in blacklist mode.
    if (blacklist.isEmpty() && !CreativeManager.getSettings().isWhitelist("get")) return;
    for (ItemStack content : player.getInventory().getContents()) {
      checkBlacklist(content, player, blacklist);
    }
  }

  public static void checkBlacklist(ItemStack itemStack, Player player, List<String> blacklist) {
    if (isBlackListed(itemStack, player, blacklist)) {
      itemStack.setAmount(0);
    }
  }

  private static boolean isBlackListed(ItemStack item, Player player, List<String> blacklist) {
    if (item == null || item.getType() == Material.AIR) {
      return false;
    }
    String itemName = item.getType().name().toLowerCase();
    if (player.hasPermission("creativemanager.bypass.blacklist.get." + itemName)) return false;
    if (SearchUtils.inList(blacklist, item) == CreativeManager.getSettings().isWhitelist("get")) {
      return false;
    }
    HashMap<String, String> replaceMap = new HashMap<>();
    replaceMap.put("{ITEM}", StringUtils.proper(item.getType().name()));
    CMUtils.sendMessage(player, "blacklist.get", replaceMap);
    return true;
  }
}
