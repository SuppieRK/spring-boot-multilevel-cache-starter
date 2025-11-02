package io.github.suppie.spring.cache.example;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

  private static final Logger log = LoggerFactory.getLogger(ProductService.class);

  private final Map<String, Product> database = new ConcurrentHashMap<>();
  private final AtomicInteger loads = new AtomicInteger();

  @Cacheable(cacheNames = "products")
  public Product getProduct(String id) {
    int count = loads.incrementAndGet();
    log.info("Cache miss for product, loading entity (load #{})", count);

    try {
      TimeUnit.MILLISECONDS.sleep(250);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while loading product", ex);
    }

    return database.computeIfAbsent(id, key -> new Product(key, "Gadget " + key, Instant.now()));
  }

  public record Product(String id, String name, Instant lastUpdated) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
  }
}
