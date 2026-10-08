package pwshield;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ShieldConfig {
    private static final Properties VALUES = load();
    public static final boolean ENABLED = bool("shield.enabled", true);
    public static final boolean QUEUES = bool("queueProtection.enabled", true);
    public static final long BUDGET_NS = number("queueProtection.networkBudgetMs", 70, 1, 1000) * 1_000_000L;
    public static final long MAX_BYTES = number("queues.maxBufferedBytes", 67_108_864, 2176, 1_073_741_824);
    public static final long MAX_PEER_BYTES = number("queues.maxConnectionBytes", 8_388_608, 2176, MAX_BYTES);
    public static final int POOL_LIMIT = (int) number("pool.maxRetainedBuffers", 4096, 0, 65536);
    public static final boolean FIREWALL = bool("firewall.enabled", true);
    public static final boolean VEHICLE = bool("firewall.validateVehiclePhysics", true);
    public static final boolean RATE_ENABLED = bool("rateLimits.enabled", true);
    public static final boolean RATE_ENFORCE = bool("rateLimits.enforce", false);
    public static final int RATE_STATES = (int) number("rateLimits.maxTrackedGuids", 16384, 1, 65536);
    public static final long RATE_TTL_NS = number("rateLimits.stateTtlSeconds", 300, 1, 3600) * 1_000_000_000L;
    public static final double PPS = decimal("rateLimits.guid.packetsPerSecond", 0);
    public static final double PACKET_BURST = decimal("rateLimits.guid.burstPackets", 0);
    public static final double BPS = decimal("rateLimits.guid.bytesPerSecond", 0);
    public static final double BYTE_BURST = decimal("rateLimits.guid.burstBytes", 0);
    public static final boolean METRICS = bool("performance.enabled", true);
    public static final boolean CONSOLE = bool("logging.console", true);
    public static final int INTERVAL = (int) number("performance.intervalSeconds", 5, 1, 3600);
    public static final Path CSV = Path.of(VALUES.getProperty("performance.csvPath", "logs/pwshield-performance.csv"));

    static {
        if ((PPS == 0) != (PACKET_BURST == 0) || (BPS == 0) != (BYTE_BURST == 0))
            throw new IllegalArgumentException("PWSHIELD: each rate and burst must both be zero or both positive");
    }

    private static Properties load() {
        Properties properties = new Properties();
        String explicit = System.getProperty("pwshield.config");
        Path path = Path.of(explicit == null ? "pwshield.properties" : explicit);
        if (explicit == null && !Files.exists(path)) {
            try {
                Path code = Path.of(ShieldConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                path = (Files.isDirectory(code) ? code : code.getParent()).resolve("pwshield.properties");
            } catch (Exception exception) {
                throw new IllegalStateException("PWSHIELD: cannot resolve configuration path", exception);
            }
        }
        if (Files.exists(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                // Explicit UTF-8 preserves Russian comments and paths.
                properties.load(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new IllegalStateException("PWSHIELD: cannot read " + path, exception);
            }
        } else if (explicit != null) {
            throw new IllegalArgumentException("PWSHIELD: missing explicit configuration " + path);
        }
        return properties;
    }

    static boolean bool(String key, boolean fallback) {
        String value = VALUES.getProperty(key, Boolean.toString(fallback)).trim();
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("PWSHIELD: invalid boolean " + key);
        return Boolean.parseBoolean(value);
    }

    static long number(String key, long fallback, long min, long max) {
        long value = Long.parseLong(VALUES.getProperty(key, Long.toString(fallback)).trim());
        if (value < min || value > max) throw new IllegalArgumentException("PWSHIELD: out of range " + key);
        return value;
    }

    static double decimal(String key, double fallback) {
        double value = Double.parseDouble(VALUES.getProperty(key, Double.toString(fallback)).trim());
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("PWSHIELD: invalid rate " + key);
        return value;
    }
}
