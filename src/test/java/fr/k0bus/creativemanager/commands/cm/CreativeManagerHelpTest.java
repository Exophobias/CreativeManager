package fr.k0bus.creativemanager.commands.cm;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CreativeManagerHelpTest {
    private static CommandSender sender(Set<String> grants, List<String> output) {
        return (CommandSender) java.lang.reflect.Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) return grants.contains(args[0].toString());
                    if (method.getName().equals("sendMessage") && args[0] instanceof String line) { output.add(line); return null; }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
    }

    @Test void rootAndHelpAreStaffOnlyAndConsoleCanReadPlayerContextCommands() {
        var command = new CreativeManagerCommands(null);
        var output = new ArrayList<String>();
        var sender = sender(Set.of("creativemanager.admin"), output);
        command.onCommand(sender, null, "cm", new String[0]);
        assertTrue(output.getFirst().contains("CreativeManager Staff"));
        assertTrue(String.join("\n", output).contains("/cm items"));
        assertEquals(6, output.size());
        output.clear();
        command.onCommand(sender, null, "cm", new String[]{"help", "banana"});
        assertEquals(6, output.size());
        assertTrue(new CreativeManagerCommandTab(command).onTabComplete(sender, null, "cm", new String[]{"re"}).contains("reload"));
    }
    @Test void deniedExecutionAndTabSuggestionsNeverExposeStaffTools() {
        var command = new CreativeManagerCommands(null);
        var output = new ArrayList<String>();
        var sender = sender(Set.of(), output);
        command.onCommand(sender, null, "cm", new String[]{"reload"});
        assertEquals(1, output.size());
        assertTrue(output.getFirst().contains("permission"));
        assertEquals(List.of("help"), new CreativeManagerCommandTab(command).onTabComplete(sender, null, "cm", new String[]{""}));
        output.clear();
        command.onCommand(sender, null, "cm", new String[]{"nonsense"});
        assertTrue(output.getFirst().contains("Unknown"));
        assertTrue(output.getLast().contains("permission"));
    }
}
