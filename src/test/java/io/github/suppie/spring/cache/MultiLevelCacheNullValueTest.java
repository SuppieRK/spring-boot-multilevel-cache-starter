package io.github.suppie.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.NullValue;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.data.redis.serializer.StringRedisSerializer;

class MultiLevelCacheNullValueTest {

  @Test
  void enabledCacheableNullResultAvoidsRepeatedLookup() {
    try (var context = new AnnotationConfigApplicationContext(NullLookupConfiguration.class)) {
      NullLookup lookup = context.getBean(NullLookup.class);

      assertThat(lookup.find("missing")).isNull();
      assertThat(lookup.find("missing")).isNull();
      assertThat(lookup.calls()).isEqualTo(1);
    }
  }

  @Test
  void enabledSynchronizedCacheableNullResultAvoidsRepeatedLookup() {
    try (var context = new AnnotationConfigApplicationContext(NullLookupConfiguration.class)) {
      NullLookup lookup = context.getBean(NullLookup.class);

      assertThat(lookup.findSynchronized("missing")).isNull();
      assertThat(lookup.findSynchronized("missing")).isNull();
      assertThat(lookup.calls()).isEqualTo(1);
    }
  }

  @ParameterizedTest(name = "atomic null insertion / {0}")
  @MethodSource("serializers")
  void enabledPutIfAbsentPreservesCachedNullAcrossInstances(
      String name, RedisSerializer<Object> serializer) {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    Cache first = cache(enabledProperties(), writer, serializer);
    Cache second = cache(enabledProperties(), writer, serializer);

    assertThat(first.putIfAbsent("missing", null)).isNull();
    assertThat(first.get("missing")).isNotNull().extracting(Cache.ValueWrapper::get).isNull();
    assertThat(first.putIfAbsent("missing", "replacement"))
        .isNotNull()
        .extracting(Cache.ValueWrapper::get)
        .isNull();
    assertThat(second.putIfAbsent("missing", "replacement"))
        .isNotNull()
        .extracting(Cache.ValueWrapper::get)
        .isNull();
    assertThat(second.get("missing")).isNotNull().extracting(Cache.ValueWrapper::get).isNull();
  }

  @ParameterizedTest(name = "disabled reader / {0}")
  @MethodSource("serializers")
  void disabledReaderTreatsSpringNullMarkerAsMiss(String name, RedisSerializer<Object> serializer) {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    new SpringRedisCache(writer).put("missing", null);
    Cache reader = cache(new MultiLevelCacheConfigurationProperties(), writer, serializer);

    assertThat(reader.get("missing")).isNull();
    assertThat(reader.get("missing", String.class)).isNull();
    AtomicInteger calls = new AtomicInteger();
    assertThat(
            reader.get(
                "missing",
                () -> {
                  calls.incrementAndGet();
                  return "replacement";
                }))
        .isEqualTo("replacement");
    assertThat(reader.get("missing", () -> "unused")).isEqualTo("replacement");
    assertThat(calls).hasValue(1);
  }

  @ParameterizedTest(name = "null reads / {0}")
  @MethodSource("serializers")
  void enabledReadersExposeNullOnColdAndWarmReads(String name, RedisSerializer<Object> serializer) {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    Cache origin = cache(enabledProperties(), writer, serializer);
    origin.put("missing", null);
    assertThat(origin.get("absent")).isNull();
    assertNullHit(origin.get("missing"));
    assertNullHit(new SpringRedisCache(writer).get("missing"));

    Cache plainReader = cache(enabledProperties(), writer, serializer);
    Cache typedReader = cache(enabledProperties(), writer, serializer);
    Cache loaderReader = cache(enabledProperties(), writer, serializer);
    assertNullHit(plainReader.get("missing"));
    assertThat(typedReader.get("missing", String.class)).isNull();
    assertThat(
            loaderReader.<String>get(
                "missing",
                () -> {
                  throw new AssertionError("cached null is a hit");
                }))
        .isNull();

    writer.failWith(new RedisConnectionFailureException("Redis is unavailable"));
    for (Cache reader : List.of(origin, plainReader, typedReader, loaderReader)) {
      assertNullHit(reader.get("missing"));
      assertThat(reader.get("missing", String.class)).isNull();
      assertThat(
              reader.<String>get(
                  "missing",
                  () -> {
                    throw new AssertionError("warm null is a hit");
                  }))
          .isNull();
    }
  }

  @ParameterizedTest(name = "null candidate / {0}")
  @MethodSource("serializers")
  void enabledNullCandidatePreservesExistingOrdinaryValue(
      String name, RedisSerializer<Object> serializer) {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    Cache first = cache(enabledProperties(), writer, serializer);
    first.put("existing", "present");
    assertThat(first.putIfAbsent("existing", null))
        .isNotNull()
        .extracting(Cache.ValueWrapper::get)
        .isEqualTo("present");
    Cache second = cache(enabledProperties(), writer, serializer);
    assertThat(second.putIfAbsent("existing", null))
        .isNotNull()
        .extracting(Cache.ValueWrapper::get)
        .isEqualTo("present");
    assertThat(new SpringRedisCache(writer, serializer).get("existing", String.class))
        .isEqualTo("present");
  }

  @ParameterizedTest(name = "disabled atomic insertion / {0}")
  @MethodSource("serializers")
  void disabledPutIfAbsentReplacesLegacyNullWithOrdinaryValue(
      String name, RedisSerializer<Object> serializer) {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    new SpringRedisCache(writer).put("missing", null);
    Cache reader = cache(new MultiLevelCacheConfigurationProperties(), writer, serializer);

    assertThat(reader.putIfAbsent("missing", "replacement")).isNull();
    assertThat(reader.get("missing", String.class)).isEqualTo("replacement");
    assertThat(new SpringRedisCache(writer, serializer).get("missing", String.class))
        .isEqualTo("replacement");
  }

  @Test
  void defaultPolicyStillEvictsNullWritesAndRejectsNullLoaders() {
    Cache cache =
        cache(
            new MultiLevelCacheConfigurationProperties(),
            new TestRedisCacheWriter(),
            RedisSerializer.json());
    cache.put("existing", "present");
    cache.put("existing", null);
    assertThat(cache.get("existing")).isNull();
    cache.put("existing", "present");
    assertThat(cache.putIfAbsent("existing", null)).isNull();
    assertThat(cache.get("existing")).isNull();
    assertThatThrownBy(() -> cache.get("missing", () -> null))
        .isInstanceOf(Cache.ValueRetrievalException.class);
    assertThat(cache.get("missing")).isNull();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nullCachingWorksDuringRedisOutageOrOpenBreaker(boolean openBreaker) {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    CircuitBreaker breaker = CircuitBreaker.ofDefaults("offline-nulls");
    if (openBreaker) breaker.transitionToOpenState();
    else writer.failWith(new RedisConnectionFailureException("Redis is unavailable"));
    Cache cache = cache(enabledProperties(), writer, RedisSerializer.json(), breaker);
    AtomicInteger calls = new AtomicInteger();
    assertThat(
            cache.<String>get(
                "loaded",
                () -> {
                  calls.incrementAndGet();
                  return null;
                }))
        .isNull();
    assertThat(
            cache.get(
                "loaded",
                () -> {
                  calls.incrementAndGet();
                  return "unexpected";
                }))
        .isNull();
    assertThat(calls).hasValue(1);
    cache.put("written", null);
    assertNullHit(cache.get("written"));
    assertThat(cache.putIfAbsent("atomic", null)).isNull();
    assertNullHit(cache.putIfAbsent("atomic", "unexpected"));
    assertNullHit(cache.get("atomic"));
  }

  @Test
  void concurrentSynchronizedCacheableNullResultLoadsOnce() throws Exception {
    try (var context = new AnnotationConfigApplicationContext(NullLookupConfiguration.class)) {
      NullLookup lookup = context.getBean(NullLookup.class);
      int callers = 8;
      var executor = Executors.newFixedThreadPool(callers);
      CountDownLatch start = new CountDownLatch(1);
      try {
        var results =
            Stream.generate(
                    () ->
                        executor.submit(
                            () -> {
                              if (!start.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("start was not released");
                              return lookup.findSynchronized("missing");
                            }))
                .limit(callers)
                .toList();
        start.countDown();
        for (var result : results) assertThat(result.get(5, TimeUnit.SECONDS)).isNull();
        assertThat(lookup.calls()).isEqualTo(1);
      } finally {
        start.countDown();
        executor.shutdownNow();
      }
    }
  }

  @Test
  void malformedOrdinaryValuesStillFailAndCanBeReplaced() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    RedisSerializer<Object> strings =
        (RedisSerializer<Object>) (RedisSerializer<?>) StringRedisSerializer.UTF_8;
    new SpringRedisCache(writer, strings).put("broken", "invalid-json");
    Cache reader = cache(enabledProperties(), writer, RedisSerializer.json());

    assertThatThrownBy(() -> reader.get("broken")).isInstanceOf(SerializationException.class);
    new SpringRedisCache(writer, RedisSerializer.json()).put("broken", "fixed");
    assertThat(reader.get("broken", String.class)).isEqualTo("fixed");
  }

  private static void assertNullHit(Cache.ValueWrapper wrapper) {
    assertThat(wrapper).isNotNull().extracting(Cache.ValueWrapper::get).isNull();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nullMarkersBypassTheApplicationSerializerRegardlessOfPolicy(boolean enabled) {
    RedisSerializer<Object> ordinaryValuesOnly =
        new RedisSerializer<>() {
          @Override
          public byte[] serialize(Object value) {
            if (value == null || value == NullValue.INSTANCE)
              throw new SerializationException("application serializer does not encode nulls");
            return RedisSerializer.json().serialize(value);
          }

          @Override
          public Object deserialize(byte[] bytes) {
            throw new SerializationException("application serializer must not decode null markers");
          }
        };
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    new SpringRedisCache(writer).put("missing", null);
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    properties.setCacheNullValues(enabled);
    Cache reader = cache(properties, writer, ordinaryValuesOnly);

    if (enabled) {
      assertNullHit(reader.get("missing"));
      assertNullHit(reader.putIfAbsent("missing", "candidate"));
    } else {
      assertThat(reader.get("missing")).isNull();
      assertThat(reader.putIfAbsent("missing", "candidate")).isNull();
      assertThat(reader.get("missing", String.class)).isEqualTo("candidate");
    }
    Cache producer = cache(enabledProperties(), writer, ordinaryValuesOnly);
    producer.put("written", null);
    assertThat(producer.putIfAbsent("atomic", null)).isNull();
    assertThat(producer.<String>get("loaded", () -> null)).isNull();
    for (String key : List.of("written", "atomic", "loaded"))
      assertNullHit(new SpringRedisCache(writer).get(key));
  }

  @Test
  void enabledAndDisabledReadersCanShareMarkersButHaveDifferentNullPolicies() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    Cache enabled = cache(enabledProperties(), writer, RedisSerializer.json());
    Cache disabled =
        cache(new MultiLevelCacheConfigurationProperties(), writer, RedisSerializer.json());
    enabled.put("missing", null);

    assertNullHit(enabled.get("missing"));
    assertThat(disabled.get("missing")).isNull();
    assertThat(disabled.get("missing", () -> "recomputed")).isEqualTo("recomputed");
    Cache freshEnabled = cache(enabledProperties(), writer, RedisSerializer.json());
    assertThat(freshEnabled.get("missing", String.class)).isEqualTo("recomputed");
  }

  @SuppressWarnings("unchecked")
  static Stream<Arguments> serializers() {
    return Stream.of(
        Arguments.of("JSON", RedisSerializer.json()),
        Arguments.of("JDK", RedisSerializer.java()),
        Arguments.of("Fory", new MultiLevelCacheForyInvalidationTest.ForyJsonSerializer()),
        Arguments.of(
            "String", (RedisSerializer<Object>) (RedisSerializer<?>) StringRedisSerializer.UTF_8));
  }

  private static Cache cache(
      MultiLevelCacheConfigurationProperties properties,
      TestRedisCacheWriter writer,
      RedisSerializer<Object> serializer) {
    return cache(properties, writer, serializer, CircuitBreaker.ofDefaults("null-values"));
  }

  private static Cache cache(
      MultiLevelCacheConfigurationProperties properties,
      TestRedisCacheWriter writer,
      RedisSerializer<Object> serializer,
      CircuitBreaker breaker) {
    RedisTemplate<Object, Object> template = mock(RedisTemplate.class);
    doReturn(serializer).when(template).getValueSerializer();
    return new MultiLevelCache(
        "products",
        properties,
        writer,
        template,
        Caffeine.newBuilder().build(),
        breaker,
        "instance");
  }

  private static MultiLevelCacheConfigurationProperties enabledProperties() {
    return new Binder(
            new MapConfigurationPropertySource(
                Map.of("spring.cache.multilevel.cache-null-values", "true")))
        .bind("spring.cache.multilevel", Bindable.of(MultiLevelCacheConfigurationProperties.class))
        .orElseGet(MultiLevelCacheConfigurationProperties::new);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableCaching
  static class NullLookupConfiguration {
    @Bean
    CacheManager cacheManager() {
      SimpleCacheManager manager = new SimpleCacheManager();
      manager.setCaches(
          List.of(
              cache(
                  enabledProperties(),
                  new TestRedisCacheWriter(),
                  new MultiLevelCacheForyInvalidationTest.ForyJsonSerializer())));
      return manager;
    }

    @Bean
    NullLookup nullLookup() {
      return new NullLookup();
    }
  }

  static class NullLookup {
    private final AtomicInteger calls = new AtomicInteger();

    @Cacheable(cacheNames = "products", key = "#p0")
    public String find(String id) {
      calls.incrementAndGet();
      return null;
    }

    @Cacheable(cacheNames = "products", key = "#p0", sync = true)
    public String findSynchronized(String id) {
      calls.incrementAndGet();
      return null;
    }

    public int calls() {
      return calls.get();
    }
  }

  private static final class SpringRedisCache extends RedisCache {
    SpringRedisCache(RedisCacheWriter writer) {
      this(writer, RedisSerializer.java());
    }

    SpringRedisCache(RedisCacheWriter writer, RedisSerializer<Object> serializer) {
      super(
          "products",
          writer,
          RedisCacheConfiguration.defaultCacheConfig()
              .serializeValuesWith(
                  RedisSerializationContext.SerializationPair.fromSerializer(serializer)));
    }
  }
}
