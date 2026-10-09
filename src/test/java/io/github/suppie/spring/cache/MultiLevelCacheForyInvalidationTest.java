package io.github.suppie.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.ArrayList;
import java.util.List;
import org.apache.fory.json.ForyJson;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.cache.autoconfigure.CacheProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@ExtendWith(OutputCaptureExtension.class)
class MultiLevelCacheForyInvalidationTest {

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void foryInvalidationsEvictLocalEntriesWithoutDecodeErrors(
      boolean clearCache, CapturedOutput output) {
    List<byte[]> published = new ArrayList<>();
    RedisConnection connection = mock(RedisConnection.class);
    when(connection.publish(any(byte[].class), any(byte[].class)))
        .thenAnswer(
            invocation -> {
              published.add(invocation.getArgument(1));
              return 1L;
            });
    RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
    when(factory.getConnection()).thenReturn(connection);
    ForyJsonSerializer serializer = new ForyJsonSerializer();
    RedisTemplate<Object, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    template.setKeySerializer(StringRedisSerializer.UTF_8);
    template.setValueSerializer(serializer);
    template.afterPropertiesSet();
    MultiLevelCache publisher =
        new MultiLevelCache(
            "products",
            new MultiLevelCacheConfigurationProperties(),
            new TestRedisCacheWriter(),
            template,
            Caffeine.newBuilder().build(),
            CircuitBreaker.ofDefaults("fory-publisher"),
            "publisher");

    if (clearCache) publisher.clear();
    else publisher.put("test", "value");

    // Both representations are required by the rolling-upgrade compatibility contract.
    assertThat(published).hasSize(2);
    MultiLevelCacheEvictMessage expected =
        new MultiLevelCacheEvictMessage("products", clearCache ? null : "test", "publisher");
    assertThat(published)
        .anySatisfy(body -> assertThat(body).isEqualTo(serializer.serialize(expected)));

    ObjectProvider<CacheProperties> properties = mock(ObjectProvider.class);
    MultiLevelCacheManager receiverManager =
        new MultiLevelCacheManager(
            properties,
            new MultiLevelCacheConfigurationProperties(),
            template,
            CircuitBreaker.ofDefaults("fory-receiver"));
    MultiLevelCache receiver = (MultiLevelCache) receiverManager.getCache("products");
    var listener =
        MultiLevelCacheAutoConfiguration.createMessageListener(template, receiverManager);

    for (byte[] body : published) {
      // Refill before each delivery so the stable message cannot mask a broken legacy decoder.
      receiver.getLocalCache().put(receiver.toLocalKey("test"), "stale");
      receiver.getLocalCache().put(receiver.toLocalKey("other"), "unaffected");
      listener.onMessage(new DefaultMessage("topic".getBytes(), body), null);

      assertThat(output).doesNotContain("Unknown Redis cache invalidation message");
      assertThat(receiver.getLocalCache().getIfPresent(receiver.toLocalKey("test"))).isNull();
      if (clearCache) assertThat(receiver.getLocalCache().estimatedSize()).isZero();
      else
        assertThat(receiver.getLocalCache().getIfPresent(receiver.toLocalKey("other")))
            .isEqualTo("unaffected");
    }
  }

  // The serializer from issue #158: Object.class reads JSON values without restoring message types.
  static class ForyJsonSerializer implements RedisSerializer<Object> {
    private final ForyJson foryJson = ForyJson.builder().build();

    @Override
    public byte @NonNull [] serialize(@Nullable Object value) throws SerializationException {
      return foryJson.toJsonBytes(value);
    }

    @Override
    public @Nullable Object deserialize(byte @Nullable [] bytes) throws SerializationException {
      if (bytes == null || bytes.length == 0) return null;
      return foryJson.fromJson(bytes, Object.class);
    }
  }
}
