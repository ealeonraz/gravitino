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

import com.google.common.base.Preconditions;
import org.apache.gravitino.NameIdentifier;

/**
 * Builds the Redis key strings and lexicographic range bounds used by the Redis entity cache.
 *
 * <p>Keyspace layout, where {@code ns} is the configured key namespace and {@code metalake} is the
 * first name level of the entity's identifier:
 *
 * <ul>
 *   <li>{@code ns:{metalake}:D:identifier:TYPE} &mdash; a cached entity value
 *   <li>{@code ns:{metalake}:F:identifier} &mdash; a version fence for a container
 *   <li>{@code ns:{metalake}:IDX} &mdash; a ZSet indexing the cached keys of the metalake; every
 *       member is an {@link EntityCacheKey#toString()} string scored 0, so members order
 *       lexicographically
 * </ul>
 *
 * <p>The braces around the metalake name are a Redis Cluster hash tag: only the braced substring is
 * hashed to pick the slot, so every key of one metalake lands in one slot and multi-key Lua scripts
 * over them are legal under Cluster ({@code CROSSSLOT}-safe). The cost is that one metalake's cache
 * is bounded by a single node; that trade is documented in the design doc for #12020.
 *
 * <p>Hierarchical operations (dropping a container with its descendants) locate descendants with
 * {@code ZRANGEBYLEX} over the index. Redis has no starts-with operator, so a prefix scan is
 * expressed as the range {@code [prefix} (inclusive) to {@code (prefix'} (exclusive), where {@code
 * prefix'} is the prefix with its final character incremented: every string starting with the
 * prefix sorts below it, and every other string sorts outside the range. The prefix always ends
 * with a child boundary (the {@code "."} name-level boundary, or the configured hierarchical schema
 * separator), never with a bare identifier: scanning {@code ml.cat.sales} would also match the
 * unrelated sibling {@code ml.cat.sales_archive}, while {@code ml.cat.sales.} cannot.
 */
public final class RedisCacheKeys {

  /** Separates {@link NameIdentifier} levels; a child identifier continues its parent with it. */
  private static final String NAME_LEVEL_BOUNDARY = ".";

  private static final String DATA_SEGMENT = ":D:";
  private static final String FENCE_SEGMENT = ":F:";
  private static final String INDEX_SEGMENT = ":IDX";

  private RedisCacheKeys() {}

  /**
   * Returns the metalake name of the given identifier, its first name level. This is the Redis
   * Cluster hash tag for every key derived from the identifier.
   *
   * @param ident The identifier of the entity.
   * @return The metalake name the identifier belongs to.
   */
  public static String metalakeOf(NameIdentifier ident) {
    Preconditions.checkArgument(ident != null, "ident cannot be null");

    return ident.namespace().isEmpty() ? ident.name() : ident.namespace().level(0);
  }

  /**
   * Returns the Redis key holding the cached value of the given entity cache key.
   *
   * @param ns The configured key namespace.
   * @param key The entity cache key.
   * @return The Redis key of the cached entity value.
   */
  public static String valueKey(String ns, EntityCacheKey key) {
    checkNamespace(ns);
    Preconditions.checkArgument(key != null, "key cannot be null");

    return ns + ":{" + metalakeOf(key.identifier()) + "}" + DATA_SEGMENT + key;
  }

  /**
   * Returns the Redis key holding the version fence of the given container identifier. A fence
   * stores the version at which the container was last dropped or renamed; cached values under it
   * with an older version are treated as invalid.
   *
   * @param ns The configured key namespace.
   * @param ident The identifier of the container.
   * @return The Redis key of the container's version fence.
   */
  public static String fenceKey(String ns, NameIdentifier ident) {
    checkNamespace(ns);
    Preconditions.checkArgument(ident != null, "ident cannot be null");

    return ns + ":{" + metalakeOf(ident) + "}" + FENCE_SEGMENT + ident;
  }

  /**
   * Returns the Redis key of the index ZSet for the given metalake.
   *
   * @param ns The configured key namespace.
   * @param metalake The metalake name.
   * @return The Redis key of the metalake's index ZSet.
   */
  public static String indexKey(String ns, String metalake) {
    checkNamespace(ns);
    Preconditions.checkArgument(
        metalake != null && !metalake.isEmpty(), "metalake cannot be null or empty");

    return ns + ":{" + metalake + "}" + INDEX_SEGMENT;
  }

  /**
   * Returns the index-member prefix shared by every descendant of the given identifier at any
   * depth. The trailing name-level boundary is what keeps a later range scan exact: it can never
   * match a sibling whose name merely starts with this identifier's name.
   *
   * @param ident The identifier of the container.
   * @return The prefix of every descendant's index member.
   */
  public static String childPrefix(NameIdentifier ident) {
    Preconditions.checkArgument(ident != null, "ident cannot be null");

    return ident + NAME_LEVEL_BOUNDARY;
  }

  /**
   * Returns the index-member prefix shared by the nested-schema descendants of the given schema
   * identifier. Nested {@code HierarchicalSchema} levels are joined by the configured external
   * separator <em>inside</em> a single name level, so they continue their parent with the separator
   * rather than with the {@code "."} boundary and need their own prefix scan.
   *
   * @param ident The identifier of the schema.
   * @param separator The configured external hierarchical schema separator.
   * @return The prefix of every nested-schema descendant's index member.
   */
  public static String schemaChildPrefix(NameIdentifier ident, String separator) {
    Preconditions.checkArgument(ident != null, "ident cannot be null");
    Preconditions.checkArgument(
        separator != null && !separator.isEmpty(), "separator cannot be null or empty");

    return ident + separator;
  }

  /**
   * Returns the inclusive {@code ZRANGEBYLEX} lower bound matching every member that starts with
   * the given prefix.
   *
   * @param prefix The member prefix, ending with a child boundary.
   * @return The inclusive lower bound.
   */
  public static String rangeMin(String prefix) {
    checkPrefix(prefix);

    return "[" + prefix;
  }

  /**
   * Returns the exclusive {@code ZRANGEBYLEX} upper bound matching every member that starts with
   * the given prefix: the prefix with its final character incremented by one. Any member starting
   * with the prefix compares below this bound at the incremented character, no matter what follows;
   * any other member falls outside the {@link #rangeMin(String)} bound instead.
   *
   * @param prefix The member prefix, ending with a child boundary.
   * @return The exclusive upper bound.
   */
  public static String rangeMax(String prefix) {
    checkPrefix(prefix);

    char last = prefix.charAt(prefix.length() - 1);
    return "(" + prefix.substring(0, prefix.length() - 1) + (char) (last + 1);
  }

  private static void checkNamespace(String ns) {
    Preconditions.checkArgument(ns != null && !ns.isEmpty(), "ns cannot be null or empty");
  }

  private static void checkPrefix(String prefix) {
    Preconditions.checkArgument(
        prefix != null && !prefix.isEmpty(), "prefix cannot be null or empty");
  }
}
