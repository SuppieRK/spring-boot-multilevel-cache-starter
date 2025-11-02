package io.github.suppie.spring.cache.benchmark;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.suppie.spring.cache.MultiLevelCache;
import io.github.suppie.spring.cache.MultiLevelCacheConfigurationProperties;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Baseline benchmarks for the current {@link MultiLevelCache} implementation that relies on a
 * synchronized {@code get(key, loader)} implementation. These benchmarks intentionally exercise the
 * existing behaviour so that subsequent optimisations can be compared against a stable baseline.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class MultiLevelCacheBenchmark {

  private static final String VALUE = "cached-value";

  @Param({"1", "32"})
  int keyCount;

  private MultiLevelCache cache;
  private String[] keys;

  private Callable<Object> hitLoader;
  private Callable<Object> producingLoader;
  private final AtomicInteger missKeyCounter = new AtomicInteger();
  private final AtomicInteger missValueCounter = new AtomicInteger();

  @Setup(Level.Trial)
  public void setUp() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    properties.getLocal().setMaxSize(10_000);
    properties.setTimeToLive(Duration.ofHours(1));

    RedisTemplate<Object, Object> redisTemplate = new RedisTemplate<>();
    redisTemplate.setKeySerializer(new StringRedisSerializer());
    redisTemplate.setValueSerializer(new JdkSerializationRedisSerializer());

    cache =
        new MultiLevelCache(
            "benchmark",
            properties,
            new InMemoryRedisCacheWriter(),
            redisTemplate,
            Caffeine.newBuilder()
                .maximumSize(properties.getLocal().getMaxSize())
                .expireAfterWrite(properties.getTimeToLive())
                .build(),
            CircuitBreaker.ofDefaults("benchmark"));

    keys = new String[keyCount];
    for (int i = 0; i < keyCount; i++) {
      String key = "hit-key-" + i;
      keys[i] = key;
      cache.put(key, VALUE);
    }

    hitLoader = () -> VALUE;

    producingLoader = () -> "value-" + missValueCounter.incrementAndGet();
  }

  @Benchmark
  public void cacheHit(ThreadState threadState, Blackhole blackhole) {
    String key = keys[keyIndex(threadState)];
    Object value = cache.get(key, hitLoader);
    blackhole.consume(value);
  }

  @Benchmark
  public void cacheMissLoads(Blackhole blackhole) {
    String key = "miss-key-" + missKeyCounter.incrementAndGet();
    Object value = cache.get(key, producingLoader);
    blackhole.consume(value);
  }

  private int keyIndex(ThreadState state) {
    if (keys.length == 1) {
      return 0;
    }
    int next = state.nextIndex++;
    if (next >= keys.length) {
      next = 0;
      state.nextIndex = 1;
    }
    return next;
  }

  @State(Scope.Thread)
  public static class ThreadState {
    int nextIndex;
  }

  /**
   * Simple in-memory implementation of {@link RedisCacheWriter} to avoid the need for a real Redis
   * connection during benchmarking. Values stored here are never evicted to keep interactions
   * deterministic.
   */
  static class InMemoryRedisCacheWriter implements RedisCacheWriter {

    private final ConcurrentMap<String, ConcurrentMap<Key, byte[]>> store =
        new ConcurrentHashMap<>();

    @Override
    public byte[] get(String name, byte[] key) {
      ConcurrentMap<Key, byte[]> cache = store.get(name);
      if (cache == null) {
        return null;
      }
      return cache.get(new Key(key));
    }

    @Override
    public CompletableFuture<byte[]> retrieve(String name, byte[] key, Duration ttl) {
      return CompletableFuture.completedFuture(get(name, key));
    }

    @Override
    public void put(String name, byte[] key, byte[] value, Duration ttl) {
      store
          .computeIfAbsent(name, k -> new ConcurrentHashMap<>())
          .put(new Key(key), Arrays.copyOf(value, value.length));
    }

    @Override
    public CompletableFuture<Void> store(String name, byte[] key, byte[] value, Duration ttl) {
      put(name, key, value, ttl);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public byte[] putIfAbsent(String name, byte[] key, byte[] value, Duration ttl) {
      return store
          .computeIfAbsent(name, k -> new ConcurrentHashMap<>())
          .putIfAbsent(new Key(key), Arrays.copyOf(value, value.length));
    }

    @Override
    public void remove(String name, byte[] key) {
      ConcurrentMap<Key, byte[]> cache = store.get(name);
      if (cache != null) {
        cache.remove(new Key(key));
      }
    }

    @Override
    public void clean(String name, byte[] pattern) {
      store.remove(name);
    }

    @Override
    public void clearStatistics(String name) {}

    @Override
    public RedisCacheWriter withStatisticsCollector(
        CacheStatisticsCollector cacheStatisticsCollector) {
      return null;
    }

    @Override
    public CacheStatistics getCacheStatistics(String cacheName) {
      return null;
    }

    private static final class Key {
      private final byte[] value;
      private final int hashCode;

      private Key(byte[] value) {
        this.value = Arrays.copyOf(value, value.length);
        this.hashCode = Arrays.hashCode(this.value);
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (!(o instanceof Key other)) {
          return false;
        }
        return Arrays.equals(this.value, other.value);
      }

      @Override
      public int hashCode() {
        return hashCode;
      }
    }
  }
}
