/*
 * MIT License
 *
 * Copyright (c) 2024 Roman Khlebnov
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.suppie.spring.cache;

import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import tools.jackson.core.io.JsonStringEncoder;

/**
 * Encodes and decodes the stable v0 JSON wire format for cache invalidation messages.
 *
 * <p>The encoder deliberately produces the same field order and escaping as {@link
 * RedisSerializer#json()}. Keeping that representation stable lets upgraded nodes communicate with
 * existing 4.1.1 nodes without an operator-controlled migration setting.
 */
final class CacheInvalidationCodec {
  private static final byte[] JSON_PREFIX =
      ("{\"@class\":\"" + MultiLevelCacheEvictMessage.class.getName() + "\",\"cacheName\":")
          .getBytes(StandardCharsets.UTF_8);
  private static final byte[] JSON_ENTRY_KEY = ",\"entryKey\":".getBytes(StandardCharsets.UTF_8);
  private static final byte[] JSON_SENDER_ID = ",\"senderId\":".getBytes(StandardCharsets.UTF_8);
  private static final byte[] JSON_NULL = "null".getBytes(StandardCharsets.UTF_8);
  private static final byte JSON_QUOTE = '"';
  private static final byte JSON_OBJECT_END = '}';
  private static final byte JAVA_STREAM_MAGIC_HIGH = (byte) 0xAC;
  private static final byte JAVA_STREAM_MAGIC_LOW = (byte) 0xED;
  private static final byte JAVA_STREAM_VERSION_HIGH = 0;
  private static final byte JAVA_STREAM_VERSION_LOW = 5;
  private static final RedisSerializer<Object> SERIALIZER = RedisSerializer.json();

  private CacheInvalidationCodec() {}

  /**
   * Serializes an invalidation message into the stable v0 JSON representation.
   *
   * @param message message to serialize
   * @return JSON payload compatible with the existing Spring JSON Redis serializer
   * @throws SerializationException when the encoded payload would exceed the maximum array size
   */
  static byte[] serialize(MultiLevelCacheEvictMessage message) {
    JsonStringEncoder encoder = JsonStringEncoder.getInstance();
    byte @Nullable [] cacheName = quoteAsUtf8(encoder, message.getCacheName());
    byte @Nullable [] entryKey = quoteAsUtf8(encoder, message.getEntryKey());
    byte @Nullable [] senderId = quoteAsUtf8(encoder, message.getSenderId());
    long payloadLength =
        JSON_PREFIX.length
            + jsonValueLength(cacheName)
            + JSON_ENTRY_KEY.length
            + jsonValueLength(entryKey)
            + JSON_SENDER_ID.length
            + jsonValueLength(senderId)
            + 1;
    if (payloadLength > Integer.MAX_VALUE) {
      throw new SerializationException("Cache invalidation message is too large to serialize");
    }

    byte[] body = new byte[(int) payloadLength];
    int offset = copy(JSON_PREFIX, body, 0);
    offset = copyJsonValue(cacheName, body, offset);
    offset = copy(JSON_ENTRY_KEY, body, offset);
    offset = copyJsonValue(entryKey, body, offset);
    offset = copy(JSON_SENDER_ID, body, offset);
    offset = copyJsonValue(senderId, body, offset);
    body[offset] = JSON_OBJECT_END;
    return body;
  }

  /**
   * Deserializes the stable v0 JSON representation.
   *
   * @param body serialized invalidation message
   * @return decoded message, or {@code null} when the serializer regards the payload as empty
   * @throws SerializationException when the payload is malformed or contains another value type
   */
  static @Nullable MultiLevelCacheEvictMessage deserialize(byte[] body) {
    return SERIALIZER.deserialize(body, MultiLevelCacheEvictMessage.class);
  }

  /**
   * Returns whether the payload starts with the standard Java serialization stream header.
   *
   * @param body serialized payload to inspect
   * @return {@code true} for a Java serialization stream
   */
  static boolean hasJavaSerializationHeader(byte[] body) {
    return body.length >= 4
        && body[0] == JAVA_STREAM_MAGIC_HIGH
        && body[1] == JAVA_STREAM_MAGIC_LOW
        && body[2] == JAVA_STREAM_VERSION_HIGH
        && body[3] == JAVA_STREAM_VERSION_LOW;
  }

  private static byte @Nullable [] quoteAsUtf8(JsonStringEncoder encoder, @Nullable String value) {
    return value == null ? null : encoder.quoteAsUTF8(value);
  }

  private static long jsonValueLength(byte @Nullable [] value) {
    return value == null ? JSON_NULL.length : (long) value.length + 2;
  }

  private static int copyJsonValue(byte @Nullable [] value, byte[] destination, int offset) {
    if (value == null) {
      return copy(JSON_NULL, destination, offset);
    }
    destination[offset++] = JSON_QUOTE;
    offset = copy(value, destination, offset);
    destination[offset++] = JSON_QUOTE;
    return offset;
  }

  private static int copy(byte[] source, byte[] destination, int offset) {
    System.arraycopy(source, 0, destination, offset, source.length);
    return offset + source.length;
  }
}
