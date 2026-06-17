package local.mmm.vaultsync.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class VaultSyncPhaseChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final SyncPhase previousPhase;
    private final SyncPhase currentPhase;

    public VaultSyncPhaseChangeEvent(SyncPhase previousPhase, SyncPhase currentPhase) {
        this.previousPhase = previousPhase;
        this.currentPhase = currentPhase;
    }

    public SyncPhase getPreviousPhase() {
        return previousPhase;
    }

    public SyncPhase getCurrentPhase() {
        return currentPhase;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
