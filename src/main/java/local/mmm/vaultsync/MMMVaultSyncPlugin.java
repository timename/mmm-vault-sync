package local.mmm.vaultsync;

import local.mmm.vaultsync.api.BalanceMutationResult;
import local.mmm.vaultsync.api.SyncPhase;
import local.mmm.vaultsync.api.VaultSyncCurrencyBalanceChangeEvent;
import local.mmm.vaultsync.api.VaultSyncCurrencyService;
import local.mmm.vaultsync.api.VaultSyncPhaseChangeEvent;
import local.mmm.vaultsync.api.VaultSyncStateService;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class MMMVaultSyncPlugin extends JavaPlugin implements Listener, TabCompleter,
        VaultSyncStateService, VaultSyncCurrencyService {
    public static final String DEFAULT_CURRENCY_ID = "default";

    private static final String ADMIN_PERMISSION = "mmmvaultsync.admin";
    private static final List<String> SUBCOMMANDS = List.of(
            "help", "reload", "status", "sync", "maintenance", "drain", "verify", "balance", "bal", "currencies"
    );
    private static final List<String> BALANCE_QUERY_ACTIONS = List.of("query", "q");
    private static final List<String> BALANCE_MUTATION_ACTIONS = List.of("set", "add", "take");
    private static final long BALANCE_NOTICE_SUPPRESSION_MILLIS = 30_000L;
    private static final long REMOTE_NOTICE_SUPPRESSION_MILLIS = 300_000L;
    private static final long RELOAD_CONFIRM_WINDOW_MILLIS = 15_000L;
    private static final long DRAIN_WAIT_TIMEOUT_MILLIS = 15_000L;
    private static final long DRAIN_POLL_INTERVAL_MILLIS = 50L;

    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingReloadConfirmations = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, NoticeMark>> recentBalanceNotices = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, RevisionNoticeMark>> remoteBalanceNotices = new ConcurrentHashMap<>();
    private final AtomicInteger activeAsyncOperations = new AtomicInteger();

    private Lang lang;
    private ExecutorService executor;
    private Economy backendEconomy;
    private SyncEconomyProxy syncEconomyProxy;
    private SyncConfig config;
    private BalanceStore store;
    private RedisSyncManager redisSyncManager;
    private VaultSyncPlaceholderExpansion placeholderExpansion;
    private BukkitTask scanTask;
    private volatile boolean maintenanceMode;
    private volatile boolean drainCompleted;
    private volatile boolean verifyCompleted;
    private volatile long lastObservedMaintenanceChangeMillis;
    private volatile SyncPhase phase = SyncPhase.NORMAL;
    private volatile boolean setupRequired;
    private volatile String setupReason = "";
    private volatile boolean cmiBalanceListenerRegistered;

    @Override
    public void onEnable() {
        boolean firstRun = !new File(getDataFolder(), "config.yml").exists();
        saveDefaultConfig();
        saveResource("lang/ch_ZN.yml", false);
        lang = new Lang(this);
        lang.reload(getConfig().getString("language", "ch_ZN"));

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

        ensureRuntimeReady();
        if (!reloadPluginInternal(true)) {
            throw new IllegalStateException("MMMVaultSync 配置加载失败");
        }
        registerServicesIfNeeded();
        registerCmiBalanceListenerIfPresent();
        registerPlaceholderExpansionIfPresent();
    }

    @Override
    public void onDisable() {
        stopScanTask();
        try {
            flushOnlinePlayersBlocking("shutdown");
        } catch (Exception exception) {
            getLogger().log(Level.WARNING, "插件关闭时刷新玩家余额失败", exception);
        }
        unregisterServices();
        unregisterPlaceholderExpansion();
        if (redisSyncManager != null) {
            redisSyncManager.close();
            redisSyncManager = null;
        }
        if (store != null) {
            store.close();
            store = null;
        }
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
        backendEconomy = null;
        syncEconomyProxy = null;
        states.clear();
        pendingReloadConfirmations.clear();
        recentBalanceNotices.clear();
        remoteBalanceNotices.clear();
        phase = SyncPhase.NORMAL;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(lang.warn("command.no-permission", Map.of()));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        if (setupRequired) {
            if ("reload".equalsIgnoreCase(args[0])) {
                return handleSetupReloadCommand(sender);
            }
            sender.sendMessage(lang.warn("setup.mode.active", Map.of()));
            sender.sendMessage(lang.info("setup.mode.reason", Map.of("reason", setupReason)));
            sender.sendMessage(lang.info("setup.mode.edit-config", Map.of()));
            sender.sendMessage(lang.ok("setup.mode.reload", Map.of()));
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> {
                sendHelp(sender);
                yield true;
            }
            case "reload" -> handleReloadCommand(sender, args);
            case "status" -> {
                sendStatus(sender);
                yield true;
            }
            case "sync" -> handleSyncCommand(sender, args);
            case "maintenance" -> handleMaintenanceCommand(sender, args);
            case "drain" -> handleDrainCommand(sender);
            case "verify" -> handleVerifyCommand(sender);
            case "balance" -> handleBalanceCommand(sender, args);
            case "bal" -> handleBalCommand(sender, args);
            case "currencies" -> {
                sendCurrencies(sender);
                yield true;
            }
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(lang.info("command.help-title", Map.of()));
        sender.sendMessage(lang.info("command.usage.help", Map.of()));
        sender.sendMessage(lang.info("command.usage.help-syntax", Map.of()));
        sender.sendMessage(lang.text("command.group.general", Map.of()));
        sender.sendMessage(lang.info("command.help.reload", Map.of()));
        sender.sendMessage(lang.info("command.help.status", Map.of()));
        sender.sendMessage(lang.info("command.help.sync", Map.of()));
        sender.sendMessage(lang.info("command.help.currencies", Map.of()));
        sender.sendMessage(lang.text("command.group.balance", Map.of()));
        sender.sendMessage(lang.info("command.help.balance-query", Map.of()));
        sender.sendMessage(lang.info("command.help.balance-admin", Map.of()));
        sender.sendMessage(lang.info("command.help.bal", Map.of()));
        sender.sendMessage(lang.text("command.group.maintenance", Map.of()));
        sender.sendMessage(lang.info("command.help.maintenance", Map.of()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterByPrefix(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && "maintenance".equalsIgnoreCase(args[0])) {
            return filterByPrefix(List.of("on", "off"), args[1]);
        }
        if (args.length == 2 && "reload".equalsIgnoreCase(args[0])) {
            return filterByPrefix(List.of("confirm"), args[1]);
        }
        if (args.length == 2 && List.of("sync", "balance", "bal").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filterByPrefix(listKnownPlayerNames(), args[1]);
        }
        if (args.length == 3 && "bal".equalsIgnoreCase(args[0])) {
            List<String> candidates = new ArrayList<>(getCurrencies().keySet());
            return filterByPrefix(candidates, args[2]);
        }
        if (args.length == 3 && "balance".equalsIgnoreCase(args[0])) {
            List<String> candidates = new ArrayList<>(BALANCE_QUERY_ACTIONS);
            candidates.addAll(BALANCE_MUTATION_ACTIONS);
            return filterByPrefix(candidates, args[2]);
        }
        if (args.length == 3 && "sync".equalsIgnoreCase(args[0])) {
            return filterByPrefix(new ArrayList<>(getCurrencies().keySet()), args[2]);
        }
        if (args.length == 4 && "bal".equalsIgnoreCase(args[0])) {
            return filterByPrefix(new ArrayList<>(getCurrencies().keySet()), args[3]);
        }
        if (args.length == 4 && "balance".equalsIgnoreCase(args[0]) && isBalanceQueryAction(args[2])) {
            return filterByPrefix(new ArrayList<>(getCurrencies().keySet()), args[3]);
        }
        if (args.length == 4 && "balance".equalsIgnoreCase(args[0]) && isBalanceMutationAction(args[2])) {
            return filterByPrefix(balanceAmountSuggestions(), args[3]);
        }
        if (args.length == 5 && "balance".equalsIgnoreCase(args[0]) && isBalanceMutationAction(args[2])) {
            return filterByPrefix(new ArrayList<>(getCurrencies().keySet()), args[4]);
        }
        return Collections.emptyList();
    }

    private List<String> balanceAmountSuggestions() {
        return List.of("1", "10", "100", "1000");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (setupRequired && (event.getPlayer().isOp() || event.getPlayer().hasPermission(ADMIN_PERMISSION))) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!event.getPlayer().isOnline()) {
                    return;
                }
                event.getPlayer().sendMessage(lang.warn("setup.mode.active", Map.of()));
                event.getPlayer().sendMessage(lang.info("setup.mode.reason", Map.of("reason", setupReason)));
                event.getPlayer().sendMessage(lang.info("setup.mode.edit-config", Map.of()));
                event.getPlayer().sendMessage(lang.info("setup.mode.minimum-fields", Map.of()));
                event.getPlayer().sendMessage(lang.ok("setup.mode.reload", Map.of()));
            }, 40L);
        }

        if (setupRequired || config == null) {
            return;
        }

        UUID uuid = event.getPlayer().getUniqueId();
        PlayerState state = states.computeIfAbsent(uuid, ignored -> new PlayerState());
        state.defaultState.lastObservedBalance = getBackendBalance(event.getPlayer());
        if (!maintenanceMode) {
            Bukkit.getScheduler().runTaskLater(this,
                    () -> scheduleAuthoritativeLoad(uuid, "join", null),
                    config.joinLoadDelayTicks());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (config != null && config.flushOnQuit() && !maintenanceMode) {
            flushPlayerNow(uuid, event.getPlayer(), "quit");
        }
        states.remove(uuid);
        recentBalanceNotices.remove(uuid);
        remoteBalanceNotices.remove(uuid);
    }

    public void afterEconomyMutation(OfflinePlayer player, EconomyResponse response, String reason) {
        if (player == null || response == null || !response.transactionSuccess()) {
            return;
        }
        BigDecimal delta = scaled(response.amount);
        BigDecimal balance = scaled(response.balance);
        debug("Vault 默认货币变更: 玩家=" + displayPlayer(player) + ", 原因=" + reason + ", 新余额=" + balance);
        notifyBalanceChangeIfNeeded(player, config.defaultCurrency(), delta, balance, reason, 0L);
        recordDefaultBalanceChange(player, balance, reason);
    }

    @Override
    public String getDefaultCurrencyId() {
        return config == null ? DEFAULT_CURRENCY_ID : config.defaultCurrencyId();
    }

    @Override
    public Map<String, CurrencyDefinition> getCurrencies() {
        if (config == null) {
            return Map.of(DEFAULT_CURRENCY_ID, new CurrencyDefinition(DEFAULT_CURRENCY_ID, "默认货币", "", BigDecimal.ZERO, true));
        }
        return config.currencies();
    }

    @Override
    public CompletableFuture<BigDecimal> getBalanceAsync(UUID playerId, String currencyId) {
        String normalizedCurrencyId = normalizeCurrencyId(currencyId);
        CurrencyDefinition currency = getCurrencies().get(normalizedCurrencyId);
        if (currency == null) {
            return CompletableFuture.completedFuture(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        }

        Player online = Bukkit.getPlayer(playerId);
        PlayerState state = states.get(playerId);
        CurrencyState currencyState = state == null ? null : state.stateFor(normalizedCurrencyId);
        if (currencyState != null && currencyState.lastObservedBalance != null) {
            return CompletableFuture.completedFuture(currencyState.lastObservedBalance);
        }
        if (online != null && normalizedCurrencyId.equals(config.defaultCurrencyId())) {
            return CompletableFuture.completedFuture(getBackendBalance(online));
        }

        return runAsync(() -> loadAuthoritativeBalance(playerId, normalizedCurrencyId));
    }

    @Override
    public CompletableFuture<BalanceMutationResult> setBalanceAsync(UUID playerId, String currencyId, BigDecimal amount, String reason) {
        return mutateBalanceAsync(playerId, normalizeCurrencyId(currencyId), normalizeAmount(amount), MutationType.SET, reason);
    }

    @Override
    public CompletableFuture<BalanceMutationResult> addBalanceAsync(UUID playerId, String currencyId, BigDecimal amount, String reason) {
        return mutateBalanceAsync(playerId, normalizeCurrencyId(currencyId), normalizeAmount(amount), MutationType.ADD, reason);
    }

    @Override
    public CompletableFuture<BalanceMutationResult> removeBalanceAsync(UUID playerId, String currencyId, BigDecimal amount, String reason) {
        return mutateBalanceAsync(playerId, normalizeCurrencyId(currencyId), normalizeAmount(amount), MutationType.TAKE, reason);
    }

    BigDecimal getBalanceSnapshot(UUID playerId, String currencyId) {
        String normalizedCurrencyId = normalizeCurrencyId(currencyId);
        CurrencyDefinition currency = getCurrencies().get(normalizedCurrencyId);
        if (currency == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        Player online = Bukkit.getPlayer(playerId);
        if (online != null && normalizedCurrencyId.equals(config.defaultCurrencyId())) {
            return getBackendBalance(online);
        }
        PlayerState state = states.get(playerId);
        CurrencyState currencyState = state == null ? null : state.stateFor(normalizedCurrencyId);
        if (currencyState != null && currencyState.lastObservedBalance != null) {
            return currencyState.lastObservedBalance;
        }
        return currency.normalizedStartingBalance();
    }

    String formatCurrencyAmount(CurrencyDefinition currency, BigDecimal amount) {
        return formatAmount(currency, amount);
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(lang.info("status.title", Map.of()));
        sender.sendMessage(lang.text("status.server-id", Map.of("server", config.serverId())));
        sender.sendMessage(lang.text("status.phase", Map.of("phase", phaseLabel(phase))));
        sender.sendMessage(lang.text("status.tracked", Map.of("count", String.valueOf(states.size()))));
        sender.sendMessage(lang.text("status.local-scan", Map.of("ticks", String.valueOf(config.localScanIntervalTicks()))));
        sender.sendMessage(lang.text("status.remote-refresh", Map.of("millis", String.valueOf(config.remoteRefreshIntervalMillis()))));
        sender.sendMessage(lang.text("status.maintenance", Map.of("value", yesNo(maintenanceMode))));
        sender.sendMessage(lang.text("status.drain", Map.of("value", yesNo(drainCompleted))));
        sender.sendMessage(lang.text("status.verify", Map.of("value", yesNo(verifyCompleted))));
        sender.sendMessage(lang.text("status.async", Map.of("count", String.valueOf(activeAsyncOperations.get()))));
        sender.sendMessage(lang.text("status.proxy", Map.of("value", syncEconomyProxy == null ? "未接管" : syncEconomyProxy.getName())));
        sender.sendMessage(lang.text("status.default-currency", Map.of("currency", config.defaultCurrency().displayLabel())));
        sender.sendMessage(lang.text("status.custom-currency-count", Map.of("count", String.valueOf(config.currencies().size() - 1))));
    }

    private void sendCurrencies(CommandSender sender) {
        sender.sendMessage(lang.info("currency.list-title", Map.of()));
        for (CurrencyDefinition currency : config.currencies().values()) {
            sender.sendMessage(lang.text(
                    "currency.list-entry",
                    Map.of(
                            "id", currency.id(),
                            "name", currency.displayName(),
                            "symbol", currency.symbol().isBlank() ? "-" : currency.symbol(),
                            "type", currency.id().equals(config.defaultCurrencyId()) ? "默认" : "自管"
                    )));
        }
    }

    private boolean handleSyncCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(lang.info("command.usage.sync", Map.of()));
            return true;
        }

        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) {
            sender.sendMessage(lang.warn("command.player-offline", Map.of()));
            return true;
        }

        String currencyId = args.length >= 3 ? normalizeCurrencyId(args[2]) : null;
        if (currencyId != null && !getCurrencies().containsKey(currencyId)) {
            sender.sendMessage(lang.warn("currency.unknown", Map.of("currency", args[2])));
            return true;
        }

        scheduleAuthoritativeLoad(player.getUniqueId(), "manual", currencyId);
        sender.sendMessage(lang.ok("command.sync-requested", Map.of(
                "player", player.getName(),
                "currency", currencyId == null ? "全部货币" : currencyDisplayName(currencyId)
        )));
        return true;
    }

    private boolean handleBalanceCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(lang.info("command.usage.balance-query", Map.of()));
            return true;
        }

        OfflinePlayer target = resolveOfflinePlayer(args[1]);
        if (target == null) {
            sender.sendMessage(lang.warn("command.player-not-found", Map.of("player", args[1])));
            return true;
        }

        String queryCurrencyId = parseBalanceQueryCurrency(args, 2);
        if (queryCurrencyId != null) {
            return sendBalanceQuery(sender, target, queryCurrencyId);
        }

        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "query";
        if (isBalanceQueryAction(action)) {
            sender.sendMessage(lang.info("command.usage.balance-query", Map.of()));
            return true;
        }

        if (args.length < 4 || args.length > 5) {
            sender.sendMessage(lang.info("command.usage.balance-admin", Map.of()));
            return true;
        }

        BigDecimal amount = parseAmount(args[3]);
        if (amount == null || amount.signum() < 0) {
            sender.sendMessage(lang.warn("balance.invalid-amount", Map.of()));
            return true;
        }

        String currencyId = args.length >= 5 ? normalizeCurrencyId(args[4]) : config.defaultCurrencyId();
        CurrencyDefinition currency = getCurrencies().get(currencyId);
        if (currency == null) {
            sender.sendMessage(lang.warn("currency.unknown", Map.of("currency", currencyId)));
            return true;
        }
        if (!canAcceptEconomicOperations()) {
            sender.sendMessage(lang.warn("currency.write-blocked", Map.of()));
            return true;
        }

        CompletableFuture<BalanceMutationResult> future = switch (action) {
            case "set" -> setBalanceAsync(target.getUniqueId(), currencyId, amount, "admin-set:" + sender.getName());
            case "add" -> addBalanceAsync(target.getUniqueId(), currencyId, amount, "admin-add:" + sender.getName());
            case "take" -> removeBalanceAsync(target.getUniqueId(), currencyId, amount, "admin-take:" + sender.getName());
            default -> null;
        };

        if (future == null) {
            sender.sendMessage(lang.info("command.usage.balance-admin", Map.of()));
            return true;
        }

        future.whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
            if (throwable != null) {
                sender.sendMessage(lang.warn("balance.change-failed", Map.of("player", displayPlayer(target))));
                log(Level.WARNING, "管理员修改余额失败: " + target.getUniqueId() + ", currency=" + currencyId, throwable);
                return;
            }
            if (!result.success()) {
                sender.sendMessage(lang.warn("balance.change-failed-detail", Map.of("detail", result.message())));
                return;
            }
            sender.sendMessage(lang.ok("balance.change-success", Map.of(
                    "player", displayPlayer(target),
                    "currency", currency.displayLabel(),
                    "balance", formatAmount(currency, result.newBalance())
            )));
        }));
        return true;
    }

    private boolean handleBalCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(lang.info("command.usage.balance-short", Map.of()));
            return true;
        }

        OfflinePlayer target = resolveOfflinePlayer(args[1]);
        if (target == null) {
            sender.sendMessage(lang.warn("command.player-not-found", Map.of("player", args[1])));
            return true;
        }

        if (args.length >= 3 && isBalanceMutationAction(args[2])) {
            sender.sendMessage(lang.warn("balance.short-query-only", Map.of()));
            sender.sendMessage(lang.info("command.usage.balance-short", Map.of()));
            return true;
        }

        String currencyId = parseBalanceQueryCurrency(args, 2);
        if (currencyId == null) {
            sender.sendMessage(lang.info("command.usage.balance-short", Map.of()));
            return true;
        }
        return sendBalanceQuery(sender, target, currencyId);
    }

    private String parseBalanceQueryCurrency(String[] args, int firstArgumentIndex) {
        if (args.length == firstArgumentIndex) {
            return config.defaultCurrencyId();
        }
        if (args.length == firstArgumentIndex + 1) {
            String argument = args[firstArgumentIndex];
            if (isBalanceQueryAction(argument)) {
                return config.defaultCurrencyId();
            }
            return isBalanceMutationAction(argument) ? null : normalizeCurrencyId(argument);
        }
        if (args.length == firstArgumentIndex + 2 && isBalanceQueryAction(args[firstArgumentIndex])) {
            return normalizeCurrencyId(args[firstArgumentIndex + 1]);
        }
        return null;
    }

    private boolean sendBalanceQuery(CommandSender sender, OfflinePlayer target, String currencyId) {
        CurrencyDefinition currency = getCurrencies().get(currencyId);
        if (currency == null) {
            sender.sendMessage(lang.warn("currency.unknown", Map.of("currency", currencyId)));
            return true;
        }

        getBalanceAsync(target.getUniqueId(), currencyId).whenComplete((balance, throwable) ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (throwable != null) {
                        sender.sendMessage(lang.warn("balance.query-failed", Map.of("player", displayPlayer(target))));
                        log(Level.WARNING, "查询余额失败: " + target.getUniqueId() + ", currency=" + currencyId, throwable);
                        return;
                    }
                    sender.sendMessage(lang.ok("balance.query-result", Map.of(
                            "player", displayPlayer(target),
                            "currency", currency.displayLabel(),
                            "balance", formatAmount(currency, balance)
                    )));
                }));
        return true;
    }

    private boolean isBalanceQueryAction(String action) {
        return "query".equalsIgnoreCase(action) || "q".equalsIgnoreCase(action);
    }

    private boolean isBalanceMutationAction(String action) {
        return "set".equalsIgnoreCase(action) || "add".equalsIgnoreCase(action) || "take".equalsIgnoreCase(action);
    }

    private boolean handleReloadCommand(CommandSender sender, String[] args) {
        if (!maintenanceMode) {
            sender.sendMessage(lang.warn("reload.require-maintenance", Map.of()));
            return true;
        }
        if (!drainCompleted || !verifyCompleted) {
            sender.sendMessage(lang.warn("reload.require-drain-verify", Map.of()));
            return true;
        }
        if (!isReloadConfirmed(sender, args)) {
            sender.sendMessage(lang.warn("reload.confirm-required", Map.of()));
            sender.sendMessage(lang.info("reload.risk-1", Map.of()));
            sender.sendMessage(lang.info("reload.risk-2", Map.of()));
            sender.sendMessage(lang.ok("reload.confirm-command", Map.of()));
            return true;
        }

        if (reloadPluginInternal(false)) {
            drainCompleted = false;
            verifyCompleted = false;
            sender.sendMessage(lang.ok("reload.success", Map.of()));
        } else {
            sender.sendMessage(lang.warn("reload.failed", Map.of()));
        }
        return true;
    }

    private boolean handleSetupReloadCommand(CommandSender sender) {
        sender.sendMessage(lang.info("setup.reload-reading", Map.of()));

        reloadConfig();
        lang.reload(getConfig().getString("language", "ch_ZN"));
        if (isConfigUsingPlaceholders()) {
            sender.sendMessage(lang.warn("setup.incomplete", Map.of()));
            sender.sendMessage(lang.info("setup.required-fields-title", Map.of()));
            sender.sendMessage("§7- §fserver-id");
            sender.sendMessage("§7- §fdatabase.host");
            sender.sendMessage("§7- §fdatabase.port");
            sender.sendMessage("§7- §fdatabase.database");
            sender.sendMessage("§7- §fdatabase.username");
            sender.sendMessage("§7- §fdatabase.password");
            printConfigPlaceholderNotice();
            return true;
        }

        ensureRuntimeReady();
        setupRequired = false;
        setupReason = "";
        if (!reloadPluginInternal(true)) {
            setupRequired = true;
            setupReason = "配置已修改，但数据库连接或初始化仍然失败";
            sender.sendMessage(lang.warn("setup.reload-failed", Map.of()));
            return true;
        }

        registerServicesIfNeeded();
        registerCmiBalanceListenerIfPresent();
        registerPlaceholderExpansionIfPresent();
        sender.sendMessage(lang.ok("setup.ready", Map.of()));
        return true;
    }

    private boolean handleMaintenanceCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(lang.info("command.usage.maintenance", Map.of()));
            return true;
        }

        if ("on".equalsIgnoreCase(args[1])) {
            maintenanceMode = true;
            drainCompleted = false;
            verifyCompleted = false;
            lastObservedMaintenanceChangeMillis = System.currentTimeMillis();
            pendingReloadConfirmations.clear();
            setPhase(SyncPhase.MAINTENANCE);
            sender.sendMessage(lang.ok("maintenance.on", Map.of()));
            sender.sendMessage(lang.info("maintenance.on-detail-1", Map.of()));
            sender.sendMessage(lang.info("maintenance.on-detail-2", Map.of()));
            sender.sendMessage(lang.ok("maintenance.on-next", Map.of()));
            return true;
        }

        if ("off".equalsIgnoreCase(args[1])) {
            maintenanceMode = false;
            drainCompleted = false;
            verifyCompleted = false;
            pendingReloadConfirmations.clear();
            setPhase(SyncPhase.NORMAL);
            sender.sendMessage(lang.ok("maintenance.off", Map.of()));
            for (Player player : Bukkit.getOnlinePlayers()) {
                scheduleAuthoritativeLoad(player.getUniqueId(), "maintenance-off", null);
            }
            return true;
        }

        sender.sendMessage(lang.info("command.usage.maintenance", Map.of()));
        return true;
    }

    private boolean handleDrainCommand(CommandSender sender) {
        if (!maintenanceMode) {
            sender.sendMessage(lang.warn("drain.require-maintenance", Map.of()));
            return true;
        }

        sender.sendMessage(lang.info("drain.start", Map.of()));
        setPhase(SyncPhase.DRAINING);
        runAsync(this::performDrain).whenComplete((success, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
            if (throwable != null || !Boolean.TRUE.equals(success)) {
                drainCompleted = false;
                setPhase(SyncPhase.MAINTENANCE);
                sender.sendMessage(lang.warn("drain.failed", Map.of()));
                if (throwable != null) {
                    log(Level.WARNING, "drain 执行失败", throwable);
                }
                return;
            }
            drainCompleted = true;
            verifyCompleted = false;
            setPhase(SyncPhase.MAINTENANCE);
            sender.sendMessage(lang.ok("drain.success", Map.of()));
        }));
        return true;
    }

    private boolean handleVerifyCommand(CommandSender sender) {
        if (!maintenanceMode) {
            sender.sendMessage(lang.warn("verify.require-maintenance", Map.of()));
            return true;
        }
        if (!drainCompleted) {
            sender.sendMessage(lang.warn("verify.require-drain", Map.of()));
            return true;
        }

        sender.sendMessage(lang.info("verify.start", Map.of()));
        setPhase(SyncPhase.VERIFYING);
        runAsync(this::performVerify).whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
            if (throwable != null) {
                verifyCompleted = false;
                setPhase(SyncPhase.MAINTENANCE);
                sender.sendMessage(lang.warn("verify.failed", Map.of()));
                log(Level.WARNING, "verify 执行失败", throwable);
                return;
            }
            if (!result.ok()) {
                verifyCompleted = false;
                setPhase(SyncPhase.MAINTENANCE);
                sender.sendMessage(lang.warn("verify.failed-detail", Map.of("detail", result.message())));
                return;
            }
            verifyCompleted = true;
            setPhase(SyncPhase.MAINTENANCE);
            sender.sendMessage(lang.ok("verify.success", Map.of("detail", result.message())));
        }));
        return true;
    }

    private boolean reloadPluginInternal(boolean startup) {
        SyncPhase previousPhase = phase;
        if (!startup) {
            setPhase(SyncPhase.RELOADING);
            stopScanTask();
            try {
                flushOnlinePlayersBlocking("reload");
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "重载前刷新玩家余额失败", exception);
                setPhase(previousPhase);
                restartScanTask();
                return false;
            }
        }

        reloadConfig();
        SyncConfig newConfig = loadSyncConfig();

        BalanceStore oldStore = this.store;
        BalanceStore newStore = new BalanceStore(newConfig);
        RedisSyncManager oldRedis = this.redisSyncManager;
        RedisSyncManager newRedis = createRedisSyncManager(newConfig);
        try {
            newStore.ensureSchema();
            if (newRedis != null) {
                newRedis.start();
            }
        } catch (SQLException exception) {
            newStore.close();
            if (newRedis != null) {
                newRedis.close();
            }
            if (oldStore != null) {
                this.store = oldStore;
            }
            getLogger().log(Level.SEVERE, "初始化同步数据库表失败", exception);
            if (!startup) {
                setPhase(previousPhase);
                restartScanTask();
            }
            return false;
        }

        this.config = newConfig;
        this.store = newStore;
        this.redisSyncManager = newRedis;
        lang.reload(newConfig.language());
        if (oldStore != null) {
            oldStore.close();
        }
        if (oldRedis != null) {
            oldRedis.close();
        }

        ensureEconomyProxyRegistered();
        resyncTrackedPlayersAfterReload();
        restartScanTask();
        setPhase(maintenanceMode ? SyncPhase.MAINTENANCE : SyncPhase.NORMAL);
        registerServicesIfNeeded();
        registerCmiBalanceListenerIfPresent();
        registerPlaceholderExpansionIfPresent();
        return true;
    }

    private SyncConfig loadSyncConfig() {
        String host = getConfig().getString("database.host", "127.0.0.1");
        int port = getConfig().getInt("database.port", 3306);
        String database = getConfig().getString("database.database", "minecraft");
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&autoReconnect=true&useUnicode=true&characterEncoding=UTF-8";

        CurrencyDefinition defaultCurrency = new CurrencyDefinition(
                DEFAULT_CURRENCY_ID,
                getConfig().getString("default-currency.display-name", "金币"),
                getConfig().getString("default-currency.symbol", ""),
                normalizeAmount(BigDecimal.ZERO),
                getConfig().getBoolean("default-currency.notify-on-change", true)
        );

        Map<String, CurrencyDefinition> currencies = new LinkedHashMap<>();
        currencies.put(defaultCurrency.id(), defaultCurrency);

        ConfigurationSection currenciesSection = getConfig().getConfigurationSection("currencies");
        if (currenciesSection != null) {
            for (String id : currenciesSection.getKeys(false)) {
                String normalizedId = normalizeCurrencyId(id);
                if (DEFAULT_CURRENCY_ID.equals(normalizedId)) {
                    continue;
                }
                ConfigurationSection section = currenciesSection.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                currencies.put(normalizedId, new CurrencyDefinition(
                        normalizedId,
                        section.getString("display-name", normalizedId),
                        section.getString("symbol", ""),
                        normalizeAmount(BigDecimal.valueOf(section.getDouble("starting-balance", 0.0D))),
                        section.getBoolean("notify-on-change", true)
                ));
            }
        }

        return new SyncConfig(
                getConfig().getString("language", "ch_ZN"),
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
                getConfig().getBoolean("logging.debug", false),
                DEFAULT_CURRENCY_ID,
                defaultCurrency,
                Collections.unmodifiableMap(currencies),
                loadRedisConfig()
        );
    }

    private void scanOnlinePlayers() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            PlayerState state = states.computeIfAbsent(uuid, ignored -> new PlayerState());
            CurrencyState defaultState = state.defaultState;
            BigDecimal observed = getBackendBalance(player);

            if (defaultState.lastObservedBalance == null) {
                defaultState.lastObservedBalance = observed;
            }

            if (maintenanceMode) {
                if (!sameBalance(observed, defaultState.lastObservedBalance)) {
                    lastObservedMaintenanceChangeMillis = now;
                    drainCompleted = false;
                    verifyCompleted = false;
                    debug("维护模式期间检测到默认货币变化: " + player.getName()
                            + " " + defaultState.lastObservedBalance + " -> " + observed);
                }
                defaultState.lastObservedBalance = observed;
                continue;
            }

            if (!defaultState.writeInFlight
                    && now >= defaultState.suppressWritesUntilMillis
                    && !sameBalance(observed, defaultState.lastObservedBalance)) {
                defaultState.lastObservedBalance = observed;
                scheduleWrite(uuid, config.defaultCurrencyId(), observed, defaultState, "scan-local-change");
            } else {
                defaultState.lastObservedBalance = observed;
            }

            if (!defaultState.remoteLoadInFlight && now >= defaultState.nextRemoteRefreshMillis) {
                defaultState.nextRemoteRefreshMillis = now + config.remoteRefreshIntervalMillis();
                scheduleAuthoritativeLoad(uuid, "periodic", config.defaultCurrencyId());
            }
        }
    }

    private void scheduleAuthoritativeLoad(UUID uuid, String reason, String targetCurrencyId) {
        if (maintenanceMode && !"manual".equals(reason) && !"reload".equals(reason) && !"maintenance-off".equals(reason)) {
            return;
        }

        PlayerState state = states.computeIfAbsent(uuid, ignored -> new PlayerState());
        CurrencyState gateState = state.stateFor(targetCurrencyId == null ? config.defaultCurrencyId() : targetCurrencyId);
        if (gateState.remoteLoadInFlight) {
            return;
        }
        gateState.remoteLoadInFlight = true;
        debug("开始远端拉取: uuid=" + uuid + ", reason=" + reason + ", currency=" + (targetCurrencyId == null ? "ALL" : targetCurrencyId));

        boolean loadAllCurrencies = targetCurrencyId == null && !"periodic".equals(reason);
        runAsync(() -> loadAuthoritativeRecords(uuid, targetCurrencyId, loadAllCurrencies))
                .whenComplete((records, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
            gateState.remoteLoadInFlight = false;
            if (throwable != null) {
                log(Level.WARNING, "加载玩家权威余额失败: " + uuid + " (" + reason + ")", throwable);
                return;
            }

            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            Player onlinePlayer = Bukkit.getPlayer(uuid);
            Map<String, BalanceRecord> nonNullRecords = new LinkedHashMap<>();
            for (Map.Entry<String, BalanceRecord> entry : records.entrySet()) {
                if (entry.getValue() != null) {
                    nonNullRecords.put(entry.getKey(), entry.getValue());
                }
            }

            if (targetCurrencyId == null || config.defaultCurrencyId().equals(targetCurrencyId)) {
                BalanceRecord defaultRecord = nonNullRecords.get(config.defaultCurrencyId());
                if (defaultRecord != null && onlinePlayer != null && onlinePlayer.isOnline()) {
                    applyAuthoritativeDefaultBalance(onlinePlayer, state.defaultState, defaultRecord, reason);
                } else if (defaultRecord == null && !maintenanceMode && onlinePlayer != null && onlinePlayer.isOnline()) {
                    BigDecimal localBalance = getBackendBalance(onlinePlayer);
                    scheduleWrite(uuid, config.defaultCurrencyId(), localBalance, state.defaultState, "seed-" + reason);
                }
            }

            for (Map.Entry<String, BalanceRecord> entry : nonNullRecords.entrySet()) {
                if (config.defaultCurrencyId().equals(entry.getKey())) {
                    continue;
                }
                applyManagedCurrencyRecord(offlinePlayer, state, entry.getValue(), reason);
            }
        }));
    }

    private Map<String, BalanceRecord> loadAuthoritativeRecords(
            UUID uuid,
            String targetCurrencyId,
            boolean loadAllCurrencies
    ) throws SQLException {
        if (targetCurrencyId != null) {
            Map<String, BalanceRecord> records = new LinkedHashMap<>();
            records.put(targetCurrencyId, store.load(uuid, targetCurrencyId).orElse(null));
            return records;
        }
        if (loadAllCurrencies) {
            return store.loadAll(uuid);
        }
        Map<String, BalanceRecord> records = new LinkedHashMap<>();
        records.put(config.defaultCurrencyId(), store.load(uuid, config.defaultCurrencyId()).orElse(null));
        return records;
    }

    private void applyAuthoritativeDefaultBalance(Player player, CurrencyState state, BalanceRecord record, String reason) {
        BigDecimal current = getBackendBalance(player);
        BigDecimal previous = state.lastObservedBalance == null ? current : state.lastObservedBalance;
        if (sameBalance(current, record.balance())) {
            state.knownRevision = Math.max(state.knownRevision, record.revision());
            state.lastObservedBalance = record.balance();
            return;
        }

        BigDecimal delta = record.balance().subtract(current);
        EconomyResponse response = delta.signum() >= 0
                ? backendEconomy.depositPlayer(player, delta.doubleValue())
                : backendEconomy.withdrawPlayer(player, delta.abs().doubleValue());
        if (!response.transactionSuccess()) {
            getLogger().warning("应用默认货币权威余额失败: " + player.getName() + ", reason=" + reason + ", error=" + response.errorMessage);
            return;
        }

        state.suppressWritesUntilMillis = System.currentTimeMillis() + config.writeSuppressMillisAfterApply();
        state.knownRevision = Math.max(state.knownRevision, record.revision());
        state.lastObservedBalance = record.balance();
        notifyBalanceChangeIfNeeded(player, config.defaultCurrency(), record.balance().subtract(current), record.balance(), reason, record.revision());
        fireBalanceChangeEvent(player.getUniqueId(), config.defaultCurrencyId(), previous, record.balance(), reason, true);
    }

    private void applyManagedCurrencyRecord(OfflinePlayer player, PlayerState state, BalanceRecord record, String reason) {
        CurrencyDefinition currency = getCurrencies().get(record.currencyId());
        if (currency == null) {
            return;
        }

        CurrencyState currencyState = state.stateFor(record.currencyId());
        BigDecimal previous = currencyState.lastObservedBalance == null
                ? currency.normalizedStartingBalance()
                : currencyState.lastObservedBalance;
        if (currencyState.knownRevision >= record.revision() && !currencyState.remoteLoadInFlight) {
            return;
        }

        currencyState.knownRevision = Math.max(currencyState.knownRevision, record.revision());
        currencyState.lastObservedBalance = record.balance();
        notifyBalanceChangeIfNeeded(player, currency, record.balance().subtract(previous), record.balance(), reason, record.revision());
        fireBalanceChangeEvent(player.getUniqueId(), currency.id(), previous, record.balance(), reason, true);
    }

    private void scheduleWrite(UUID uuid, String currencyId, BigDecimal balance, CurrencyState state, String reason) {
        if (maintenanceMode) {
            drainCompleted = false;
            verifyCompleted = false;
            debug("维护模式期间跳过写入: " + uuid + ", currency=" + currencyId + ", reason=" + reason);
            return;
        }

        if (state.writeInFlight) {
            state.pendingWriteBalance = balance;
            return;
        }

        state.writeInFlight = true;
        long knownRevisionSnapshot = state.knownRevision;
        runAsync(() -> store.writeSnapshot(uuid, currencyId, balance, knownRevisionSnapshot, config.serverId()))
                .whenComplete((record, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
                    state.writeInFlight = false;
                    if (throwable != null) {
                        log(Level.WARNING, "写入玩家余额失败: " + uuid + ", currency=" + currencyId + ", reason=" + reason, throwable);
                    } else if (record != null) {
                        state.knownRevision = Math.max(state.knownRevision, record.revision());
                        state.lastObservedBalance = record.balance();
                        publishRedisBalanceChange(record.uuid(), record.currencyId(), record.revision());
                    }

                    if (state.pendingWriteBalance != null) {
                        BigDecimal pending = state.pendingWriteBalance;
                        state.pendingWriteBalance = null;
                        scheduleWrite(uuid, currencyId, pending, state, "pending");
                    }
                }));
    }

    private void flushPlayerNow(UUID uuid, OfflinePlayer player, String reason) {
        if (store == null) {
            return;
        }

        PlayerState state = states.computeIfAbsent(uuid, ignored -> new PlayerState());
        BigDecimal defaultBalance = player.isOnline() && player instanceof Player online
                ? getBackendBalance(online)
                : state.defaultState.lastObservedBalance;
        if (defaultBalance == null) {
            defaultBalance = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        long defaultRevision = state.defaultState.knownRevision;
        BigDecimal defaultSnapshot = defaultBalance;
        runAsync(() -> store.writeSnapshot(uuid, config.defaultCurrencyId(), defaultSnapshot, defaultRevision, config.serverId()))
                .exceptionally(throwable -> {
                    log(Level.WARNING, "刷新默认货币失败: " + uuid + ", reason=" + reason, throwable);
                    return null;
                });

        for (Map.Entry<String, CurrencyState> entry : state.managedStates.entrySet()) {
            CurrencyState currencyState = entry.getValue();
            if (currencyState.lastObservedBalance == null) {
                continue;
            }
            long revision = currencyState.knownRevision;
            BigDecimal balance = currencyState.lastObservedBalance;
            String currencyId = entry.getKey();
            runAsync(() -> store.writeSnapshot(uuid, currencyId, balance, revision, config.serverId()))
                    .exceptionally(throwable -> {
                        log(Level.WARNING, "刷新自管货币失败: " + uuid + ", currency=" + currencyId + ", reason=" + reason, throwable);
                        return null;
                });
        }
    }

    private RedisSyncManager createRedisSyncManager(SyncConfig syncConfig) {
        RedisSyncConfig redisConfig = syncConfig.redisSync();
        if (redisConfig == null || !redisConfig.enabled()) {
            return null;
        }
        return new RedisSyncManager(this, redisConfig, this::handleRedisBalanceChange);
    }

    private RedisSyncConfig loadRedisConfig() {
        ConfigurationSection redis = getConfig().getConfigurationSection("redis");
        if (redis == null) {
            return new RedisSyncConfig(false, "127.0.0.1", 6379, "", 0, "mmm:vaultsync:balance", 3000L);
        }
        return new RedisSyncConfig(
                redis.getBoolean("enabled", false),
                redis.getString("host", "127.0.0.1"),
                redis.getInt("port", 6379),
                redis.getString("password", ""),
                redis.getInt("database", 0),
                redis.getString("channel", "mmm:vaultsync:balance"),
                redis.getLong("reconnect-delay-millis", 3000L)
        );
    }

    private void publishRedisBalanceChange(UUID playerId, String currencyId, long revision) {
        if (redisSyncManager == null || !redisSyncManager.isEnabled()) {
            return;
        }
        redisSyncManager.publishBalanceChange(playerId, currencyId, revision, config.serverId());
    }

    private void handleRedisBalanceChange(RedisBalanceChangeMessage message) {
        if (message == null || config == null) {
            return;
        }
        if (message.sourceServerId().equalsIgnoreCase(config.serverId())) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            if (store == null || setupRequired) {
                return;
            }
            markRemoteBalanceNotice(message.playerId(), message.currencyId(), message.revision());
            scheduleAuthoritativeLoad(message.playerId(), "redis", message.currencyId());
        });
    }

    private void flushOnlinePlayersBlocking(String reason) {
        if (store == null || config == null) {
            return;
        }

        List<CompletableFuture<BalanceRecord>> futures = new ArrayList<>();
        for (OfflinePlayer player : collectKnownPlayers()) {
            UUID uuid = player.getUniqueId();
            PlayerState state = states.computeIfAbsent(uuid, ignored -> new PlayerState());
            BigDecimal defaultBalance = player.isOnline() && player instanceof Player online
                    ? getBackendBalance(online)
                    : state.defaultState.lastObservedBalance;
            if (defaultBalance != null) {
                long knownRevision = state.defaultState.knownRevision;
                BigDecimal snapshotBalance = defaultBalance;
                futures.add(runAsync(() -> store.writeSnapshot(uuid, config.defaultCurrencyId(), snapshotBalance, knownRevision, config.serverId())));
            }

            for (Map.Entry<String, CurrencyState> entry : state.managedStates.entrySet()) {
                if (entry.getValue().lastObservedBalance == null) {
                    continue;
                }
                String currencyId = entry.getKey();
                BigDecimal balance = entry.getValue().lastObservedBalance;
                long revision = entry.getValue().knownRevision;
                futures.add(runAsync(() -> store.writeSnapshot(uuid, currencyId, balance, revision, config.serverId())));
            }
        }

        for (CompletableFuture<BalanceRecord> future : futures) {
            future.join();
        }
        debug("已完成阻塞刷盘: " + reason);
    }

    private void resyncTrackedPlayersAfterReload() {
        if (config == null) {
            return;
        }

        Set<UUID> onlinePlayers = Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId)
                .collect(Collectors.toSet());
        states.keySet().retainAll(onlinePlayers);

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            PlayerState state = states.computeIfAbsent(uuid, ignored -> new PlayerState());
            state.defaultState.lastObservedBalance = getBackendBalance(player);
            state.defaultState.pendingWriteBalance = null;
            state.defaultState.writeInFlight = false;
            state.defaultState.remoteLoadInFlight = false;
            state.defaultState.suppressWritesUntilMillis = 0L;
            state.defaultState.nextRemoteRefreshMillis = 0L;
            for (CurrencyState managedState : state.managedStates.values()) {
                managedState.pendingWriteBalance = null;
                managedState.writeInFlight = false;
                managedState.remoteLoadInFlight = false;
                managedState.suppressWritesUntilMillis = 0L;
                managedState.nextRemoteRefreshMillis = 0L;
            }
            Bukkit.getScheduler().runTaskLater(this,
                    () -> scheduleAuthoritativeLoad(uuid, "reload", null),
                    config.joinLoadDelayTicks());
        }
    }

    private void restartScanTask() {
        stopScanTask();
        if (config != null) {
            scanTask = Bukkit.getScheduler().runTaskTimer(
                    this,
                    this::scanOnlinePlayers,
                    config.localScanIntervalTicks(),
                    config.localScanIntervalTicks()
            );
        }
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
            PlayerState state = states.computeIfAbsent(uuid, ignored -> new PlayerState());
            BigDecimal localDefaultBalance = getBackendBalance(player);
            BigDecimal dbDefaultBalance = loadAuthoritativeBalance(uuid, config.defaultCurrencyId());
            if (!sameBalance(localDefaultBalance, dbDefaultBalance)) {
                return new VerifyResult(false, "玩家 " + player.getName() + " 的默认货币不一致，本地="
                        + localDefaultBalance + "，数据库=" + dbDefaultBalance);
            }

            for (Map.Entry<String, CurrencyDefinition> entry : config.currencies().entrySet()) {
                String currencyId = entry.getKey();
                if (config.defaultCurrencyId().equals(currencyId)) {
                    continue;
                }
                CurrencyState currencyState = state.stateFor(currencyId);
                BigDecimal localManaged = currencyState.lastObservedBalance == null
                        ? entry.getValue().normalizedStartingBalance()
                        : currencyState.lastObservedBalance;
                BigDecimal dbManaged = loadAuthoritativeBalance(uuid, currencyId);
                if (!sameBalance(localManaged, dbManaged)) {
                    return new VerifyResult(false, "玩家 " + player.getName() + " 的货币 " + currencyId
                            + " 不一致，本地=" + localManaged + "，数据库=" + dbManaged);
                }
            }
        }

        if (lastObservedMaintenanceChangeMillis > verifyStartedAt || activeAsyncOperations.get() > 0) {
            return new VerifyResult(false, "校验期间检测到新的余额活动");
        }
        return new VerifyResult(true, "在线玩家余额与 MySQL 一致");
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

    private void ensureRuntimeReady() {
        if (backendEconomy == null) {
            Economy resolved = resolveBackendEconomy();
            if (resolved == null) {
                throw new IllegalStateException("未找到可用的 Vault 经济提供者");
            }
            backendEconomy = resolved;
        }
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newFixedThreadPool(2, new SyncThreadFactory());
        }
    }

    private Economy resolveBackendEconomy() {
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null || provider.getProvider() == null) {
            return null;
        }
        Economy candidate = provider.getProvider();
        if (candidate instanceof SyncEconomyProxy proxy) {
            return proxy.delegate();
        }
        return candidate;
    }

    private void ensureEconomyProxyRegistered() {
        if (backendEconomy == null) {
            throw new IllegalStateException("经济后端尚未初始化");
        }
        if (syncEconomyProxy == null || syncEconomyProxy.delegate() != backendEconomy) {
            syncEconomyProxy = new SyncEconomyProxy(this, backendEconomy);
        }
        getServer().getServicesManager().unregister(Economy.class, this);
        getServer().getServicesManager().register(Economy.class, syncEconomyProxy, this, ServicePriority.Highest);
        debug("已注册 Vault 经济代理层，后端提供者: " + backendEconomy.getName());
    }

    private void registerServicesIfNeeded() {
        RegisteredServiceProvider<VaultSyncStateService> stateRegistration =
                getServer().getServicesManager().getRegistration(VaultSyncStateService.class);
        if (stateRegistration == null || stateRegistration.getProvider() != this) {
            getServer().getServicesManager().register(VaultSyncStateService.class, this, this, ServicePriority.Normal);
        }

        RegisteredServiceProvider<VaultSyncCurrencyService> currencyRegistration =
                getServer().getServicesManager().getRegistration(VaultSyncCurrencyService.class);
        if (currencyRegistration == null || currencyRegistration.getProvider() != this) {
            getServer().getServicesManager().register(VaultSyncCurrencyService.class, this, this, ServicePriority.Normal);
        }
    }

    private void unregisterServices() {
        getServer().getServicesManager().unregister(Economy.class, this);
        getServer().getServicesManager().unregister(VaultSyncStateService.class, this);
        getServer().getServicesManager().unregister(VaultSyncCurrencyService.class, this);
    }

    private void registerPlaceholderExpansionIfPresent() {
        if (placeholderExpansion != null) {
            return;
        }
        Plugin placeholderApi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (placeholderApi == null || !placeholderApi.isEnabled()) {
            return;
        }
        placeholderExpansion = new VaultSyncPlaceholderExpansion(this);
        placeholderExpansion.register();
        getLogger().info("已注册 PlaceholderAPI 变量: %mmmvaultsync_*%");
    }

    private void unregisterPlaceholderExpansion() {
        if (placeholderExpansion == null) {
            return;
        }
        placeholderExpansion.unregister();
        placeholderExpansion = null;
    }

    private void registerCmiBalanceListenerIfPresent() {
        if (cmiBalanceListenerRegistered) {
            return;
        }
        Plugin cmi = Bukkit.getPluginManager().getPlugin("CMI");
        if (cmi == null || !cmi.isEnabled()) {
            return;
        }

        try {
            Class<?> eventClass = Class.forName("com.Zrips.CMI.events.CMIUserBalanceChangeEvent");
            Method getUserMethod = eventClass.getMethod("getUser");
            Method getHandlersMethod = eventClass.getMethod("getHandlerList");

            EventExecutor executor = (listener, event) -> handleCmiBalanceChange(event, eventClass, getUserMethod);
            Bukkit.getPluginManager().registerEvent(
                    eventClass.asSubclass(Event.class),
                    this,
                    EventPriority.MONITOR,
                    executor,
                    this,
                    true
            );

            getHandlersMethod.invoke(null);
            cmiBalanceListenerRegistered = true;
            getLogger().info("已接入 CMIUserBalanceChangeEvent，用于捕获 CMI 原生命令的默认货币变更");
        } catch (ReflectiveOperationException exception) {
            cmiBalanceListenerRegistered = false;
            getLogger().warning("未能注册 CMI 余额变更事件监听，将仅依赖 Vault 代理层和保底扫描");
            debug("CMI 事件注册失败: " + exception.getMessage());
        }
    }

    private void handleCmiBalanceChange(Event event, Class<?> eventClass, Method getUserMethod) {
        if (maintenanceMode || store == null || !eventClass.isInstance(event)) {
            return;
        }
        try {
            Object user = getUserMethod.invoke(event);
            if (user == null) {
                return;
            }
            Method getUniqueIdMethod = user.getClass().getMethod("getUniqueId");
            UUID uuid = (UUID) getUniqueIdMethod.invoke(user);
            if (uuid == null) {
                return;
            }
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            recordDefaultBalanceChange(player, getBackendBalance(player), "cmi-balance-event");
        } catch (ReflectiveOperationException exception) {
            log(Level.WARNING, "处理 CMI 余额变更事件失败", exception);
        }
    }

    private void recordDefaultBalanceChange(OfflinePlayer player, BigDecimal balance, String reason) {
        if (player == null || store == null || config == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        PlayerState state = states.computeIfAbsent(uuid, ignored -> new PlayerState());
        CurrencyState defaultState = state.defaultState;
        BigDecimal previous = defaultState.lastObservedBalance;
        defaultState.lastObservedBalance = balance;

        long now = System.currentTimeMillis();
        if (previous != null
                && now < defaultState.suppressWritesUntilMillis
                && sameBalance(previous, balance)) {
            return;
        }

        scheduleWrite(uuid, config.defaultCurrencyId(), balance, defaultState, reason);

        if (maintenanceMode) {
            lastObservedMaintenanceChangeMillis = now;
            drainCompleted = false;
            verifyCompleted = false;
        }
    }

    private CompletableFuture<BalanceMutationResult> mutateBalanceAsync(
            UUID playerId,
            String currencyId,
            BigDecimal amount,
            MutationType mutationType,
            String reason
    ) {
        CurrencyDefinition currency = getCurrencies().get(currencyId);
        if (currency == null) {
            return CompletableFuture.completedFuture(BalanceMutationResult.failed(playerId, currencyId, "未知货币"));
        }
        if (!canAcceptEconomicOperations()) {
            return CompletableFuture.completedFuture(BalanceMutationResult.failed(playerId, currencyId, "当前阶段不允许修改余额"));
        }
        if (amount.signum() < 0) {
            return CompletableFuture.completedFuture(BalanceMutationResult.failed(playerId, currencyId, "金额不能为负数"));
        }

        return runAsync(() -> {
            Optional<BalanceRecord> existingRecord = store.load(playerId, currencyId);
            long knownRevision = existingRecord.map(BalanceRecord::revision).orElseGet(() -> {
                PlayerState state = states.get(playerId);
                CurrencyState currencyState = state == null ? null : state.stateFor(currencyId);
                return currencyState == null ? 0L : currencyState.knownRevision;
            });
            BigDecimal previous = existingRecord.map(BalanceRecord::balance)
                    .orElseGet(() -> initialAuthoritativeBalance(playerId, currencyId));
            BigDecimal target = switch (mutationType) {
                case SET -> amount;
                case ADD -> previous.add(amount);
                case TAKE -> previous.subtract(amount);
            };
            target = currency.normalize(target);
            if (target.signum() < 0) {
                return BalanceMutationResult.failed(playerId, currencyId, "余额不能小于 0");
            }

            BalanceRecord record = store.writeSnapshot(playerId, currencyId, target, knownRevision, config.serverId());
            publishRedisBalanceChange(record.uuid(), record.currencyId(), record.revision());
            return new BalanceMutationResult(
                    true,
                    "ok",
                    playerId,
                    currencyId,
                    previous,
                    record.balance(),
                    record.balance().subtract(previous),
                    record.revision()
            );
        }).thenCompose(result -> {
            if (!result.success()) {
                return CompletableFuture.completedFuture(result);
            }

            CompletableFuture<BalanceMutationResult> future = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(this, () -> {
                applyMutationResultToLocalState(result, reason);
                future.complete(result);
            });
            return future;
        });
    }

    private void applyMutationResultToLocalState(BalanceMutationResult result, String reason) {
        CurrencyDefinition currency = getCurrencies().get(result.currencyId());
        if (currency == null) {
            return;
        }

        UUID playerId = result.playerId();
        PlayerState state = states.computeIfAbsent(playerId, ignored -> new PlayerState());
        CurrencyState currencyState = state.stateFor(result.currencyId());
        currencyState.knownRevision = Math.max(currencyState.knownRevision, result.revision());
        currencyState.lastObservedBalance = result.newBalance();

        Player online = Bukkit.getPlayer(playerId);
        if (config.defaultCurrencyId().equals(result.currencyId()) && online != null && online.isOnline()) {
            BalanceRecord record = new BalanceRecord(
                    playerId,
                    result.currencyId(),
                    result.newBalance(),
                    result.revision(),
                    System.currentTimeMillis(),
                    config.serverId()
            );
            applyAuthoritativeDefaultBalance(online, currencyState, record, reason);
        } else if (online != null && online.isOnline()) {
            notifyBalanceChangeIfNeeded(online, currency, result.changedAmount(), result.newBalance(), reason, result.revision());
        }

        fireBalanceChangeEvent(playerId, result.currencyId(), result.previousBalance(), result.newBalance(), reason, false);
    }

    private BigDecimal loadAuthoritativeBalance(UUID playerId, String currencyId) throws SQLException {
        CurrencyDefinition currency = getCurrencies().get(currencyId);
        if (currency == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return store.load(playerId, currencyId)
                .map(BalanceRecord::balance)
                .orElseGet(currency::normalizedStartingBalance);
    }

    private BigDecimal initialAuthoritativeBalance(UUID playerId, String currencyId) {
        if (config.defaultCurrencyId().equals(currencyId)) {
            Player online = Bukkit.getPlayer(playerId);
            if (online != null && online.isOnline()) {
                return getBackendBalance(online);
            }
            PlayerState state = states.get(playerId);
            if (state != null && state.defaultState.lastObservedBalance != null) {
                return state.defaultState.lastObservedBalance;
            }
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        CurrencyDefinition currency = getCurrencies().get(currencyId);
        return currency == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP) : currency.normalizedStartingBalance();
    }

    private void notifyBalanceChange(Player onlinePlayer, CurrencyDefinition currency, BigDecimal delta, BigDecimal balance, String reason) {
        if (onlinePlayer == null) {
            return;
        }
        if (!currency.notifyOnChange() || delta.signum() == 0) {
            return;
        }

        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("amount", formatAmount(currency, delta.abs()));
        placeholders.put("balance", formatAmount(currency, balance));
        placeholders.put("currency", currency.displayLabel());
        placeholders.put("reason", reason);

        if (delta.signum() > 0) {
            onlinePlayer.sendMessage(lang.text("player.balance-added", placeholders));
        } else {
            onlinePlayer.sendMessage(lang.text("player.balance-removed", placeholders));
        }
    }

    private void notifyBalanceChangeIfNeeded(OfflinePlayer player, CurrencyDefinition currency, BigDecimal delta, BigDecimal balance, String reason, long revision) {
        if (player == null || currency == null) {
            return;
        }
        if (reason != null && ("join".equals(reason) || "reload".equals(reason) || "maintenance-off".equals(reason))) {
            return;
        }
        if (!currency.notifyOnChange() || delta == null || delta.signum() == 0) {
            return;
        }
        Player onlinePlayer = resolveOnlinePlayer(player);
        if (onlinePlayer == null) {
            return;
        }
        if (shouldSuppressRemoteBalanceNotice(player.getUniqueId(), currency.id(), revision)) {
            return;
        }
        if (shouldSuppressBalanceNotice(player.getUniqueId(), currency.id(), delta, balance)) {
            return;
        }
        notifyBalanceChange(onlinePlayer, currency, delta, balance, reason);
    }

    private void markRemoteBalanceNotice(UUID playerId, String currencyId, long revision) {
        if (playerId == null || currencyId == null || revision <= 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, RevisionNoticeMark> notices = remoteBalanceNotices.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
        RevisionNoticeMark previous = notices.get(currencyId);
        if (previous == null || revision > previous.revision()) {
            notices.put(currencyId, new RevisionNoticeMark(revision, now));
        }
        cleanupRemoteNoticeMarks(playerId, notices, now);
    }

    private boolean shouldSuppressRemoteBalanceNotice(UUID playerId, String currencyId, long revision) {
        if (playerId == null || currencyId == null || revision <= 0L) {
            return false;
        }
        Map<String, RevisionNoticeMark> notices = remoteBalanceNotices.get(playerId);
        if (notices == null) {
            return false;
        }
        RevisionNoticeMark mark = notices.get(currencyId);
        if (mark == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - mark.markedAtMillis() >= REMOTE_NOTICE_SUPPRESSION_MILLIS) {
            cleanupRemoteNoticeMarks(playerId, notices, now);
            return false;
        }
        return mark.revision() >= revision;
    }

    private void cleanupRemoteNoticeMarks(UUID playerId, Map<String, RevisionNoticeMark> notices, long now) {
        notices.entrySet().removeIf(entry -> now - entry.getValue().markedAtMillis() >= REMOTE_NOTICE_SUPPRESSION_MILLIS);
        if (notices.isEmpty()) {
            remoteBalanceNotices.remove(playerId, notices);
        }
    }

    private boolean shouldSuppressBalanceNotice(UUID playerId, String currencyId, BigDecimal delta, BigDecimal balance) {
        if (playerId == null || currencyId == null || delta == null || balance == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        Map<String, NoticeMark> notices = recentBalanceNotices.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
        NoticeMark previous = notices.get(currencyId);
        if (previous != null
                && previous.delta().compareTo(delta) == 0
                && previous.balance().compareTo(balance) == 0
                && now - previous.notifiedAtMillis() < BALANCE_NOTICE_SUPPRESSION_MILLIS) {
            return true;
        }
        notices.put(currencyId, new NoticeMark(delta, balance, now));
        if (notices.size() > 8) {
            notices.entrySet().removeIf(entry -> now - entry.getValue().notifiedAtMillis() >= BALANCE_NOTICE_SUPPRESSION_MILLIS);
            if (notices.isEmpty()) {
                recentBalanceNotices.remove(playerId, notices);
            }
        }
        return false;
    }

    private Player resolveOnlinePlayer(OfflinePlayer player) {
        if (player instanceof Player onlinePlayer && onlinePlayer.isOnline()) {
            return onlinePlayer;
        }
        return Bukkit.getPlayer(player.getUniqueId());
    }

    private void fireBalanceChangeEvent(UUID playerId, String currencyId, BigDecimal previous, BigDecimal current, String reason, boolean remoteSync) {
        if (sameBalance(previous, current)) {
            return;
        }
        Bukkit.getPluginManager().callEvent(new VaultSyncCurrencyBalanceChangeEvent(
                playerId,
                currencyId,
                previous,
                current,
                reason,
                remoteSync
        ));
    }

    private List<OfflinePlayer> collectKnownPlayers() {
        Map<UUID, OfflinePlayer> players = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            players.put(player.getUniqueId(), player);
        }
        for (UUID uuid : states.keySet()) {
            players.putIfAbsent(uuid, Bukkit.getOfflinePlayer(uuid));
        }
        return new ArrayList<>(players.values());
    }

    private List<String> listKnownPlayerNames() {
        return collectKnownPlayers().stream()
                .map(this::displayPlayer)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private OfflinePlayer resolveOfflinePlayer(String input) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return online;
        }
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(input));
        } catch (IllegalArgumentException ignored) {
            return Bukkit.getOfflinePlayer(input);
        }
    }

    private BigDecimal getBackendBalance(OfflinePlayer player) {
        if (backendEconomy == null || player == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return scaled(backendEconomy.getBalance(player));
    }

    private String displayPlayer(OfflinePlayer player) {
        return player.getName() != null ? player.getName() : player.getUniqueId().toString();
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

    private BigDecimal normalizeAmount(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal parseAmount(String raw) {
        try {
            return normalizeAmount(new BigDecimal(raw));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String formatAmount(CurrencyDefinition currency, BigDecimal amount) {
        if (config != null && currency.id().equals(config.defaultCurrencyId()) && backendEconomy != null) {
            return backendEconomy.format(amount.doubleValue());
        }
        return amount.stripTrailingZeros().toPlainString();
    }

    private String currencyDisplayName(String currencyId) {
        CurrencyDefinition currency = getCurrencies().get(currencyId);
        return currency == null ? currencyId : currency.displayLabel();
    }

    private boolean sameBalance(BigDecimal left, BigDecimal right) {
        return left.subtract(right).abs().compareTo(BigDecimal.valueOf(config.epsilon())) <= 0;
    }

    private boolean isConfigUsingPlaceholders() {
        String password = getConfig().getString("database.password", "");
        String serverId = getConfig().getString("server-id", "server");
        return password == null
                || password.isBlank()
                || "password".equalsIgnoreCase(password.trim())
                || "server".equalsIgnoreCase(serverId.trim());
    }

    private String normalizeCurrencyId(String currencyId) {
        if (currencyId == null || currencyId.isBlank()) {
            return DEFAULT_CURRENCY_ID;
        }
        return currencyId.toLowerCase(Locale.ROOT);
    }

    private void printFirstRunSetupNotice() {
        printSetupBanner(
                "检测到 MMMVaultSync 为首次加载，默认配置文件已经生成。",
                "请先编辑 plugins/MMMVaultSync/config.yml，完成后执行 /mmmvaultsync reload。",
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
                "请检查 plugins/MMMVaultSync/config.yml，并填写真实配置后执行 /mmmvaultsync reload。",
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
        getLogger().warning("请确认以下项目：");
        for (String item : items) {
            getLogger().warning("- " + item);
        }
        getLogger().warning(footer);
        getLogger().warning("==================================================");
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

    @Override
    public boolean canAcceptEconomicOperations() {
        return !setupRequired && phase == SyncPhase.NORMAL;
    }

    private void debug(String message) {
        if (config != null && config.debugLogging()) {
            getLogger().info("[debug] " + message);
        }
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
        SyncPhase oldPhase = phase;
        if (oldPhase == newPhase) {
            return;
        }
        phase = newPhase;
        Bukkit.getPluginManager().callEvent(new VaultSyncPhaseChangeEvent(oldPhase, newPhase));
        debug("阶段变化: " + oldPhase + " -> " + newPhase);
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

    private enum MutationType {
        SET,
        ADD,
        TAKE
    }

    private static final class PlayerState {
        private final CurrencyState defaultState = new CurrencyState();
        private final Map<String, CurrencyState> managedStates = new ConcurrentHashMap<>();

        private CurrencyState stateFor(String currencyId) {
            if (currencyId == null || DEFAULT_CURRENCY_ID.equals(currencyId)) {
                return defaultState;
            }
            return managedStates.computeIfAbsent(currencyId, ignored -> new CurrencyState());
        }
    }

    private static final class CurrencyState {
        private BigDecimal lastObservedBalance;
        private BigDecimal pendingWriteBalance;
        private long knownRevision;
        private long suppressWritesUntilMillis;
        private long nextRemoteRefreshMillis;
        private boolean writeInFlight;
        private boolean remoteLoadInFlight;
    }

    private record NoticeMark(BigDecimal delta, BigDecimal balance, long notifiedAtMillis) {
    }

    private record RevisionNoticeMark(long revision, long markedAtMillis) {
    }

    private static final class SyncThreadFactory implements ThreadFactory {
        private int threadCounter;

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
