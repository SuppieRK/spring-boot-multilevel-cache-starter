package io.github.suppie.spring.cache;

import ch.qos.logback.classic.LoggerContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/** Shared deterministic state for {@link MultiLevelCache} benchmarks. */
public abstract class MultiLevelCacheBenchmarkSupport {
  public static final String CACHE_NAME = "benchmark";
  public static final String KEY = "benchmark-key";
  public static final String VALUE = "cached-value";

  protected MultiLevelCacheConfigurationProperties properties;
  protected InMemoryRedisCacheWriter writer;
  protected RedisTemplate<Object, Object> redisTemplate;
  protected Cache<Object, Object> localCache;
  protected CircuitBreaker circuitBreaker;
  public MultiLevelCache cache;

  protected final void initializeCache() {
    initializeCache(new JdkSerializationRedisSerializer());
  }

  /** Initializes the benchmark cache with the requested legacy invalidation serializer. */
  protected final void initializeCache(RedisSerializer<Object> valueSerializer) {
    disableCacheLogging();

    properties = new MultiLevelCacheConfigurationProperties();
    properties.getLocal().setMaxSize(10_000);
    properties.setTimeToLive(Duration.ofHours(1));

    writer = new InMemoryRedisCacheWriter();
    redisTemplate = new NoOpPubSubRedisTemplate();
    redisTemplate.setKeySerializer(new StringRedisSerializer());
    redisTemplate.setValueSerializer(valueSerializer);

    localCache =
        Caffeine.newBuilder()
            .maximumSize(properties.getLocal().getMaxSize())
            .expireAfterWrite(properties.getTimeToLive())
            .build();
    circuitBreaker = CircuitBreaker.ofDefaults("benchmark");
    cache =
        new MultiLevelCache(
            CACHE_NAME,
            properties,
            writer,
            redisTemplate,
            localCache,
            circuitBreaker,
            "benchmark-instance");
  }

  protected final MultiLevelCache createCache(String instanceId) {
    Cache<Object, Object> instanceLocalCache =
        Caffeine.newBuilder()
            .maximumSize(properties.getLocal().getMaxSize())
            .expireAfterWrite(properties.getTimeToLive())
            .build();
    return new MultiLevelCache(
        CACHE_NAME,
        properties,
        writer,
        redisTemplate,
        instanceLocalCache,
        circuitBreaker,
        instanceId);
  }

  protected final void seed(String key) {
    cache.put(key, VALUE);
  }

  /** Seeds only L1 so setup does not execute Redis writes or invalidation serialization. */
  protected final void seedLocalOnly(String key) {
    localCache.put(cache.toLocalKey(key), VALUE);
  }

  protected final void seedRemoteOnly(String key) {
    seed(key);
    localCache.invalidate(cache.toLocalKey(key));
  }

  protected final void remove(String key) {
    cache.evict(key);
  }

  protected final void resetAndPopulate(int entryCount) {
    cache.clear();
    for (int index = 0; index < entryCount; index++) {
      seed(KEY + '-' + index);
    }
  }

  /** Replaces only L1 contents so inbound-listener setup stays out of measured JFR paths. */
  protected final void resetLocalAndPopulate(int entryCount) {
    localCache.invalidateAll();
    for (int index = 0; index < entryCount; index++) {
      seedLocalOnly(KEY + '-' + index);
    }
  }

  /** Clears both benchmark tiers directly, avoiding mutation and publication setup work. */
  protected final void resetTiersWithoutPublication() {
    localCache.invalidateAll();
    writer.clear(CACHE_NAME, null);
  }

  protected final boolean isLocalPresent(String key) {
    return localCache.getIfPresent(cache.toLocalKey(key)) != null;
  }

  protected final boolean isRemotePresent(String key) {
    return (Object) cache.nativeGet(key) != null;
  }

  /** Returns the current L1 value without populating it from Redis. */
  protected final Object localValue(String key) {
    return localCache.getIfPresent(cache.toLocalKey(key));
  }

  /** Returns the current Redis value without consulting or populating L1. */
  protected final Object remoteValue(String key) {
    return cache.nativeGet(key);
  }

  protected final void requireAbsent(String key) {
    if (isLocalPresent(key) || isRemotePresent(key)) {
      throw new IllegalStateException("Benchmark key was not invalidated");
    }
  }

  protected final void requireEmpty() {
    if (localCache.estimatedSize() != 0 || writer.size(CACHE_NAME) != 0) {
      throw new IllegalStateException("Benchmark cache was not cleared");
    }
  }

  private static void disableCacheLogging() {
    LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    loggerContext.getLogger(MultiLevelCache.class).setLevel(ch.qos.logback.classic.Level.WARN);
    loggerContext
        .getLogger(MultiLevelCacheAutoConfiguration.class)
        .setLevel(ch.qos.logback.classic.Level.WARN);
  }

  /** Keeps invalidation serialization measured while removing external PubSub transport. */
  static final class NoOpPubSubRedisTemplate extends RedisTemplate<Object, Object> {

    @Override
    public <T> T execute(RedisCallback<T> action) {
      return null;
    }

    @Override
    public <T> T execute(RedisCallback<T> action, boolean exposeConnection) {
      return null;
    }
  }

  /** In-memory Redis writer used to isolate cache implementation costs from network latency. */
  static final class InMemoryRedisCacheWriter implements RedisCacheWriter {
    private final Map<String, ConcurrentMap<Key, byte[]>> store = new ConcurrentHashMap<>();
    private volatile CacheStatisticsCollector statistics = CacheStatisticsCollector.create();

    @Override
    public byte[] get(String name, byte[] key) {
      ConcurrentMap<Key, byte[]> entries = store.get(name);
      return entries == null ? null : copy(entries.get(new Key(key)));
    }

    @Override
    public boolean supportsAsyncRetrieve() {
      return true;
    }

    @Override
    public CompletableFuture<byte[]> retrieve(String name, byte[] key, Duration ttl) {
      return CompletableFuture.completedFuture(get(name, key));
    }

    @Override
    public void put(String name, byte[] key, byte[] value, Duration ttl) {
      store
          .computeIfAbsent(name, ignored -> new ConcurrentHashMap<>())
          .put(new Key(key), copy(value));
    }

    @Override
    public CompletableFuture<Void> store(String name, byte[] key, byte[] value, Duration ttl) {
      put(name, key, value, ttl);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public byte[] putIfAbsent(String name, byte[] key, byte[] value, Duration ttl) {
      return copy(
          store
              .computeIfAbsent(name, ignored -> new ConcurrentHashMap<>())
              .putIfAbsent(new Key(key), copy(value)));
    }

    @Override
    public void evict(String name, byte[] key) {
      ConcurrentMap<Key, byte[]> entries = store.get(name);
      if (entries != null) {
        entries.remove(new Key(key));
      }
    }

    @Override
    public void clear(String name, byte[] pattern) {
      store.remove(name);
    }

    @Override
    public boolean invalidate(String name, byte[] pattern) {
      ConcurrentMap<Key, byte[]> removed = store.remove(name);
      return removed != null && !removed.isEmpty();
    }

    @Override
    public void clearStatistics(String name) {
      statistics.reset(name);
    }

    @Override
    public RedisCacheWriter withStatisticsCollector(CacheStatisticsCollector collector) {
      statistics = collector;
      return this;
    }

    @Override
    public CacheStatistics getCacheStatistics(String name) {
      return statistics.getCacheStatistics(name);
    }

    boolean contains(String name, byte[] key) {
      ConcurrentMap<Key, byte[]> entries = store.get(name);
      return entries != null && entries.containsKey(new Key(key));
    }

    int size(String name) {
      ConcurrentMap<Key, byte[]> entries = store.get(name);
      return entries == null ? 0 : entries.size();
    }

    private static byte[] copy(byte[] value) {
      return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static final class Key {
      private final byte[] value;

      private Key(byte[] value) {
        this.value = Arrays.copyOf(value, value.length);
      }

      @Override
      public boolean equals(Object other) {
        return other instanceof Key key && Arrays.equals(value, key.value);
      }

      @Override
      public int hashCode() {
        return Arrays.hashCode(value);
      }
    }
  }
}
