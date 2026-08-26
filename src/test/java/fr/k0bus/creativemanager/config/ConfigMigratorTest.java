package fr.k0bus.creativemanager.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigMigratorTest {
  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "config.yml",
        "lang/ba_BA.yml",
        "lang/en_EN.yml",
        "lang/es_ES.yml",
        "lang/fr_FR.yml",
        "lang/hu_HU.yml",
        "lang/it_IT.yml",
        "lang/ru_RU.yml",
        "lang/zh_CN.yml"
      })
  void everyManagedBundledResourceCarriesOneAcceptedCurrentMarker(String resource)
      throws Exception {
    Path installed = temporaryDirectory.resolve(resource.replace('/', '-'));
    String contents = bundled(resource);
    Files.writeString(installed, contents);

    ConfigMigrator.Result result =
        ConfigMigrator.upgrade(
            installed,
            contents,
            resource.equals("config.yml")
                ? ConfigMigrator.Kind.CONFIG
                : ConfigMigrator.Kind.LANGUAGE);

    assertEquals(ConfigMigrator.State.CURRENT, result.state());
    assertEquals(1, markerCount(contents));
    assertEquals(0, backupCount(installed));
  }

  @Test
  void bundledCurrentConfigIsAcceptedWithoutRewriteOrBackup() throws Exception {
    Path installed = temporaryDirectory.resolve("config.yml");
    byte[] original = bundled("config.yml").getBytes(StandardCharsets.UTF_8);
    Files.write(installed, original);

    ConfigMigrator.Result result = migrateConfig(installed);

    assertEquals(ConfigMigrator.State.CURRENT, result.state());
    assertEquals(1, result.loadedVersion());
    assertNull(result.backup());
    assertArrayEquals(original, Files.readAllBytes(installed));
    assertArrayEquals(original, result.loadedBytes());
    assertEquals(0, backupCount(installed));
  }

  @Test
  void missingManagedFileIsAtomicallyInstalledAndValidatedWithoutBackup() throws Exception {
    Path installed = temporaryDirectory.resolve("nested/config.yml");
    byte[] expected = bundled("config.yml").getBytes(StandardCharsets.UTF_8);

    ConfigMigrator.Result result =
        ConfigMigrator.prepare(installed, bundled("config.yml"), ConfigMigrator.Kind.CONFIG, true);

    assertEquals(ConfigMigrator.State.CREATED, result.state());
    assertArrayEquals(expected, Files.readAllBytes(installed));
    assertArrayEquals(expected, result.loadedBytes());
    assertNull(result.backup());
    assertEquals(0, backupCount(installed));
  }

  @Test
  void freshInstallCompareAndSwapDoesNotOverwriteAFileThatAppears() throws Exception {
    Path installed = temporaryDirectory.resolve("nested/config.yml");

    ConfigMigrator.Result result =
        ConfigMigrator.prepare(
            installed,
            bundled("config.yml"),
            ConfigMigrator.Kind.CONFIG,
            true,
            (target, contents, expected) -> {
              Files.createDirectories(target.getParent());
              Files.writeString(target, "operator-edit: true\n");
              ConfigMigrator.writeUtf8AtomicRequired(target, contents, expected);
            });

    assertEquals(ConfigMigrator.State.ERROR, result.state());
    assertEquals("operator-edit: true\n", Files.readString(installed));
    assertEquals(0, backupCount(installed));
  }

  @Test
  void realPatriamSchemaZeroConfigMovesIntoTemplateLayoutAndKeepsValues() throws Exception {
    Path installed = temporaryDirectory.resolve("config.yml");
    byte[] original = fixture("fixtures/config-v0-patriam.yml");
    Files.write(installed, original);

    ConfigMigrator.Result result = migrateConfig(installed);

    assertEquals(ConfigMigrator.State.UPGRADED, result.state());
    assertEquals(0, result.sourceVersion());
    assertNotNull(result.backup());
    assertArrayEquals(original, Files.readAllBytes(result.backup()));
    String migrated = Files.readString(installed);
    assertEquals(1, markerCount(migrated));
    assertOrdered(
        migrated,
        "config-version: 1",
        "tag: '&cSystem &8>> '",
        "stop-inv-close-on-gmchange: false",
        "protections:",
        "  build-container: true",
        "  blockcopy: false",
        "inventory:",
        "list:",
        "  mode:",
        "  place:",
        "creative_armor:",
        "creative-lore:");
    assertFalse(migrated.contains("blacklist:"));

    YamlConfiguration yaml = result.prepared().configuration();
    assertEquals("&cSystem &8>> ", yaml.getString("tag"));
    assertTrue(yaml.getBoolean("protections.spawn"));
    assertTrue(yaml.getBoolean("protections.pvp"));
    assertEquals("bedrock", yaml.getStringList("list.place").get(0));
    assertEquals("shop", yaml.getStringList("list.commands").get(0));
    assertTrue(yaml.getBoolean("protections.build-container"));
    assertFalse(yaml.getBoolean("protections.blockcopy"));
  }

  @Test
  void migrationIsIdempotentAndDoesNotCreateAnotherBackup() throws Exception {
    Path installed = temporaryDirectory.resolve("config.yml");
    Files.write(installed, fixture("fixtures/config-v0-patriam.yml"));
    ConfigMigrator.Result first = migrateConfig(installed);
    byte[] once = Files.readAllBytes(installed);

    ConfigMigrator.Result second = migrateConfig(installed);

    assertEquals(ConfigMigrator.State.UPGRADED, first.state());
    assertEquals(ConfigMigrator.State.CURRENT, second.state());
    assertArrayEquals(once, Files.readAllBytes(installed));
    assertEquals(1, backupCount(installed));
  }

  @Test
  void explicitNewListPathsBeatLegacyBlacklistPathsInHybridFiles() throws Exception {
    Path installed = temporaryDirectory.resolve("config.yml");
    String old =
        "list:\n"
            + "  place: [stone]\n"
            + "blacklist:\n"
            + "  place: [bedrock]\n"
            + "  use: [lava_bucket]\n";
    Files.writeString(installed, old);

    ConfigMigrator.Result result = migrateConfig(installed);

    assertTrue(result.compatible());
    YamlConfiguration yaml = result.prepared().configuration();
    assertEquals(List.of("stone"), yaml.getStringList("list.place"));
    assertEquals(List.of("lava_bucket"), yaml.getStringList("list.use"));
    assertFalse(yaml.contains("blacklist"));
  }

  @Test
  void malformedExplicitNewListPathIsNotHiddenByLegacyBlacklistMigration() throws Exception {
    String hybrid = "list: not-a-section\nblacklist:\n  place: [bedrock]\n";
    assertBlockedUnchanged(hybrid, ConfigMigrator.State.INVALID);
  }

  @Test
  void malformedLegacyBlacklistSectionIsNotSilentlyIgnored() throws Exception {
    assertBlockedUnchanged("blacklist: not-a-section\n", ConfigMigrator.State.INVALID);
  }

  @Test
  void unknownNestedExtensionsArePreservedAfterKnownTemplateKeys() throws Exception {
    Path installed = temporaryDirectory.resolve("config.yml");
    String old =
        "protections:\n"
            + "  container: false\n"
            + "  custom-protection:\n"
            + "    enabled: true\n"
            + "root-extension:\n"
            + "  nested: keep-me\n";
    Files.writeString(installed, old);

    ConfigMigrator.Result result = migrateConfig(installed);

    String migrated = Files.readString(installed);
    assertTrue(result.compatible());
    assertFalse(result.prepared().configuration().getBoolean("protections.container"));
    assertTrue(
        result.prepared().configuration().getBoolean("protections.custom-protection.enabled"));
    assertEquals("keep-me", result.prepared().configuration().getString("root-extension.nested"));
    assertOrdered(
        migrated, "  plugins:", "  custom-protection:", "creative-lore:", "root-extension:");
    assertRecursiveTemplateOrder(
        result.prepared().configuration(), parseYaml(bundled("config.yml")));
  }

  @Test
  void unversionedLanguageUsesLanguageTemplateOrderAndKeepsTranslations() throws Exception {
    Path installed = temporaryDirectory.resolve("en_EN.yml");
    String old =
        "permission:\n"
            + "  general: custom permission\n"
            + "custom-section:\n"
            + "  greeting: hello\n";
    Files.writeString(installed, old);

    ConfigMigrator.Result result =
        ConfigMigrator.upgrade(installed, bundled("lang/en_EN.yml"), ConfigMigrator.Kind.LANGUAGE);

    assertEquals(ConfigMigrator.State.UPGRADED, result.state());
    assertEquals(
        "custom permission", result.prepared().configuration().getString("permission.general"));
    assertEquals("hello", result.prepared().configuration().getString("custom-section.greeting"));
    assertOrdered(
        Files.readString(installed),
        "config-version: 1",
        "permission:",
        "blacklist:",
        "inventory:",
        "custom-section:");
  }

  @Test
  void emptyAndFlowSchemaZeroDocumentsAreNormalAdoptionInputs() throws Exception {
    for (String input : List.of("", "{}\n", "{tag: custom}\n")) {
      Path installed = temporaryDirectory.resolve("config-" + Math.abs(input.hashCode()) + ".yml");
      Files.writeString(installed, input);
      ConfigMigrator.Result result = migrateConfig(installed);
      assertEquals(ConfigMigrator.State.UPGRADED, result.state());
      assertEquals(1, result.prepared().configuration().getInt("config-version"));
    }
  }

  @Test
  void flowRootPlainCurrentMarkerIsAccepted() throws Exception {
    Path installed = temporaryDirectory.resolve("flow-language.yml");
    String template = "{config-version: 1, message: default}\n";
    String configured = "{config-version: 1, message: custom}\n";
    Files.writeString(installed, configured);

    ConfigMigrator.Result result =
        ConfigMigrator.upgrade(installed, template, ConfigMigrator.Kind.LANGUAGE);

    assertEquals(ConfigMigrator.State.CURRENT, result.state());
    assertEquals("custom", result.prepared().configuration().getString("message"));
    assertEquals(configured, Files.readString(installed));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "config-version:\n",
        "config-version: null\n",
        "config-version: ~\n",
        "config-version: '1'\n",
        "config-version: \"1\"\n",
        "'config-version': 1\n",
        "\"config\\u002dversion\": 1\n",
        "!!str config-version: 1\n",
        "&schema config-version: 1\n",
        "config-version: !!int 1\n",
        "config-version: &schema 1\n",
        "schema: &schema 1\nconfig-version: *schema\n",
        "schema: &schema config-version\n*schema: 1\n",
        "config-version: -1\n",
        "config-version: +1\n",
        "config-version: 01\n",
        "config-version: 1.0\n",
        "config-version: 1e0\n",
        "config-version: true\n",
        "config-version: [1\n",
        "config-version: 0\nconfig-version: 1\n",
        "config-version: 0\n\"config-version\": 1\n",
        "config-version: 0\n---\nconfig-version: 1\n"
      })
  void ambiguousOrMalformedMarkersBlockWithoutChangingSource(String input) throws Exception {
    Path installed = temporaryDirectory.resolve("config.yml");
    byte[] original = input.getBytes(StandardCharsets.UTF_8);
    Files.write(installed, original);

    ConfigMigrator.Result result = migrateConfig(installed);

    assertFalse(result.compatible());
    assertArrayEquals(original, Files.readAllBytes(installed));
    assertEquals(0, backupCount(installed));
  }

  @Test
  void futureMarkerBlocksWithoutChangingSource() throws Exception {
    assertBlockedUnchanged("config-version: 2\ntag: untouched\n", ConfigMigrator.State.FUTURE);
  }

  @Test
  void unrepresentableYamlConstructsBlockSchemaZeroWithoutDataLoss() throws Exception {
    assertBlockedUnchanged("tag: null\n", ConfigMigrator.State.INVALID);
    assertBlockedUnchanged("extension:\n  - null\n", ConfigMigrator.State.INVALID);
    assertBlockedUnchanged("1: value\n", ConfigMigrator.State.INVALID);
    assertBlockedUnchanged("extension:\n  1: value\n", ConfigMigrator.State.INVALID);
    assertBlockedUnchanged(
        "base: &base\n  enabled: true\ncopy:\n  <<: *base\n", ConfigMigrator.State.INVALID);
  }

  @Test
  void duplicateOrdinaryKeysBlockSchemaZeroWithoutDataLoss() throws Exception {
    assertBlockedUnchanged("tag: one\ntag: two\n", ConfigMigrator.State.INVALID);
  }

  @Test
  void invalidUtf8BlocksWithoutChangingSource() throws Exception {
    Path installed = temporaryDirectory.resolve("config.yml");
    byte[] original = {(byte) 0xc3, (byte) 0x28};
    Files.write(installed, original);

    ConfigMigrator.Result result = migrateConfig(installed);

    assertEquals(ConfigMigrator.State.ERROR, result.state());
    assertArrayEquals(original, Files.readAllBytes(installed));
    assertEquals(0, backupCount(installed));
  }

  @Test
  void currentSchemaMissingKnownKeysOrUsingWrongTypesIsRejectedUnchanged() throws Exception {
    String missing = "config-version: 1\ntag: value\n";
    assertBlockedUnchanged(missing, ConfigMigrator.State.INVALID);

    String wrongType = bundled("config.yml").replace("save-interval: 300", "save-interval: nope");
    assertBlockedUnchanged(wrongType, ConfigMigrator.State.INVALID);

    assertBlockedUnchanged("tag: {}\n", ConfigMigrator.State.INVALID);
    assertBlockedUnchanged("protections: false\n", ConfigMigrator.State.INVALID);
  }

  @Test
  void failedReplacementLeavesInstalledBytesAndExactBackup() throws Exception {
    Path installed = temporaryDirectory.resolve("config.yml");
    byte[] original = fixture("fixtures/config-v0-patriam.yml");
    Files.write(installed, original);

    ConfigMigrator.Result result =
        ConfigMigrator.upgrade(
            installed,
            bundled("config.yml"),
            ConfigMigrator.Kind.CONFIG,
            (target, contents, expected) -> {
              throw new IOException("simulated write failure");
            });

    assertEquals(ConfigMigrator.State.ERROR, result.state());
    assertArrayEquals(original, Files.readAllBytes(installed));
    assertNotNull(result.backup());
    assertArrayEquals(original, Files.readAllBytes(result.backup()));
  }

  @Test
  void compareAndSwapRefusesToReplaceAFileChangedDuringPreparation() throws Exception {
    Path installed = temporaryDirectory.resolve("config.yml");
    byte[] original = fixture("fixtures/config-v0-patriam.yml");
    Files.write(installed, original);

    ConfigMigrator.Result result =
        ConfigMigrator.upgrade(
            installed,
            bundled("config.yml"),
            ConfigMigrator.Kind.CONFIG,
            (target, contents, expected) -> {
              Files.writeString(target, "external-change: true\n");
              ConfigMigrator.writeUtf8AtomicRequired(target, contents, expected);
            });

    assertEquals(ConfigMigrator.State.ERROR, result.state());
    assertEquals("external-change: true\n", Files.readString(installed));
    assertNotNull(result.backup());
    assertArrayEquals(original, Files.readAllBytes(result.backup()));
  }

  @Test
  void postWriteVerificationBlocksAWriterThatPromotesDifferentBytes() throws Exception {
    Path installed = temporaryDirectory.resolve("config.yml");
    Files.writeString(installed, "tag: old\n");

    ConfigMigrator.Result result =
        ConfigMigrator.upgrade(
            installed,
            bundled("config.yml"),
            ConfigMigrator.Kind.CONFIG,
            (target, contents, expected) -> Files.writeString(target, "config-version: 1\n"));

    assertEquals(ConfigMigrator.State.ERROR, result.state());
    assertTrue(result.detail().contains("immediately after migration"));
  }

  @Test
  void backupNameCollisionUsesAStableNumericSuffix() throws Exception {
    Path installed = temporaryDirectory.resolve("config.yml");
    byte[] original = "tag: old\n".getBytes(StandardCharsets.UTF_8);
    Files.write(installed, original);
    Files.writeString(temporaryDirectory.resolve("config.yml.v0.bak"), "earlier");

    ConfigMigrator.Result result = migrateConfig(installed);

    assertEquals("config.yml.v0.bak.1", result.backup().getFileName().toString());
    assertArrayEquals(original, Files.readAllBytes(result.backup()));
  }

  @Test
  void invalidBundledTemplateNeverChangesInstalledFile() throws Exception {
    Path installed = temporaryDirectory.resolve("config.yml");
    byte[] original = "tag: old\n".getBytes(StandardCharsets.UTF_8);
    Files.write(installed, original);

    ConfigMigrator.Result result =
        ConfigMigrator.upgrade(installed, "tag: invalid-defaults\n", ConfigMigrator.Kind.CONFIG);

    assertEquals(ConfigMigrator.State.ERROR, result.state());
    assertArrayEquals(original, Files.readAllBytes(installed));
    assertEquals(0, backupCount(installed));
  }

  @Test
  void resultByteArraysAreDefensiveCopies() throws Exception {
    Path installed = temporaryDirectory.resolve("config.yml");
    Files.writeString(installed, bundled("config.yml"));
    ConfigMigrator.Result result = migrateConfig(installed);
    byte[] first = result.loadedBytes();
    first[0] ^= 1;
    assertFalse(first[0] == result.loadedBytes()[0]);
  }

  private ConfigMigrator.Result migrateConfig(Path installed) throws IOException {
    return ConfigMigrator.upgrade(installed, bundled("config.yml"), ConfigMigrator.Kind.CONFIG);
  }

  private void assertBlockedUnchanged(String input, ConfigMigrator.State expectedState)
      throws Exception {
    Path installed = temporaryDirectory.resolve("blocked-" + Math.abs(input.hashCode()) + ".yml");
    byte[] original = input.getBytes(StandardCharsets.UTF_8);
    Files.write(installed, original);
    ConfigMigrator.Result result = migrateConfig(installed);
    assertEquals(expectedState, result.state());
    assertArrayEquals(original, Files.readAllBytes(installed));
    assertEquals(0, backupCount(installed));
  }

  private static void assertOrdered(String text, String... tokens) {
    int previous = -1;
    for (String token : tokens) {
      int found = text.indexOf(token);
      assertTrue(found >= 0, () -> "missing token: " + token);
      assertTrue(found > previous, () -> "token out of order: " + token);
      previous = found;
    }
  }

  private static void assertRecursiveTemplateOrder(
      ConfigurationSection actual, ConfigurationSection template) {
    List<String> actualKeys = new ArrayList<>(actual.getKeys(false));
    List<String> templateKeys = new ArrayList<>(template.getKeys(false));
    int previousKnown = -1;
    for (String known : templateKeys) {
      int index = actualKeys.indexOf(known);
      assertTrue(index >= 0, () -> "missing known key: " + known);
      assertTrue(index > previousKnown, () -> "known key out of template order: " + known);
      previousKnown = index;
      ConfigurationSection actualChild = actual.getConfigurationSection(known);
      ConfigurationSection templateChild = template.getConfigurationSection(known);
      if (actualChild != null && templateChild != null) {
        assertRecursiveTemplateOrder(actualChild, templateChild);
      }
    }
    for (String extension : actualKeys) {
      if (!templateKeys.contains(extension)) {
        int extensionIndex = actualKeys.indexOf(extension);
        assertTrue(
            extensionIndex > previousKnown,
            () -> "extension key was not placed after known siblings: " + extension);
      }
    }
  }

  private static YamlConfiguration parseYaml(String text) throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(text);
    return yaml;
  }

  private static long markerCount(String text) {
    return text.lines().filter(line -> line.equals("config-version: 1")).count();
  }

  private static long backupCount(Path installed) throws IOException {
    String prefix = installed.getFileName() + ".v";
    try (var files = Files.list(installed.getParent())) {
      return files.filter(path -> path.getFileName().toString().startsWith(prefix)).count();
    }
  }

  private static String bundled(String resource) throws IOException {
    return new String(fixture(resource), StandardCharsets.UTF_8);
  }

  private static byte[] fixture(String resource) throws IOException {
    try (InputStream stream =
        ConfigMigratorTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(stream, "missing test resource " + resource);
      return stream.readAllBytes();
    }
  }
}
