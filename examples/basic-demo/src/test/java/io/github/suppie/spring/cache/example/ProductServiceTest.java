package io.github.suppie.spring.cache.example;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ProductServiceTest.TestConfig.class)
class ProductServiceTest {

  @Configuration
  @EnableCaching
  static class TestConfig {

    @Bean
    ProductService productService() {
      return new ProductService();
    }

    @Bean
    CacheManager cacheManager() {
      return new ConcurrentMapCacheManager("products");
    }
  }

  @Autowired private ProductService productService;

  @Test
  void cachesValueAfterFirstLoad() {
    AtomicInteger loads = (AtomicInteger) ReflectionTestUtils.getField(productService, "loads");
    Assertions.assertNotNull(loads);
    loads.set(0);

    ProductService.Product first = productService.getProduct("42");
    Assertions.assertEquals(1, loads.get(), "First lookup must load the value");

    ProductService.Product second = productService.getProduct("42");
    Assertions.assertEquals(1, loads.get(), "Cached lookup must not hit loader again");
    Assertions.assertSame(first, second, "Cached value must be reused");
  }
}
