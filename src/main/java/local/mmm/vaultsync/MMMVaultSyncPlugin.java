package local.mmm.vaultsync;

import local.mmm.vaultsync.api.SyncPhase;
import local.mmm.vaultsync.api.VaultSyncPhaseChangeEvent;
import local.mmm.vaultsync.api.VaultSyncStateService;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class MMMVaultSyncPlugin extends JavaPlugin implements Listener, TabCompleter, VaultSyncStateService {
    private static final String ADMIN_PERMISSION = "mmmvaultsync.admin";
    private static final List<String> SUBCOMMANDS = List.of("reload", "status", "sync", "maintenance", "drain", "verify");
    private static final long RELOAD_CONFIRM_WINDOW_MILLIS = 15_000L;
    private static final long DRAIN_WAIT_TIMEOUT_MILLIS = 15_000L;
    private static final long DRAIN_POLL_INTERVAL_MILLIS = 50L;

    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingReloadConfirmations = new ConcurrentHashMap<>();
    private final AtomicInteger activeAsyncOperations = new AtomicInteger();

    private ExecutorService executor;
    private Economy economy;
    private SyncConfig config;
    private BalanceStore store;
    private BukkitTask scanTask;
    private volatile boolean maintenanceMode;
    private volatile boolean drainCompleted;
    private volatile boolean verifyCompleted;
    private volatile long maintenanceSinceMillis;
    private volatile long lastObservedMaintenanceChangeMillis;
    private volatile SyncPhase phase = SyncPhase.NORMAL;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null || provider.getProvider() == null) {
            throw new IllegalStateException("No Vault economy provider found.");
        }
        this.economy = provider.getProvider();
        this.executor = Executors.newFixedThreadPool(2, new SyncThreadFactory());

        if (!reloadPluginInternal(true)) {
            throw new IllegalStateException("Failed to load MMMVaultSync configuration.");
        }
        if (getCommand("mmmvaultsync") != null) {
            getCommand("mmmvaultsync").setTabCompleter(this);
        }
        getServer().getServicesManager().register(VaultSyncStateService.class, this, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        stopScanTask();
        try {
            flushOnlinePlayersBlocking("shutdown");
        } catch (Exception exception) {
            getLogger().log(Level.WARNING, "Failed to flush balances during shutdown", exception);
        }
        if (store != null) {
            store.close();
            store = null;
        }
        getServer().getServicesManager().unregister(VaultSyncStateService.class, this);
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
        states.clear();
        pendingReloadConfirmations.clear();
        phase = SyncPhase.NORMAL;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("No permission.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("/mmmvaultsync reload|status|sync|maintenance|drain|verify");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                return handleReloadCommand(sender, args);
            }
            case "status" -> {
                sender.sendMessage("server-id=" + config.serverId()
                        + ", tracked=" + states.size()
                        + ", phase=" + phase
                        + ", scan-interval=" + config.localScanIntervalTicks() + "t"
                        + ", refresh=" + config.remoteRefreshIntervalMillis() + "ms"
                        + ", maintenance=" + maintenanceMode
                        + ", drain=" + drainCompleted
                        + ", verify=" + verifyCompleted
                        + ", active-async=" + activeAsyncOperations.get());
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
            case "maintenance" -> {
                return handleMaintenanceCommand(sender, args);
            }
            case "drain" -> {
                return handleDrainCommand(sender);
            }
            case "verify" -> {
                return handleVerifyCommand(sender);
            }
            default -> {
                sender.sendMessage("/mmmvaultsync reload|status|sync|maintenance|drain|verify");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterByPrefix(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && "sync".equalsIgnoreCase(args[0])) {
            List<String> onlinePlayers = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toCollection(ArrayList::new));
            return filterByPrefix(onlinePlayers, args[1]);
        }
        if (args.length == 2 && "maintenance".equalsIgnoreCase(args[0])) {
            return filterByPrefix(List.of("on", "off"), args[1]);
        }
        if (args.length == 2 && "reload".equalsIgnoreCase(args[0])) {
            return filterByPrefix(List.of("confirm"), args[1]);
        }
        return Collections.emptyList();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        states.put(uuid, new PlayerState());
        if (!maintenanceMode) {
            Bukkit.getScheduler().runTaskLater(this, () -> scheduleAuthoritativeLoad(uuid, "join"), config.joinLoadDelayTicks());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (config.flushOnQuit() && !maintenanceMode) {
            flushPlayerNow(uuid, scaled(economy.getBalance(event.getPlayer())), "quit");
        }
        states.remove(uuid);
    }

    private boolean handleReloadCommand(CommandSender sender, String[] args) {
        if (!maintenanceMode) {
            sender.sendMessage("Reload is blocked. Enable maintenance mode, then run drain and verify first.");
            return true;
        }
        if (!drainCompleted || !verifyCompleted) {
            sender.sendMessage("Reload is blocked. Required state: maintenance=on, drain=done, verify=done.");
            return true;
        }
        if (!isReloadConfirmed(sender, args)) {
            sender.sendMessage("Reload requires confirmation.");
            sender.sendMessage("Risk: other plugins can still change balances outside this sync layer during maintenance.");
            sender.sendMessage("Recommended: reload only after drain+verify succeed and while no players are trading or changing money.");
            sender.sendMessage("Run '/mmmvaultsync reload confirm' within 15 seconds to continue.");
            return true;
        }

        if (reloadPluginInternal(false)) {
            drainCompleted = false;
            verifyCompleted = false;
            sender.sendMessage("MMMVaultSync reloaded.");
        } else {
            sender.sendMessage("MMMVaultSync reload failed. Check console.");
        }
        return true;
    }

    private boolean handleMaintenanceCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("/mmmvaultsync maintenance <on|off>");
            return true;
        }

        if ("on".equalsIgnoreCase(args[1])) {
            maintenanceMode = true;
            drainCompleted = false;
            verifyCompleted = false;
            maintenanceSinceMillis = System.currentTimeMillis();
            lastObservedMaintenanceChangeMillis = maintenanceSinceMillis;
            pendingReloadConfirmations.clear();
            setPhase(SyncPhase.MAINTENANCE);
            sender.sendMessage("Maintenance mode enabled.");
            sender.sendMessage("Sync writes and remote refresh are now frozen.");
            sender.sendMessage("Important: this plugin cannot universally block other plugins from changing balances.");
            sender.sendMessage("Recommended next steps: /mmmvaultsync drain -> /mmmvaultsync verify -> /mmmvaultsync reload confirm");
            return true;
        }

        if ("off".equalsIgnoreCase(args[1])) {
            maintenanceMode = false;
            drainCompleted = false;
            verifyCompleted = false;
            pendingReloadConfirmations.clear();
            setPhase(SyncPhase.NORMAL);
            sender.sendMessage("Maintenance mode disabled.");
            for (Player player : Bukkit.getOnlinePlayers()) {
                scheduleAuthoritativeLoad(player.getUniqueId(), "maintenance-off");
            }
            return true;
        }

        sender.sendMessage("/mmmvaultsync maintenance <on|off>");
        return true;
    }

    private boolean handleDrainCommand(CommandSender sender) {
        if (!maintenanceMode) {
            sender.sendMessage("Drain is blocked. Enable maintenance mode first.");
            return true;
        }

        sender.sendMessage("Starting drain. Waiting for in-flight tasks and flushing online balances...");
        setPhase(SyncPhase.DRAINING);
        runAsync(this::performDrain).whenComplete((success, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
            if (throwable != null || !Boolean.TRUE.equals(success)) {
                drainCompleted = false;
                setPhase(SyncPhase.MAINTENANCE);
                sender.sendMessage("Drain failed. Check console.");
                if (throwable != null) {
                    log(Level.WARNING, "Drain failed", throwable);
                }
                return;
            }
            drainCompleted = true;
            verifyCompleted = false;
            setPhase(SyncPhase.MAINTENANCE);
            sender.sendMessage("Drain completed.");
        }));
        return true;
    }

    private boolean handleVerifyCommand(CommandSender sender) {
        if (!maintenanceMode) {
            sender.sendMessage("Verify is blocked. Enable maintenance mode first.");
            return true;
        }
        if (!drainCompleted) {
            sender.sendMessage("Verify is blocked. Run drain first.");
            return true;
        }

        sender.sendMessage("Starting verification against MySQL...");
        setPhase(SyncPhase.VERIFYING);
        runAsync(this::performVerify).whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
            if (throwable != null) {
                verifyCompleted = false;
                setPhase(SyncPhase.MAINTENANCE);
                sender.sendMessage("Verify failed. Check console.");
                log(Level.WARNING, "Verify failed", throwable);
                return;
            }

            if (!result.ok()) {
                verifyCompleted = false;
                setPhase(SyncPhase.MAINTENANCE);
                sender.sendMessage("Verify failed: " + result.message());
                return;
            }

            verifyCompleted = true;
            setPhase(SyncPhase.MAINTENANCE);
            sender.sendMessage("Verify succeeded: " + result.message());
        }));
        return true;
    }

    private boolean reloadPluginInternal(boolean startup) {
        SyncPhase previousPhase = phase;
        if (!startup) {
            setPhase(SyncPhase.RELOADING);
        }
        if (!startup) {
            stopScanTask();
            try {
                flushOnlinePlayersBlocking("reload");
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "Failed to flush online balances before reload", exception);
                setPhase(previousPhase);
                restartScanTask();
                return false;
            }
        }

        reloadConfig();
        SyncConfig newConfig = loadSyncConfig();

        BalanceStore oldStore = this.store;
        BalanceStore newStore = new BalanceStore(newConfig);
        try {
            newStore.ensureSchema();
        } catch (SQLException exception) {
            newStore.close();
            if (oldStore != null) {
                this.store = oldStore;
            }
            getLogger().log(Level.SEVERE, "Failed to initialize sync database schema", exception);
            if (!startup) {
                setPhase(previousPhase);
                restartScanTask();
            }
            return false;
        }

        this.config = newConfig;
        this.store = newStore;
        if (oldStore != null) {
            oldStore.close();
        }

        resyncTrackedPlayersAfterReload();
        restartScanTask();
        if (maintenanceMode) {
            setPhase(SyncPhase.MAINTENANCE);
        } else {
            setPhase(SyncPhase.NORMAL);
        }
        return true;
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

            if (maintenanceMode) {
                if (!sameBalance(observed, state.lastObservedBalance)) {
                    lastObservedMaintenanceChangeMillis = now;
                    drainCompleted = false;
                    verifyCompleted = false;
                    debug("Detected balance change during maintenance for " + player.getName()
                            + ": " + state.lastObservedBalance + " -> " + observed);
                }
                state.lastObservedBalance = observed;
                continue;
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
        if (maintenanceMode && !"manual".equals(reason) && !"reload".equals(reason) && !"maintenance-off".equals(reason)) {
            return;
        }

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
                if (authoritative.revision() > state.knownRevision || "join".equals(reason) || "manual".equals(reason)) {
                    applyAuthoritativeBalance(player, state, authoritative, reason);
                }
            } else if (!maintenanceMode) {
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
        if (maintenanceMode) {
            drainCompleted = false;
            verifyCompleted = false;
            debug("Skipped write during maintenance for " + uuid + " (" + reason + ")");
            return;
        }

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

    private void flushOnlinePlayersBlocking(String reason) {
        BalanceStore targetStore = this.store;
        if (targetStore == null) {
            return;
        }

        List<CompletableFuture<BalanceRecord>> futures = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            PlayerState state = states.get(uuid);
            long knownRevision = state == null ? 0L : state.knownRevision;
            BigDecimal balance = scaled(economy.getBalance(player));
            futures.add(runAsync(() -> targetStore.writeSnapshot(uuid, balance, knownRevision, config.serverId())));
        }

        for (CompletableFuture<BalanceRecord> future : futures) {
            future.join();
        }
    }

    private void resyncTrackedPlayersAfterReload() {
        states.keySet().retainAll(Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId)
                .collect(Collectors.toSet()));

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            PlayerState state = states.computeIfAbsent(uuid, ignored -> new PlayerState());
            state.lastObservedBalance = scaled(economy.getBalance(player));
            state.pendingWriteBalance = null;
            state.writeInFlight = false;
            state.remoteLoadInFlight = false;
            state.suppressWritesUntilMillis = 0L;
            state.nextRemoteRefreshMillis = 0L;
            Bukkit.getScheduler().runTaskLater(this, () -> scheduleAuthoritativeLoad(uuid, "reload"), config.joinLoadDelayTicks());
        }
    }

    private void restartScanTask() {
        stopScanTask();
        scanTask = Bukkit.getScheduler().runTaskTimer(this, this::scanOnlinePlayers,
                config.localScanIntervalTicks(), config.localScanIntervalTicks());
    }

    private void stopScanTask() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
    }

    private List<String> filterByPrefix(List<String> candidates, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

    private boolean isReloadConfirmed(CommandSender sender, String[] args) {
        purgeExpiredReloadConfirmations();
        String senderKey = getSenderKey(sender);
        long now = System.currentTimeMillis();

        if (args.length >= 2 && "confirm".equalsIgnoreCase(args[1])) {
            Long expiry = pendingReloadConfirmations.remove(senderKey);
            return expiry != null && expiry >= now;
        }

        pendingReloadConfirmations.put(senderKey, now + RELOAD_CONFIRM_WINDOW_MILLIS);
        return false;
    }

    private void purgeExpiredReloadConfirmations() {
        long now = System.currentTimeMillis();
        pendingReloadConfirmations.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private String getSenderKey(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId().toString();
        }
        return "console:" + sender.getName();
    }

    private boolean performDrain() throws Exception {
        waitForInFlightTasks();
        flushOnlinePlayersBlocking("drain");
        waitForInFlightTasks();
        return true;
    }

    private VerifyResult performVerify() throws Exception {
        long verifyStartedAt = System.currentTimeMillis();
        waitForInFlightTasks();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            BigDecimal localBalance = scaled(economy.getBalance(player));
            Optional<BalanceRecord> record = store.load(uuid);
            if (record.isEmpty()) {
                return new VerifyResult(false, "missing database row for " + player.getName());
            }
            if (!sameBalance(localBalance, record.get().balance())) {
                return new VerifyResult(false, "balance mismatch for " + player.getName()
                        + " local=" + localBalance + " db=" + record.get().balance());
            }
        }

        if (lastObservedMaintenanceChangeMillis > verifyStartedAt || activeAsyncOperations.get() > 0) {
            return new VerifyResult(false, "new activity detected during verification");
        }
        return new VerifyResult(true, "all online players match MySQL");
    }

    @Override
    public SyncPhase getPhase() {
        return phase;
    }

    @Override
    public boolean isMaintenanceMode() {
        return maintenanceMode;
    }

    @Override
    public boolean isDrainCompleted() {
        return drainCompleted;
    }

    @Override
    public boolean isVerifyCompleted() {
        return verifyCompleted;
    }

    @Override
    public boolean isReloadInProgress() {
        return phase == SyncPhase.RELOADING;
    }

    private void waitForInFlightTasks() throws InterruptedException {
        long deadline = System.currentTimeMillis() + DRAIN_WAIT_TIMEOUT_MILLIS;
        while (activeAsyncOperations.get() > 0) {
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("Timed out waiting for async operations to finish");
            }
            Thread.sleep(DRAIN_POLL_INTERVAL_MILLIS);
        }
    }

    private <T> CompletableFuture<T> runAsync(CheckedSupplier<T> supplier) {
        activeAsyncOperations.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            } finally {
                activeAsyncOperations.decrementAndGet();
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

    private void setPhase(SyncPhase newPhase) {
        SyncPhase oldPhase = this.phase;
        if (oldPhase == newPhase) {
            return;
        }
        this.phase = newPhase;
        Bukkit.getPluginManager().callEvent(new VaultSyncPhaseChangeEvent(oldPhase, newPhase));
        debug("Phase changed: " + oldPhase + " -> " + newPhase);
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

    private record VerifyResult(boolean ok, String message) {
    }
}
