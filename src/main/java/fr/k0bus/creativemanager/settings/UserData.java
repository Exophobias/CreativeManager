package fr.k0bus.creativemanager.settings;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Inventory data is never repaired by silently replacing an unreadable file with empty YAML. */
public class UserData {
  private final Path file;
  private final Logger logger;
  private YamlConfiguration configuration = new YamlConfiguration();
  private byte[] loadedBytes;
  private boolean healthy;

  public UserData(Player player, JavaPlugin plugin) {
    this(plugin.getDataFolder().toPath().resolve("data").resolve(player.getUniqueId() + ".yml"),
        plugin.getLogger());
  }

  public UserData(Path file, Logger logger) {
    this.file = file;
    this.logger = logger;
    try {
      loadedBytes = readCurrent();
      if (loadedBytes != null) configuration = parse(loadedBytes);
      healthy = true;
    } catch (IOException | RuntimeException e) {
      logger.log(Level.SEVERE, "Inventory data could not be loaded; preserving " + file, e);
    }
  }

  public YamlConfiguration getConfiguration() {
    return configuration;
  }

  public void requireHealthy() throws IOException {
    if (!healthy) throw new IOException("Inventory data is unavailable: " + file);
  }

  public boolean isHealthy() {
    return healthy;
  }

  /** Publish a complete candidate only after the replacement file has been acknowledged. */
  public void saveInventory(GameMode mode, String content, String armor) throws IOException {
    requireHealthy();
    Path temp = null;
    try {
      if (!Arrays.equals(loadedBytes, readCurrent())) {
        throw new IOException("Inventory data changed since it was loaded: " + file);
      }
      YamlConfiguration candidate = parse(configuration.saveToString().getBytes(StandardCharsets.UTF_8));
      candidate.set(mode.name() + ".content", content);
      candidate.set(mode.name() + ".armor", armor);
      byte[] bytes = candidate.saveToString().getBytes(StandardCharsets.UTF_8);
      Files.createDirectories(file.toAbsolutePath().getParent());
      temp = Files.createTempFile(file.toAbsolutePath().getParent(), file.getFileName().toString(), ".tmp");
      try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      try {
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
      if (!Arrays.equals(bytes, readCurrent())) throw new IOException("Inventory write readback failed: " + file);
      configuration = candidate;
      loadedBytes = bytes;
    } catch (IOException | RuntimeException e) {
      healthy = false;
      throw new IOException("Could not save inventory data: " + file, e);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException e) {
          logger.log(Level.WARNING, "Could not remove inventory scratch file " + temp, e);
        }
      }
    }
  }

  private byte[] readCurrent() throws IOException {
    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return null;
    if (Files.isSymbolicLink(file)) throw new IOException("Inventory data is a symbolic link: " + file);
    return Files.readAllBytes(file);
  }

  private static YamlConfiguration parse(byte[] bytes) throws IOException {
    String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    try {
      Object root = new Yaml(new SafeConstructor(options)).load(text);
      if (root != null && !(root instanceof Map)) throw new IOException("Inventory YAML must be a mapping");
      YamlConfiguration candidate = new YamlConfiguration();
      candidate.loadFromString(text);
      for (GameMode mode : GameMode.values()) {
        String key = mode.name();
        if (root instanceof Map<?, ?> values && values.containsKey(key)
            && (!candidate.isConfigurationSection(key)
                || !candidate.isString(key + ".content") || !candidate.isString(key + ".armor"))) {
          throw new IOException("Incomplete inventory data for " + key);
        }
      }
      return candidate;
    } catch (InvalidConfigurationException | RuntimeException e) {
      throw new IOException("Invalid inventory YAML", e);
    }
  }
}
