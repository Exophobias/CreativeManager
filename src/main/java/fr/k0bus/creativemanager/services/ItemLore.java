package fr.k0bus.creativemanager.services;

import fr.k0bus.creativemanager.CreativeManager;
import fr.k0bus.creativemanager.utils.CMUtils;
import fr.k0bus.creativemanager.utils.SpigotUtils;
import fr.k0bus.creativemanager.utils.TextUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class ItemLore {

  /**
   * Stamps the configured creative lore onto everything in the player's inventory.
   *
   * <p>Must be called from the main thread: it mutates live {@link ItemStack} mirrors.
   *
   * @param player the player to check.
   */
  public static void check(Player player) {
    List<String> lore = CreativeManager.getSettings().getLore();
    if (lore.isEmpty()) return;
    for (ItemStack content : player.getInventory().getContents()) {
      addLore(content, player, lore);
    }
  }

  private static void addLore(ItemStack itemStack, Player p, List<String> lore) {
    if (itemStack == null || itemStack.getType() == Material.AIR || p == null) return;
    ItemMeta meta = itemStack.getItemMeta();
    if (meta == null) return;

    List<String> tempLore = new ArrayList<>(lore.size());
    for (String line : lore) {
      tempLore.add(getFinalString(line, p, itemStack));
    }
    // Re-writing identical meta is the single most expensive thing this class can do, and on a
    // busy inventory it is almost always a no-op, so skip it when nothing would change.
    if (tempLore.equals(meta.getLore())) return;

    SpigotUtils.setItemMetaLore(meta, tempLore);
    itemStack.setItemMeta(meta);
  }

  private static String getFinalString(String string, Player player, ItemStack itemStack) {
    return CMUtils.parse(
        TextUtils.replacePlaceholders(
            string,
            Map.of(
                "PLAYER", player.getName(),
                "UUID", player.getUniqueId().toString(),
                "ITEM", itemStack.getType().name())));
  }
}
