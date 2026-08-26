package fr.k0bus.creativemanager.config;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.AnchorNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/** Strict, template-first migration for CreativeManager's operator-owned YAML. */
public final class ConfigMigrator {
  public static final String VERSION_KEY = "config-version";
  public static final int CURRENT_VERSION = 1;

  /** Each bundled operator file owns an independent schema chain. */
  public enum Kind {
    CONFIG,
    LANGUAGE
  }

  @FunctionalInterface
  interface ConfigWriter {
    void write(Path target, String contents, byte[] expectedCurrent) throws IOException;
  }

  public enum State {
    CREATED,
    CURRENT,
    UPGRADED,
    INVALID,
    FUTURE,
    ERROR
  }

  /** A parsed runtime view derived from the exact bytes accepted by the migrator. */
  public record Prepared(YamlConfiguration configuration) {
    public Prepared {
      Objects.requireNonNull(configuration, "configuration");
    }
  }

  /** Sanitized migration outcome used by startup and the config status command. */
  public record Result(
      State state,
      int sourceVersion,
      Path backup,
      String detail,
      Prepared prepared,
      byte[] loadedBytes) {
    public Result(State state, int sourceVersion, Path backup, String detail) {
      this(state, sourceVersion, backup, detail, null, null);
    }

    public Result {
      loadedBytes = loadedBytes == null ? null : loadedBytes.clone();
    }

    @Override
    public byte[] loadedBytes() {
      return loadedBytes == null ? null : loadedBytes.clone();
    }

    public boolean compatible() {
      return state == State.CREATED || state == State.CURRENT || state == State.UPGRADED;
    }

    public int loadedVersion() {
      return compatible() ? CURRENT_VERSION : sourceVersion;
    }
  }

  private record Version(boolean valid, int value, String issue) {
    private Version(boolean valid, int value) {
      this(valid, value, null);
    }
  }

  static final class FileContentChangedException extends IOException {
    private static final long serialVersionUID = 1L;

    private FileContentChangedException() {
      super("target changed while replacement was prepared");
    }
  }

  private ConfigMigrator() {}

  public static Result upgrade(Path configFile, String bundledYaml, Kind kind) {
    return prepare(configFile, bundledYaml, kind, false, ConfigMigrator::writeUtf8AtomicRequired);
  }

  static Result upgrade(Path configFile, String bundledYaml, Kind kind, ConfigWriter writer) {
    return prepare(configFile, bundledYaml, kind, false, writer);
  }

  static Result prepare(
      Path configFile, String bundledYaml, Kind kind, boolean installWhenMissing) {
    return prepare(
        configFile, bundledYaml, kind, installWhenMissing, ConfigMigrator::writeUtf8AtomicRequired);
  }

  static Result prepare(
      Path configFile,
      String bundledYaml,
      Kind kind,
      boolean installWhenMissing,
      ConfigWriter writer) {
    Objects.requireNonNull(configFile, "configFile");
    Objects.requireNonNull(bundledYaml, "bundledYaml");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(writer, "writer");

    final YamlConfiguration defaults;
    try {
      defaults = parse(bundledYaml);
    } catch (InvalidConfigurationException failure) {
      return new Result(
          State.ERROR, -1, null, "the plugin jar contains an invalid default configuration file");
    }
    Version bundledVersion = readVersion(bundledYaml);
    if (!bundledVersion.valid()
        || bundledVersion.value() != CURRENT_VERSION
        || validationIssue(defaults, defaults, kind) != null) {
      return new Result(
          State.ERROR, -1, null, "the plugin jar contains invalid configuration defaults");
    }

    if (!Files.exists(configFile)) {
      if (!installWhenMissing) {
        return new Result(State.ERROR, -1, null, "configuration file could not be read");
      }
      byte[] bundledBytes = bundledYaml.getBytes(StandardCharsets.UTF_8);
      try {
        writer.write(configFile, bundledYaml, null);
        Prepared promoted = readPublished(configFile, bundledBytes, defaults, kind);
        return new Result(
            State.CREATED,
            CURRENT_VERSION,
            null,
            "created schema v" + CURRENT_VERSION,
            promoted,
            bundledBytes);
      } catch (FileContentChangedException failure) {
        return new Result(
            State.ERROR,
            -1,
            null,
            "configuration file appeared while the bundled file was being installed; retry");
      } catch (AtomicMoveNotSupportedException failure) {
        return new Result(
            State.ERROR,
            -1,
            null,
            "the filesystem cannot atomically install the bundled configuration file");
      } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
        return new Result(
            State.ERROR,
            -1,
            null,
            "the bundled configuration file could not be installed and validated safely");
      }
    }

    final byte[] installedBytes;
    try {
      installedBytes = Files.readAllBytes(configFile);
    } catch (IOException failure) {
      return new Result(State.ERROR, -1, null, "configuration file could not be read");
    }

    final String installedText;
    try {
      installedText = decodeUtf8(installedBytes);
    } catch (CharacterCodingException failure) {
      return new Result(
          State.ERROR, -1, null, "the installed configuration file is not valid UTF-8");
    }

    Version installedVersion = readVersion(installedText);
    if (!installedVersion.valid()) {
      State state =
          "configuration file is not valid YAML".equals(installedVersion.issue())
              ? State.ERROR
              : State.INVALID;
      return new Result(
          state,
          -1,
          null,
          installedVersion.issue() == null
              ? VERSION_KEY + " must be one plain, unquoted decimal integer"
              : installedVersion.issue());
    }
    if (installedVersion.value() > CURRENT_VERSION) {
      return new Result(
          State.FUTURE,
          installedVersion.value(),
          null,
          "schema v"
              + installedVersion.value()
              + " is newer than supported schema v"
              + CURRENT_VERSION);
    }

    final YamlConfiguration installed;
    try {
      installed = parse(installedText);
    } catch (InvalidConfigurationException failure) {
      return new Result(
          State.ERROR,
          installedVersion.value(),
          null,
          "the installed configuration file is not valid YAML");
    }
    int sourceVersion = installedVersion.value();
    YamlConfiguration candidate = installed;
    int workingVersion = sourceVersion;
    while (workingVersion < CURRENT_VERSION) {
      candidate = migrateFrom(workingVersion, installed, bundledYaml, kind);
      if (candidate == null) {
        return new Result(
            State.ERROR, sourceVersion, null, "no migration exists from schema v" + workingVersion);
      }
      workingVersion++;
    }

    String issue = validationIssue(candidate, defaults, kind);
    if (issue != null) {
      return new Result(
          State.INVALID, sourceVersion, null, "the configuration candidate is invalid: " + issue);
    }

    if (sourceVersion == CURRENT_VERSION) {
      try {
        if (!Arrays.equals(installedBytes, Files.readAllBytes(configFile))) {
          return new Result(
              State.ERROR,
              sourceVersion,
              null,
              "configuration file changed while it was being validated; retry");
        }
      } catch (IOException failure) {
        return new Result(
            State.ERROR,
            sourceVersion,
            null,
            "configuration file could not be rechecked after validation");
      }
      return new Result(
          State.CURRENT,
          sourceVersion,
          null,
          "schema v" + CURRENT_VERSION + " is current",
          new Prepared(candidate),
          installedBytes);
    }

    final Path backup;
    try {
      backup = createVerifiedBackup(configFile, installedBytes, sourceVersion);
    } catch (FileContentChangedException failure) {
      return new Result(
          State.ERROR,
          sourceVersion,
          null,
          "configuration file changed while its migration backup was prepared; retry");
    } catch (AtomicMoveNotSupportedException failure) {
      return new Result(
          State.ERROR,
          sourceVersion,
          null,
          "the filesystem cannot atomically create the migration backup");
    } catch (IOException failure) {
      return new Result(
          State.ERROR, sourceVersion, null, "configuration file could not be backed up safely");
    }

    String migratedText = candidate.saveToString();
    byte[] migratedBytes = migratedText.getBytes(StandardCharsets.UTF_8);
    try {
      writer.write(configFile, migratedText, installedBytes);
    } catch (FileContentChangedException failure) {
      return new Result(
          State.ERROR,
          sourceVersion,
          backup,
          "configuration file changed while its replacement was prepared; it was not replaced");
    } catch (AtomicMoveNotSupportedException failure) {
      return new Result(
          State.ERROR,
          sourceVersion,
          backup,
          "the filesystem cannot atomically replace the configuration file");
    } catch (IOException failure) {
      return new Result(
          State.ERROR,
          sourceVersion,
          backup,
          "the migrated configuration file could not replace the installed file");
    }

    try {
      Prepared promoted = readPublished(configFile, migratedBytes, defaults, kind);
      return new Result(
          State.UPGRADED,
          sourceVersion,
          backup,
          "upgraded schema v" + sourceVersion + " to v" + CURRENT_VERSION,
          promoted,
          migratedBytes);
    } catch (FileContentChangedException failure) {
      return new Result(
          State.ERROR,
          sourceVersion,
          backup,
          "configuration file changed immediately after migration; activation is blocked");
    } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
      return new Result(
          State.ERROR,
          sourceVersion,
          backup,
          "the promoted configuration file could not be reloaded and validated");
    }
  }

  private static YamlConfiguration migrateFrom(
      int sourceVersion, YamlConfiguration installed, String bundledYaml, Kind kind) {
    if (sourceVersion == 0) {
      return migrateZeroToOne(installed, bundledYaml, kind);
    }
    return null;
  }

  /** Uses the bundled layout and comments, then overlays explicit legacy choices. */
  private static YamlConfiguration migrateZeroToOne(
      YamlConfiguration installed, String bundledYaml, Kind kind) {
    final YamlConfiguration candidate;
    try {
      candidate = parse(bundledYaml);
    } catch (InvalidConfigurationException impossible) {
      throw new IllegalStateException("bundled config was already parsed", impossible);
    }
    overlayInstalledValues(candidate, installed, true);
    if (kind == Kind.CONFIG) {
      migrateLegacyBlacklist(candidate, installed);
    }
    candidate.set(VERSION_KEY, CURRENT_VERSION);
    return candidate;
  }

  /**
   * CreativeManager 1.x called the collection {@code blacklist}; newer releases call it {@code
   * list}. Explicit values already written under the new path win in hybrid files.
   */
  private static void migrateLegacyBlacklist(
      YamlConfiguration candidate, YamlConfiguration installed) {
    ConfigurationSection legacy = installed.getConfigurationSection("blacklist");
    if (legacy == null) {
      return;
    }
    if (installed.contains("list") && !installed.isConfigurationSection("list")) {
      // A malformed explicit new path must fail validation unchanged; the legacy path must not
      // overwrite it and make an ambiguous hybrid file appear safe.
      return;
    }
    ConfigurationSection explicit = installed.getConfigurationSection("list");
    ConfigurationSection target = candidate.getConfigurationSection("list");
    if (target == null) {
      target = candidate.createSection("list");
    }
    for (String key : legacy.getKeys(false)) {
      if (explicit != null && explicit.contains(key)) {
        continue;
      }
      if (target.contains(key)) {
        replaceKnownValue(target, key, legacy.get(key));
      } else {
        copyValue(target, legacy, key);
      }
    }
    candidate.set("blacklist", null);
  }

  private static void overlayInstalledValues(
      ConfigurationSection target, ConfigurationSection installed, boolean root) {
    List<String> knownKeys = new ArrayList<>(target.getKeys(false));
    List<String> installedKeys = new ArrayList<>(installed.getKeys(false));

    for (String key : knownKeys) {
      if ((root && VERSION_KEY.equals(key)) || !installedKeys.contains(key)) {
        continue;
      }
      Object bundledValue = target.get(key);
      Object installedValue = installed.get(key);
      if (bundledValue instanceof ConfigurationSection bundledSection
          && installedValue instanceof ConfigurationSection installedSection) {
        overlayInstalledValues(bundledSection, installedSection, false);
      } else {
        replaceKnownValue(target, key, installedValue);
      }
    }
    for (String key : installedKeys) {
      if ((root && VERSION_KEY.equals(key)) || knownKeys.contains(key)) {
        continue;
      }
      copyValue(target, installed, key);
    }
  }

  private static void replaceKnownValue(
      ConfigurationSection target, String key, Object installedValue) {
    List<String> comments = target.getComments(key);
    List<String> inlineComments = target.getInlineComments(key);
    if (installedValue instanceof ConfigurationSection section) {
      ConfigurationSection replacement = target.createSection(key);
      for (String child : section.getKeys(false)) {
        copyValue(replacement, section, child);
      }
    } else {
      target.set(key, installedValue);
    }
    target.setComments(key, comments);
    target.setInlineComments(key, inlineComments);
  }

  private static void copyValue(
      ConfigurationSection target, ConfigurationSection installed, String key) {
    Object value = installed.get(key);
    if (value instanceof ConfigurationSection section) {
      ConfigurationSection copy = target.createSection(key);
      for (String child : section.getKeys(false)) {
        copyValue(copy, section, child);
      }
    } else {
      target.set(key, value);
    }
  }

  /** Returns key names and expected types only; configured values never enter diagnostics. */
  private static String validationIssue(
      ConfigurationSection candidate, ConfigurationSection defaults, Kind kind) {
    Object schema = candidate.get(VERSION_KEY);
    if (!(schema instanceof Integer) || ((Integer) schema) != CURRENT_VERSION) {
      return VERSION_KEY + " must equal " + CURRENT_VERSION;
    }
    String shapeIssue = templateShapeIssue(candidate, defaults, "");
    if (shapeIssue != null) {
      return shapeIssue;
    }
    if (kind == Kind.CONFIG) {
      if (candidate.contains("blacklist")) {
        return "blacklist is a reserved legacy path and must be a section in schema v0";
      }
      String language = candidate.getString("lang", "");
      if (!language.matches("[A-Za-z0-9_-]+")) {
        return "lang must be a simple language identifier";
      }
      if (candidate.getInt("save-interval") < 0) {
        return "save-interval must not be negative";
      }
      if (candidate.getInt("antispam-tick") < 0) {
        return "antispam-tick must not be negative";
      }
    }
    return null;
  }

  private static String templateShapeIssue(
      ConfigurationSection candidate, ConfigurationSection defaults, String prefix) {
    for (String key : defaults.getKeys(false)) {
      if (prefix.isEmpty() && VERSION_KEY.equals(key)) {
        continue;
      }
      String path = prefix + key;
      Object expected = defaults.get(key);
      Object actual = candidate.get(key);
      if (expected instanceof ConfigurationSection expectedSection) {
        if (!(actual instanceof ConfigurationSection actualSection)) {
          return path + " must be a section";
        }
        String childIssue = templateShapeIssue(actualSection, expectedSection, path + ".");
        if (childIssue != null) {
          return childIssue;
        }
      } else if (!compatibleValue(expected, actual)) {
        return path + " has the wrong value type";
      }
    }
    return null;
  }

  private static boolean compatibleValue(Object expected, Object actual) {
    if (expected instanceof List<?> expectedList) {
      if (!(actual instanceof List<?> actualList)) {
        return false;
      }
      Object example = expectedList.stream().filter(Objects::nonNull).findFirst().orElse(null);
      return actualList.stream().noneMatch(Objects::isNull)
          && (example == null
              || actualList.stream().allMatch(value -> compatibleScalar(example, value)));
    }
    return compatibleScalar(expected, actual);
  }

  private static boolean compatibleScalar(Object expected, Object actual) {
    if (expected instanceof Integer) {
      return actual instanceof Integer;
    }
    return expected != null && actual != null && expected.getClass().isInstance(actual);
  }

  private static Version readVersion(String text) {
    final Object loaded;
    final Node document;
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      Yaml loader = new Yaml(new SafeConstructor(options));
      loaded = loader.load(text);

      LoaderOptions composeOptions = new LoaderOptions();
      composeOptions.setAllowDuplicateKeys(false);
      Yaml composer = new Yaml(new SafeConstructor(composeOptions));
      List<Node> documents = new ArrayList<>();
      composer.composeAll(new StringReader(text)).forEach(documents::add);
      if (documents.size() > 1) {
        return new Version(false, -1, "configuration file must contain exactly one YAML document");
      }
      document = documents.isEmpty() ? null : documents.get(0);
    } catch (DuplicateKeyException duplicate) {
      return new Version(false, -1, "configuration file contains duplicate or ambiguous YAML keys");
    } catch (RuntimeException malformed) {
      return new Version(false, -1, "configuration file is not valid YAML");
    }

    if (document == null) {
      return new Version(true, 0);
    }
    if (!(loaded instanceof Map<?, ?> mapping) || !(document instanceof MappingNode root)) {
      return new Version(false, -1, "configuration file must contain a top-level mapping");
    }
    String unsafeIssue =
        unsupportedYamlIssue(document, Collections.newSetFromMap(new IdentityHashMap<>()));
    if (unsafeIssue != null) {
      return new Version(false, -1, unsafeIssue);
    }
    if (!mapping.containsKey(VERSION_KEY)) {
      return new Version(true, 0);
    }

    ScalarNode physicalValue = null;
    for (NodeTuple tuple : root.getValue()) {
      if (tuple.getKeyNode() instanceof ScalarNode key && VERSION_KEY.equals(key.getValue())) {
        if (!key.isPlain()
            || !Tag.STR.equals(key.getTag())
            || key.getAnchor() != null
            || !physicalToken(text, key).equals(VERSION_KEY)
            || !(tuple.getValueNode() instanceof ScalarNode value)
            || !value.isPlain()
            || !Tag.INT.equals(value.getTag())
            || value.getAnchor() != null
            || !physicalToken(text, value).equals(value.getValue())
            || physicalValue != null) {
          return new Version(
              false, -1, VERSION_KEY + " must be one plain, unquoted decimal integer");
        }
        physicalValue = value;
      }
    }
    Object raw = mapping.get(VERSION_KEY);
    if (physicalValue == null
        || !physicalValue.getValue().matches("0|[1-9][0-9]*")
        || !isInteger(raw)) {
      return new Version(false, -1, VERSION_KEY + " must be one plain, unquoted decimal integer");
    }
    try {
      int value = Integer.parseInt(physicalValue.getValue());
      if (((Number) raw).longValue() != value) {
        return new Version(false, -1, VERSION_KEY + " must be one plain, unquoted decimal integer");
      }
      return new Version(true, value);
    } catch (NumberFormatException failure) {
      return new Version(false, -1, VERSION_KEY + " must fit a non-negative 32-bit integer");
    }
  }

  private static String unsupportedYamlIssue(Node node, Set<Node> visited) {
    if (!visited.add(node)) {
      return null;
    }
    if (Tag.NULL.equals(node.getTag())) {
      return "configuration file contains a null value that cannot be preserved";
    }
    if (node instanceof MappingNode mapping) {
      for (NodeTuple tuple : mapping.getValue()) {
        Node key = tuple.getKeyNode();
        if (Tag.MERGE.equals(key.getTag())) {
          return "configuration file contains a YAML merge key that cannot be preserved";
        }
        if (!(key instanceof ScalarNode scalar) || !Tag.STR.equals(scalar.getTag())) {
          return "configuration file contains a non-string mapping key";
        }
        String keyIssue = unsupportedYamlIssue(key, visited);
        if (keyIssue != null) {
          return keyIssue;
        }
        String valueIssue = unsupportedYamlIssue(tuple.getValueNode(), visited);
        if (valueIssue != null) {
          return valueIssue;
        }
      }
    } else if (node instanceof SequenceNode sequence) {
      for (Node child : sequence.getValue()) {
        String issue = unsupportedYamlIssue(child, visited);
        if (issue != null) {
          return issue;
        }
      }
    } else if (node instanceof AnchorNode anchor) {
      return unsupportedYamlIssue(anchor.getRealNode(), visited);
    }
    return null;
  }

  private static boolean isInteger(Object value) {
    return value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long;
  }

  private static String physicalToken(String text, ScalarNode scalar) {
    int start = scalar.getStartMark().getIndex();
    int end = scalar.getEndMark().getIndex();
    if (start < 0 || end < start || end > text.length()) {
      return "";
    }
    return text.substring(start, end).trim();
  }

  private static YamlConfiguration parse(String text) throws InvalidConfigurationException {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.options().parseComments(true);
    yaml.loadFromString(text);
    return yaml;
  }

  private static Prepared readPublished(
      Path file, byte[] expected, YamlConfiguration defaults, Kind kind)
      throws IOException, InvalidConfigurationException {
    byte[] promotedBytes = Files.readAllBytes(file);
    if (!Arrays.equals(expected, promotedBytes)) {
      throw new FileContentChangedException();
    }
    String promotedText = decodeUtf8(promotedBytes);
    Version promotedVersion = readVersion(promotedText);
    YamlConfiguration promoted = parse(promotedText);
    if (!promotedVersion.valid()
        || promotedVersion.value() != CURRENT_VERSION
        || validationIssue(promoted, defaults, kind) != null) {
      throw new InvalidConfigurationException("promoted configuration failed validation");
    }
    return new Prepared(promoted);
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

  private static Path createVerifiedBackup(Path configFile, byte[] expected, int sourceVersion)
      throws IOException {
    Path parent = parentOf(configFile);
    Path temporary =
        Files.createTempFile(
            parent, "." + configFile.getFileName() + ".v" + sourceVersion + ".bak-", ".tmp");
    boolean promoted = false;
    try {
      writeForced(temporary, expected);
      if (!Arrays.equals(expected, Files.readAllBytes(configFile))) {
        throw new FileContentChangedException();
      }
      Path backup = nextBackupPath(configFile, sourceVersion);
      Files.move(temporary, backup, StandardCopyOption.ATOMIC_MOVE);
      if (!Arrays.equals(expected, Files.readAllBytes(backup))) {
        Files.deleteIfExists(backup);
        throw new IOException("migration backup verification failed");
      }
      promoted = true;
      return backup;
    } finally {
      if (!promoted) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  private static Path nextBackupPath(Path configFile, int sourceVersion) {
    Path parent = configFile.toAbsolutePath().normalize().getParent();
    String base = configFile.getFileName() + ".v" + sourceVersion + ".bak";
    Path candidate = parent.resolve(base);
    for (int suffix = 1; Files.exists(candidate); suffix++) {
      candidate = parent.resolve(base + "." + suffix);
    }
    return candidate;
  }

  static void writeUtf8AtomicRequired(Path target, String content, byte[] expectedCurrent)
      throws IOException {
    Path parent = parentOf(target);
    Files.createDirectories(parent);
    Path temporary = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
    boolean moved = false;
    try {
      Files.createFile(temporary);
      writeForced(temporary, content.getBytes(StandardCharsets.UTF_8));
      if (expectedCurrent == null) {
        if (Files.exists(target)) {
          throw new FileContentChangedException();
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
      } else {
        if (!Arrays.equals(expectedCurrent, Files.readAllBytes(target))) {
          throw new FileContentChangedException();
        }
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      }
      moved = true;
    } finally {
      if (!moved) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  private static void writeForced(Path target, byte[] bytes) throws IOException {
    try (FileChannel channel =
        FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
  }

  private static Path parentOf(Path path) throws IOException {
    Path parent = path.toAbsolutePath().normalize().getParent();
    if (parent == null) {
      throw new IOException("configuration file has no parent directory");
    }
    return parent;
  }
}
