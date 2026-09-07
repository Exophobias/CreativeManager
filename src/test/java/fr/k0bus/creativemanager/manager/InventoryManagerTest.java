package fr.k0bus.creativemanager.manager;

import static org.junit.jupiter.api.Assertions.*;
import fr.k0bus.creativemanager.event.PlayerGamemodeChange;
import fr.k0bus.creativemanager.settings.UserData;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InventoryManagerTest {
  @TempDir Path directory;
  private ItemStack[] contents = new ItemStack[41];
  private ItemStack[] armor = new ItemStack[4];
  private int writes;
  private boolean failArmor;
  private boolean kicked;
  private Runnable onKick = () -> {};
  private final UUID playerId = UUID.randomUUID();
  private Runnable beforeWrite = () -> {};
  private final PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(
      getClass().getClassLoader(), new Class<?>[] {PlayerInventory.class}, (proxy, method, args) -> {
        switch (method.getName()) {
          case "getSize": return 41;
          case "getContents": return contents;
          case "getArmorContents": return armor;
          case "setContents":
            beforeWrite.run();
            writes++;
            contents = ((ItemStack[]) args[0]).clone();
            return null;
          case "setArmorContents":
            if (failArmor) {
              failArmor = false;
              throw new IllegalStateException("Injected armor application failure");
            }
            writes++;
            armor = ((ItemStack[]) args[0]).clone();
            return null;
          default: throw new UnsupportedOperationException(method.getName());
        }
      });
  private final Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
      new Class<?>[] {Player.class}, (proxy, method, args) -> {
        switch (method.getName()) {
          case "getInventory": return inventory;
          case "getUniqueId": return playerId;
          case "hasPermission": return false;
          case "getGameMode": return GameMode.SURVIVAL;
          case "kickPlayer":
            kicked = true;
            onKick.run();
            return null;
          default: throw new UnsupportedOperationException(method.getName());
        }
      });

  private Path file() { return directory.resolve("player.yml"); }

  private InventoryManager manager(String destinationArmor) throws Exception {
    UserData data = new UserData(file(), Logger.getAnonymousLogger());
    data.saveInventory(GameMode.CREATIVE,
        InventoryManager.itemStackArrayToBase64(new ItemStack[36]), destinationArmor);
    return new InventoryManager(player, data, new YamlConfiguration(), Logger.getAnonymousLogger());
  }

  @Test void failedForcedModeRestorationBlocksTheResultingQuitSaveBeforeDisconnect() throws Exception {
    InventoryManager manager = manager("corrupt armor");
    String previousFile = Files.readString(file());
    onKick = () -> {
      InventoryManager quitManager = new InventoryManager(player,
          new UserData(file(), Logger.getAnonymousLogger()), new YamlConfiguration(), Logger.getAnonymousLogger());
      assertFalse(quitManager.saveInventory(GameMode.SURVIVAL));
    };
    assertFalse(manager.restoreForcedGameMode(GameMode.CREATIVE));
    assertTrue(kicked);
    assertEquals(0, writes);
    assertEquals(previousFile, Files.readString(file()));
    InventoryManager.beginSession(playerId);
  }

  private String validArmor() {
    return InventoryManager.itemStackArrayToBase64(new ItemStack[4]);
  }

  @Test void corruptArmorCannotApplyDecodedContentOrOverwriteSourceBackup() throws Exception {
    InventoryManager manager = manager("broken armor");
    String previousFile = Files.readString(file());
    assertFalse(manager.switchInventory(GameMode.SURVIVAL, GameMode.CREATIVE));
    assertEquals(0, writes);
    assertEquals(41, contents.length);
    assertEquals(previousFile, Files.readString(file()));
  }

  @Test void failedSourceSaveLeavesEveryLiveSlotUntouched() throws Exception {
    InventoryManager manager = manager(validArmor());
    Files.writeString(file(), "externally changed");
    assertFalse(manager.switchInventory(GameMode.SURVIVAL, GameMode.CREATIVE));
    assertEquals(0, writes);
    assertEquals("externally changed", Files.readString(file()));
  }

  @Test void sourceIsPublishedBeforeFirstDestinationSlotChanges() throws Exception {
    InventoryManager manager = manager(validArmor());
    beforeWrite = () -> assertTrue(new UserData(file(), Logger.getAnonymousLogger())
        .getConfiguration().isString("SURVIVAL.content"));
    assertTrue(manager.switchInventory(GameMode.SURVIVAL, GameMode.CREATIVE));
    assertEquals(36, contents.length);
    assertEquals(2, writes);
  }

  @Test void unexpectedArmorFailureRestoresCompleteSource() throws Exception {
    InventoryManager manager = manager(validArmor());
    failArmor = true;
    assertFalse(manager.switchInventory(GameMode.SURVIVAL, GameMode.CREATIVE));
    assertEquals(41, contents.length);
    assertEquals(4, armor.length);
    assertEquals(3, writes);
  }

  @Test void laterGamemodeCancellationRestoresSourceAndReleasesPendingEvent() throws Exception {
    InventoryManager manager = manager(validArmor());
    assertTrue(manager.switchInventory(GameMode.SURVIVAL, GameMode.CREATIVE));
    PlayerGamemodeChange listener = new PlayerGamemodeChange(null);
    PlayerGameModeChangeEvent event = new PlayerGameModeChangeEvent(player, GameMode.CREATIVE);
    var field = PlayerGamemodeChange.class.getDeclaredField("switched");
    field.setAccessible(true);
    @SuppressWarnings("unchecked") Map<PlayerGameModeChangeEvent, InventoryManager> pending =
        (Map<PlayerGameModeChangeEvent, InventoryManager>) field.get(listener);
    pending.put(event, manager);
    event.setCancelled(true);
    listener.restoreCancelledSwitch(event);
    assertEquals(41, contents.length);
    assertTrue(pending.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 42, Integer.MAX_VALUE})
  void invalidArrayLengthsAreRejectedBeforeAllocation(int size) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeInt(size); }
    String encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
    assertThrows(IOException.class, () -> InventoryManager.itemStackArrayFromBase64(encoded, 41, false));
  }

  @Test void decodedNonItemAndWrongArmorLengthAreRejected() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeInt(1);
      output.writeObject("not an item");
    }
    assertThrows(IOException.class, () -> InventoryManager.itemStackArrayFromBase64(
        Base64.getEncoder().encodeToString(bytes.toByteArray()), 41, false));
    assertThrows(IOException.class, () -> InventoryManager.itemStackArrayFromBase64(
        InventoryManager.itemStackArrayToBase64(new ItemStack[3]), 4, true));
  }
}
