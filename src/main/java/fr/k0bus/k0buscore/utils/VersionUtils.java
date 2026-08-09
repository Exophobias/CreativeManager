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
 * relocated to {@code fr.k0bus.creativemanager_libs.k0buscore.utils} with everything else.
 *
 * <p><b>Upstream fixed the same bug independently, and this file stays anyway.</b> 1.35.23's source
 * landed on K0bus/CreativeManager as 36a4562 on 2026-08-09: the same override at the same path in
 * the same package, using the same shade-precedence trick, returning the same value on both version
 * schemes. K0busCore itself is untouched and still ships the broken parser, so the override is still
 * load-bearing no matter whose copy occupies this path.
 *
 * <p>Because both copies are the same file, merging or rebasing onto 1.35.23 conflicts here as an
 * add/add. Resolve it by KEEPING THIS ONE. The two differ in only two ways, both in this copy's
 * favour: the pattern is compiled once into a constant instead of on every call, and the comments
 * record why the 1.x line returns its second component while later versions return their first.
 * Upstream's regex additionally allows a missing minor component ("MC: 26"), which no released
 * Minecraft version has ever reported.
 *
 * <p>DELETE THIS FILE only when K0busCore itself parses the current version scheme. Taking the
 * official jar is still not a substitute, since it would lose this fork's
 * perf/hot-path-optimisations work.
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
