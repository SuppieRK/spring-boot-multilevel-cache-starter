package io.github.suppie.spring.cache;

import static io.github.suppie.spring.cache.MultiLevelCacheBenchmarkSupport.CACHE_NAME;
import static io.github.suppie.spring.cache.MultiLevelCacheBenchmarkSupport.KEY;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.cache.autoconfigure.CacheProperties;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;

/** Benchmarks public invalidation operations and inbound invalidation messages. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MultiLevelCacheInvalidationBenchmark {

  @Benchmark
  public void evictPresent(PresentState state) {
    state.cache.evict(KEY);
  }

  @Benchmark
  public void evictIfPresent(EvictIfPresentState state, Blackhole blackhole) {
    blackhole.consume(state.cache.evictIfPresent(KEY));
  }

  @Benchmark
  public void clear(BulkState state) {
    state.cache.clear();
  }

  @Benchmark
  public void clearPattern(BulkState state) {
    state.cache.clear("benchmark-*");
  }

  @Benchmark
  public void invalidate(InvalidateState state, Blackhole blackhole) {
    blackhole.consume(state.cache.invalidate());
  }

  @Benchmark
  public void receiveInvalidation(InboundState state) {
    state.listener.onMessage(state.message, null);
  }

  @State(Scope.Benchmark)
  public static class PresentState extends MultiLevelCacheBenchmarkSupport {
    @Setup(Level.Trial)
    public void setUpTrial() {
      initializeCache();
    }

    @Setup(Level.Invocation)
    public void setUpInvocation() {
      seed(KEY);
    }

    @TearDown(Level.Invocation)
    public void tearDownInvocation() {
      requireAbsent(KEY);
    }
  }

  @State(Scope.Benchmark)
  public static class EvictIfPresentState extends MultiLevelCacheBenchmarkSupport {
    @Param({"true", "false"})
    boolean present;

    @Setup(Level.Trial)
    public void setUpTrial() {
      initializeCache();
    }

    @Setup(Level.Invocation)
    public void setUpInvocation() {
      if (present) {
        seed(KEY);
      } else {
        remove(KEY);
      }
    }

    @TearDown(Level.Invocation)
    public void tearDownInvocation() {
      requireAbsent(KEY);
    }
  }

  @State(Scope.Benchmark)
  public static class BulkState extends MultiLevelCacheBenchmarkSupport {
    @Param({"1", "32"})
    int entryCount;

    @Setup(Level.Trial)
    public void setUpTrial() {
      initializeCache();
    }

    @Setup(Level.Invocation)
    public void setUpInvocation() {
      resetAndPopulate(entryCount);
    }

    @TearDown(Level.Invocation)
    public void tearDownInvocation() {
      requireEmpty();
    }
  }

  @State(Scope.Benchmark)
  public static class InvalidateState extends MultiLevelCacheBenchmarkSupport {
    @Param({"0", "32"})
    int entryCount;

    @Setup(Level.Trial)
    public void setUpTrial() {
      initializeCache();
    }

    @Setup(Level.Invocation)
    public void setUpInvocation() {
      resetAndPopulate(entryCount);
    }

    @TearDown(Level.Invocation)
    public void tearDownInvocation() {
      requireEmpty();
    }
  }

  @State(Scope.Benchmark)
  public static class InboundState extends MultiLevelCacheBenchmarkSupport {
    @Param({"STABLE_ENTRY", "STABLE_CACHE", "LEGACY_ENTRY"})
    InvalidationMode mode;

    MessageListener listener;
    Message message;

    @Setup(Level.Trial)
    public void setUpTrial() {
      initializeCache();
      StubCacheManager manager =
          new StubCacheManager(properties, redisTemplate, circuitBreaker, cache);
      listener = MultiLevelCacheAutoConfiguration.createMessageListener(redisTemplate, manager);

      String entryKey = mode == InvalidationMode.STABLE_CACHE ? null : KEY;
      MultiLevelCacheEvictMessage request =
          new MultiLevelCacheEvictMessage(CACHE_NAME, entryKey, "other-instance");
      byte[] body =
          mode == InvalidationMode.LEGACY_ENTRY
              ? new JdkSerializationRedisSerializer().serialize(request)
              : CacheInvalidationCodec.serialize(request);
      message = new DefaultMessage("benchmark-topic".getBytes(StandardCharsets.UTF_8), body);
    }

    @Setup(Level.Invocation)
    public void setUpInvocation() {
      if (mode == InvalidationMode.STABLE_CACHE) {
        resetAndPopulate(32);
      } else {
        resetAndPopulate(0);
        seed(KEY);
      }
    }

    @TearDown(Level.Invocation)
    public void tearDownInvocation() {
      if (mode == InvalidationMode.STABLE_CACHE) {
        if (localCache.estimatedSize() != 0) {
          throw new IllegalStateException("Inbound full-cache invalidation did not clear L1");
        }
      } else if (isLocalPresent(KEY)) {
        throw new IllegalStateException("Inbound entry invalidation did not clear L1");
      }
    }
  }

  public enum InvalidationMode {
    STABLE_ENTRY,
    STABLE_CACHE,
    LEGACY_ENTRY
  }

  static final class StubCacheManager extends MultiLevelCacheManager {
    private final MultiLevelCache cache;

    StubCacheManager(
        MultiLevelCacheConfigurationProperties properties,
        RedisTemplate<Object, Object> redisTemplate,
        CircuitBreaker circuitBreaker,
        MultiLevelCache cache) {
      super(
          new ObjectProvider<CacheProperties>() {
            @Override
            public CacheProperties getIfAvailable() {
              return null;
            }
          },
          properties,
          redisTemplate,
          circuitBreaker);
      this.cache = cache;
    }

    @Override
    MultiLevelCache getExistingCache(String name) {
      return CACHE_NAME.equals(name) ? cache : null;
    }

    @Override
    String getInstanceId() {
      return "benchmark-instance";
    }
  }
}
