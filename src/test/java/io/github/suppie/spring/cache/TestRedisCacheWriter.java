package io.github.suppie.spring.cache;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;

final class TestRedisCacheWriter implements RedisCacheWriter {
  private final Map<Key, byte[]> values = new ConcurrentHashMap<>();
  private final CacheStatisticsCollector statistics = CacheStatisticsCollector.create();

  final AtomicInteger gets = new AtomicInteger();
  final AtomicInteger puts = new AtomicInteger();
  final AtomicInteger putIfAbsentCalls = new AtomicInteger();
  final AtomicInteger clears = new AtomicInteger();
  final AtomicInteger invalidates = new AtomicInteger();

  private volatile CountDownLatch getEntered;
  private volatile CountDownLatch releaseGet;
  private volatile CountDownLatch synchronizePutIfAbsent;
  private volatile RuntimeException failure;

  void failWith(RuntimeException exception) {
    this.failure = exception;
  }

  void blockNextGet(CountDownLatch entered, CountDownLatch release) {
    this.getEntered = entered;
    this.releaseGet = release;
  }

  void synchronizeNextPutIfAbsentCalls(int participants) {
    this.synchronizePutIfAbsent = new CountDownLatch(participants);
  }

  @Override
  public byte[] get(String name, byte[] key) {
    throwIfFailing();
    gets.incrementAndGet();
    byte[] captured = copy(values.get(new Key(name, key)));

    CountDownLatch entered = getEntered;
    CountDownLatch release = releaseGet;
    if (entered != null && release != null) {
      getEntered = null;
      releaseGet = null;
      entered.countDown();
      await(release);
    }

    return captured;
  }

  @Override
  public CompletableFuture<byte[]> retrieve(String name, byte[] key, Duration ttl) {
    return CompletableFuture.completedFuture(get(name, key));
  }

  @Override
  public void put(String name, byte[] key, byte[] value, Duration ttl) {
    throwIfFailing();
    puts.incrementAndGet();
    values.put(new Key(name, key), copy(value));
  }

  @Override
  public CompletableFuture<Void> store(String name, byte[] key, byte[] value, Duration ttl) {
    put(name, key, value, ttl);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public byte[] putIfAbsent(String name, byte[] key, byte[] value, Duration ttl) {
    throwIfFailing();
    putIfAbsentCalls.incrementAndGet();
    CountDownLatch synchronization = synchronizePutIfAbsent;
    if (synchronization != null) {
      synchronization.countDown();
      await(synchronization);
      if (synchronization.getCount() == 0) {
        synchronizePutIfAbsent = null;
      }
    }
    return copy(values.putIfAbsent(new Key(name, key), copy(value)));
  }

  @Override
  public void evict(String name, byte[] key) {
    throwIfFailing();
    values.remove(new Key(name, key));
  }

  @Override
  public void clear(String name, byte[] pattern) {
    throwIfFailing();
    clears.incrementAndGet();
    values.keySet().removeIf(key -> key.cacheName.equals(name));
  }

  @Override
  public boolean invalidate(String name, byte[] pattern) {
    throwIfFailing();
    invalidates.incrementAndGet();
    boolean present = values.keySet().stream().anyMatch(key -> key.cacheName.equals(name));
    values.keySet().removeIf(key -> key.cacheName.equals(name));
    return present;
  }

  @Override
  public void clearStatistics(String name) {
    statistics.reset(name);
  }

  @Override
  public RedisCacheWriter withStatisticsCollector(CacheStatisticsCollector collector) {
    return this;
  }

  @Override
  public CacheStatistics getCacheStatistics(String name) {
    return statistics.getCacheStatistics(name);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for test coordination");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while coordinating test", exception);
    }
  }

  private void throwIfFailing() {
    RuntimeException exception = failure;
    if (exception != null) {
      throw exception;
    }
  }

  private static byte[] copy(byte[] value) {
    return value == null ? null : value.clone();
  }

  private static final class Key {
    private final String cacheName;
    private final byte[] key;

    private Key(String cacheName, byte[] key) {
      this.cacheName = cacheName;
      this.key = key.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key candidate
          && cacheName.equals(candidate.cacheName)
          && Arrays.equals(key, candidate.key);
    }

    @Override
    public int hashCode() {
      return 31 * cacheName.hashCode() + Arrays.hashCode(key);
    }
  }
}
