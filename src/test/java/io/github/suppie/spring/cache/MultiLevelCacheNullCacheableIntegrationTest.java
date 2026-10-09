package io.github.suppie.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(
    classes = {
      DataRedisAutoConfiguration.class,
      CacheAutoConfiguration.class,
      MultiLevelCacheAutoConfiguration.class
    })
class MultiLevelCacheNullCacheableIntegrationTest extends AbstractRedisIntegrationTest {

  @Autowired ObjectProvider<CacheProperties> cacheProperties;

  @Autowired
  @Qualifier(MultiLevelCacheAutoConfiguration.CACHE_REDIS_TEMPLATE_NAME)
  RedisTemplate<Object, Object> configuredTemplate;

  @ParameterizedTest(name = "{0} / sync={1}")
  @MethodSource("lookups")
  void annotatedNullResultSkipsRepeatedAndCrossInstanceLookups(
      String serializerName, boolean sync) {
    RedisTemplate<Object, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(configuredTemplate.getConnectionFactory());
    template.setKeySerializer(StringRedisSerializer.UTF_8);
    template.setValueSerializer(serializer(serializerName));
    template.afterPropertiesSet();
    MultiLevelCacheConfigurationProperties properties =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "spring.cache.multilevel.cache-null-values", "true",
                        "spring.cache.multilevel.use-key-prefix", "true",
                        "spring.cache.multilevel.key-prefix",
                            "annotated-null-" + UUID.randomUUID() + ":")))
            .bind(
                "spring.cache.multilevel",
                Bindable.of(MultiLevelCacheConfigurationProperties.class))
            .get();
    CacheManager originManager = manager(properties, template);
    CacheManager receiverManager = manager(properties, template);
    try (var origin = lookupContext(originManager);
        var receiver = lookupContext(receiverManager)) {
      NullLookup first = origin.getBean(NullLookup.class);
      NullLookup second = receiver.getBean(NullLookup.class);
      String key = "missing";

      assertThat(find(first, key, sync)).isNull();
      assertThat(find(first, key, sync)).isNull();
      assertThat(first.calls()).isEqualTo(1);

      // Independent managers have separate local tiers; the receiver must read the hit from Redis.
      assertThat(find(second, key, sync)).isNull();
      assertThat(find(second, key, sync)).isNull();
      assertThat(second.calls()).isZero();
    } finally {
      originManager.getCache("annotated-nulls").clear();
    }
  }

  static Stream<Arguments> lookups() {
    return Stream.of("JSON", "JDK", "Fory", "String")
        .flatMap(name -> Stream.of(false, true).map(sync -> Arguments.of(name, sync)));
  }

  @SuppressWarnings("unchecked")
  private static RedisSerializer<Object> serializer(String name) {
    return switch (name) {
      case "JSON" -> RedisSerializer.json();
      case "JDK" -> RedisSerializer.java();
      case "Fory" -> new MultiLevelCacheForyInvalidationTest.ForyJsonSerializer();
      case "String" -> (RedisSerializer<Object>) (RedisSerializer<?>) StringRedisSerializer.UTF_8;
      default -> throw new IllegalArgumentException(name);
    };
  }

  private CacheManager manager(
      MultiLevelCacheConfigurationProperties properties, RedisTemplate<Object, Object> template) {
    return new MultiLevelCacheManager(
        cacheProperties, properties, template, CircuitBreaker.ofDefaults("annotated-nulls"));
  }

  private static AnnotationConfigApplicationContext lookupContext(CacheManager manager) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.registerBean(CacheManager.class, () -> manager);
    context.register(LookupConfiguration.class);
    context.refresh();
    return context;
  }

  private static String find(NullLookup lookup, String key, boolean sync) {
    return sync ? lookup.findSynchronized(key) : lookup.findOrdinary(key);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableCaching
  static class LookupConfiguration {
    @Bean
    NullLookup nullLookup() {
      return new NullLookup();
    }
  }

  static class NullLookup {
    private final AtomicInteger calls = new AtomicInteger();

    @Cacheable(cacheNames = "annotated-nulls", key = "#p0")
    public String findOrdinary(String key) {
      calls.incrementAndGet();
      return null;
    }

    @Cacheable(cacheNames = "annotated-nulls", key = "#p0", sync = true)
    public String findSynchronized(String key) {
      calls.incrementAndGet();
      return null;
    }

    public int calls() {
      return calls.get();
    }
  }
}
