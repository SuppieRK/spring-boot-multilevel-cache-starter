# Spring Boot multi-level cache starter

Opinionated multi-level caching for [Spring Boot](https://spring.io/projects/spring-boot) that
combines [Redis](https://redis.io/) for the distributed tier and [Caffeine](https://github.com/ben-manes/caffeine) for
the in-memory tier, guarded by a Resilience4j circuit breaker.

### Highlights

- **Drop-in starter** – activates automatically when `spring.cache.type=redis`
- **Aggressive hot-path focus** – randomized local TTL keeps Redis warm while preventing stampedes
- **Graceful degradation** – circuit breaker keeps serving from Caffeine if Redis is slow or down
- **Batteries included** – curated defaults so you only tweak what matters

[![Build status](https://github.com/SuppieRK/spring-boot-multilevel-cache-starter/actions/workflows/build.yml/badge.svg)](https://github.com/SuppieRK/spring-boot-multilevel-cache-starter/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.suppierk/spring-boot-multilevel-cache-starter.svg)](https://search.maven.org/artifact/io.github.suppierk/spring-boot-multilevel-cache-starter)
[![Javadoc](https://javadoc.io/badge2/io.github.suppierk/spring-boot-multilevel-cache-starter/javadoc.svg)](https://javadoc.io/doc/io.github.suppierk/spring-boot-multilevel-cache-starter)
[![SonarCloud Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=SuppieRK_spring-boot-multilevel-cache-starter&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=SuppieRK_spring-boot-multilevel-cache-starter)
[![SonarCloud Coverage](https://sonarcloud.io/api/project_badges/measure?project=SuppieRK_spring-boot-multilevel-cache-starter&metric=coverage)](https://sonarcloud.io/summary/new_code?id=SuppieRK_spring-boot-multilevel-cache-starter)
[![SonarCloud Maintainability](https://sonarcloud.io/api/project_badges/measure?project=SuppieRK_spring-boot-multilevel-cache-starter&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=SuppieRK_spring-boot-multilevel-cache-starter)
[![FOSSA Status](https://app.fossa.com/api/projects/git%2Bgithub.com%2FSuppieRK%2Fspring-boot-multilevel-cache-starter.svg?type=shield)](https://app.fossa.com/projects/git%2Bgithub.com%2FSuppieRK%2Fspring-boot-multilevel-cache-starter?ref=badge_shield)

## Usage

### Maven

```xml

<dependency>
    <groupId>io.github.suppierk</groupId>
    <artifactId>spring-boot-multilevel-cache-starter</artifactId>
    <version>4.1.1.2</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.github.suppierk:spring-boot-multilevel-cache-starter:4.1.1.2'
```

### Examples

- `examples/basic-demo` — minimal REST service demonstrating `@Cacheable` with the starter. Clone the repo, start Redis via `docker compose up -d` inside the example directory, then run `./gradlew :examples:basic-demo:bootRun` from the project root.

## Use cases

### Cache behavior

- Caffeine is always checked first. An L1 hit is returned without calling Redis or the circuit
  breaker.
- Redis is the shared L2 fallback after an L1 miss. A Redis hit warms L1.
- On a cold `putIfAbsent`, Redis coordinates concurrent connected instances. If Redis is
  unavailable, the operation remains atomic only inside the current application instance.
- Connection failures, timeouts, and an open circuit breaker fall back to local caching. Cache key
  conversion, serialization, validation, and programming errors are propagated to the caller.
- Null caching is opt in through `spring.cache.multilevel.cache-null-values=true`. By default,
  `put(key, null)` and `putIfAbsent(key, null)` evict, and a null loader result fails with
  `Cache.ValueRetrievalException`.
- Redis Pub/Sub invalidation uses its own stable v0 JSON codec, independently of the configured
  cache-value serializer.

Spring's asynchronous `Cache.retrieve(...)` methods are not yet multilevel-aware. Applications
that require L1-first behavior should use the synchronous cache methods until async support is
implemented.

### Caching null results

Enable null caching to avoid repeating lookups for missing data:

```yaml
spring:
  cache:
    multilevel:
      cache-null-values: true
```

This applies to ordinary `@Cacheable`, `@Cacheable(sync=true)`, and synchronous Spring Cache
operations. A cached null is a hit: `get(key)` returns a non-null wrapper containing null, typed
and loader reads return null, and `putIfAbsent` preserves the existing entry. Null entries use the
same expiration, capacity, and invalidation rules as other values, including during Redis outages.

The local tier stores Spring's internal null sentinel; Redis uses Spring RedisCache's existing
binary null representation. Null entries bypass the configured value serializer. Serialization
of ordinary values and the invalidation message format are unchanged. Upgraded readers recognize
stored null markers even with null caching disabled and treat those entries as misses.

Before enabling this setting, upgrade every reader sharing the Redis cache namespace to a release
with this support. Released `4.1.1.0` through `4.1.1.2` readers are incompatible: JSON and Fory
readers throw on the marker, JDK readers recompute, and String readers can return an incorrect
value. `4.1.0.0` recognizes the marker but can expose the internal sentinel on a warm loader read;
its null loader path can also have written markers before this feature. Check other applications
using standard RedisCache or reading the same keys directly. Keep a common null-caching policy
across a namespace: disabled readers can recompute and overwrite enabled readers' cached nulls.

To disable the feature, restart upgraded instances with the setting off. Persisted null entries
remain until overwritten, evicted, or expired. Before rolling back to an incompatible reader,
stop every null writer and clear the affected Redis cache namespace and local tiers, or verify
that all null entries have expired. Waiting requires a known finite remaining Redis TTL after
the last possible null write; entries with no expiration have no finite waiting guarantee.
Choosing a distinct `key-prefix` with `use-key-prefix=true` can isolate null-enabled entries when
older readers must coexist. Both active namespaces still need source-data invalidation.

### Invalidation message compatibility

Invalidation messaging will move to the stable JSON format over several releases so rolling
upgrades do not silently leave stale L1 entries:

1. Version `4.1.1.1` publishes both the stable v0 JSON message and, when different, the configured
   legacy representation. It accepts both formats. This compatibility release allows every
   application instance to learn the stable format while older instances still receive messages
   they can decode.
2. A later release will publish only the stable JSON message while continuing to accept both stable
   and legacy messages. Before adopting that release, complete a rollout through `4.1.1.1` (or
   another dual-publication release) on every instance that shares the invalidation topic.
3. Legacy message decoding will be removed only in a subsequent release after the stable-only
   publication transition has had a full compatibility window.

Cache-value serialization is independent of this transition and remains controlled by the
configured Redis serializer.

JSON invalidation messages are decoded using the fixed message schema, including legacy JSON
without `@class` metadata. If metadata is present, it must identify `MultiLevelCacheEvictMessage`.
The `cacheName` and `senderId` fields must be strings; `entryKey` may be a string, null, or omitted
for cache-wide invalidation. Non-JSON legacy payloads still use the configured value serializer.

### Suitable for

- Microservices working with immutable cached entities under low latency requirements
    - The goal is to not only reduce the number of calls to external service but also reduce the number of calls to
      Redis

### Not a good fit for

- Mutable cached entities
- Entities with short time to live (< 5 minutes)
- Cases when entities in local cache **must** outlive entities in distributed cache
    - Consider using only local cache instead
- Cases when all calls to Redis must be synchronized with distributed locks

## Ideas

- Use well-known Spring primitives for implementation
- Microservices environment needs to fit the requirement of fault tolerance:
    - Redis calls covered by [Resilience4j Circuit Breaker](https://resilience4j.readme.io/docs/circuitbreaker) which
      allows falling back to use local cache at the cost of increased latency and more calls to external services.
- Redis TTL behaves similar to `expireAfterWrite` in Caffeine which allows us to set randomized expiry time for local
  cache:
    - This is useful to ensure that local cache entries will expire earlier for a higher chance to hit Redis instead of
      performing external call.
    - This also implicitly reduces the load on the Redis by spreading calls to it over time.
    - In the case of Redis connection errors, randomized expiry and Circuit Breaker will help to
      mitigate [thundering herd problem](https://en.wikipedia.org/wiki/Thundering_herd_problem).
- Expiry randomization follows the rule: `(time-to-live / 2) * (1 ± ((expiry-jitter / 100) * RNG(0, 1)))`, for example:
    - If `spring.cache.multilevel.time-to-live` is `1h`
    - And `spring.cache.multilevel.local.expiry-jitter` is `50` (percents)
    - Then entries in local cache will expire in approximately `15-45m`:

```
(1h / 2) * (1 ± ((50 / 100) * RNG(0, 1))) ->
30m * (1 ± MAXRNG(0.5)) ->
30m * RANGE(0.5, 1.5) ->
15-45m
```

## Configuration options

| Property                                        | Default                  | Notes                                                                                               |
|-------------------------------------------------|--------------------------|-----------------------------------------------------------------------------------------------------|
| `spring.cache.multilevel.time-to-live`          | `1h`                     | TTL applied to Redis entries; local cache derives its randomized expiry from here unless overridden |
| `spring.cache.multilevel.cache-null-values`     | `false`                  | Cache null results in both tiers; upgrade all readers before enabling                               |
| `spring.cache.multilevel.use-key-prefix`        | `false`                  | Enables `key-prefix`; set to `true` only when you supply a non-empty prefix                         |
| `spring.cache.multilevel.key-prefix`            | `""`                     | Optional Redis key prefix                                                                           |
| `spring.cache.multilevel.topic`                 | `cache:multilevel:topic` | Redis Pub/Sub channel used to broadcast evictions                                                   |
| `spring.cache.multilevel.local.max-size`        | `2000`                   | Maximum number of entries retained in Caffeine                                                      |
| `spring.cache.multilevel.local.expiry-jitter`   | `50`                     | Percentage used to randomize the local TTL                                                          |
| `spring.cache.multilevel.local.expiration-mode` | `after-create`           | One of `after-create`, `after-update`, `after-read`                                                 |
| `spring.cache.multilevel.local.time-to-live`    | empty                    | Optional dedicated TTL for the local cache                                                          |
| `spring.cache.multilevel.circuit-breaker.*`     | see YAML                 | Passed directly to Resilience4j’s circuit breaker builder                                           |

## Default configuration

```yaml
spring:
  data:
    redis:
      host: ${HOST:localhost}
      port: ${PORT:6379}
  cache:
    type: redis

    # These properties are custom
    multilevel:
      # Redis properties
      time-to-live: 1h
      cache-null-values: false
      use-key-prefix: false
      key-prefix: ""
      topic: "cache:multilevel:topic"
      # Local Caffeine cache properties
      local:
        max-size: 2000
        expiry-jitter: 50
        expiration-mode: after-create
        # other valid values for expiration-mode: after-update, after-read
      # Resilience4j Circuit Breaker properties for Redis
      circuit-breaker:
        failure-rate-threshold: 25
        slow-call-rate-threshold: 25
        slow-call-duration-threshold: 250ms
        sliding-window-type: count_based
        permitted-number-of-calls-in-half-open-state: 20
        max-wait-duration-in-half-open-state: 5s
        sliding-window-size: 40
        minimum-number-of-calls: 10
        wait-duration-in-open-state: 2500ms
```

## Honorable mentions

- [Circuit Breaker Redis Cache by gee4vee](https://github.com/gee4vee/circuit-breaker-redis-cache)
- [Multilevel cache Spring Boot starter by pig777](https://github.com/pig-mesh/multilevel-cache-spring-boot-starter)

## Contributing

Pull requests are welcome. Before submitting, please run:

```bash
./gradlew spotlessApply check test
./gradlew jmh
```

The complete five-fork benchmark suite covers the operational cache API, invalidation, and
synchronized contention waves, and typically takes 25–30 minutes on a developer workstation.
