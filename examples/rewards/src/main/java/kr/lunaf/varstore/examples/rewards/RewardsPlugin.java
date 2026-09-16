package kr.lunaf.varstore.examples.rewards;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.paper.PaperVarStore;
import kr.lunaf.varstore.paper.PaperSessions;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** The reward itself is a DB point balance, committed atomically with the daily ledger. */
public final class RewardsPlugin extends JavaPlugin implements Listener {
    private static final VarKey<Long> DAY = VarKey.longKey("last-claim-utc-day");
    private static final VarKey<Long> POINTS = VarKey.longKey("reward-points");
    private PaperSessions sessions;
    private VarStore.Namespace namespace;
    private VarStoreExtensions extensions;

    @Override public void onEnable() {
        PaperVarStore registrations = getServer().getServicesManager().load(PaperVarStore.class);
        if (registrations == null) throw new IllegalStateException("VarStore service missing");
        namespace = registrations.register(this, "varstorerewards");
        extensions = java.util.Objects.requireNonNull(getServer().getServicesManager().load(VarStoreExtensions.class));
        sessions = new PaperSessions(this);
        sessions.own(java.util.Objects.requireNonNull(getServer().getServicesManager().load(PaperVarStore.class)).define(this, "varstorerewards", new KeyDefinition<>(POINTS, 0L, "Database reward point balance", false, CachePolicy.DISPLAY_ONLY, 1)));
        sessions.own(registrations.define(this, "varstorerewards", new KeyDefinition<>(DAY, -1L, "Last UTC reward claim day", true, CachePolicy.DISABLED, 1)));
        getServer().getPluginManager().registerEvents(this, this);
        java.util.Objects.requireNonNull(getCommand("dailyreward")).setExecutor(this);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("This command requires a player."); return true; }
        if (args.length > 1) return false;
        UUID id = player.getUniqueId();
        PaperSessions.Session session = sessions.capture(id);
        VarStore.Data data = namespace.network().player(id);
        if (args.length == 1 && args[0].equals("balance")) {
            data.get(POINTS).whenComplete((balance, error) -> online(id, session, current -> current.sendMessage(
                    error == null ? "Reward points: " + balance.orElse(0L) : "Storage unavailable; balance was not loaded.")));
            return true;
        }
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        if (args.length == 1) {
            // Explicit dates only resolve prior operations: they never create a backdated reward.
            try { date = LocalDate.parse(args[0]); } catch (java.time.format.DateTimeParseException invalid) { return false; }
            UUID operation = operationId(id, "claim/" + date);
            namespace.operation(operation).whenComplete((status, error) -> online(id, session, current -> current.sendMessage(
                    error == null ? "Reward operation " + operation + ": " + status : "Cannot resolve reward operation " + operation)));
            return true;
        }
        long day = date.toEpochDay();
        UUID operation = operationId(id, "claim/" + date);
        String lookup = "/dailyreward " + date;
        // Stable initialization IDs and stable day-based claim IDs survive restart and server transfer.
        CompletionStage<TransactionReceipt> claim = initialize(data, DAY, -1L, operationId(id, "initialize-day"))
                .thenCompose(unused -> initialize(data, POINTS, 0L, operationId(id, "initialize-points")))
                .thenCompose(unused -> extensions.pendingWrites().execute("varstorerewards", TransactionPlan.builder()
                        .requireLongRange(data.target(DAY), -1L, day - 1)
                        .set(data.target(DAY), day)
                        .increment(data.target(POINTS), 1L).build(), operation, PendingWrites.Policy.defaults()))
                .thenApply(result -> result.receipt().orElseThrow(() -> new VarStoreException(ErrorCode.UNKNOWN_COMMIT_OUTCOME, "Reward needs reconciliation", operation)));
        claim.whenComplete((receipt, error) -> online(id, session, current -> {
            if (error != null) {
                current.sendMessage("Reward unresolved/failed. Run " + lookup + " to inspect the original operation; retry today keeps the same ID.");
            } else if (receipt.outcome() == Outcome.CONDITION_FAILED) {
                current.sendMessage("Reward was already claimed for this UTC day (or a later day).");
            } else {
                current.sendMessage(receipt.replayed() ? "Daily reward already committed; duplicate request did not add points."
                        : "Daily reward committed: +1 point. /dailyreward balance");
            }
        }));
        return true;
    }

    private CompletionStage<Void> initialize(VarStore.Data data, VarKey<Long> key, long value, UUID operation) {
        return data.get(key).thenCompose(current -> current.isPresent()
                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : data.setIfAbsent(key, value, operation).thenApply(unused -> null));
    }

    static UUID operationId(UUID player, String businessEvent) {
        return UUID.nameUUIDFromBytes(("varstore-rewards/v1/" + player + "/" + businessEvent).getBytes(StandardCharsets.UTF_8));
    }
    private void online(UUID id, PaperSessions.Session session, java.util.function.Consumer<Player> action) {
        sessions.run(session, action);
    }
    @Override public void onDisable() { if (sessions != null) sessions.close(); }
}
