package fr.k0bus.creativemanager.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.k0bus.creativemanager.CreativeManager;
import fr.k0bus.creativemanager.settings.Settings;
import fr.k0bus.creativemanager.task.SaveTask;
import fr.k0bus.k0buscore.config.Configuration;
import fr.k0bus.k0buscore.config.Lang;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LifecycleContractTest {
  @Test
  void startupPreparesConfigurationBeforeCoreOrMutableGameplayState() throws Exception {
    String source =
        Files.readString(Path.of("src/main/java/fr/k0bus/creativemanager/CreativeManager.java"));
    int gate = source.indexOf("ConfigGeneration initialGeneration = prepareConfigGeneration()");
    assertTrue(gate >= 0);
    assertTrue(gate < source.indexOf("super.onEnable()"));
    assertTrue(gate < source.indexOf("this.registerEvent"));
    assertTrue(gate < source.indexOf("this.loadLog()"));
    assertTrue(gate < source.indexOf("SaveTask.run(this, getSettings())"));
  }

  @Test
  void lifecycleDoesNotUseBukkitSecondReadsOrDirectResourceWrites() throws Exception {
    String root = "src/main/java/fr/k0bus/creativemanager/";
    String lifecycle =
        Files.readString(Path.of(root + "CreativeManager.java"))
            + Files.readString(Path.of(root + "config/ConfigFiles.java"));
    assertFalse(lifecycle.contains("saveResource("));
    assertFalse(lifecycle.contains("reloadConfig("));
    assertFalse(lifecycle.contains("saveConfig("));
    assertFalse(lifecycle.contains("getConfig()"));
  }

  @Test
  void historicalPublicDescriptorsAndTypesRemainAvailable() throws Exception {
    assertEquals(void.class, CreativeManager.class.getMethod("loadConfigManager").getReturnType());
    assertEquals(void.class, CreativeManager.class.getMethod("updateConfig").getReturnType());
    assertEquals(Lang.class, CreativeManager.class.getMethod("getLang").getReturnType());
    assertTrue(Lang.class.isAssignableFrom(Messages.class));
    assertEquals(Configuration.class, Settings.class.getSuperclass());
    assertNotNull(Settings.class.getConstructor(CreativeManager.class));
    assertNotNull(SaveTask.class.getMethod("run", CreativeManager.class));
    var tag = CreativeManager.class.getField("TAG");
    assertEquals(String.class, tag.getType());
    assertTrue(Modifier.isStatic(tag.getModifiers()));
  }
}
