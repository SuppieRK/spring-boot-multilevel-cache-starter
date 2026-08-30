package io.github.suppie.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

class CacheInvalidationCodecTest {

  @Test
  void serializeRemainsReadableByExistingJsonSerializer() {
    MultiLevelCacheEvictMessage message =
        new MultiLevelCacheEvictMessage("cache\"\\\n☃", null, "sender\t😀");

    Object decoded = RedisSerializer.json().deserialize(CacheInvalidationCodec.serialize(message));

    assertThat(decoded).isEqualTo(message);
  }

  @Test
  void serializePreservesTheExistingJsonWireRepresentation() {
    MultiLevelCacheEvictMessage message =
        new MultiLevelCacheEvictMessage("cache\"\\\n☃", null, "sender\t😀");

    assertThat(CacheInvalidationCodec.serialize(message))
        .isEqualTo(RedisSerializer.json().serialize(message));
  }

  @Test
  void deserializeReadsPayloadProducedByExistingJsonSerializer() {
    MultiLevelCacheEvictMessage message =
        new MultiLevelCacheEvictMessage("cache", "entry", "sender");
    byte[] payload = RedisSerializer.json().serialize(message);

    assertThat(CacheInvalidationCodec.deserialize(payload)).isEqualTo(message);
  }

  @Test
  void recognizesStandardJavaSerializationStreamHeader() {
    byte[] legacyPayload =
        new JdkSerializationRedisSerializer()
            .serialize(new MultiLevelCacheEvictMessage("cache", "entry", "sender"));

    assertThat(CacheInvalidationCodec.hasJavaSerializationHeader(legacyPayload)).isTrue();
    assertThat(
            CacheInvalidationCodec.hasJavaSerializationHeader(
                CacheInvalidationCodec.serialize(
                    new MultiLevelCacheEvictMessage("cache", "entry", "sender"))))
        .isFalse();
    assertThat(CacheInvalidationCodec.hasJavaSerializationHeader(new byte[0])).isFalse();
    assertThat(
            CacheInvalidationCodec.hasJavaSerializationHeader(
                "not-java".getBytes(StandardCharsets.UTF_8)))
        .isFalse();
  }

  @Test
  void deserializeReturnsNullForAnEmptyPayload() {
    assertThat(CacheInvalidationCodec.deserialize(new byte[0])).isNull();
  }

  @Test
  void deserializeRejectsPayloadsOfTheWrongType() {
    byte[] payload = RedisSerializer.json().serialize("not-an-invalidation-message");

    assertThatThrownBy(() -> CacheInvalidationCodec.deserialize(payload))
        .isInstanceOf(SerializationException.class)
        .hasMessageContaining("Expected cache invalidation message")
        .hasMessageContaining(String.class.getName());
  }
}
