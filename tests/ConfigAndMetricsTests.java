package pwshield;

import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Separate JVMs are required because ShieldConfig initializes once per class loader. */
public final class ConfigAndMetricsTests {
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            switch (args[1]) {
                case "defaults" -> defaults();
                case "minimum" -> minimum();
                case "disabled" -> disabled();
                case "invalid" -> invalid(args[2]);
                case "metrics" -> metrics();
                default -> throw new IllegalArgumentException("Unknown test mode: " + args[1]);
            }
            System.out.println("PASS: " + checks + " configuration/metrics checks in " + args[1]);
            return;
        }
        if (args.length != 1) throw new IllegalArgumentException("ConfigAndMetricsTests <tests-directory>");
        Path configs = Path.of(args[0]).toAbsolutePath();
        runChild(configs.resolve("config-defaults.properties"), "defaults");
        runChild(configs.resolve("config-minimum.properties"), "minimum");
        runChild(configs.resolve("config-disabled.properties"), "disabled");
        runChild(configs.resolve("config-invalid-quota.properties"), "invalid", "out of range queues.maxBufferedBytes");
        runChild(configs.resolve("config-invalid-peer-quota.properties"), "invalid", "out of range queues.maxConnectionBytes");
        runChild(configs.resolve("config-invalid-nan.properties"), "invalid", "invalid rate rateLimits.guid.packetsPerSecond");
        runChild(configs.resolve("config-invalid-packet-pair.properties"), "invalid", "each rate and burst");
        runChild(configs.resolve("config-invalid-byte-pair.properties"), "invalid", "each rate and burst");

        Path temporary = Files.createTempDirectory("pwshield-metrics-test-");
        Path config = temporary.resolve("metrics.properties"), csv = temporary.resolve("performance.csv");
        try {
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(configs.resolve("config-metrics.properties"), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            properties.setProperty("performance.csvPath", csv.toString());
            try (var writer = Files.newBufferedWriter(config, StandardCharsets.UTF_8)) {
                properties.store(writer, "Isolated CSV test; daemon disabled");
            }
            runChild(config, "metrics");
        } finally {
            Files.deleteIfExists(csv);
            Files.deleteIfExists(csv.resolveSibling(csv.getFileName() + ".1"));
            Files.deleteIfExists(config);
            Files.deleteIfExists(temporary);
        }
        System.out.println("PASS: configuration boundaries and CSV lifecycle in 9 isolated JVM scenarios");
    }

    private static void runChild(Path config, String... mode) throws Exception {
        check(Files.isRegularFile(config), "configuration fixture exists: " + config.getFileName());
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
        command.add("-Xverify:all");
        command.add("-Dpwshield.config=" + config);
        command.add("-classpath");
        command.add(System.getProperty("java.class.path"));
        command.add(ConfigAndMetricsTests.class.getName());
        command.add("child");
        command.addAll(List.of(mode));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!child.waitFor(30, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            child.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError("Test JVM timed out: " + mode[0]);
        }
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(child.exitValue() == 0, "isolated " + mode[0] + " failed:\n" + output);
        System.out.print(output);
    }

    private static void check(boolean condition, String label) {
        ++checks;
        if (!condition) throw new AssertionError(label);
    }

    private static void defaults() {
        check(ShieldConfig.ENABLED, "default master switch enabled");
        check(ShieldConfig.MAX_BYTES == 67_108_864 && ShieldConfig.MAX_PEER_BYTES == 8_388_608, "default memory quotas");
        check(ShieldConfig.PPS == 0 && ShieldConfig.PACKET_BURST == 0 && ShieldConfig.BPS == 0 && ShieldConfig.BYTE_BURST == 0,
            "frequency thresholds disabled by default");
        check(!ShieldConfig.RATE_ENFORCE, "frequency enforcement disabled by default");
        BacklogBudget budget = new BacklogBudget(ShieldConfig.MAX_BYTES, ShieldConfig.MAX_PEER_BYTES);
        var reservation = budget.reserve(1, 2048);
        check(reservation != null && reservation.bytes() == 2176 && budget.bytes() == 2176, "minimum packet actual accounting cost");
        budget.cancel(reservation);
        check(budget.bytes() == 0 && budget.trackedPeers() == 0, "default quota reservation released");
    }

    private static void minimum() {
        check(ShieldConfig.MAX_BYTES == 2176 && ShieldConfig.MAX_PEER_BYTES == 2176, "smallest supported configured quotas");
        BacklogBudget budget = new BacklogBudget(ShieldConfig.MAX_BYTES, ShieldConfig.MAX_PEER_BYTES);
        var reservation = budget.reserve(7, 2048);
        check(reservation != null && reservation.bytes() == 2176, "one minimum packet fits quota boundary");
        check(budget.reserve(8, 2048) == null && budget.reserve(7, 2048) == null, "boundary blocks additional peer/global packets");
        Object packet = new Object();
        budget.attach(packet, reservation);
        budget.release(packet);
        check(budget.bytes() == 0 && budget.reserve(8, 2048) != null, "quota reusable after packet cleanup");
    }

    private static void disabled() {
        check(!ShieldConfig.ENABLED && ShieldConfig.POOL_LIMIT == 0, "disabled master switch with zero pool setting");
        BoundedPoolQueue<Integer> pool = new BoundedPoolQueue<>();
        for (int i = 0; i < 8192; ++i) check(pool.offer(i), "disabled shield does not cap default pool");
        check(pool.size() == 8192, "disabled default pool retains all offered buffers");
        for (int i = 0; i < 8192; ++i) check(pool.poll() == i, "disabled default pool FIFO");
        check(pool.poll() == null && pool.isEmpty(), "disabled pool drains cleanly");
        check(!new BoundedPoolQueue<Integer>(0).offer(1), "explicit helper constructor still honors explicit zero limit");
    }

    private static void invalid(String expectedMessage) {
        try {
            Class.forName("pwshield.ShieldConfig", true, ConfigAndMetricsTests.class.getClassLoader());
            throw new AssertionError("Invalid configuration initialized successfully");
        } catch (ExceptionInInitializerError failure) {
            Throwable cause = failure.getCause();
            check(cause instanceof IllegalArgumentException, "invalid config rejected with an argument error");
            check(cause.getMessage() != null && cause.getMessage().contains(expectedMessage), "invalid config reports the relevant setting");
        } catch (ClassNotFoundException failure) {
            throw new AssertionError("ShieldConfig missing", failure);
        }
    }

    private static void metrics() throws Exception {
        check(!ShieldConfig.METRICS && !ShieldConfig.CONSOLE, "CSV tests run without scheduled reporter");
        Path path = ShieldConfig.CSV;
        check(!Files.exists(path), "CSV test starts with fresh path");
        Method report = ShieldHooks.class.getDeclaredMethod("report");
        report.setAccessible(true);
        report.invoke(null);
        checkCsv(path, 1, 0);

        Files.writeString(path, "", StandardCharsets.UTF_8);
        report.invoke(null);
        checkCsv(path, 1, 0);
        ShieldHooks.preAuthRejected();
        ShieldHooks.preAuthRejected();
        ShieldHooks.preAuthRejected();
        report.invoke(null);
        checkCsv(path, 2, 3);

        makeRotationSized(path, "first rotation\n");
        report.invoke(null);
        Path previous = path.resolveSibling(path.getFileName() + ".1");
        check(Files.size(previous) == 10_485_760L, "rotation preserves complete prior CSV");
        checkCsv(path, 1, 3);
        makeRotationSized(path, "second rotation\n");
        report.invoke(null);
        try (var input = Files.newInputStream(previous)) {
            check(new String(input.readNBytes(16), StandardCharsets.UTF_8).equals("second rotation\n"), "second rotation replaces previous CSV");
        }
        checkCsv(path, 1, 3);
        try (var files = Files.list(path.getParent())) {
            check(files.filter(file -> file.getFileName().toString().startsWith("performance.csv")).count() == 2,
                "only current CSV and one previous CSV retained");
        }
    }

    private static void makeRotationSized(Path path, String marker) throws Exception {
        Files.writeString(path, marker, StandardCharsets.UTF_8);
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(10_485_760L);
        }
    }

    private static void checkCsv(Path path, int expectedRows, long preauth) throws Exception {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        check(lines.size() == expectedRows + 1, "CSV row count and single header");
        String header = lines.getFirst();
        check(header.startsWith("utc,accepted_total,") && header.split(",", -1).length == 15, "CSV header schema");
        for (int i = 1; i < lines.size(); ++i) {
            String[] fields = lines.get(i).split(",", -1);
            check(fields.length == 15, "CSV row matches schema width");
            Instant.parse(fields[0]);
            for (int column = 1; column < fields.length; ++column) check(Long.parseLong(fields[column]) >= 0, "nonnegative metric counter");
        }
        check(Long.parseLong(lines.getLast().split(",", -1)[4]) == preauth, "CSV cumulative preauth count");
    }
}
