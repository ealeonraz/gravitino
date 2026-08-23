/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.gravitino.cache;

import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestRedisCacheKeys {

  private static final String NS = "gravitino";

  @Test
  void testValueKey() {
    EntityCacheKey key =
        EntityCacheKey.of(
            NameIdentifier.of("ml", "cat", "sales", "orders"), Entity.EntityType.TABLE);

    Assertions.assertEquals(
        "gravitino:{ml}:D:ml.cat.sales.orders:TABLE", RedisCacheKeys.valueKey(NS, key));
  }

  @Test
  void testFenceKey() {
    Assertions.assertEquals(
        "gravitino:{ml}:F:ml.cat", RedisCacheKeys.fenceKey(NS, NameIdentifier.of("ml", "cat")));
  }

  @Test
  void testIndexKey() {
    Assertions.assertEquals("gravitino:{ml}:IDX", RedisCacheKeys.indexKey(NS, "ml"));
  }

  @Test
  void testMetalakeOfRootIdentifier() {
    // A metalake identifier has an empty namespace; its own name is the hash tag.
    Assertions.assertEquals("ml", RedisCacheKeys.metalakeOf(NameIdentifier.of("ml")));
  }

  @Test
  void testMetalakeOfNestedIdentifier() {
    Assertions.assertEquals(
        "ml", RedisCacheKeys.metalakeOf(NameIdentifier.of("ml", "cat", "sales", "orders")));
  }

  @Test
  void testRangeMaxIncrementsLastByte() {
    // '.' is 46, '/' is 47: the smallest string that no descendant key can reach.
    Assertions.assertEquals("(ml.cat.sales/", RedisCacheKeys.rangeMax("ml.cat.sales."));
  }

  @Test
  void testRangeRejectsEmptyPrefix() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> RedisCacheKeys.rangeMin(""));
    Assertions.assertThrows(IllegalArgumentException.class, () -> RedisCacheKeys.rangeMax(""));
  }

  @Test
  void testChildRangeIncludesDescendants() {
    String prefix = RedisCacheKeys.childPrefix(NameIdentifier.of("ml", "cat", "sales"));
    String min = RedisCacheKeys.rangeMin(prefix);
    String max = RedisCacheKeys.rangeMax(prefix);

    Assertions.assertTrue(inRange("ml.cat.sales.orders:TABLE", min, max));
    Assertions.assertTrue(inRange("ml.cat.sales.orders_2024:TABLE", min, max));
    // Depth does not matter: the whole-string match collects grandchildren in the same scan.
    Assertions.assertTrue(inRange("ml.cat.sales.orders.col1:COLUMN", min, max));
  }

  @Test
  void testChildRangeExcludesSiblingsAndSelf() {
    String prefix = RedisCacheKeys.childPrefix(NameIdentifier.of("ml", "cat", "sales"));
    String min = RedisCacheKeys.rangeMin(prefix);
    String max = RedisCacheKeys.rangeMax(prefix);

    // The container's own key sorts after its children (':' = 58 > '.' = 46) and is deleted
    // directly by the drop, which knows the exact key; the range must not need to cover it.
    Assertions.assertFalse(inRange("ml.cat.sales:SCHEMA", min, max));
    // Unrelated siblings sharing the name prefix, on both sides of the boundary bytes:
    // '-' = 45 sorts below the lower bound, '/' = 47 and '_' = 95 at or above the upper bound.
    Assertions.assertFalse(inRange("ml.cat.sales-old:SCHEMA", min, max));
    Assertions.assertFalse(inRange("ml.cat.sales/eu:SCHEMA", min, max));
    Assertions.assertFalse(inRange("ml.cat.sales_archive:SCHEMA", min, max));
  }

  @Test
  void testSchemaSeparatorRange() {
    NameIdentifier schema = NameIdentifier.of("ml", "cat", "raw:events");
    String prefix = RedisCacheKeys.schemaChildPrefix(schema, ":");
    String min = RedisCacheKeys.rangeMin(prefix);
    String max = RedisCacheKeys.rangeMax(prefix);

    Assertions.assertEquals("[ml.cat.raw:events:", min);
    Assertions.assertEquals("(ml.cat.raw:events;", max);

    // Nested schema levels continue the parent with the separator, not with '.'.
    Assertions.assertTrue(inRange("ml.cat.raw:events:2024:SCHEMA", min, max));
    // Entities under a nested schema are collected by the same scan, at any depth.
    Assertions.assertTrue(inRange("ml.cat.raw:events:2024.orders:TABLE", min, max));
    // A sibling schema whose name merely extends the parent's name is out.
    Assertions.assertFalse(inRange("ml.cat.raw:events2:SCHEMA", min, max));
    // Intentional: the parent's own key matches this range, because its ':TYPE' suffix begins
    // with the separator byte. That is harmless; the drop removes the parent key anyway.
    Assertions.assertTrue(inRange("ml.cat.raw:events:SCHEMA", min, max));
  }

  /** Applies ZRANGEBYLEX semantics: '[' bound is inclusive, '(' bound is exclusive. */
  private boolean inRange(String member, String min, String max) {
    String lower = min.substring(1);
    String upper = max.substring(1);

    return member.compareTo(lower) >= 0 && member.compareTo(upper) < 0;
  }
}
