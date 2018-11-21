package org.robolectric.util;

import java.util.LinkedHashMap;
import java.util.Map;

public class CacheMap<K,V> extends LinkedHashMap<K,V> {

  private final int maxSize;

  public CacheMap(int maxSize) {
    this.maxSize = maxSize;
  }

  @Override
  protected boolean removeEldestEntry(Map.Entry eldest) {
    return size() > maxSize;
  }
}
