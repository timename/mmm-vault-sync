package local.mmm.vaultsync;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class RedisSyncManager implements AutoCloseable {
    private final MMMVaultSyncPlugin plugin;
    private final RedisSyncConfig config;
    private final Consumer<RedisBalanceChangeMessage> listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService publisherExecutor;
    private volatile JedisPool pool;
    private volatile Thread subscriberThread;
    private volatile JedisPubSub pubSub;

    public RedisSyncManager(MMMVaultSyncPlugin plugin, RedisSyncConfig config, Consumer<RedisBalanceChangeMessage> listener) {
        this.plugin = plugin;
        this.config = config;
        this.listener = listener;
        this.publisherExecutor = Executors.newSingleThreadExecutor(new RedisThreadFactory("MMMVaultSync-Redis-Publish"));
    }

    public boolean isEnabled() {
        return config.enabled();
    }

    public void start() {
        if (!config.enabled() || !running.compareAndSet(false, true)) {
            return;
        }

        this.pool = createPool();
        this.subscriberThread = new Thread(this::runSubscriberLoop, "MMMVaultSync-Redis-Subscribe");
        this.subscriberThread.setDaemon(true);
        this.subscriberThread.start();
    }

    public void publishBalanceChange(UUID playerId, String currencyId, long revision, String sourceServerId) {
        if (!config.enabled() || !running.get() || pool == null) {
            return;
        }

        String payload = new RedisBalanceChangeMessage(playerId, currencyId, revision, sourceServerId).serialize();
        publisherExecutor.execute(() -> {
            if (!running.get() || pool == null) {
                return;
            }
            try (Jedis jedis = pool.getResource()) {
                jedis.publish(config.channel(), payload);
            } catch (Exception exception) {
                plugin.getLogger().warning("Redis 事件发布失败: " + exception.getMessage());
                plugin.getLogger().warning("Redis 发布异常: " + exception.getMessage());
            }
        });
    }

    @Override
    public void close() {
        running.set(false);
        JedisPubSub currentPubSub = pubSub;
        if (currentPubSub != null) {
            try {
                currentPubSub.unsubscribe();
            } catch (Exception ignored) {
            }
        }
        Thread thread = subscriberThread;
        if (thread != null) {
            thread.interrupt();
        }
        publisherExecutor.shutdownNow();
        JedisPool currentPool = pool;
        pool = null;
        if (currentPool != null) {
            currentPool.close();
        }
    }

    private JedisPool createPool() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(4);
        poolConfig.setMaxIdle(2);
        poolConfig.setMinIdle(0);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);
        if (config.password() == null || config.password().isBlank()) {
            return new JedisPool(poolConfig, config.host(), config.port(), 2000, null, config.database());
        }
        return new JedisPool(poolConfig, config.host(), config.port(), 2000, config.password(), config.database());
    }

    private void runSubscriberLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try (Jedis jedis = pool.getResource()) {
                pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        if (!config.channel().equals(channel)) {
                            return;
                        }
                        RedisBalanceChangeMessage parsed = RedisBalanceChangeMessage.parse(message);
                        if (parsed != null) {
                            listener.accept(parsed);
                        }
                    }
                };
                jedis.subscribe(pubSub, config.channel());
            } catch (Exception exception) {
                if (!running.get()) {
                    return;
                }
                plugin.getLogger().warning("Redis 订阅断开，稍后重连: " + exception.getMessage());
                plugin.getLogger().warning("Redis 订阅异常: " + exception.getMessage());
                sleepQuietly(config.reconnectDelayMillis());
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(250L, millis));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RedisThreadFactory implements ThreadFactory {
        private final String prefix;
        private int counter;

        private RedisThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + (++counter));
            thread.setDaemon(true);
            return thread;
        }
    }
}
