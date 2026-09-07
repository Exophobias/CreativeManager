package fr.k0bus.creativemanager.manager;

import fr.k0bus.creativemanager.CreativeManager;
import fr.k0bus.creativemanager.settings.UserData;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/** Saves the current inventory before applying a completely decoded destination. */
public class InventoryManager {
  private static final java.util.Set<java.util.UUID> BLOCKED_SAVES =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final Player player;
  private final UserData data;
  private final FileConfiguration settings;
  private final Logger logger;
  private InventoryState previous;

  public InventoryManager(Player player, CreativeManager plugin) {
    this(player, new UserData(player, plugin), CreativeManager.getSettings().getConfiguration(),
        plugin.getLogger());
  }

  InventoryManager(Player player, UserData data, FileConfiguration settings, Logger logger) {
    this.player = player;
    this.data = data;
    this.settings = settings;
    this.logger = logger;
  }

  public boolean hasContent() {
    return !data.isHealthy() || !data.getConfiguration().getKeys(false).isEmpty();
  }

  private boolean disabled() {
    return settings.getBoolean("stop-inventory-save")
        || (settings.getBoolean("stop-inventory-save-perm")
            && player.hasPermission("creativemanager.bypass.inventory-save"));
  }

  public boolean loadInventory(GameMode mode) {
    if (disabled()) return true;
    previous = null;
    try {
      InventoryState destination = read(mode);
      previous = snapshot();
      apply(destination);
      log("Load", mode);
      return true;
    } catch (IOException | RuntimeException e) {
      restorePreviousInventory();
      logger.log(Level.SEVERE, "Could not load inventory for " + player.getUniqueId(), e);
      return false;
    }
  }

  public boolean saveInventory(GameMode mode) {
    if (BLOCKED_SAVES.contains(player.getUniqueId())) return false;
    if (disabled()) return true;
    try {
      save(mode, snapshot());
      return true;
    } catch (IOException | RuntimeException e) {
      logger.log(Level.SEVERE, "Could not save inventory for " + player.getUniqueId(), e);
      return false;
    }
  }

  public boolean switchInventory(GameMode from, GameMode to) {
    if (BLOCKED_SAVES.contains(player.getUniqueId())) return false;
    if (disabled() || from == to) return true;
    previous = null;
    try {
      // Decode both arrays, including armor, before saving or touching a live slot.
      InventoryState destination = read(to);
      InventoryState source = snapshot();
      save(from, source);
      previous = source;
      apply(destination);
      log("Load", to);
      return true;
    } catch (IOException | RuntimeException e) {
      restorePreviousInventory();
      logger.log(Level.SEVERE, "Inventory switch refused for " + player.getUniqueId(), e);
      return false;
    }
  }

  public void rememberCurrentInventory() {
    previous = snapshot();
  }

  public static void beginSession(java.util.UUID playerId) {
    BLOCKED_SAVES.remove(playerId);
  }

  /** A forced native mode is already applied at join; failed restoration must block quit saves. */
  public boolean restoreForcedGameMode(GameMode mode) {
    boolean success = hasContent() ? loadInventory(mode) : saveInventory(player.getGameMode());
    if (!success) {
      BLOCKED_SAVES.add(player.getUniqueId());
      player.kickPlayer("Your saved inventory could not be loaded. Please contact staff.");
    }
    return success;
  }

  /** Restores the source if another HIGHEST listener subsequently cancels the gamemode event. */
  public void restorePreviousInventory() {
    if (previous == null) return;
    try {
      apply(previous);
      previous = null;
    } catch (RuntimeException e) {
      logger.log(Level.SEVERE, "Could not restore inventory; saved source remains available for "
          + player.getUniqueId(), e);
    }
  }

  private void save(GameMode mode, InventoryState source) throws IOException {
    data.saveInventory(mode, itemStackArrayToBase64(source.contents), itemStackArrayToBase64(source.armor));
    log("Save", mode);
  }

  private InventoryState snapshot() {
    return new InventoryState(copy(player.getInventory().getContents()),
        copy(player.getInventory().getArmorContents()));
  }

  private static ItemStack[] copy(ItemStack[] source) {
    ItemStack[] result = source.clone();
    for (int i = 0; i < result.length; i++) {
      if (result[i] != null) result[i] = result[i].clone();
    }
    return result;
  }

  private InventoryState read(GameMode mode) throws IOException {
    data.requireHealthy();
    PlayerInventory inventory = player.getInventory();
    if (!data.getConfiguration().contains(mode.name())) {
      return new InventoryState(new ItemStack[inventory.getSize()], new ItemStack[4]);
    }
    return new InventoryState(
        itemStackArrayFromBase64(data.getConfiguration().getString(mode.name() + ".content"),
            inventory.getSize(), false),
        itemStackArrayFromBase64(data.getConfiguration().getString(mode.name() + ".armor"), 4, true));
  }

  private void apply(InventoryState state) {
    player.getInventory().setContents(state.contents);
    player.getInventory().setArmorContents(state.armor);
  }

  private void log(String action, GameMode mode) {
    if (settings.getBoolean("log")) {
      logger.info(action + " inventory of " + player.getUniqueId() + " for gamemode " + mode.name());
    }
  }

  public static String itemStackArrayToBase64(ItemStack[] items) throws IllegalStateException {
    try (ByteArrayOutputStream output = new ByteArrayOutputStream();
        BukkitObjectOutputStream stream = new BukkitObjectOutputStream(output)) {
      stream.writeInt(items.length);
      for (ItemStack item : items) stream.writeObject(item);
      stream.flush();
      return Base64.getMimeEncoder().encodeToString(output.toByteArray());
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException("Unable to save item stacks.", e);
    }
  }

  static ItemStack[] itemStackArrayFromBase64(String encoded, int maximum, boolean exact)
      throws IOException {
    if (encoded == null) throw new IOException("Missing inventory data");
    try (BukkitObjectInputStream stream = new BukkitObjectInputStream(
        new ByteArrayInputStream(Base64.getMimeDecoder().decode(encoded)))) {
      int size = stream.readInt();
      if (size < 0 || size > maximum || (exact && size != maximum)) {
        throw new IOException("Invalid inventory size " + size);
      }
      ItemStack[] items = new ItemStack[size];
      for (int i = 0; i < size; i++) {
        Object item = stream.readObject();
        if (item != null && !(item instanceof ItemStack)) throw new IOException("Invalid inventory item");
        items[i] = (ItemStack) item;
      }
      if (stream.read() != -1) throw new IOException("Trailing inventory data");
      return items;
    } catch (ClassNotFoundException | RuntimeException e) {
      throw new IOException("Unable to decode inventory.", e);
    }
  }

  private record InventoryState(ItemStack[] contents, ItemStack[] armor) {}
}
