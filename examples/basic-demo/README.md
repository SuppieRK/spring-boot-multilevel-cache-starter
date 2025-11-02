# Basic Demo

Simple Spring Boot application that showcases the multi-level cache starter with a REST endpoint.

## Prerequisites

- JDK 17+
- Docker (or any running Redis instance)

- Local Redis instance

```bash
docker compose up -d
```

## Run the example

```bash
./gradlew :examples:basic-demo:bootRun
```

Hit the endpoint a couple of times to see cache hits locally:

```bash
curl http://localhost:8080/products/42
```

The first request warms Redis and Caffeine; later calls are served from the local tier until the randomized TTL expires.
Watch the application logs—messages from `MultiLevelCache` show whether the value came from the local tier, Redis, or
the loader fallback.

To stop the demo:

```bash
docker compose down -v
```
