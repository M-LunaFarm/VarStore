package kr.lunaf.varstore.paper;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import kr.lunaf.varstore.core.StoreConfig;
import kr.lunaf.varstore.postgres.PostgresSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

final class PaperConfiguration {
    private PaperConfiguration() { }
    static StoreConfig read(FileConfiguration config) {
        exact(config, "storage.driver", "postgres");
        exact(config, "storage.schema-mode", "validate");
        exact(config, "storage.durability-check", "strict");
        exact(config, "operations.full-result-retention", "7d");
        exact(config, "operations.dedup-marker-retention", "forever");
        if (config.getBoolean("cache.enabled")) throw new IllegalArgumentException("Cache is not supported");
        fixedLimit(config, "limits.max-value-bytes", 16384);
        fixedLimit(config, "limits.max-batch-read-keys", 64);
        fixedLimit(config, "limits.max-transaction-keys", 16);
        fixedLimit(config, "limits.max-transaction-bytes", 65536);
        return new StoreConfig(new PostgresSettings(
                environment(config, "storage.jdbc-url-env"), environment(config, "storage.username-env"),
                environment(config, "storage.password-env"), config.getString("network-id"),
                config.getString("server-id"), config.getString("storage.tls-mode"), true,
                config.getInt("storage.maximum-pool-size"), duration(config, "execution.connection-acquire-timeout"),
                duration(config, "execution.lock-timeout"), duration(config, "execution.statement-timeout")),
                config.getInt("execution.db-workers"), config.getInt("execution.queue-max-requests"),
                config.getLong("execution.queue-max-bytes"), duration(config, "execution.request-timeout"),
                duration(config, "execution.shutdown-drain-timeout"), config.getInt("execution.max-attempts"));
    }
    static Map<String, Set<String>> shared(FileConfiguration config) {
        Map<String, Set<String>> result = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("shared-namespaces");
        if (section != null) for (String name : section.getKeys(false))
            result.put(name, new HashSet<>(section.getStringList(name)));
        return result;
    }
    private static String environment(FileConfiguration config, String key) {
        String name = config.getString(key);
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("Invalid environment variable setting: " + key);
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable: " + name);
        return value;
    }
    private static void exact(FileConfiguration config, String key, String expected) {
        if (!expected.equals(config.getString(key))) throw new IllegalArgumentException(key + " must be " + expected);
    }
    private static void fixedLimit(FileConfiguration config, String key, int expected) {
        if (config.getInt(key) != expected) throw new IllegalArgumentException(key + " must be " + expected + " in this release");
    }
    static Duration parseDuration(String value) {
        if (value == null || !value.matches("[1-9][0-9]*(ms|s|m)"))
            throw new IllegalArgumentException("Duration must be a positive integer followed by ms, s or m");
        if (value.endsWith("ms")) return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
        long amount = Long.parseLong(value.substring(0, value.length() - 1));
        return value.endsWith("s") ? Duration.ofSeconds(amount) : Duration.ofMinutes(amount);
    }
    private static Duration duration(FileConfiguration config, String key) { return parseDuration(config.getString(key)); }
}
