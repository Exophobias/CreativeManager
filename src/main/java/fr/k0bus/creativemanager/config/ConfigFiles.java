package fr.k0bus.creativemanager.config;

import fr.k0bus.creativemanager.CreativeManager;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Coordinates CreativeManager's independently versioned operator files. */
public final class ConfigFiles {
  public static final String MAIN_CONFIG = "config.yml";

  private static final List<String> HISTORICALLY_INSTALLED_LANGUAGES =
      List.of("en_EN", "es_ES", "fr_FR", "it_IT", "ru_RU");
  private static final List<String> OTHER_BUNDLED_LANGUAGES = List.of("ba_BA", "hu_HU", "zh_CN");
  private static final List<String> ALL_BUNDLED_LANGUAGES;

  static {
    List<String> names = new ArrayList<>(HISTORICALLY_INSTALLED_LANGUAGES);
    names.addAll(OTHER_BUNDLED_LANGUAGES);
    ALL_BUNDLED_LANGUAGES = List.copyOf(names);
  }

  /** One prepare pass. Map iteration order is migration order and is stable for diagnostics. */
  public record Batch(
      ConfigMigrator.Result config,
      Map<String, ConfigMigrator.Result> results,
      String selectedLanguage) {
    public Batch {
      Objects.requireNonNull(config, "config");
      results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
      selectedLanguage = selectedLanguage == null ? "unknown" : selectedLanguage;
    }

    public boolean compatible() {
      return results.values().stream().allMatch(ConfigMigrator.Result::compatible);
    }

    public String firstBlockedResource() {
      return results.entrySet().stream()
          .filter(entry -> !entry.getValue().compatible())
          .map(Map.Entry::getKey)
          .findFirst()
          .orElse(null);
    }

    public ConfigMigrator.Result selectedLanguageResult() {
      return results.get(languageResource(selectedLanguage));
    }
  }

  private ConfigFiles() {}

  /**
   * Migrates the main config first, then every language file this fork historically installed. Less
   * common bundled languages are migrated when installed or selected. Installed custom language
   * files use the English catalog as their template while preserving custom translations.
   */
  public static Batch prepare(CreativeManager plugin) {
    Objects.requireNonNull(plugin, "plugin");
    LinkedHashMap<String, ConfigMigrator.Result> results = new LinkedHashMap<>();

    ConfigMigrator.Result main = prepareOne(plugin, MAIN_CONFIG, ConfigMigrator.Kind.CONFIG, true);
    results.put(MAIN_CONFIG, main);
    if (!main.compatible()) {
      return new Batch(main, results, null);
    }

    String selected = main.prepared().configuration().getString("lang", "");
    List<String> order = new ArrayList<>();
    boolean selectedIsBundled = ALL_BUNDLED_LANGUAGES.contains(selected);
    if (selectedIsBundled) {
      order.add(selected);
    } else {
      String customResource = languageResource(selected);
      Path customTarget = installedPath(plugin, customResource);
      ConfigMigrator.Result customResult =
          Files.exists(customTarget)
              ? prepareOneWithTemplate(
                  plugin,
                  customResource,
                  languageResource("en_EN"),
                  ConfigMigrator.Kind.LANGUAGE,
                  false)
              : new ConfigMigrator.Result(
                  ConfigMigrator.State.INVALID,
                  -1,
                  null,
                  "selected custom language file " + customResource + " does not exist");
      results.put(customResource, customResult);
      if (!customResult.compatible()) {
        return new Batch(main, results, selected);
      }
    }
    for (String language : HISTORICALLY_INSTALLED_LANGUAGES) {
      if (!order.contains(language)) {
        order.add(language);
      }
    }
    for (String language : OTHER_BUNDLED_LANGUAGES) {
      Path target = installedPath(plugin, languageResource(language));
      if ((language.equals(selected) || Files.exists(target)) && !order.contains(language)) {
        order.add(language);
      }
    }

    for (String language : order) {
      String resource = languageResource(language);
      ConfigMigrator.Result result =
          prepareOne(plugin, resource, ConfigMigrator.Kind.LANGUAGE, true);
      results.put(resource, result);
      if (!result.compatible()) {
        break;
      }
    }
    return new Batch(main, results, selected);
  }

  public static Path installedPath(CreativeManager plugin, String resourceName) {
    return plugin
        .getDataFolder()
        .toPath()
        .resolve(resourceName.replace('/', File.separatorChar))
        .toAbsolutePath()
        .normalize();
  }

  public static String languageResource(String language) {
    return "lang/" + language + ".yml";
  }

  static boolean isBundledLanguage(String language) {
    return ALL_BUNDLED_LANGUAGES.contains(language);
  }

  private static ConfigMigrator.Result prepareOne(
      CreativeManager plugin,
      String resourceName,
      ConfigMigrator.Kind kind,
      boolean installWhenMissing) {
    return prepareOneWithTemplate(plugin, resourceName, resourceName, kind, installWhenMissing);
  }

  private static ConfigMigrator.Result prepareOneWithTemplate(
      CreativeManager plugin,
      String resourceName,
      String bundledResourceName,
      ConfigMigrator.Kind kind,
      boolean installWhenMissing) {
    String bundled;
    try {
      bundled = readBundledUtf8(plugin, bundledResourceName);
    } catch (IOException failure) {
      return new ConfigMigrator.Result(
          ConfigMigrator.State.ERROR,
          -1,
          null,
          "the plugin jar does not contain a valid bundled " + bundledResourceName);
    }

    Path target = installedPath(plugin, resourceName);
    return ConfigMigrator.prepare(target, bundled, kind, installWhenMissing);
  }

  private static String readBundledUtf8(CreativeManager plugin, String resourceName)
      throws IOException {
    try (InputStream stream = plugin.getResource(resourceName)) {
      if (stream == null) {
        throw new IOException("missing resource");
      }
      return decodeUtf8(stream.readAllBytes());
    }
  }

  private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
    CharBuffer decoded =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes));
    return decoded.toString();
  }
}
