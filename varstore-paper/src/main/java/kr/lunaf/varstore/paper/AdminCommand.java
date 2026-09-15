package kr.lunaf.varstore.paper;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import kr.lunaf.varstore.api.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

final class AdminCommand implements CommandExecutor, Listener {
    private final VarStorePlugin plugin;
    private final VarStore store;
    private final ConfirmationTokens<Pending> confirmations = new ConfirmationTokens<>(Clock.systemUTC(), Duration.ofSeconds(60));
    private final Map<UUID, UUID> sessions = new HashMap<>();
    private final long[] errorSamples = new long[60];
    private int errorCursor;

    AdminCommand(VarStorePlugin plugin, VarStore store) {
        this.plugin = plugin;
        this.store = store;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            errorSamples[errorCursor] = store.metrics().storageErrors();
            errorCursor = (errorCursor + 1) % errorSamples.length;
        }, 20L, 20L);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { sessions.remove(event.getPlayer().getUniqueId()); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { usage(sender); return true; }
        String action = args[0].toLowerCase(java.util.Locale.ROOT);
        String permission = switch (action) {
            case "status" -> "varstore.status";
            case "diagnostics", "operation" -> "varstore.diagnostics";
            case "inspect" -> "varstore.inspect";
            case "set", "delete", "confirm" -> "varstore.modify";
            default -> null;
        };
        if (permission == null) { usage(sender); return true; }
        if (!(sender instanceof ConsoleCommandSender) && !sender.hasPermission(permission)) {
            sender.sendMessage("Permission denied."); return true;
        }
        if (permission.equals("varstore.modify") && !(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("Modification commands are console-only."); return true;
        }
        Reply reply = reply(sender);
        try {
            switch (action) {
                case "status", "diagnostics" -> sender.sendMessage("VarStore state=" + store.state() + " schema=" + (store.state() == StoreState.READY ? "1(validated)" : "unverified") + " lastError=" + store.lastError() + " recentErrors60s=" + Math.max(0, store.metrics().storageErrors() - errorSamples[errorCursor]) + " metrics=" + store.metrics());
                case "operation" -> {
                    if (args.length != 3) throw new IllegalArgumentException();
                    complete(store.namespace(args[1]).operation(UUID.fromString(args[2])), reply,
                            result -> "Operation: " + result);
                }
                case "confirm" -> {
                    if (args.length != 2) throw new IllegalArgumentException();
                    Optional<Pending> pending = confirmations.consume(reply.actor(), args[1]);
                    if (pending.isEmpty()) { sender.sendMessage("Invalid or expired confirmation token."); break; }
                    Pending request = pending.get();
                    complete(store.namespace(request.namespace()).execute(request.plan(), request.operationId()), reply,
                            result -> "Operation " + request.operationId() + ": " + result);
                }
                case "inspect", "set", "delete" -> addressCommand(action, args, reply);
                default -> usage(sender);
            }
        } catch (RuntimeException error) { sender.sendMessage("Invalid command or address. Use /varstore for syntax."); }
        return true;
    }

    private void addressCommand(String action, String[] args, Reply reply) {
        if ((action.equals("set") && args.length < 10) || (!action.equals("set") && args.length != 9))
            throw new IllegalArgumentException();
        if (!args[1].equals(plugin.getConfig().getString("network-id"))) throw new IllegalArgumentException();
        VarStore.Namespace namespace = store.namespace(args[2]);
        VarStore.Scope scope;
        if (args[3].equals("NETWORK") && args[4].equals("_")) scope = namespace.network();
        else if (args[3].equals("SERVER")) scope = namespace.server(args[4]);
        else throw new IllegalArgumentException();
        VarStore.Data data = scope.owner(new Owner(args[5], args[6]));
        switch (args[8]) {
            case "STRING" -> typed(action, data, VarKey.stringKey(args[7]), args.length >= 10 ? String.join(" ", Arrays.copyOfRange(args, 9, args.length)) : null, args[2], reply);
            case "LONG" -> typed(action, data, VarKey.longKey(args[7]), args.length == 10 ? Long.valueOf(args[9]) : null, args[2], reply);
            case "BOOLEAN" -> {
                if (action.equals("set") && (args.length != 10 || !(args[9].equals("true") || args[9].equals("false")))) throw new IllegalArgumentException();
                typed(action, data, VarKey.booleanKey(args[7]), args.length == 10 ? Boolean.valueOf(args[9]) : null, args[2], reply);
            }
            case "UUID" -> typed(action, data, VarKey.uuidKey(args[7]), args.length == 10 ? UUID.fromString(args[9]) : null, args[2], reply);
            default -> throw new IllegalArgumentException();
        }
    }

    private <T> void typed(String action, VarStore.Data data, VarKey<T> key, T value, String namespace, Reply reply) {
        if (action.equals("set") && value == null) throw new IllegalArgumentException();
        data.getVersioned(key).whenComplete((current, error) -> onMain(reply, sender -> {
            if (error != null) { sender.sendMessage(safeError(error)); return; }
            if (action.equals("inspect")) {
                sender.sendMessage("Address=" + data.address(key) + " type=" + key.type() + " value/version=" + current);
                return;
            }
            try {
                Target<T> target = data.target(key);
                UUID operationId = UUID.randomUUID();
                TransactionPlan plan = AdminPlan.create(reply.actor(), action, target, current.map(VersionedValue::version), value);
                String token = confirmations.issue(reply.actor(), new Pending(namespace, plan, operationId));
                sender.sendMessage("Preview: " + action + " " + data.address(key) + " expected=" + current.map(VersionedValue::version)
                        + " value=" + value + " operation=" + operationId);
                sender.sendMessage("Confirm within 60s: /varstore confirm " + token);
            } catch (RuntimeException invalid) { sender.sendMessage("Invalid modification or confirmation capacity reached; nothing was submitted."); }
        }));
    }

    private <T> void complete(CompletionStage<T> stage, Reply reply, java.util.function.Function<T, String> render) {
        stage.whenComplete((result, error) -> onMain(reply, sender -> sender.sendMessage(error == null ? render.apply(result) : safeError(error))));
    }
    private Reply reply(CommandSender sender) {
        if (sender instanceof Player player) {
            UUID id = player.getUniqueId();
            return new Reply(id, sessions.computeIfAbsent(id, unused -> UUID.randomUUID()), "player:" + id);
        }
        return new Reply(null, null, "console");
    }
    private void onMain(Reply reply, java.util.function.Consumer<CommandSender> action) {
        try { plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled()) return;
            if (reply.player() == null) { action.accept(plugin.getServer().getConsoleSender()); return; }
            if (!reply.session().equals(sessions.get(reply.player()))) return;
            Player player = plugin.getServer().getPlayer(reply.player());
            if (player != null && player.isOnline()) action.accept(player);
        }); } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { }
    }
    static String safeError(Throwable error) {
        while ((error instanceof CompletionException || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null) error = error.getCause();
        return error instanceof VarStoreException storeError ? "Storage request failed: " + storeError.code() + (storeError.operationId() == null ? "" : " operation=" + storeError.operationId()) : "Storage request failed.";
    }
    private void usage(CommandSender sender) {
        sender.sendMessage("/varstore status|diagnostics; /varstore operation <namespace> <operation-id>");
        sender.sendMessage("/varstore inspect|set|delete <network> <namespace> <NETWORK|SERVER> <scope-id> <owner-type> <owner-id> <key> <STRING|LONG|BOOLEAN|UUID> [value]");
        sender.sendMessage("/varstore confirm <token> (console-only, valid 60 seconds)");
    }
    private record Reply(UUID player, UUID session, String actor) { }
    private record Pending(String namespace, TransactionPlan plan, UUID operationId) { }
}
