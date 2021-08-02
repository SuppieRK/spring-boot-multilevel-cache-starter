[![codecov](https://codecov.io/gh/SuppieRK/spring-boot-multilevel-cache-starter/branch/master/graph/badge.svg?token=ABBXFLFW6O)](https://codecov.io/gh/SuppieRK/spring-boot-multilevel-cache-starter)
[![Total alerts](https://img.shields.io/lgtm/alerts/g/SuppieRK/spring-boot-multilevel-cache-starter.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/SuppieRK/spring-boot-multilevel-cache-starter/alerts/)
[![Language grade: Java](https://img.shields.io/lgtm/grade/java/g/SuppieRK/spring-boot-multilevel-cache-starter.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/SuppieRK/spring-boot-multilevel-cache-starter/context:java)

# Spring Boot Multi-level cache starter

Opinionated version of multi-level caching for [Spring Boot](https://spring.io/projects/spring-boot) with [Redis](https://redis.io/) as L2 (remote) cache and [Caffeine](https://github.com/ben-manes/caffeine) as L1 (local) cache.

This version does not allow setting most of the local cache properties in favor of managing local cache expiry by itself.

## Ideas

- Multi-level cache is most suitable for entries that are immutable:
  - If entities must often change you should consider avoid caching or cache with the least possible expiry times.
  - If you need to have longer expiry time for local cache then distributed one, consider using just local cache instead.
- Redis TTL behaves similar to `expireAfterWrite` in Caffeine:
  - To reduce the load and distribute load over time `expireAfterWrite` is randomized as `±spring.cache.multilevel.local.expiry-jitter * spring.cache.multilevel.time-to-live * 100 / 200`
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