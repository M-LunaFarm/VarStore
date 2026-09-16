package kr.lunaf.varstore.api;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.DrbgParameters;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/** Cryptographically unpredictable runtime IDs with no entropy I/O after initialization.
 *
 * <p>The provider calls {@link #initialize()} on its background initialization thread
 * before READY. It obtains a 256-bit secret from the standard JDK DRBG and warms a
 * standard HmacSHA256 engine. Each ID is a keyed, domain-separated counter output
 * truncated to UUID v4's 122 random bits. The secret lives only in this classloader's
 * process memory; it is neither a persisted business ID ledger nor an encryption key
 * API. Restart creates a new secret. JVM memory compromise is outside this guarantee.</p>
 *
 * <p>Oracle's DRBG contract permits automatic reseeding, even when prediction
 * resistance is not requested. Consequently random() never calls SecureRandom at
 * all: only initialization can obtain entropy. No weak or blocking fallback exists.</p>
 *
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/DrbgParameters.html">Java 21 DRBG contract</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Mac.html">Java 21 MAC contract</a>
 */
public final class RuntimeIds {
    private static final Object INITIALIZATION = new Object();
    private static volatile Generator generator;
    private RuntimeIds() {}

    /** Initializes once; may perform entropy/provider I/O and must run off game threads.
     * All expensive work finishes before publication. Concurrent random() calls fail
     * immediately with NOT_READY instead of waiting for this initialization lock. */
    public static void initialize() {
        if (generator != null) return;
        synchronized (INITIALIZATION) {
            if (generator != null) return;
            byte[] secret = new byte[32];
            try {
                SecureRandom entropy = SecureRandom.getInstance("DRBG", DrbgParameters.instantiation(
                        256, DrbgParameters.Capability.NONE, "VarStore RuntimeIds seed v1".getBytes(StandardCharsets.US_ASCII)));
                entropy.nextBytes(secret, DrbgParameters.nextBytes(256, false, null));
                // Pin the JDK software MAC provider: no HSM or user-configured provider
                // with hidden device access is introduced into the gameplay call path.
                Mac mac = Mac.getInstance("HmacSHA256", "SunJCE");
                mac.init(new SecretKeySpec(secret, "HmacSHA256"));
                Generator prepared = new Generator(mac);
                prepared.next(); // Warm MAC execution and UUID construction before READY.
                generator = prepared;
            } catch (GeneralSecurityException error) {
                throw new VarStoreException(ErrorCode.NOT_READY, "Runtime ID cryptography could not initialize", null, error);
            } finally { Arrays.fill(secret, (byte) 0); }
        }
    }

    public static boolean isInitialized() { return generator != null; }

    /** CPU-only UUID v4 generation after initialization. Only the short MAC/counter
     * operation is serialized; the entropy initializer lock is never acquired here.
     * Counter exhaustion fails closed and requires process restart. */
    public static UUID random() {
        Generator ready = generator;
        if (ready == null) throw new VarStoreException(ErrorCode.NOT_READY, "Runtime IDs are not initialized");
        return ready.next();
    }

    private static final class Generator {
        private final Mac mac;
        private final byte[] input;
        private long counter;
        Generator(Mac mac) {
            this.mac = mac;
            byte[] domain = "VarStore UUID v1\0".getBytes(StandardCharsets.US_ASCII);
            input = Arrays.copyOf(domain, domain.length + Long.BYTES);
        }
        synchronized UUID next() {
            if (counter == Long.MAX_VALUE)
                throw new VarStoreException(ErrorCode.NOT_READY, "Runtime ID counter exhausted; restart required");
            long sequence = ++counter;
            for (int i = 0; i < Long.BYTES; i++) input[input.length - 1 - i] = (byte) (sequence >>> (8 * i));
            // doFinal resets the MAC to its initialized key state; it does not seed,
            // reseed, create providers or obtain randomness.
            byte[] digest = mac.doFinal(input);
            long high = 0, low = 0;
            for (int i = 0; i < Long.BYTES; i++) { high = (high << 8) | (digest[i] & 255L); low = (low << 8) | (digest[i + 8] & 255L); }
            high = (high & 0xffffffffffff0fffL) | 0x0000000000004000L;
            low = (low & 0x3fffffffffffffffL) | 0x8000000000000000L;
            return new UUID(high, low);
        }
    }
}
