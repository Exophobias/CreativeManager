package fr.k0bus.k0buscore.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;

/**
 * Replaces the copy shipped in K0busCore 1.0.2, which cannot read a Minecraft version that does
 * not begin with "1.".
 *
 * <p>The upstream implementation matches {@code "MC: \d\.(\d+)"} against {@link Bukkit#getVersion()}.
 * That single {@code \d} assumes every Minecraft version looks like {@code 1.x}, which stopped being
 * true at 26.1. On Paper 26.2 the server reports:
 *
 * <pre>26.2-87-a95ae8d (MC: 26.2)</pre>
 *
 * <p>so the pattern finds nothing and upstream throws {@code IllegalArgumentException}. That call
 * sits under {@code StringUtils.translateColor}, which runs in {@code CreativeManager.<clinit>}, so
 * the failure is not a degraded feature: the plugin cannot be constructed and Paper reports
 * {@code Could not load plugin}.
 *
 * <p>This class exists in the plugin's own sources under the ORIGINAL package name. maven-shade
 * gives project classes precedence over dependency classes, so this replaces the broken copy and is
 * relocated to {@code fr.k0bus.creativemanager_libs.k0buscore.utils} with everything else. Delete it
 * as soon as K0busCore ships a version-scheme-agnostic parser upstream.
 */
public abstract class VersionUtils {

    /** Matches the major component in "(MC: 26.2)" and in "(MC: 1.21.4)" alike. */
    private static final Pattern MC_VERSION = Pattern.compile("MC: (\\d+)\\.(\\d+)");

    private VersionUtils() {
    }

    /**
     * @return the minor version for the 1.x line (1.21 gives 21), and the major version from 26.1
     *         onwards (26.2 gives 26)
     */
    public static int getMCVersion() {
        String raw = Bukkit.getVersion();
        Matcher matcher = MC_VERSION.matcher(raw);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Failed to parse server version from: " + raw);
        }
        int major = Integer.parseInt(matcher.group(1));
        // Callers written against the 1.x scheme compare this against numbers like 13 or 16, so the
        // second component has to keep being what they get. Past 1.x that convention has no
        // meaning left, and the major version is the only value that orders correctly.
        return major == 1 ? Integer.parseInt(matcher.group(2)) : major;
    }
}
