package kr.lunaf.varstore.skript;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.Skript;
import ch.njol.skript.effects.Delay;
import ch.njol.skript.lang.*;
import ch.njol.skript.registrations.EventValues;
import ch.njol.skript.variables.Variables;
import ch.njol.util.Kleenean;
import java.util.*;
import java.util.concurrent.*;
import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.paper.PaperSessions;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.skriptlang.skript.lang.script.Script;

/** A true completion-driven continuation: expressions and local variables stay on the main thread. */
public final class StorageEffect extends Effect {
    private Expression<?>[] expressions;
    private int pattern;
    private Variable<?> output;
    private record Result(String status, Object value, String error, String operation, String cursor, List<String> keys) { }
    @Override public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean delayed, SkriptParser.ParseResult parsed) {
        this.expressions = expressions; pattern = matchedPattern;
        if (pattern == 1) {
            expressions[2] = ch.njol.skript.util.LiteralUtils.defendExpression(expressions[2]);
            if (!ch.njol.skript.util.LiteralUtils.canInitSafely(expressions[2])) return false;
        }
        if (!(expressions[expressions.length - 1] instanceof Variable<?> variable) || !variable.isLocal() || !variable.isList()) {
            Skript.error("VarStore results require a local list variable, e.g. {_result::*}"); return false;
        }
        output = variable;
        getParser().setHasDelayBefore(Kleenean.TRUE);
        return true;
    }
    @Override protected void execute(Event event) { throw new UnsupportedOperationException("Continuation uses walk"); }
    @Override protected TriggerItem walk(Event event) {
        PaperSessions.requireMainThread();
        VarStoreSkriptPlugin plugin = VarStoreSkriptPlugin.instance;
        if (plugin == null || !plugin.isEnabled()) return null;
        Script script = getTrigger() == null ? null : getTrigger().getScript();
        Player player = (Player) single(expressions.length - 2, event);
        if (player == null && expressions[expressions.length - 2] != null) return null;
        if (player == null) player = EventValues.getEventValue(event, Player.class, 0);
        if (player == null && event instanceof ch.njol.skript.command.CommandEvent command && command.getSender() instanceof Player sender) player = sender;
        PaperSessions.Session session = player == null ? null : plugin.sessions.capture(player.getUniqueId());
        String outputName = output.getName().toString(event);
        String prefix = outputName.substring(0, outputName.length() - 1);
        CompletionStage<Result> request;
        String operation = "";
        try {
            int nsIndex = pattern == 1 ? 3 : pattern == 4 ? 1 : 2;
            String ns = text(nsIndex, event), scope = text(nsIndex + 1, event), owner = text(nsIndex + 2, event);
            VarStore.Namespace namespace = plugin.registrations.register(plugin, ns);
            VarStore.Scope addressed = scope.equals("network") ? namespace.network()
                    : scope.startsWith("server:") ? namespace.server(scope.substring(7)) : invalid("Scope must be network or server:<id>");
            VarStore.Data data = owner.startsWith("player:") ? addressed.player(UUID.fromString(owner.substring(7)))
                    : owner.startsWith("system:") ? addressed.system(owner.substring(7)) : invalid("Owner must be player:<uuid> or system:<id>");
            if (pattern == 4) {
                String cursor = text(4, event);
                int limit = Math.toIntExact(exactLong(single(5, event)));
                request = plugin.extensions.scanKeys(data, text(0, event), cursor.isEmpty() ? Optional.empty() : Optional.of(cursor), limit)
                    .thenApply(page -> new Result("SUCCESS", null, "", "", page.nextCursor().orElse(""), page.keys().stream().map(key -> key.key() + ":" + key.type() + ":" + key.version()).toList()));
            } else {
                String keyName = text(1, event);
                ValueType type = pattern == 2 ? ValueType.LONG : ValueType.valueOf(text(0, event).toUpperCase(Locale.ROOT));
                VarKey<?> key = switch (type) { case STRING -> VarKey.stringKey(keyName); case LONG -> VarKey.longKey(keyName); case BOOLEAN -> VarKey.booleanKey(keyName); case UUID -> VarKey.uuidKey(keyName); };
                if (pattern == 0) request = data.get(key).thenApply(value -> new Result(value.isPresent() ? "VALUE" : "ABSENT", value.orElse(null), "", "", "", List.of()));
                else {
                    operation = text(nsIndex + 3, event); UUID id = UUID.fromString(operation); String operationId = operation;
                    CompletionStage<? extends WriteReceipt<?>> write = switch (pattern) {
                        case 1 -> set(data, key, single(2, event), id);
                        case 2 -> data.increment(VarKey.longKey(keyName), exactLong(single(0, event)), id);
                        case 3 -> data.delete(key, id);
                        default -> throw new IllegalArgumentException();
                    };
                    request = write.thenApply(receipt -> new Result(receipt.outcome().name(), receipt.value().orElse(null), "", operationId, "", List.of()));
                }
            }
        } catch (RuntimeException error) { request = CompletableFuture.failedFuture(error); }
        String operationId = operation;
        Delay.addDelayedEvent(event);
        Object locals = Variables.removeLocals(event);
        request.whenComplete((result, error) -> {
            if (plugin.stopping) return;
            try { plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!plugin.isEnabled() || !ch.njol.skript.Skript.getInstance().isEnabled()
                        || (script != null && !ScriptLoader.getLoadedScripts().contains(script))
                        || (session != null && !plugin.sessions.isCurrent(session))) return;
                if (locals != null) Variables.setLocalVariables(event, locals);
                try {
                    Variables.setVariable(outputName, null, event, true);
                    Result delivered = result;
                    if (error != null) {
                        Throwable cause = PaperSessions.unwrap(error);
                        String code = cause instanceof VarStoreException failure ? failure.code().name() : "INVALID_ARGUMENT";
                        delivered = new Result("FAILED", null, code, operationId, "", List.of());
                    }
                    Variables.setVariable(prefix + "status", delivered.status(), event, true);
                    Variables.setVariable(prefix + "value", delivered.value(), event, true);
                    Variables.setVariable(prefix + "error", delivered.error(), event, true);
                    Variables.setVariable(prefix + "operation", delivered.operation(), event, true);
                    Variables.setVariable(prefix + "cursor", delivered.cursor(), event, true);
                    for (int i = 0; i < delivered.keys().size(); i++) Variables.setVariable(prefix + "keys::" + (i + 1), delivered.keys().get(i), event, true);
                    if (getNext() != null) TriggerItem.walk(getNext(), event);
                } finally { Variables.removeLocals(event); }
            }); } catch (org.bukkit.plugin.IllegalPluginAccessException disabled) { /* Continuation discarded on shutdown. */ }
        });
        return null;
    }
    private Object single(int index, Event event) { return expressions[index] == null ? null : expressions[index].getSingle(event); }
    private String text(int index, Event event) { return Objects.requireNonNull((String) single(index, event), "Required string missing"); }
    private static <T> T invalid(String message) { throw new IllegalArgumentException(message); }
    private static long exactLong(Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("LONG requires an integer");
        return new java.math.BigDecimal(number.toString()).longValueExact();
    }
    private static <T> CompletionStage<WriteReceipt<T>> set(VarStore.Data data, VarKey<T> key, Object value, UUID id) {
        Object typed = switch (key.type()) {
            case LONG -> exactLong(value);
            case UUID -> value instanceof UUID ? value : UUID.fromString((String) value);
            case STRING, BOOLEAN -> value;
        };
        key.type().validate(typed);
        @SuppressWarnings("unchecked") T cast = (T) typed;
        return data.set(key, cast, id);
    }
    @Override public String toString(Event event, boolean debug) { return "VarStore asynchronous " + pattern; }
}
