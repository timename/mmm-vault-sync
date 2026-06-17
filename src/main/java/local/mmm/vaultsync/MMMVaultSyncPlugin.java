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
import java.io.File;
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
    private volatile boolean setupRequired;
    private volatile String setupReason = "";

    @Override
    public void onEnable() {
        boolean firstRun = !new File(getDataFolder(), "config.yml").exists();
        saveDefaultConfig();

        if (getCommand("mmmvaultsync") != null) {
            getCommand("mmmvaultsync").setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);

        if (firstRun) {
            setupRequired = true;
            setupReason = "首次加载，配置文件刚刚生成";
            printFirstRunSetupNotice();
            return;
        }

        if (isConfigUsingPlaceholders()) {
            setupRequired = true;
            setupReason = "配置文件仍在使用默认占位值";
            printConfigPlaceholderNotice();
            return;
        }

        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null || provider.getProvider() == null) {
            throw new IllegalStateException("未找到可用的 Vault 经济提供者。");
        }
        this.economy = provider.getProvider();
        this.executor = Executors.newFixedThreadPool(2, new SyncThreadFactory());

        if (!reloadPluginInternal(true)) {
            throw new IllegalStateException("MMMVaultSync 配置加载失败。");
        }
        getServer().getServicesManager().register(VaultSyncStateService.class, this, this, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        stopScanTask();
        try {
            flushOnlinePlayersBlocking("shutdown");
        } catch (Exception exception) {
            getLogger().log(Level.WARNING, "插件关闭时刷新余额到数据库失败", exception);
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
            sender.sendMessage("你没有权限使用这个命令。");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("用法: /mmmvaultsync reload|status|sync|maintenance|drain|verify");
            return true;
        }

        if (setupRequired) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("reload".equals(sub)) {
                return handleSetupReloadCommand(sender);
            }

            sender.sendMessage("MMMVaultSync 当前处于待配置模式，尚未连接数据库。");
            sender.sendMessage("原因: " + setupReason);
            sender.sendMessage("请先修改 plugins/MMMVaultSync/config.yml 中的数据库配置和 server-id。");
            sender.sendMessage("修改完成后可执行 /mmmvaultsync reload，无需重启整个服务器。");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                return handleReloadCommand(sender, args);
            }
            case "status" -> {
                sender.sendMessage("MMMVaultSync 当前状态:");
                sender.sendMessage("服务器标识: " + config.serverId());
                sender.sendMessage("当前阶段: " + phaseLabel(phase));
                sender.sendMessage("在线追踪玩家数: " + states.size());
                sender.sendMessage("本地扫描间隔: " + config.localScanIntervalTicks() + " tick");
                sender.sendMessage("远端刷新间隔: " + config.remoteRefreshIntervalMillis() + " 毫秒");
                sender.sendMessage("维护模式: " + yesNo(maintenanceMode));
                sender.sendMessage("drain 状态: " + yesNo(drainCompleted));
                sender.sendMessage("verify 状态: " + yesNo(verifyCompleted));
                sender.sendMessage("异步任务数: " + activeAsyncOperations.get());
                return true;
            }
            case "sync" -> {
                if (args.length < 2) {
                    sender.sendMessage("用法: /mmmvaultsync sync <玩家名>");
                    return true;
                }
                Player player = Bukkit.getPlayerExact(args[1]);
                if (player == null) {
                    sender.sendMessage("目标玩家不在线。");
                    return true;
                }
                scheduleAuthoritativeLoad(player.getUniqueId(), "manual");
                sender.sendMessage("已请求同步玩家 " + player.getName() + " 的余额。");
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
                sender.sendMessage("用法: /mmmvaultsync reload|status|sync|maintenance|drain|verify");
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
        if (setupRequired && (event.getPlayer().isOp() || event.getPlayer().hasPermission(ADMIN_PERMISSION))) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!event.getPlayer().isOnline()) {
                    return;
                }
                event.getPlayer().sendMessage("§6[MMMVaultSync] §e插件当前处于待配置模式。");
                event.getPlayer().sendMessage("§6[MMMVaultSync] §e原因: " + setupReason);
                event.getPlayer().sendMessage("§6[MMMVaultSync] §e请先编辑 plugins/MMMVaultSync/config.yml");
                event.getPlayer().sendMessage("§6[MMMVaultSync] §e至少需要填写: server-id、database.username、database.password");
                event.getPlayer().sendMessage("§6[MMMVaultSync] §e修改完成后请重启服务器。");
            }, 40L);
        }

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
            sender.sendMessage("当前禁止重载。请先开启维护模式，再依次执行 drain 和 verify。");
            return true;
        }
        if (!drainCompleted || !verifyCompleted) {
            sender.sendMessage("当前禁止重载。必须满足: maintenance=on、drain=done、verify=done。");
            return true;
        }
        if (!isReloadConfirmed(sender, args)) {
            sender.sendMessage("重载需要二次确认。");
            sender.sendMessage("风险提示: 即使处于维护模式，其他插件仍可能绕过本同步层直接修改余额。");
            sender.sendMessage("建议: 只在 drain 和 verify 都成功、且没有玩家交易或改钱时执行重载。");
            sender.sendMessage("请在 15 秒内执行 '/mmmvaultsync reload confirm' 继续。");
            return true;
        }

        if (reloadPluginInternal(false)) {
            drainCompleted = false;
            verifyCompleted = false;
            sender.sendMessage("MMMVaultSync 已重载。");
        } else {
            sender.sendMessage("MMMVaultSync 重载失败，请检查控制台日志。");
        }
        return true;
    }

    private boolean handleSetupReloadCommand(CommandSender sender) {
        sender.sendMessage("正在重新读取 MMMVaultSync 配置...");

        reloadConfig();
        if (isConfigUsingPlaceholders()) {
            sender.sendMessage("配置仍未完成，插件继续保持待配置模式。");
            sender.sendMessage("请确认以下关键项已经填写真实值:");
            sender.sendMessage("- server-id");
            sender.sendMessage("- database.host");
            sender.sendMessage("- database.port");
            sender.sendMessage("- database.database");
            sender.sendMessage("- database.username");
            sender.sendMessage("- database.password");
            printConfigPlaceholderNotice();
            return true;
        }

        setupRequired = false;
        setupReason = "";
        if (!reloadPluginInternal(true)) {
            setupRequired = true;
            setupReason = "配置已修改，但数据库连接或初始化仍然失败";
            sender.sendMessage("配置重新加载失败，请检查控制台日志和数据库连接配置。");
            return true;
        }

        getServer().getServicesManager().register(VaultSyncStateService.class, this, this, ServicePriority.Normal);
        sender.sendMessage("MMMVaultSync 已完成初始化，现在开始正常工作。");
        return true;
    }

    private boolean handleMaintenanceCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("用法: /mmmvaultsync maintenance <on|off>");
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
            sender.sendMessage("维护模式已开启。");
            sender.sendMessage("当前已冻结同步写入和远端定时刷新。");
            sender.sendMessage("注意: 本插件无法绝对阻止其他插件直接修改余额。");
            sender.sendMessage("建议下一步执行: /mmmvaultsync drain -> /mmmvaultsync verify -> /mmmvaultsync reload confirm");
            return true;
        }

        if ("off".equalsIgnoreCase(args[1])) {
            maintenanceMode = false;
            drainCompleted = false;
            verifyCompleted = false;
            pendingReloadConfirmations.clear();
            setPhase(SyncPhase.NORMAL);
            sender.sendMessage("维护模式已关闭。");
            for (Player player : Bukkit.getOnlinePlayers()) {
                scheduleAuthoritativeLoad(player.getUniqueId(), "maintenance-off");
            }
            return true;
        }

        sender.sendMessage("用法: /mmmvaultsync maintenance <on|off>");
        return true;
    }

    private boolean handleDrainCommand(CommandSender sender) {
        if (!maintenanceMode) {
            sender.sendMessage("当前禁止执行 drain，请先开启维护模式。");
            return true;
        }

        sender.sendMessage("开始执行 drain，正在等待在途任务完成并刷新在线玩家余额到数据库...");
        setPhase(SyncPhase.DRAINING);
        runAsync(this::performDrain).whenComplete((success, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
            if (throwable != null || !Boolean.TRUE.equals(success)) {
                drainCompleted = false;
                setPhase(SyncPhase.MAINTENANCE);
                sender.sendMessage("drain 执行失败，请检查控制台日志。");
                if (throwable != null) {
                    log(Level.WARNING, "Drain failed", throwable);
                }
                return;
            }
            drainCompleted = true;
            verifyCompleted = false;
            setPhase(SyncPhase.MAINTENANCE);
            sender.sendMessage("drain 执行完成。");
        }));
        return true;
    }

    private boolean handleVerifyCommand(CommandSender sender) {
        if (!maintenanceMode) {
            sender.sendMessage("当前禁止执行 verify，请先开启维护模式。");
            return true;
        }
        if (!drainCompleted) {
            sender.sendMessage("当前禁止执行 verify，请先完成 drain。");
            return true;
        }

        sender.sendMessage("开始校验在线玩家余额与 MySQL 是否一致...");
        setPhase(SyncPhase.VERIFYING);
        runAsync(this::performVerify).whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
            if (throwable != null) {
                verifyCompleted = false;
                setPhase(SyncPhase.MAINTENANCE);
                sender.sendMessage("verify 执行失败，请检查控制台日志。");
                log(Level.WARNING, "Verify failed", throwable);
                return;
            }

            if (!result.ok()) {
                verifyCompleted = false;
                setPhase(SyncPhase.MAINTENANCE);
                sender.sendMessage("verify 失败: " + result.message());
                return;
            }

            verifyCompleted = true;
            setPhase(SyncPhase.MAINTENANCE);
            sender.sendMessage("verify 成功: " + result.message());
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
                getLogger().log(Level.SEVERE, "重载前刷新在线玩家余额失败", exception);
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
            getLogger().log(Level.SEVERE, "初始化同步数据库表结构失败", exception);
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
                log(Level.WARNING, "加载玩家权威余额失败: " + uuid + " (" + reason + ")", throwable);
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
            getLogger().warning("应用玩家权威余额失败: " + player.getName()
                    + "，触发原因=" + reason + "，错误=" + response.errorMessage);
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
                        log(Level.WARNING, "写入玩家余额失败: " + uuid + " (" + reason + ")", throwable);
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
                    log(Level.WARNING, "刷新玩家余额失败: " + uuid + " (" + reason + ")", throwable);
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
                return new VerifyResult(false, "玩家 " + player.getName() + " 在数据库中缺少余额记录");
            }
            if (!sameBalance(localBalance, record.get().balance())) {
                return new VerifyResult(false, "玩家 " + player.getName()
                        + " 的余额不一致，本地=" + localBalance + "，数据库=" + record.get().balance());
            }
        }

        if (lastObservedMaintenanceChangeMillis > verifyStartedAt || activeAsyncOperations.get() > 0) {
            return new VerifyResult(false, "校验期间检测到新的余额活动");
        }
        return new VerifyResult(true, "所有在线玩家余额都与 MySQL 一致");
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
                throw new IllegalStateException("等待异步任务完成超时");
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

    private boolean isConfigUsingPlaceholders() {
        String password = getConfig().getString("database.password", "");
        String serverId = getConfig().getString("server-id", "server");
        return password == null
                || password.isBlank()
                || "password".equalsIgnoreCase(password.trim())
                || "server".equalsIgnoreCase(serverId.trim());
    }

    private void printFirstRunSetupNotice() {
        printSetupBanner(
                "检测到 MMMVaultSync 为首次加载，默认配置文件已生成。",
                "请先编辑 plugins/MMMVaultSync/config.yml 后再重启服务器。",
                List.of(
                        "server-id",
                        "database.host",
                        "database.port",
                        "database.database",
                        "database.username",
                        "database.password"
                ),
                "当前插件已进入待配置模式，不会尝试连接数据库。"
        );
    }

    private void printConfigPlaceholderNotice() {
        printSetupBanner(
                "检测到 MMMVaultSync 配置仍在使用默认占位值。",
                "请检查 plugins/MMMVaultSync/config.yml，并填写真实配置后重启服务器。",
                List.of(
                        "server-id 不能保留为 'server'",
                        "database.password 不能保留为 'password' 或空值"
                ),
                "当前插件已进入待配置模式，不会尝试连接数据库。"
        );
    }

    private void printSetupBanner(String title, String action, List<String> items, String footer) {
        getLogger().warning("==================================================");
        getLogger().warning("MMMVaultSync 安装向导");
        getLogger().warning("==================================================");
        getLogger().warning(title);
        getLogger().warning(action);
        getLogger().warning("请确认以下项目:");
        for (String item : items) {
            getLogger().warning("- " + item);
        }
        getLogger().warning(footer);
        getLogger().warning("==================================================");
    }

    private String yesNo(boolean value) {
        return value ? "是" : "否";
    }

    private String phaseLabel(SyncPhase syncPhase) {
        return switch (syncPhase) {
            case NORMAL -> "正常运行";
            case MAINTENANCE -> "维护模式";
            case DRAINING -> "正在排空";
            case VERIFYING -> "正在校验";
            case RELOADING -> "正在重载";
        };
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
