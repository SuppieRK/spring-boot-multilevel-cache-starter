# Contributing

When contributing to this repository, please first discuss the change you wish to make via issue,
email, or any other method with the owners of this repository before making a change.

Please note we have a code of conduct, please follow it in all your interactions with the project.

## Pull Request Process

1. Ensure any installation or build dependencies are removed before the end of the layer when doing a
   build.
2. Update the README.md with details of changes to the interface, this includes new environment
   variables, exposed ports, useful file locations and container parameters.
3. Increase the version numbers in any example files and the README.md to the new version that this
   Pull Request would represent. The first three numbers track the Spring version and the fourth
   number tracks the library release.
4. You may merge the Pull Request once it has the sign-off of at least one other developer.

## Guides

The following guides illustrate how to use some features concretely:

* [Caching Data with Spring](https://spring.io/guides/gs/caching/)
* [Messaging with Redis](https://spring.io/guides/gs/messaging-redis/)
* [Caffeine cache wiki](https://github.com/ben-manes/caffeine/wiki)
* [Resilience4J CircuitBreaker docs](https://resilience4j.readme.io/docs/circuitbreaker)

## Reference Documentation

For further reference, please consider the following sections:

### Build tools

The command typically used to build the project is:

```shell
./gradlew clean spotlessApply build
```

* [Official Gradle documentation](https://docs.gradle.org)

### Spring

* [Spring Boot Gradle Plugin Reference Guide](https://docs.spring.io/spring-boot/gradle-plugin/index.html)
* [Spring Boot caching](https://docs.spring.io/spring-boot/reference/io/caching.html)
* [Spring cache abstraction](https://docs.spring.io/spring-framework/reference/integration/cache.html)
* [Spring Data Redis cache](https://docs.spring.io/spring-data/redis/reference/redis/redis-cache.html)
* [Spring configuration metadata](https://docs.spring.io/spring-boot/specification/configuration-metadata/index.html)

### Third-party libraries

* [Caffeine](https://github.com/ben-manes/caffeine)
* [Resilience4J](https://resilience4j.readme.io/docs/getting-started)

### Additional test libraries

* [Testcontainers](https://java.testcontainers.org/)

### Setting up test environment

- You need typical Spring Boot project for testing with Spring Cache / Spring Web for invoking something to trigger the cache.
- Spin up local Redis instance by using
```shell
docker run -d --name redis -p 6379:6379 redis:7.2.4-alpine
```
- Spin up Redis Insight dashboard to observe Redis state by using:
```shell
docker run -d --name redisinsight -p 5540:5540 redis/redisinsight:latest -v redisinsight:/data
```
- Redis Insight is typically available at http://localhost:5540. Use the mapped localhost Redis
  port for development and let the repository's Testcontainers tests manage their own Redis.
