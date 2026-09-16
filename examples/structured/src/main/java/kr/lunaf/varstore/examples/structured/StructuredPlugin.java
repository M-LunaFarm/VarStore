package kr.lunaf.varstore.examples.structured;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.codec.*;
import kr.lunaf.varstore.paper.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;

/** Explicit small DTO storage. It never serializes Player, World, or arbitrary services. */
public final class StructuredPlugin extends JavaPlugin {
    /** Copy user input into this immutable record on the game thread before worker submission. */
    public record ProfileSettings(String language, boolean notifications) {
        public ProfileSettings {
            if (language == null || !language.matches("[a-z]{2}_[a-z]{2}"))
                throw new IllegalArgumentException("Language must look like ko_kr or en_us");
        }
    }

    private static final Codec<ProfileSettings> CODEC = new Codec<>() {
        public String id() { return "varstore.profile-settings"; }
        public int schemaVersion() { return 1; }
        public JsonValue encode(ProfileSettings settings) {
            return JsonValue.object(Map.of("language", JsonValue.string(settings.language()),
                    "notifications", JsonValue.bool(settings.notifications())));
        }
        public ProfileSettings decode(int version, JsonValue payload) {
            var fields = ((JsonValue.ObjectValue) payload).fields();
            if (!fields.keySet().equals(Set.of("language", "notifications")))
                throw new IllegalArgumentException("Unexpected profile fields");
            return new ProfileSettings(((JsonValue.StringValue) fields.get("language")).value(),
                    ((JsonValue.BooleanValue) fields.get("notifications")).value());
        }
    };
    private static final CodecKey<ProfileSettings> SETTINGS = CodecKey.of("profile/settings", CODEC);
    private final Set<CompletableFuture<?>> preparations = ConcurrentHashMap.newKeySet();
    private PaperSessions sessions;
    private CodecAdapter adapter;
    private ThreadPoolExecutor serializer;
    private VarStore store;
    private VarStore.Namespace namespace;
    private volatile boolean stopping;

    @Override public void onEnable() {
        store = Objects.requireNonNull(getServer().getServicesManager().load(VarStore.class));
        PaperVarStore registrations = Objects.requireNonNull(getServer().getServicesManager().load(PaperVarStore.class));
        namespace = registrations.register(this, "varstorestructured");
        sessions = new PaperSessions(this);
        adapter = sessions.own(new CodecAdapter(1, 32));
        serializer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "varstore-example-profile-prepare"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        // Metadata only: no automatic database initialization or implicit object interpretation.
        sessions.own(registrations.define(this, "varstorestructured", new KeyDefinition<>(SETTINGS.storageKey(), "",
                "Explicit profile settings JSON envelope", false, CachePolicy.DISABLED, 1)));
        Objects.requireNonNull(getCommand("profilesettings")).setExecutor(this);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Player required."); return true; }
        if (store.state() != StoreState.READY) { player.sendMessage("Profile storage unavailable; no default or write was applied."); return true; }
        PaperSessions.Session session = sessions.capture(player.getUniqueId());
        ObjectData data = adapter.data(namespace.network().player(player.getUniqueId()));
        if (args.length == 1 && args[0].equalsIgnoreCase("get")) {
            sessions.complete(session, data.get(SETTINGS), (current, value) -> current.sendMessage(value.map(settings ->
                    "Profile settings: language=" + settings.language() + " notifications=" + settings.notifications()).orElse("Profile settings: not set")),
                    (current, error) -> current.sendMessage("Profile read failed: " + errorCode(error)));
            return true;
        }
        if (args.length != 4 || !args[0].equalsIgnoreCase("save")) return false;
        try {
            if (!args[2].equals("true") && !args[2].equals("false")) throw new IllegalArgumentException("Expected true or false");
            ProfileSettings snapshot = new ProfileSettings(args[1], Boolean.parseBoolean(args[2]));
            UUID operationId = UUID.fromString(args[3]);
            if (!operationId.toString().equals(args[3])) throw new IllegalArgumentException("Use a canonical operation UUID");
            // The caller supplies a stable business ID. Reuse this ID AND these same
            // settings after an uncertain response; another payload must use its own ID.
            var write = prepare(snapshot).thenCompose(encoded -> data.set(SETTINGS, encoded, operationId));
            sessions.complete(session, write,
                    (current, receipt) -> current.sendMessage("Profile settings committed: outcome=" + receipt.outcome() + " operation=" + receipt.operationId()),
                    (current, error) -> current.sendMessage("Profile save failed/unresolved: " + errorCode(error)
                            + " operation=" + operationId + "; keep this ID and the original settings for confirmation."));
        } catch (IllegalArgumentException invalid) {
            player.sendMessage("Usage: /profilesettings save <ko_kr|en_us> <true|false> <persisted-operation-uuid>");
        }
        return true;
    }

    private CompletionStage<EncodedValue<ProfileSettings>> prepare(ProfileSettings immutableSnapshot) {
        CompletableFuture<EncodedValue<ProfileSettings>> result = new CompletableFuture<>();
        preparations.add(result);
        result.whenComplete((value, error) -> preparations.remove(result));
        if (stopping) result.completeExceptionally(new IllegalStateException("Consumer stopped"));
        else try {
            serializer.execute(() -> {
                try { result.complete(adapter.prepare(SETTINGS, immutableSnapshot)); }
                catch (RuntimeException error) { result.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException overloaded) { result.completeExceptionally(new CodecException(CodecError.OVERLOADED, "Profile serializer capacity reached")); }
        return result.minimalCompletionStage();
    }

    private static String errorCode(Throwable error) {
        Throwable cause = PaperSessions.unwrap(error);
        if (cause instanceof CodecException codec) return codec.code().name();
        if (cause instanceof VarStoreException storage) return storage.code().name();
        return "CONSUMER_UNAVAILABLE";
    }

    @Override public void onDisable() {
        stopping = true;
        if (sessions != null) sessions.close();
        if (serializer != null) serializer.shutdownNow();
        for (CompletableFuture<?> pending : preparations) pending.completeExceptionally(new IllegalStateException("Consumer stopped"));
        preparations.clear();
    }
}
