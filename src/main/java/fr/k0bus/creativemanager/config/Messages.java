package fr.k0bus.creativemanager.config;

import fr.k0bus.creativemanager.CreativeManager;
import fr.k0bus.k0buscore.config.Lang;
import fr.k0bus.k0buscore.utils.StringUtils;
import java.io.File;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;

/** Exact-generation language view which retains CreativeManager's historical {@link Lang} ABI. */
public final class Messages extends Lang {
  private static final ThreadLocal<YamlConfiguration> CONSTRUCTION = new ThreadLocal<>();

  private final String language;
  private YamlConfiguration configuration;

  public static Messages from(
      CreativeManager plugin, String language, YamlConfiguration configuration) {
    Objects.requireNonNull(plugin, "plugin");
    Objects.requireNonNull(language, "language");
    Objects.requireNonNull(configuration, "configuration");
    if (CONSTRUCTION.get() != null) {
      throw new IllegalStateException("nested language snapshot construction is not supported");
    }
    CONSTRUCTION.set(configuration);
    try {
      return new Messages(plugin, language);
    } finally {
      CONSTRUCTION.remove();
    }
  }

  private Messages(CreativeManager plugin, String language) {
    // Configuration's constructor calls loadConfig virtually. Our override prevents a second disk
    // read; Lang then sees the exact prepared tree through the construction context.
    super(ConfigFiles.isBundledLanguage(language) ? language : "en_EN", plugin);
    this.language = language;
    this.configuration = Objects.requireNonNull(CONSTRUCTION.get(), "prepared language");
  }

  @Override
  public void loadConfig() {
    // The strict lifecycle already read, parsed, and validated this exact generation.
  }

  @Override
  public YamlConfiguration getConfiguration() {
    YamlConfiguration active = configuration;
    return active == null
        ? Objects.requireNonNull(CONSTRUCTION.get(), "prepared language")
        : active;
  }

  @Override
  public File getFile() {
    return ConfigFiles.installedPath(
            (CreativeManager) getPlugin(), ConfigFiles.languageResource(language))
        .toFile();
  }

  @Override
  public void save() {
    throw new UnsupportedOperationException(
        "managed language snapshots can only be replaced through CreativeManager reload");
  }

  public String getString(String path) {
    String value = configuration.getString(path);
    return value == null ? "" : StringUtils.translateColor(value);
  }

  public String getString(String path, Map<String, String> replacements) {
    String value = configuration.getString(path);
    if (value == null) {
      return "";
    }
    for (Map.Entry<String, String> replacement : replacements.entrySet()) {
      value = value.replace(replacement.getKey(), replacement.getValue());
    }
    return StringUtils.translateColor(value);
  }
}
