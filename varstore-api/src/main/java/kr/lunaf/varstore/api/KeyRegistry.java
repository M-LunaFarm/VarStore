package kr.lunaf.varstore.api;

import java.util.List;
import java.util.Optional;

/** Bounded, process-local definitions. Registering does not synchronize definitions across servers. */
public interface KeyRegistry {
    AutoCloseable register(String namespace, KeyDefinition<?> definition);
    Optional<KeyDefinition<?>> find(String namespace, String key);
    List<KeyDefinition<?>> list(String namespace);
}
