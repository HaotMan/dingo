/*
 * Copyright 2021 DataCanvas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dingodb.server.executor.schedule;

import io.dingodb.calcite.operation.ShowLocksOperation;
import io.dingodb.cluster.ClusterService;
import io.dingodb.common.codec.PrimitiveCodec;
import io.dingodb.common.concurrent.Executors;
import io.dingodb.common.config.DingoConfiguration;
import io.dingodb.common.util.Optional;
import io.dingodb.net.api.ApiRegistry;
import io.dingodb.sdk.service.IndexService;
import io.dingodb.sdk.service.LockService;
import io.dingodb.sdk.service.Services;
import io.dingodb.sdk.service.StoreService;
import io.dingodb.sdk.service.entity.common.Location;
import io.dingodb.sdk.service.entity.common.Region;
import io.dingodb.sdk.service.entity.common.RegionType;
import io.dingodb.sdk.service.entity.coordinator.GetRegionMapRequest;
import io.dingodb.sdk.service.entity.coordinator.UpdateGCSafePointRequest;
import io.dingodb.sdk.service.entity.store.Action;
import io.dingodb.sdk.service.entity.store.LockInfo;
import io.dingodb.sdk.service.entity.store.TxnCheckTxnStatusRequest;
import io.dingodb.sdk.service.entity.store.TxnCheckTxnStatusResponse;
import io.dingodb.sdk.service.entity.store.TxnPessimisticRollbackRequest;
import io.dingodb.sdk.service.entity.store.TxnResolveLockRequest;
import io.dingodb.sdk.service.entity.store.TxnScanLockRequest;
import io.dingodb.sdk.service.entity.store.TxnScanLockResponse;
import io.dingodb.sdk.service.entity.version.Kv;
import io.dingodb.sdk.service.entity.version.RangeRequest;
import io.dingodb.sdk.service.entity.version.RangeResponse;
import io.dingodb.store.proxy.Configuration;
import io.dingodb.transaction.api.TableLock;
import io.dingodb.transaction.api.TableLockService;
import io.dingodb.tso.TsoService;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static io.dingodb.sdk.common.utils.ByteArrayUtils.toHex;
import static io.dingodb.sdk.service.entity.store.Action.LockNotExistRollback;
import static io.dingodb.sdk.service.entity.store.Action.TTLExpirePessimisticRollback;
import static io.dingodb.sdk.service.entity.store.Action.TTLExpireRollback;
import static io.dingodb.sdk.service.entity.store.Op.Lock;
import static io.dingodb.store.proxy.Configuration.coordinatorSet;
import static io.dingodb.transaction.api.LockType.ROW;
import static java.lang.Math.min;

@Slf4j
public final class SafePointUpdateTask {

    private static final String lockKey = "safe-point-update";
    private static final String disableKey = "safe-point-update-disable";
    public static final RangeRequest disableKeyReq = RangeRequest.builder().key(disableKey.getBytes()).build();
    private static final byte[] txnDurationKey = "txn-duration".getBytes();
    private static final long defaultTxnDuration = TimeUnit.DAYS.toMillis(7);
    private static final List<Action> pessimisticRollbackActions = Arrays.asList(
        LockNotExistRollback, TTLExpirePessimisticRollback, TTLExpireRollback
    );
    private static final LockService lockService = new LockService(lockKey, Configuration.coordinators());
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private SafePointUpdateTask() {
    }

    public static void run() {
        Executors.execute("safe-point-update", () -> {
            try {
                LockService.Lock lock = lockService.newLock();
                lock.lock();
                log.info("Start safe point update task.");
                ScheduledFuture<?> future = Executors.scheduleWithFixedDelay(
                    "safe-point-update", SafePointUpdateTask::safePointUpdate, 1, 600, TimeUnit.SECONDS
                );
                lock.watchDestroy().thenRun(() -> {
                    future.cancel(true);
                    run();
                });
            } catch (Exception e) {
                run();
            }
        });
    }

    private static void safePointUpdate() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            log.info("Run safe point update task.");
            Set<Location> coordinators = coordinatorSet();
            long reqTs = tso();
            long safeTs = safeTs(coordinators, reqTs);
            List<Region> regions = Services.coordinatorService(coordinators).getRegionMap(
                reqTs, GetRegionMapRequest.builder().build()
            ).getRegionmap().getRegions();
            log.info("Run safe point update task, current ts: {}, safe ts: {}", reqTs, safeTs);
            for (Region region : regions) {
                long regionId = region.getId();
                if (region.getDefinition().getRange().getStartKey()[0] != 't') {
                    continue;
                }
                log.info("Scan {} locks.", regionId);
                byte[] startKey = region.getDefinition().getRange().getStartKey();
                byte[] endKey = region.getDefinition().getRange().getEndKey();
                TxnScanLockResponse scanLockResponse;
                do {
                    log.info("Scan {} locks range: [{}, {}).", regionId, toHex(startKey), toHex(endKey));
                    TxnScanLockRequest req = TxnScanLockRequest.builder()
                        .startKey(startKey).endKey(endKey).maxTs(safeTs).limit(1024).build();
                    if (region.getRegionType() == RegionType.INDEX_REGION) {
                        scanLockResponse = indexRegionService(regionId).txnScanLock(reqTs, req);
                    } else {
                        scanLockResponse = storeRegionService(regionId).txnScanLock(reqTs, req);
                    }
                    if (scanLockResponse.getLocks() != null && !scanLockResponse.getLocks().isEmpty()) {
                        safeTs = resolveLock(safeTs, reqTs, scanLockResponse.getLocks(), coordinators, region);
                    }
                    if (scanLockResponse.isHasMore()) {
                        startKey = scanLockResponse.getEndKey();
                    } else {
                        break;
                    }
                } while (true);
            }

            log.info("Update safe point to: {}", safeTs);
            if (isDisable(reqTs)) {
                log.info("Safe point update task disabled, skip call coordinator.");
            }
            Services.coordinatorService(coordinators)
                .updateGCSafePoint(reqTs, UpdateGCSafePointRequest.builder().safePoint(safeTs - 1).build());
        } catch (Exception e) {
            log.error("Update safe point error, skip this run.", e);
            throw e;
        } finally {
            running.set(false);
        }
    }

    private static boolean isDisable(long reqTs) {
        return !Optional.mapOrGet(
            Services.versionService(coordinatorSet()).kvRange(reqTs, disableKeyReq),
            RangeResponse::getKvs,
            Collections::emptyList
        ).isEmpty();
    }

    private static StoreService storeRegionService(long regionId) {
        return Services.storeRegionService(Configuration.coordinatorSet(), regionId, 30);
    }

    private static IndexService indexRegionService(long regionId) {
        return Services.indexRegionService(Configuration.coordinatorSet(), regionId, 30);
    }

    private static long safeTs(Set<Location> coordinators, long requestId) {
        long safeTs;
        List<Kv> kvs = Services.versionService(coordinators).kvRange(
            requestId, RangeRequest.builder().key(txnDurationKey).build()
        ).getKvs();
        if (kvs != null && !kvs.isEmpty()) {
            safeTs = requestId - PrimitiveCodec.decodeLong(kvs.get(0).getKv().getValue());
        } else {
            TsoService tsoService = TsoService.getDefault();
            safeTs = tsoService.tso(tsoService.timestamp(requestId) - defaultTxnDuration);
        }
        long minLockTs = Stream.concat(
                TableLockService.getDefault().allTableLocks().stream(),
                ClusterService.getDefault().getComputingLocations().stream()
                    .filter($ -> !$.equals(DingoConfiguration.location()))
                    .map($ -> ApiRegistry.getDefault().proxy(ShowLocksOperation.Api.class, $))
                    .flatMap($ -> $.tableLocks().stream())
                    .filter($ -> $.getType() == ROW)
            ).mapToLong(TableLock::getLockTs).min().orElse(Long.MAX_VALUE);
        return Math.min(minLockTs, safeTs);
    }

    private static long tso() {
        return TsoService.getDefault().tso();
    }

    private static boolean pessimisticRollback(
        long reqTs, LockInfo lock, Set<Location> coordinators, Region region
    ) {
        log.info("Rollback pessimistic lock: {}, resolve ts: {}.", lock, reqTs);
        TxnPessimisticRollbackRequest req = TxnPessimisticRollbackRequest.builder()
            .startTs(lock.getLockTs())
            .forUpdateTs(lock.getForUpdateTs())
            .keys(Collections.singletonList(lock.getKey()))
            .build();
        if (region.getRegionType() == RegionType.INDEX_REGION) {
            return indexRegionService(region.getId()).txnPessimisticRollback(reqTs, req).getTxnResult() == null;
        } else {
            return storeRegionService(region.getId()).txnPessimisticRollback(reqTs, req).getTxnResult() == null;
        }
    }

    private static boolean resolve(
        long reqTs, LockInfo lock, long commitTs, Set<Location> coordinators, Region region
    ) {
        log.info("Resolve lock: {}, resolve ts: {}, commit ts: {}.", lock, reqTs, commitTs);
        TxnResolveLockRequest req = TxnResolveLockRequest.builder()
            .startTs(lock.getLockTs())
            .commitTs(commitTs)
            .keys(Collections.singletonList(lock.getKey()))
            .build();
        if (region.getRegionType() == RegionType.INDEX_REGION) {
            return indexRegionService(region.getId()).txnResolveLock(reqTs, req).getTxnResult() == null;
        } else {
            return storeRegionService(region.getId()).txnResolveLock(reqTs, req).getTxnResult() == null;
        }
    }

    private static TxnCheckTxnStatusResponse checkTxn(long safeTs, long reqTs, LockInfo lock) {
        log.info("Check lock: {}, check ts: {}.", lock, reqTs);
        return Services.storeRegionService(coordinatorSet(), lock.getPrimaryLock(), 30).txnCheckTxnStatus(
            reqTs,
            TxnCheckTxnStatusRequest
                .builder()
                .callerStartTs(safeTs)
                .currentTs(safeTs)
                .lockTs(lock.getLockTs())
                .primaryKey(lock.getPrimaryLock())
                .build()
        );
    }

    private static boolean isPessimisticRollbackStatus(LockInfo lock, Action action) {
        return lock.getLockType() == Lock && lock.getForUpdateTs() != 0 && pessimisticRollbackActions.contains(action);
    }

    private static boolean isResolveLockStatus(TxnCheckTxnStatusResponse res) {
        return res.getCommitTs() > 0 || (res.getLockTtl() == 0 && res.getCommitTs() == 0);
    }

    private static long resolveLock(
        long safeTs, long reqTs, List<LockInfo> locks, Set<Location> coordinators, Region region
    ) {
        long result = safeTs;
        for (LockInfo lock : locks) {
            TxnCheckTxnStatusResponse checkTxnRes = checkTxn(safeTs, reqTs, lock);
            if (checkTxnRes.getTxnResult() == null) {
                if (isPessimisticRollbackStatus(lock, checkTxnRes.getAction())) {
                    if (!pessimisticRollback(reqTs, lock, coordinators, region)) {
                        result = min(result, lock.getLockTs());
                    }
                } else if (isResolveLockStatus(checkTxnRes)) {
                    if (!resolve(reqTs, lock, checkTxnRes.getCommitTs(), coordinators, region)) {
                        result = min(result, lock.getLockTs());
                    }
                } else {
                    result = min(result, lock.getLockTs());
                }
            } else {
                result = min(result, lock.getLockTs());
            }
        }
        return result;
    }

}
