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
- `AFTER_UPDATE` and `AFTER_READ` remain behaviorally compatible but are deprecated for a future
  major release.
- Asynchronous `Cache.retrieve(...)` methods are not yet multilevel-aware.
