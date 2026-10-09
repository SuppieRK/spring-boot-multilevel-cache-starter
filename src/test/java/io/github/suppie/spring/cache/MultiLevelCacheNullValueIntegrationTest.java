package io.github.suppie.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(
    classes = {
      DataRedisAutoConfiguration.class,
      CacheAutoConfiguration.class,
      MultiLevelCacheAutoConfiguration.class
    },
    properties = "spring.cache.multilevel.cache-null-values=true")
class MultiLevelCacheNullValueIntegrationTest extends AbstractRedisIntegrationTest {
  private static final Duration WAIT = Duration.ofSeconds(10);

  @Autowired MultiLevelCacheManager configuredManager;
  @Autowired ObjectProvider<CacheProperties> cacheProperties;

  @Autowired
  @Qualifier(MultiLevelCacheAutoConfiguration.CACHE_REDIS_TEMPLATE_NAME)
  RedisTemplate<Object, Object> configuredTemplate;

  @Test
  void autoConfiguredCacheRetainsNullResults() {
    Cache cache = configuredManager.getCache("configured-nulls-" + UUID.randomUUID());
    assertThat(cache).isNotNull();
    try {
      cache.put("missing", null);
      assertNullHit(cache.get("missing"));
    } finally {
      cache.clear();
    }
  }

  @ParameterizedTest(name = "Redis invalidation / {0}")
  @MethodSource("io.github.suppie.spring.cache.MultiLevelCacheNullValueTest#serializers")
  void nullEntriesWarmFromRedisAndRespondToEntryAndCacheInvalidations(
      String name, RedisSerializer<Object> serializer) throws Exception {
    RedisTemplate<Object, Object> template = template(serializer);
    MultiLevelCacheConfigurationProperties properties = properties();
    MultiLevelCacheManager originManager = manager(properties, template);
    MultiLevelCacheManager receiverManager = manager(properties, template);
    String cacheName = "null-invalidation-" + UUID.randomUUID();
    Cache origin = originManager.getCache(cacheName);
    Cache receiver = receiverManager.getCache(cacheName);
    RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
    listener.setConnectionFactory(Objects.requireNonNull(template.getConnectionFactory()));
    listener.addMessageListener(
        MultiLevelCacheAutoConfiguration.createMessageListener(template, receiverManager),
        new ChannelTopic(properties.getTopic()));
    listener.afterPropertiesSet();
    listener.start();
    try {
      origin.put("missing", null);
      assertNullHit(receiver.get("missing"));
      assertThat(
              receiver.<String>get(
                  "missing",
                  () -> {
                    throw new AssertionError("Redis null is a hit");
                  }))
          .isNull();
      receiver.put("other", "unaffected");
      origin.put("missing", "fresh");
      Awaitility.await()
          .atMost(WAIT)
          .untilAsserted(
              () -> assertThat(receiver.get("missing", String.class)).isEqualTo("fresh"));
      assertThat(receiver.get("other", String.class)).isEqualTo("unaffected");

      origin.put("missing", null);
      Awaitility.await().atMost(WAIT).untilAsserted(() -> assertNullHit(receiver.get("missing")));
      origin.evict("missing");
      Awaitility.await()
          .atMost(WAIT)
          .untilAsserted(() -> assertThat(receiver.get("missing")).isNull());

      origin.put("missing", null);
      assertNullHit(receiver.get("missing"));
      origin.clear();
      Awaitility.await()
          .atMost(WAIT)
          .untilAsserted(
              () -> {
                assertThat(receiver.get("missing")).isNull();
                assertThat(receiver.get("other")).isNull();
              });
    } finally {
      listener.stop();
      listener.destroy();
      origin.clear();
    }
  }

  @Test
  void expirationAllowsNullLookupToRunAgain() {
    MultiLevelCacheConfigurationProperties properties = properties();
    properties.setTimeToLive(Duration.ofSeconds(2));
    properties.getLocal().setTimeToLive(Optional.of(Duration.ofMillis(200)));
    Cache cache =
        manager(properties, configuredTemplate).getCache("null-expiry-" + UUID.randomUUID());
    AtomicInteger calls = new AtomicInteger();
    try {
      assertThat(
              cache.<String>get(
                  "missing",
                  () -> {
                    calls.incrementAndGet();
                    return null;
                  }))
          .isNull();
      assertNullHit(cache.get("missing"));
      Awaitility.await()
          .atMost(WAIT)
          .untilAsserted(() -> assertThat(cache.get("missing")).isNull());
      assertThat(
              cache.<String>get(
                  "missing",
                  () -> {
                    calls.incrementAndGet();
                    return null;
                  }))
          .isNull();
      assertThat(calls).hasValue(2);
    } finally {
      cache.clear();
    }
  }

  @Test
  void nullEntriesConsumeLocalCapacityAndCanBeReloadedFromRedis() {
    MultiLevelCacheConfigurationProperties properties = properties();
    properties.getLocal().setMaxSize(1);
    CircuitBreaker breaker = CircuitBreaker.ofDefaults("capacity-nulls");
    MultiLevelCacheManager manager =
        new MultiLevelCacheManager(cacheProperties, properties, configuredTemplate, breaker);
    Cache cache = manager.getCache("null-capacity-" + UUID.randomUUID());
    Cache untouched = manager.getCache("other-cache-" + UUID.randomUUID());
    try {
      cache.put("first", null);
      cache.put("second", null);
      untouched.put("missing", null);
      breaker.transitionToOpenState();
      Awaitility.await()
          .atMost(WAIT)
          .untilAsserted(
              () ->
                  assertThat(
                          Stream.of(cache.get("first"), cache.get("second"))
                              .filter(Objects::nonNull)
                              .count())
                      .isEqualTo(1));
      breaker.transitionToClosedState();
      assertNullHit(cache.get("first"));
      assertNullHit(cache.get("second"));
      cache.invalidate();
      assertThat(cache.get("first")).isNull();
      assertThat(cache.get("second")).isNull();
      assertNullHit(untouched.get("missing"));
    } finally {
      breaker.transitionToClosedState();
      cache.clear();
      untouched.clear();
    }
  }

  private MultiLevelCacheManager manager(
      MultiLevelCacheConfigurationProperties properties, RedisTemplate<Object, Object> template) {
    return new MultiLevelCacheManager(
        cacheProperties, properties, template, CircuitBreaker.ofDefaults("integration-nulls"));
  }

  private RedisTemplate<Object, Object> template(RedisSerializer<Object> serializer) {
    RedisTemplate<Object, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(configuredTemplate.getConnectionFactory());
    template.setKeySerializer(StringRedisSerializer.UTF_8);
    template.setValueSerializer(serializer);
    template.afterPropertiesSet();
    return template;
  }

  private static MultiLevelCacheConfigurationProperties properties() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    properties.setCacheNullValues(true);
    properties.setTopic("null-values:" + UUID.randomUUID());
    properties.getLocal().setExpiryJitter(0);
    return properties;
  }

  private static void assertNullHit(Cache.ValueWrapper wrapper) {
    assertThat(wrapper).isNotNull().extracting(Cache.ValueWrapper::get).isNull();
  }
}
