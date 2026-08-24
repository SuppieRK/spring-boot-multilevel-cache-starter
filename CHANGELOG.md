# Changelog

## 4.1.1.0

### Fixed

- Preserve L1-first reads while making connected cold-cache `putIfAbsent` races converge.
- Keep user loaders outside the Redis circuit breaker.
- Fall back only for Redis availability failures and propagate unexpected cache failures.
- Prevent lock eviction and stale Redis reads from repopulating invalidated L1 entries.
- Align Redis with the existing no-null cache contract.
- Decouple Pub/Sub invalidation from custom cache-value serializers.
- Prevent inbound invalidation messages from creating cache instances.
- Apply patterned clearing to both Redis and the local cache.
- Back off auto-configuration for user-managed caching and missing Redis infrastructure.

### Compatibility

- Existing public constructors, property names, bean names, Redis key layout, topic, and v0
  invalidation message remain unchanged.
- Existing custom cache-value serializer discovery remains unchanged.
- Rolling upgrades accept both stable JSON and legacy custom-serializer invalidation messages;
  publishers emit a best-effort legacy copy when the custom serializer supports the message type.
- `AFTER_CREATE`, `AFTER_UPDATE`, and `AFTER_READ` remain supported and unchanged.
- Asynchronous `Cache.retrieve(...)` methods are not yet multilevel-aware.
