package fr.k0bus.creativemanager.task;

import fr.k0bus.creativemanager.CreativeManager;
import fr.k0bus.creativemanager.settings.Settings;
import org.bukkit.Bukkit;

/** Save task class. */
public class SaveTask {
  /** Historical ABI overload; new lifecycle code passes its prepared settings explicitly. */
  @Deprecated
  public static int run(CreativeManager plugin) {
    return run(plugin, CreativeManager.getSettings());
  }

  /**
   * Run interval.
   *
   * @param plugin the plugin.
   * @param settings the prepared configuration generation
   * @return the task id, or {@code -1} when periodic saves are disabled
   */
  public static int run(CreativeManager plugin, Settings settings) {
    int interval = settings.getConfiguration().getInt("save-interval");
    if (interval > 0) {
      int taskId =
          Bukkit.getScheduler()
              .scheduleSyncRepeatingTask(
                  plugin, () -> plugin.getDataManager().saveAsync(), 0L, interval * 20L);
      if (taskId < 0) {
        throw new IllegalStateException("save schedule was rejected");
      }
      return taskId;
    }
    return -1;
  }
}
