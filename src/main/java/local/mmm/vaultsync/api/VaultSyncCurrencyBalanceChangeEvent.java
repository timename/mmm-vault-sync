package local.mmm.vaultsync.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.math.BigDecimal;
import java.util.UUID;

public final class VaultSyncCurrencyBalanceChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String currencyId;
    private final BigDecimal previousBalance;
    private final BigDecimal newBalance;
    private final String reason;
    private final boolean remoteSync;

    public VaultSyncCurrencyBalanceChangeEvent(
            UUID playerId,
            String currencyId,
            BigDecimal previousBalance,
            BigDecimal newBalance,
            String reason,
            boolean remoteSync
    ) {
        this.playerId = playerId;
        this.currencyId = currencyId;
        this.previousBalance = previousBalance;
        this.newBalance = newBalance;
        this.reason = reason;
        this.remoteSync = remoteSync;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getCurrencyId() {
        return currencyId;
    }

    public BigDecimal getPreviousBalance() {
        return previousBalance;
    }

    public BigDecimal getNewBalance() {
        return newBalance;
    }

    public String getReason() {
        return reason;
    }

    public boolean isRemoteSync() {
        return remoteSync;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
