package local.mmm.vaultsync;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;

public final class MMMVaultSyncPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    private ExecutorService executor;
    private Economy economy;
    private SyncConfig config;
    private BalanceStore store;
    private BukkitTask scanTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null || provider.getProvider() == null) {
            throw new IllegalStateException("No Vault economy provider found.");
        }
        this.economy = provider.getProvider();

        this.executor = Executors.newFixedThreadPool(2, new SyncThreadFactory());
        reloadPlugin();
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            flushPlayerNow(player.getUniqueId(), scaled(economy.getBalance(player)), "shutdown");
        }
        if (store != null) {
            store.close();
            store = null;
        }
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
        states.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mmmvaultsync.admin")) {
            sender.sendMessage("No permission.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("/mmmvaultsync reload|status|sync <player>");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                reloadPlugin();
                sender.sendMessage("MMMVaultSync reloaded.");
                return true;
            }
            case "status" -> {
                sender.sendMessage("server-id=" + config.serverId()
                        + ", tracked=" + states.size()
                        + ", scan-interval=" + config.localScanIntervalTicks() + "t"
                        + ", refresh=" + config.remoteRefreshIntervalMillis() + "ms");
                return true;
            }
            case "sync" -> {
                if (args.length < 2) {
                    sender.sendMessage("/mmmvaultsync sync <player>");
                    return true;
                }
                Player player = Bukkit.getPlayerExact(args[1]);
                if (player == null) {
                    sender.sendMessage("Player not online.");
                    return true;
                }
                scheduleAuthoritativeLoad(player.getUniqueId(), "manual");
                sender.sendMessage("Sync requested for " + player.getName());
                return true;
            }
            default -> {
                sender.sendMessage("/mmmvaultsync reload|status|sync <player>");
                return true;
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        states.put(uuid, new PlayerState());
        Bukkit.getScheduler().runTaskLater(this, () -> scheduleAuthoritativeLoad(uuid, "join"), config.joinLoadDelayTicks());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (config.flushOnQuit()) {
            flushPlayerNow(uuid, scaled(economy.getBalance(event.getPlayer())), "quit");
        }
        states.remove(uuid);
    }

    private void reloadPlugin() {
        reloadConfig();
        SyncConfig newConfig = loadSyncConfig();

        BalanceStore oldStore = this.store;
        this.config = newConfig;
        this.store = new BalanceStore(newConfig);
        try {
            this.store.ensureSchema();
        } catch (SQLException exception) {
            if (oldStore != null) {
                oldStore.close();
            }
            throw new IllegalStateException("Failed to initialize sync database schema", exception);
        }
        if (oldStore != null) {
            oldStore.close();
        }

        if (scanTask != null) {
            scanTask.cancel();
        }
        scanTask = Bukkit.getScheduler().runTaskTimer(this, this::scanOnlinePlayers,
                config.localScanIntervalTicks(), config.localScanIntervalTicks());
        states.clear();
    }

    private SyncConfig loadSyncConfig() {
        String host = getConfig().getString("database.host", "127.0.0.1");
        int port = getConfig().getInt("database.port", 3306);
        String database = getConfig().getString("database.database", "minecraft");
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&autoReconnect=true&useUnicode=true&characterEncoding=UTF-8";
        return new SyncConfig(
                getConfig().getString("server-id", "server"),
                jdbcUrl,
                getConfig().getString("database.username", "root"),
                getConfig().getString("database.password", ""),
                Math.max(2, getConfig().getInt("database.pool-size", 4)),
                Math.max(1000L, getConfig().getLong("database.connect-timeout-millis", 5000L)),
                getConfig().getString("database.table", "mmm_vault_sync_balances"),
                Math.max(1L, getConfig().getLong("sync.join-load-delay-ticks", 10L)),
                Math.max(1L, getConfig().getLong("sync.local-scan-interval-ticks", 20L)),
                Math.max(1000L, getConfig().getLong("sync.remote-refresh-interval-seconds", 15L) * 1000L),
                Math.max(0L, getConfig().getLong("sync.write-suppress-millis-after-apply", 3000L)),
                Math.max(0.0D, getConfig().getDouble("sync.epsilon", 0.0001D)),
                getConfig().getBoolean("sync.flush-on-quit", true),
                getConfig().getBoolean("logging.debug", false)
        );
    }

    private void scanOnlinePlayers() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            PlayerState state = states.computeIfAbsent(uuid, ignored -> new PlayerState());
            BigDecimal observed = scaled(economy.getBalance(player));

            if (state.lastObservedBalance == null) {
                state.lastObservedBalance = observed;
            }

            if (!state.writeInFlight && now >= state.suppressWritesUntilMillis && !sameBalance(observed, state.lastObservedBalance)) {
                state.lastObservedBalance = observed;
                scheduleWrite(uuid, observed, state, "local-change");
            } else {
                state.lastObservedBalance = observed;
            }

            if (!state.remoteLoadInFlight && now >= state.nextRemoteRefreshMillis) {
                state.nextRemoteRefreshMillis = now + config.remoteRefreshIntervalMillis();
                scheduleAuthoritativeLoad(uuid, "periodic");
            }
        }
    }

    private void scheduleAuthoritativeLoad(UUID uuid, String reason) {
        PlayerState state = states.computeIfAbsent(uuid, ignored -> new PlayerState());
        if (state.remoteLoadInFlight) {
            return;
        }
        state.remoteLoadInFlight = true;

        runAsync(() -> store.load(uuid)).whenComplete((record, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
            state.remoteLoadInFlight = false;
            if (throwable != null) {
                log(Level.WARNING, "Failed to load authoritative balance for " + uuid + " (" + reason + ")", throwable);
                return;
            }

            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                return;
            }

            if (record.isPresent()) {
                BalanceRecord authoritative = record.get();
                if (authoritative.revision() > state.knownRevision || reason.equals("join") || reason.equals("manual")) {
                    applyAuthoritativeBalance(player, state, authoritative, reason);
                }
            } else {
                BigDecimal localBalance = scaled(economy.getBalance(player));
                scheduleWrite(uuid, localBalance, state, "seed-" + reason);
            }
        }));
    }

    private void applyAuthoritativeBalance(Player player, PlayerState state, BalanceRecord record, String reason) {
        BigDecimal current = scaled(economy.getBalance(player));
        if (sameBalance(current, record.balance())) {
            state.knownRevision = Math.max(state.knownRevision, record.revision());
            state.lastObservedBalance = record.balance();
            return;
        }

        BigDecimal delta = record.balance().subtract(current);
        EconomyResponse response = delta.signum() >= 0
                ? economy.depositPlayer(player, delta.doubleValue())
                : economy.withdrawPlayer(player, delta.abs().doubleValue());

        if (!response.transactionSuccess()) {
            getLogger().warning("Failed to apply authoritative balance for " + player.getName()
                    + " via " + reason + ": " + response.errorMessage);
            return;
        }

        state.suppressWritesUntilMillis = System.currentTimeMillis() + config.writeSuppressMillisAfterApply();
        state.knownRevision = Math.max(state.knownRevision, record.revision());
        state.lastObservedBalance = record.balance();
        debug("Applied remote balance for " + player.getName() + ": " + current + " -> " + record.balance() + " (" + reason + ")");
    }

    private void scheduleWrite(UUID uuid, BigDecimal balance, PlayerState state, String reason) {
        if (state.writeInFlight) {
            state.pendingWriteBalance = balance;
            return;
        }
        state.writeInFlight = true;
        long knownRevisionSnapshot = state.knownRevision;

        runAsync(() -> store.writeSnapshot(uuid, balance, knownRevisionSnapshot, config.serverId()))
                .whenComplete((record, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
                    state.writeInFlight = false;
                    if (throwable != null) {
                        log(Level.WARNING, "Failed to write balance for " + uuid + " (" + reason + ")", throwable);
                    } else if (record != null) {
                        state.knownRevision = Math.max(state.knownRevision, record.revision());
                        debug("Wrote balance for " + uuid + ": " + record.balance() + " rev=" + record.revision() + " (" + reason + ")");
                    }

                    if (state.pendingWriteBalance != null) {
                        BigDecimal pending = state.pendingWriteBalance;
                        state.pendingWriteBalance = null;
                        if (!sameBalance(pending, state.lastObservedBalance)) {
                            state.lastObservedBalance = pending;
                        }
                        scheduleWrite(uuid, pending, state, "pending");
                    }
                }));
    }

    private void flushPlayerNow(UUID uuid, BigDecimal balance, String reason) {
        PlayerState state = states.get(uuid);
        long knownRevision = state == null ? 0L : state.knownRevision;
        runAsync(() -> store.writeSnapshot(uuid, balance, knownRevision, config.serverId()))
                .exceptionally(throwable -> {
                    log(Level.WARNING, "Failed to flush balance for " + uuid + " (" + reason + ")", throwable);
                    return null;
                });
    }

    private <T> CompletableFuture<T> runAsync(CheckedSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, executor);
    }

    private BigDecimal scaled(double amount) {
        return BigDecimal.valueOf(amount).setScale(4, RoundingMode.HALF_UP);
    }

    private boolean sameBalance(BigDecimal left, BigDecimal right) {
        return left.subtract(right).abs().doubleValue() <= config.epsilon();
    }

    private void debug(String message) {
        if (config.debugLogging()) {
            getLogger().info("[debug] " + message);
        }
    }

    private void log(Level level, String message, Throwable throwable) {
        Throwable cause = throwable instanceof RuntimeException runtime && runtime.getCause() != null
                ? runtime.getCause()
                : throwable;
        getLogger().log(level, message, cause);
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private static final class PlayerState {
        private BigDecimal lastObservedBalance;
        private BigDecimal pendingWriteBalance;
        private long knownRevision;
        private long suppressWritesUntilMillis;
        private long nextRemoteRefreshMillis;
        private boolean writeInFlight;
        private boolean remoteLoadInFlight;
    }

    private static final class SyncThreadFactory implements ThreadFactory {
        private int threadCounter = 0;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "MMMVaultSync-" + (++threadCounter));
            thread.setDaemon(true);
            return thread;
        }
    }
}
