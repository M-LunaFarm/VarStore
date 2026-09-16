package kr.lunaf.varstore.core;

import kr.lunaf.varstore.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class LocalKeyRegistry implements KeyRegistry, AutoCloseable {
    private record Key(String namespace, String key) { }
    private static final class Entry {
        final KeyDefinition<?> definition; int references = 1;
        Entry(KeyDefinition<?> definition) { this.definition = definition; }
    }
    private final Map<Key, Entry> definitions = new HashMap<>();
    private boolean closed;
    private int registrations;
    @Override public synchronized AutoCloseable register(String namespace, KeyDefinition<?> definition) {
        Names.identifier(namespace, "namespace"); Objects.requireNonNull(definition);
        if (closed) throw new VarStoreException(ErrorCode.SHUTTING_DOWN, "Definition registry is closed");
        if (registrations >= 8192) throw new VarStoreException(ErrorCode.OVERLOADED, "Definition registration limit reached");
        Key key = new Key(namespace, definition.key().name()); Entry existing = definitions.get(key);
        if (existing != null) {
            if (!existing.definition.equals(definition)) throw new VarStoreException(ErrorCode.TYPE_MISMATCH, "Conflicting local key definition");
            existing.references++;
        } else {
            if (definitions.size() >= 4096) throw new VarStoreException(ErrorCode.OVERLOADED, "Definition registry limit reached");
            definitions.put(key, new Entry(definition));
        }
        registrations++;
        Entry entry = definitions.get(key); AtomicBoolean released = new AtomicBoolean();
        return () -> { synchronized (LocalKeyRegistry.this) {
            if (released.compareAndSet(false, true)) { registrations--; if (--entry.references == 0) definitions.remove(key, entry); }
        }};
    }
    @Override public synchronized Optional<KeyDefinition<?>> find(String namespace, String key) {
        Names.identifier(namespace, "namespace"); Names.key(key);
        Entry entry = definitions.get(new Key(namespace, key));
        return entry == null ? Optional.empty() : Optional.of(entry.definition);
    }
    @Override public synchronized List<KeyDefinition<?>> list(String namespace) {
        Names.identifier(namespace, "namespace");
        return definitions.entrySet().stream().filter(e -> e.getKey().namespace.equals(namespace))
                .sorted(Comparator.comparing(e -> e.getKey().key)).<KeyDefinition<?>>map(e -> e.getValue().definition).toList();
    }
    @Override public synchronized void close() { closed = true; definitions.clear(); }
}
