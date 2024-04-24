package io.github.suppie.spring.cache;

import com.github.benmanes.caffeine.cache.Expiry;

public enum LocalExpirationMode {
  /** See {@link Expiry#expireAfterCreate(Object, Object, long)} */
  AFTER_CREATE,
  /** See {@link Expiry#expireAfterUpdate(Object, Object, long, long)} */
  AFTER_UPDATE,
  /** See {@link Expiry#expireAfterRead(Object, Object, long, long)} */
  AFTER_READ
}
