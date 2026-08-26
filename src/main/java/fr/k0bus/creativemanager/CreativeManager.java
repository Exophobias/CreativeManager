package fr.k0bus.creativemanager;

import fr.k0bus.creativemanager.commands.Commands;
import fr.k0bus.creativemanager.commands.cm.CreativeManagerCommandTab;
import fr.k0bus.creativemanager.commands.cm.CreativeManagerCommands;
import fr.k0bus.creativemanager.config.ConfigFiles;
import fr.k0bus.creativemanager.config.ConfigMigrator;
import fr.k0bus.creativemanager.config.GenerationSwap;
import fr.k0bus.creativemanager.config.Messages;
import fr.k0bus.creativemanager.event.*;
import fr.k0bus.creativemanager.event.plugin.ChestShop;
import fr.k0bus.creativemanager.event.plugin.ItemsAdderListener;
import fr.k0bus.creativemanager.event.plugin.SlimeFun;
import fr.k0bus.creativemanager.log.DataManager;
import fr.k0bus.creativemanager.settings.Settings;
import fr.k0bus.creativemanager.task.SaveTask;
import fr.k0bus.k0buscore.K0busCore;
import fr.k0bus.k0buscore.config.Lang;
import fr.k0bus.k0buscore.updater.UpdateChecker;
import fr.k0bus.k0buscore.utils.StringUtils;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Set;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.PluginManager;

public class CreativeManager extends K0busCore {

  public static volatile String TAG = StringUtils.translateColor("&r[&cCreativeManager&r] ");
  public static final String TAG_INV = "&l&4CM &r> ";
  private static volatile ConfigGeneration activeGeneration;
  private volatile ConfigFiles.Batch configStatus;
  private volatile ConfigFiles.Batch activeConfig;
  private DataManager dataManager;
  private int saveTask = -1;
  private static final HashMap<String, Set<Material>> tagMap = new HashMap<>();
  private static UpdateChecker updateChecker;

  @Override
  public void onEnable() {
    ConfigGeneration initialGeneration = prepareConfigGeneration();
    if (initialGeneration == null) {
      logBlockedConfig();
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    publishConfigGeneration(initialGeneration);
    super.onEnable();
    getLog().log("&9=============================================================");
    updateChecker = new UpdateChecker(this, 75097);
    if (updateChecker.isUpToDate()) {
      getLog()
          .log(
              "&2" + this.getDescription().getName() + " &av" + this.getDescription().getVersion());
    } else {
      getLog()
          .log(
              "&2"
                  + this.getDescription().getName()
                  + " &cv"
                  + this.getDescription().getVersion()
                  + " (Update "
                  + updateChecker.getVersion()
                  + " available on SpigotMC)");
    }
    new Metrics(this, 11481);
    getLog().log("&9=============================================================");
    getLog().log("&2Created by K0bus for AkuraGaming");
    getLog().log("&9=============================================================");
    logConfigStatus();
    this.registerEvent(this.getServer().getPluginManager());
    getLog().log("&2Listener registered");
    this.registerCommand();
    getLog().log("&2Commands registered");
    this.registerPermissions();
    this.loadLog();
    this.loadTags();
    this.saveTask = SaveTask.run(this, getSettings());
    if (getSettings().getConfiguration().getBoolean("stop-inventory-save")) {
      getLog()
          .log(
              "&cWarning : &4'stop-inventory-save' set on 'true' "
                  + "then all features about inventory as been disabled !");
    }
    getLog().log("&9=============================================================");
  }

  /** Prepare-then-swap reload. Failure retains the entire previous runtime generation. */
  public boolean reloadConfigManager() {
    ConfigGeneration replacement = prepareConfigGeneration();
    if (replacement == null) {
      logBlockedConfig();
      return false;
    }

    GenerationSwap.Outcome swap =
        GenerationSwap.activate(
            replacement,
            saveTask,
            candidate -> dataManager == null ? -1 : SaveTask.run(this, candidate.settings()),
            this::publishConfigGeneration,
            this::cancelSaveTask);
    if (!swap.activated()) {
      getLogger().severe("CreativeManager config reload could not prepare its runtime generation");
      return false;
    }
    saveTask = swap.activeTaskId();
    if (!swap.oldTaskRetired()) {
      getLogger().warning("CreativeManager retained an obsolete save schedule after config reload");
    }
    logConfigStatus();
    return true;
  }

  /**
   * Historical ABI entry point. Callers needing an outcome should use {@link
   * #reloadConfigManager()}.
   */
  @Deprecated
  public void loadConfigManager() {
    reloadConfigManager();
  }

  /** Compatibility entry point for integrations which called the old append-only updater. */
  @Deprecated
  public void updateConfig() {
    reloadConfigManager();
  }

  private ConfigGeneration prepareConfigGeneration() {
    ConfigFiles.Batch batch = ConfigFiles.prepare(this);
    configStatus = batch;
    if (!batch.compatible()) {
      return null;
    }
    ConfigMigrator.Result language = batch.selectedLanguageResult();
    if (language == null || language.prepared() == null) {
      return null;
    }
    Settings preparedSettings = Settings.from(this, batch.config().prepared().configuration());
    Messages preparedMessages =
        Messages.from(this, batch.selectedLanguage(), language.prepared().configuration());
    return new ConfigGeneration(batch, preparedSettings, preparedMessages);
  }

  private void publishConfigGeneration(ConfigGeneration generation) {
    activeGeneration = generation;
    configStatus = generation.batch();
    activeConfig = generation.batch();
    TAG = generation.settings().getTag();
    antiSpamTick = generation.settings().getConfiguration().getInt("antispam-tick");
  }

  private void logBlockedConfig() {
    ConfigFiles.Batch status = configStatus;
    String resource = status == null ? "configuration" : status.firstBlockedResource();
    if (resource == null) {
      resource = "configuration";
    }
    ConfigMigrator.Result result = resource == null ? null : status.results().get(resource);
    String detail = result == null ? "configuration preparation failed" : result.detail();
    String installed =
        result == null || result.sourceVersion() < 0 ? "unknown" : "v" + result.sourceVersion();
    String state = result == null ? "error" : result.state().name().toLowerCase();
    String active = activeConfig == null ? "none" : "v" + activeConfig.config().loadedVersion();
    getLogger()
        .severe(
            "CreativeManager v"
                + getDescription().getVersion()
                + " startup/reload blocked for "
                + resource
                + " (supported schema v"
                + ConfigMigrator.CURRENT_VERSION
                + ", installed "
                + installed
                + ", active "
                + active
                + ", state "
                + state
                + "): "
                + detail);
  }

  private void logConfigStatus() {
    ConfigFiles.Batch status = activeConfig;
    if (status == null) {
      return;
    }
    for (var entry : status.results().entrySet()) {
      ConfigMigrator.Result result = entry.getValue();
      String backup = result.backup() == null ? "" : ", backup=" + result.backup().getFileName();
      String source = result.sourceVersion() < 0 ? "unknown" : "v" + result.sourceVersion();
      getLog()
          .log(
              "&2"
                  + "CreativeManager v"
                  + getDescription().getVersion()
                  + ", "
                  + entry.getKey()
                  + " supported schema v"
                  + ConfigMigrator.CURRENT_VERSION
                  + ", source "
                  + source
                  + ", installed v"
                  + result.loadedVersion()
                  + ", state "
                  + result.state().name().toLowerCase()
                  + backup);
    }
  }

  private void cancelSaveTask(int taskId) {
    if (taskId >= 0
        && (Bukkit.getScheduler().isCurrentlyRunning(taskId)
            || Bukkit.getScheduler().isQueued(taskId))) {
      Bukkit.getScheduler().cancelTask(taskId);
    }
  }

  private record ConfigGeneration(ConfigFiles.Batch batch, Settings settings, Messages messages) {}

  private void registerEvent(PluginManager pm) {
    pm.registerEvents(new PlayerBuild(this), this);
    pm.registerEvents(new PlayerBreak(this), this);
    pm.registerEvents(new PlayerInteract(this), this);
    pm.registerEvents(new PlayerInteractEntity(this), this);
    pm.registerEvents(new PlayerInteractAtEntity(this), this);
    pm.registerEvents(new PlayerDrop(this), this);
    pm.registerEvents(new PlayerGamemodeChange(this), this);
    pm.registerEvents(new PlayerQuit(this), this);
    pm.registerEvents(new PlayerLogin(this), this);
    pm.registerEvents(new PistonEvent(this), this);
    pm.registerEvents(new MonsterSpawnEvent(this), this);
    pm.registerEvents(new ProjectileThrow(this), this);
    pm.registerEvents(new InventoryOpen(this), this);
    pm.registerEvents(new PlayerPreCommand(this), this);
    pm.registerEvents(new ExplodeEvent(this), this);
    pm.registerEvents(new PlayerDeath(), this);
    pm.registerEvents(new FlowEvent(this), this);
    pm.registerEvents(new BlockEvent(this), this);
    pm.registerEvents(new WorldEvent(this), this);
    /*  Add event checked for old version */
    try {
      ItemMeta.class.getMethod("getPersistentDataContainer", (Class<?>[]) null);
      pm.registerEvents(new InventoryMove(this, true), this);
    } catch (NoSuchMethodException | SecurityException e) {
      getLog().log("NBT Protection disabled on your Minecraft version");
      pm.registerEvents(new InventoryMove(this, false), this);
    }
    try {
      ProjectileHitEvent.class.getMethod("getHitEntity", (Class<?>[]) null);
      pm.registerEvents(new PlayerHitEvent(true, this), this);
    } catch (NoSuchMethodException | SecurityException e) {
      getLog().log("PvP / PvE Protection can't protect from projectile on this Spigot version !");
      pm.registerEvents(new PlayerHitEvent(false, this), this);
    }
    try {
      Class.forName("org.bukkit.event.entity.EntityPickupItemEvent");
      pm.registerEvents(new PlayerPickup(), this);
    } catch (ClassNotFoundException e) {
      getLog().log("Player pickup protection not enabled on this Spigot version !");
    }
    /* Add plugin event */
    if (getServer().getPluginManager().isPluginEnabled("Slimefun"))
      pm.registerEvents(new SlimeFun(this), this);
    if (getServer().getPluginManager().isPluginEnabled("ChestShop"))
      pm.registerEvents(new ChestShop(this), this);
    if (getServer().getPluginManager().isPluginEnabled("ItemsAdder"))
      pm.registerEvents(new ItemsAdderListener(this), this);
  }

  private void registerCommand() {
    PluginCommand mainCommand = this.getCommand("cm");
    if (mainCommand != null) {
      mainCommand.setExecutor(new CreativeManagerCommands(this));
      mainCommand.setTabCompleter(
          new CreativeManagerCommandTab((Commands) mainCommand.getExecutor()));
    }
  }

  private void registerPermissions() {
    PluginManager pm = getServer().getPluginManager();
    int n = 0;
    for (EntityType entityType : EntityType.values()) {
      registerPerm("creativemanager.bypass.entity." + entityType.name(), pm);
      n++;
    }
    registerPerm("creativemanager.bypass.deathdrop", pm);
    getLog().log("&2Entities permissions registered ! &7[" + n + "]");

    /* Add plugin permissions */
    if (getServer().getPluginManager().isPluginEnabled("ChestShop")) {
      registerPerm("creativemanager.bypass.chestshop", pm);
      getLog().log("&2ChestShop permissions registered !");
    }
    if (getServer().getPluginManager().isPluginEnabled("ItemsAdder")) {
      registerPerm("creativemanager.bypass.itemsadder.furnituresplace", pm);
      registerPerm("creativemanager.bypass.itemsadder.blockplace", pm);
      registerPerm("creativemanager.bypass.itemsadder.blockbreak", pm);
      registerPerm("creativemanager.bypass.itemsadder.blockinteract", pm);
      registerPerm("creativemanager.bypass.itemsadder.furnituresinteract", pm);
      registerPerm("creativemanager.bypass.itemsadder.killentity", pm);
      getLog().log("&2ItemsAdder permissions registered !");
    }
    if (getServer().getPluginManager().isPluginEnabled("Slimefun")) {
      registerPerm("creativemanager.bypass.slimefun", pm);
      getLog().log("&2Slimefun permissions registered !");
    }
  }

  private void registerPerm(String permission, PluginManager pm) {
    if (!pm.getPermissions().contains(new Permission(permission))) {
      try {
        pm.addPermission(new Permission(permission));
      } catch (IllegalArgumentException ignored) {
      }
    }
  }

  private void loadTags() {
    try {
      Field[] fieldlist = Tag.class.getDeclaredFields();
      for (Field fld : fieldlist) {
        try {
          if (Tag.class.isAssignableFrom(fld.getType())) {
            Type genericType = fld.getGenericType();
            if (genericType instanceof ParameterizedType) {
              ParameterizedType aType = (ParameterizedType) genericType;
              Type[] fieldArgTypes = aType.getActualTypeArguments();
              if (fieldArgTypes.length > 0 && fieldArgTypes[0] == Material.class) {
                @SuppressWarnings("unchecked")
                Tag<Material> tag = (Tag<Material>) fld.get(null);
                if (tag != null) {
                  tagMap.put(fld.getName(), tag.getValues());
                }
              }
            }
          }
        } catch (Exception ignored) {
        }
      }
      getLog().log("&2Tag loaded from Spigot ! &7[" + tagMap.size() + "]");
    } catch (NoClassDefFoundError e) {
      getLog().log("&cThis minecraft version could not use the TAG system.");
    }
  }

  private void loadLog() {
    dataManager = new DataManager("data", this);
    getLog()
        .log("&2Log loaded from database ! &7[" + dataManager.getBlockLogHashMap().size() + "]");
  }

  public static Settings getSettings() {
    ConfigGeneration generation = activeGeneration;
    if (generation == null) {
      throw new IllegalStateException("CreativeManager configuration is not active");
    }
    return generation.settings();
  }

  /** Returns the tag from the same atomic generation as settings and messages. */
  public static String getTag() {
    ConfigGeneration generation = activeGeneration;
    return generation == null ? TAG : generation.settings().getTag();
  }

  public static Lang getLang() {
    return getMessages();
  }

  public static Messages getMessages() {
    ConfigGeneration generation = activeGeneration;
    if (generation == null) {
      throw new IllegalStateException("CreativeManager configuration is not active");
    }
    return generation.messages();
  }

  public ConfigFiles.Batch getConfigStatus() {
    return configStatus;
  }

  public boolean isLatestConfigAttemptActive() {
    return configStatus != null && configStatus == activeConfig;
  }

  public static HashMap<String, Set<Material>> getTagMap() {
    return tagMap;
  }

  public DataManager getDataManager() {
    return dataManager;
  }

  public static UpdateChecker getUpdateChecker() {
    return updateChecker;
  }

  @Override
  public void onDisable() {
    cancelSaveTask(saveTask);
    if (dataManager != null) dataManager.save();
  }
}
