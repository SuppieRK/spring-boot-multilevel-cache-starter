[![codecov](https://codecov.io/gh/SuppieRK/spring-boot-multilevel-cache-starter/branch/master/graph/badge.svg?token=ABBXFLFW6O)](https://codecov.io/gh/SuppieRK/spring-boot-multilevel-cache-starter)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=SuppieRK_spring-boot-multilevel-cache-starter&metric=sqale_rating)](https://sonarcloud.io/dashboard?id=SuppieRK_spring-boot-multilevel-cache-starter)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=SuppieRK_spring-boot-multilevel-cache-starter&metric=reliability_rating)](https://sonarcloud.io/dashboard?id=SuppieRK_spring-boot-multilevel-cache-starter)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=SuppieRK_spring-boot-multilevel-cache-starter&metric=security_rating)](https://sonarcloud.io/dashboard?id=SuppieRK_spring-boot-multilevel-cache-starter)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=SuppieRK_spring-boot-multilevel-cache-starter&metric=bugs)](https://sonarcloud.io/dashboard?id=SuppieRK_spring-boot-multilevel-cache-starter)

# Spring Boot Multi-level cache starter

Opinionated version of multi-level caching for [Spring Boot](https://spring.io/projects/spring-boot) with [Redis](https://redis.io/) as L2 (remote) cache and [Caffeine](https://github.com/ben-manes/caffeine) as L1 (local) cache.

This version does not allow setting most of the local cache properties in favor of managing local cache expiry by itself.

## Usage
### Maven
```xml
<dependency>
  <groupId>io.github.suppierk</groupId>
  <artifactId>spring-boot-multilevel-cache-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

### Gradle
```groovy
implementation 'io.github.suppierk:spring-boot-multilevel-cache-starter:1.0.0'
```

## Ideas

- Multi-level cache is most suitable for entries that are immutable:
  - If entities must often change you should consider avoid caching or cache with the least possible expiry times.
  - If you need to have longer expiry time for local cache then distributed one, consider using just local cache instead.
- Redis TTL behaves similar to `expireAfterWrite` in Caffeine which allows us to set randomized expiry time for local cache:
  - This is useful to ensure that local cache entries will expire earlier and there will be a chance to hit Redis instead of performing external call.
  - This is also implicitly reduces the load on the Redis.
  - Expiry randomization follows the rule: `time-to-live * (1 ± ((local.expiry-jitter / 100) * RNG(0, 1))) / 2`, for example:
    - If `spring.cache.multilevel.time-to-live` is `1h`
    - And `spring.cache.multilevel.local.expiry-jitter` is `50` (percents)
    - Then entries in local cache will expire in `15-45m`:
```
1h * (1 ± ((50 / 100) * RNG(0, 1))) / 2
(1h / 2) * (1 ± MAXRNG(0.5))
30m * RANGE(0.5, 1.5)
15-45m
```
- Additionally, Redis calls covered by Circuit Breaker which allows falling back onto using local cache at the cost of slightly increased latency and more calls to external services.

## Default configuration

```yaml
spring:
  redis:
    host: ${HOST:localhost}
    port: ${PORT:6379}
  cache:
    type: redis
    multilevel:
      time-to-live: 1h
      use-key-prefix: false
      key-prefix: ""
      topic: "cache:multilevel:topic"
      local:
        max-size: 2000
        expiry-jitter: 50
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

## Inspired by

- [Circuit Breaker Redis Cache by gee4vee](https://github.com/gee4vee/circuit-breaker-redis-cache)
- [Multilevel cache Spring Boot starter by pig777](https://github.com/pig-mesh/multilevel-cache-spring-boot-starter)