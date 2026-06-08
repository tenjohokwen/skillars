/**
 * Public API contract for the Video module.
 *
 * <h2>Integration Boundaries</h2>
 * <ol>
 *   <li>{@code teamId} is intentionally absent from the {@code Video} entity.
 *       Consuming applications own the linkage between teams and videos.</li>
 *   <li>{@code Visibility} is stored by this module but NOT enforced here.
 *       Consuming applications must gate access based on Visibility in their own layer.</li>
 *   <li>End-user REST controllers for video operations (browse, stream, etc.) are the
 *       consuming application's responsibility. This module exposes only internal service
 *       APIs and the integration contract types defined in this package.</li>
 *   <li>{@code QuotaProvider} integration contract:
 *       <ol>
 *           <li>Implementations are responsible for concurrent-safe quota enforcement.</li>
 *           <li>The module orchestrates the check → reserve → commit/release sequence.</li>
 *           <li>All {@code QuotaProvider} implementations must be idempotent (e.g., state-based transition).</li>
 *           <li>Implementations must enforce a reservation TTL and provide a mechanism (e.g., reaper) to prune orphaned reservations.</li>
 *       </ol>
 *   </li>
 * </ol>
 */
package com.softropic.skillars.platform.video.contract;
