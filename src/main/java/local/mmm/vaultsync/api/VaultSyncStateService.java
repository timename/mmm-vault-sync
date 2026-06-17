package local.mmm.vaultsync.api;

public interface VaultSyncStateService {
    SyncPhase getPhase();

    boolean isMaintenanceMode();

    boolean isDrainCompleted();

    boolean isVerifyCompleted();

    boolean isReloadInProgress();

    default boolean canAcceptEconomicOperations() {
        return getPhase() == SyncPhase.NORMAL;
    }
}
