package kr.lunaf.varstore.paper;

import kr.lunaf.varstore.api.StoreState;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Dispatched on the Paper main thread after a storage state transition. */
public final class VarStoreStateEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final StoreState state;
    public VarStoreStateEvent(StoreState state) { this.state = state; }
    public StoreState state() { return state; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
