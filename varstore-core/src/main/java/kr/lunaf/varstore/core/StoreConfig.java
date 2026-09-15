package kr.lunaf.varstore.core;

import java.time.Duration;
import java.util.Objects;
import kr.lunaf.varstore.postgres.PostgresSettings;

/** Execution limits include requests awaiting callback delivery. */
public record StoreConfig(PostgresSettings storage, int dbWorkers, int queueMaxRequests,
        long queueMaxBytes, Duration requestTimeout, Duration shutdownDrainTimeout, int maxAttempts) {
    public StoreConfig {
        Objects.requireNonNull(storage);
        Objects.requireNonNull(requestTimeout);
        Objects.requireNonNull(shutdownDrainTimeout);
        if (dbWorkers < 1 || dbWorkers > 64 || queueMaxRequests < 1 || queueMaxRequests > 65536
                || queueMaxBytes < 1024 || queueMaxBytes > 1_073_741_824L
                || requestTimeout.isNegative() || requestTimeout.isZero() || requestTimeout.compareTo(Duration.ofMinutes(1)) > 0
                || shutdownDrainTimeout.isNegative() || shutdownDrainTimeout.compareTo(Duration.ofMinutes(1)) > 0
                || maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException("Invalid execution limits");
        }
    }
    public static StoreConfig defaults(PostgresSettings storage) {
        return new StoreConfig(storage, 4, 256, 8_388_608, Duration.ofSeconds(3), Duration.ofSeconds(10), 3);
    }
}
