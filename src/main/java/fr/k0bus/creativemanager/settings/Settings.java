package fr.k0bus.creativemanager.settings;

import fr.k0bus.creativemanager.CreativeManager;
import fr.k0bus.k0buscore.config.Configuration;
import fr.k0bus.k0buscore.utils.StringUtils;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/** Settings class. */
public class Settings extends Configuration {

  /**
   * Values read on every event are cached here so that hot paths do not walk the YAML tree. The
   * cache is rebuilt whenever a new Settings instance is created, which is what {@code /cm reload}
   * does, and kept in sync by {@link #setProtection(Protections, boolean)}.
   */
  private final Map<Protections, Boolean> protectionCache = new EnumMap<>(Protections.class);

  private final Map<String, Boolean> whitelistModeCache = new HashMap<>();
  private boolean sendPlayerMessages;

  /**
   * Instantiates a new Settings.
   *
   * @param instance the instance
   */
  public Settings(CreativeManager instance) {
    super("config.yml", instance);
    cacheHotValues();
  }

  private void cacheHotValues() {
    for (Protections protection : Protections.values()) {
      protectionCache.put(
          protection, getConfiguration().getBoolean("protections." + protection.getName()));
    }
    ConfigurationSection modes = getConfiguration().getConfigurationSection("list.mode");
    if (modes != null) {
      for (String key : modes.getKeys(false)) {
        whitelistModeCache.put(key, "whitelist".equalsIgnoreCase(modes.getString(key)));
      }
    }
    sendPlayerMessages = getConfiguration().getBoolean("send-player-messages");
  }

  /**
   * Gets protection.
   *
   * @param protections the protections.
   * @return the protection.
   */
  public boolean getProtection(Protections protections) {
    Boolean cached = protectionCache.get(protections);
    if (cached != null) {
      return cached;
    }
    return getConfiguration().getBoolean("protections." + protections.getName());
  }

  /**
   * Gets protection.
   *
   * @param protections the protections.
   * @param value the protections.
   */
  public void setProtection(Protections protections, boolean value) {
    getConfiguration().set("protections." + protections.getName(), value);
    protectionCache.put(protections, value);
  }

  /**
   * Whether the named list is in whitelist mode. Anything that is not explicitly {@code whitelist}
   * is treated as a blacklist, which is what the previous inline checks did.
   *
   * @param listName the list name, e.g. {@code place} or {@code get}.
   * @return true when the list is a whitelist.
   */
  public boolean isWhitelist(String listName) {
    return whitelistModeCache.getOrDefault(listName, false);
  }

  /**
   * Whether denial messages should be sent to players.
   *
   * @return true when messages are enabled.
   */
  public boolean sendPlayerMessages() {
    return sendPlayerMessages;
  }

  /**
   * Creative inv enable boolean.
   *
   * @return True if yes, otherwise false.
   */
  public boolean creativeInvEnable() {
    return getConfiguration().getBoolean("inventory.creative");
  }

  /**
   * Adventure inv enable boolean.
   *
   * @return True if yes, otherwise false.
   */
  public boolean adventureInvEnable() {
    return getConfiguration().getBoolean("inventory.adventure");
  }

  /**
   * Spectator inv enable boolean.
   *
   * @return True if yes, otherwise false.
   */
  public boolean spectatorInvEnable() {
    return getConfiguration().getBoolean("inventory.spectator");
  }

  /**
   * Gets place bl.
   *
   * @return the placed blocks.
   */
  public List<String> getPlaceBL() {
    return getConfiguration().getStringList("list.place");
  }

  /**
   * Gets use bl.
   *
   * @return the use blacklist.
   */
  public List<String> getUseBL() {
    return getConfiguration().getStringList("list.use");
  }

  /**
   * Gets use bl.
   *
   * @return the use blacklist.
   */
  public List<String> getUseBlockBL() {
    return getConfiguration().getStringList("list.useblock");
  }

  /**
   * Gets get bl.
   *
   * @return the get blacklist.
   */
  public List<String> getGetBL() {
    return getConfiguration().getStringList("list.get");
  }

  /**
   * Gets break bl.
   *
   * @return the break blacklist.
   */
  public List<String> getBreakBL() {
    return getConfiguration().getStringList("list.break");
  }

  /**
   * Gets command bl.
   *
   * @return the command blacklist.
   */
  public List<String> getCommandBL() {
    return getConfiguration().getStringList("list.commands");
  }

  /**
   * Gets command bl.
   *
   * @return the command blacklist.
   */
  public List<String> getNBTWhitelist() {
    return getConfiguration().getStringList("list.nbt-whitelist");
  }

  /**
   * Gets lore.
   *
   * @return the lore.
   */
  public List<String> getLore() {
    return getConfiguration().getStringList("creative-lore");
  }

  public String getLang() {
    return getConfiguration().getString("lang");
  }

  public String getTag() {
    return StringUtils.translateColor(getConfiguration().getString("tag"));
  }
}
