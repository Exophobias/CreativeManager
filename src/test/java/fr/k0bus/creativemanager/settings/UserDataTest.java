package fr.k0bus.creativemanager.settings;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserDataTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"CREATIVE: [broken", "scalar", "CREATIVE: {}", "CREATIVE: null",
      "CREATIVE: {content: 1, armor: text}", "CREATIVE: {}\nCREATIVE: {}"})
  void invalidFileIsPreservedAndNeverReplacedByAnEmptyInventory(String original) throws Exception {
    Path file = directory.resolve("player.yml");
    Files.writeString(file, original);
    UserData data = new UserData(file, Logger.getAnonymousLogger());
    assertFalse(data.isHealthy());
    assertThrows(IOException.class, () -> data.saveInventory(GameMode.SURVIVAL, "content", "armor"));
    assertEquals(original, Files.readString(file));
  }

  @Test void malformedUtf8IsNotReplacedByDecodedReplacementCharacters() throws Exception {
    Path file = directory.resolve("player.yml");
    byte[] bytes = {(byte) 0xc3, (byte) 0x28};
    Files.write(file, bytes);
    UserData data = new UserData(file, Logger.getAnonymousLogger());
    assertFalse(data.isHealthy());
    assertThrows(IOException.class, () -> data.saveInventory(GameMode.SURVIVAL, "content", "armor"));
    assertArrayEquals(bytes, Files.readAllBytes(file));
  }

  @Test void fileChangedAfterReadCannotBeOverwritten() throws Exception {
    Path file = directory.resolve("player.yml");
    Files.writeString(file, "annotation: keep\n");
    UserData data = new UserData(file, Logger.getAnonymousLogger());
    Files.writeString(file, "annotation: externally-edited\n");
    assertThrows(IOException.class, () -> data.saveInventory(GameMode.SURVIVAL, "content", "armor"));
    assertFalse(data.isHealthy());
    assertEquals("annotation: externally-edited\n", Files.readString(file));
    assertFalse(data.getConfiguration().contains("SURVIVAL"));
  }

  @Test void unreadableDataNeverFallsBackToAFileOutsideItsDirectory() throws Exception {
    Path blocker = directory.resolve("data");
    Files.writeString(blocker, "retain");
    UserData data = new UserData(blocker.resolve("player.yml"), Logger.getAnonymousLogger());
    assertThrows(IOException.class, () -> data.saveInventory(GameMode.SURVIVAL, "content", "armor"));
    assertEquals("retain", Files.readString(blocker));
    assertFalse(Files.exists(directory.resolve("player.yml")));
  }

  @Test void acknowledgedSavePreservesOtherModesAndLoadsOnRestart() throws Exception {
    Path file = directory.resolve("data/player.yml");
    UserData data = new UserData(file, Logger.getAnonymousLogger());
    data.saveInventory(GameMode.SURVIVAL, "survival", "armor1");
    data.saveInventory(GameMode.CREATIVE, "creative", "armor2");
    UserData restarted = new UserData(file, Logger.getAnonymousLogger());
    assertTrue(restarted.isHealthy());
    assertEquals("survival", restarted.getConfiguration().getString("SURVIVAL.content"));
    assertEquals("creative", restarted.getConfiguration().getString("CREATIVE.content"));
    try (var files = Files.list(file.getParent())) {
      assertEquals(1, files.count());
    }
  }
}
