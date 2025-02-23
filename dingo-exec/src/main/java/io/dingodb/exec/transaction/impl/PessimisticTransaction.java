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

package io.dingodb.exec.transaction.impl;

import io.dingodb.common.CommonId;
import io.dingodb.common.Location;
import io.dingodb.common.codec.PrimitiveCodec;
import io.dingodb.common.store.KeyValue;
import io.dingodb.exec.Services;
import io.dingodb.exec.base.JobManager;
import io.dingodb.exec.transaction.base.BaseTransaction;
import io.dingodb.exec.transaction.base.CacheToObject;
import io.dingodb.exec.transaction.base.TransactionStatus;
import io.dingodb.exec.transaction.base.TransactionType;
import io.dingodb.exec.transaction.base.TxnLocalData;
import io.dingodb.exec.transaction.util.TransactionCacheToMutation;
import io.dingodb.exec.transaction.util.TransactionUtil;
import io.dingodb.exec.transaction.visitor.DingoTransactionRenderJob;
import io.dingodb.exec.utils.ByteUtils;
import io.dingodb.meta.MetaService;
import io.dingodb.store.api.StoreInstance;
import io.dingodb.store.api.transaction.data.IsolationLevel;
import io.dingodb.store.api.transaction.data.Op;
import io.dingodb.store.api.transaction.data.prewrite.ForUpdateTsCheck;
import io.dingodb.store.api.transaction.data.prewrite.PessimisticCheck;
import io.dingodb.store.api.transaction.data.prewrite.TxnPreWrite;
import io.dingodb.store.api.transaction.exception.RegionSplitException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Future;

@Slf4j
public class PessimisticTransaction extends BaseTransaction {

    @Getter
    @Setter
    private long forUpdateTs = 0L;

    private byte[] primaryKeyLock;

    public PessimisticTransaction(long startTs, int isolationLevel) {
        super(startTs, isolationLevel);
    }

    public PessimisticTransaction(CommonId txnId, int isolationLevel) {
        super(txnId, isolationLevel);
    }

    @Override
    public void setPrimaryKeyFuture(Future future) {
        this.future = future;
    }

    @Override
    public TransactionType getType() {
        return TransactionType.PESSIMISTIC;
    }

    @Override
    public synchronized void rollBackPessimisticLock(JobManager jobManager) {
        long rollBackStart = System.currentTimeMillis();
        cache.setJobId(job.getJobId());
        if(!cache.checkPessimisticLockContinue()) {
            log.warn("{} The current {} has no data to rollBackPessimisticLock",txnId, transactionOf());
            return;
        }
        log.info("{} {} RollBackPessimisticLock Start", txnId, transactionOf());
        Location currentLocation = MetaService.root().currentLocation();
        CommonId jobId = CommonId.EMPTY_JOB;
        // for_update_ts
        long jobSeqId = job.getJobId().seq;
        this.setForUpdateTs(jobSeqId);
        try {
            // 1、get rollback_ts
            long rollBackTs = TransactionManager.nextTimestamp();
            // 2、generator job、task、rollBackPessimisticLockOperator
            job = jobManager.createJob(startTs, rollBackTs, txnId, null);
            jobId = job.getJobId();
            DingoTransactionRenderJob.renderRollBackPessimisticLockJob(job, currentLocation, this, true);
            // 3、run RollBackPessimisticLock
            Iterator<Object[]> iterator = jobManager.createIterator(job, null);
            while (iterator.hasNext()) {
                Object[] next = iterator.next();
            }
            this.status = TransactionStatus.ROLLBACK_PESSIMISTIC_LOCK;
        } catch (Throwable t) {
            log.info(t.getMessage(), t);
            this.status = TransactionStatus.ROLLBACK_PESSIMISTIC_LOCK_FAIL;
            throw new RuntimeException(t);
        } finally {
            this.status = TransactionStatus.START;
            log.info("{} {}  RollBackPessimisticLock End Status:{}, Cost:{}ms",
                txnId, transactionOf(), status, (System.currentTimeMillis() - rollBackStart));
            jobManager.removeJob(jobId);
            cleanPessimisticPrimaryLock();
        }
    }

    private void cleanPessimisticPrimaryLock() {
        if (primaryKeyLock != null && forUpdateTs != 0) {
            KeyValue keyValue = cache.get(primaryKeyLock);
            if (keyValue == null || keyValue.getValue() == null) {
                primaryKeyLock = null;
                forUpdateTs = 0L;
            }
        }
    }

    @Override
    public void rollBackPessimisticPrimaryLock(JobManager jobManager) {
        try {
            if (future != null) {
                future.cancel(true);
            }
            if (primaryKeyLock != null && forUpdateTs != 0) {
                Object[] objects = ByteUtils.decodePessimisticExtraKey(primaryKeyLock);
                TxnLocalData txnLocalData = (TxnLocalData) objects[0];
                CommonId tableId = txnLocalData.getTableId();
                CommonId newPartId = txnLocalData.getPartId();
                byte[] key = txnLocalData.getKey();
                cache.deletePrefix(primaryKeyLock);
                log.info("{} pessimisticPrimaryLockRollBack key:{}", txnId, Arrays.toString(key));
                TransactionUtil.pessimisticPrimaryLockRollBack(
                    txnId,
                    tableId,
                    newPartId,
                    isolationLevel,
                    startTs,
                    forUpdateTs,
                    key
                );
            }
        } catch (Throwable throwable) {
            log.error("rollBackPessimisticPrimaryLock exception:{}", throwable, throwable);
        } finally {
            future = null;
            primaryKeyLock = null;
            forUpdateTs = 0L;
        }
    }

    @Override
    public synchronized byte[] getPrimaryKeyLock() {
        return primaryKeyLock;
    }

    @Override
    public synchronized void setPrimaryKeyLock(byte[] primaryKeyLock) {
        if (this.primaryKeyLock == null) {
            this.primaryKeyLock = primaryKeyLock;
        }
    }

    public void rollBackResidualPessimisticLock(JobManager jobManager) {
        long rollBackStart = System.currentTimeMillis();
        if(!cache.checkResidualPessimisticLockContinue()) {
            log.warn("{} The current {} has no data to rollBackResidualPessimisticLock",txnId, transactionOf());
            return;
        }
        log.info("{} {} rollBackResidualPessimisticLock Start", txnId, transactionOf());
        Location currentLocation = MetaService.root().currentLocation();
        CommonId jobId = CommonId.EMPTY_JOB;
        try {
            // 1、get rollback_ts
            long rollBackTs = TransactionManager.nextTimestamp();
            // 2、generator job、task、rollBackResidualPessimisticLock
            job = jobManager.createJob(startTs, rollBackTs, txnId, null);
            jobId = job.getJobId();
            DingoTransactionRenderJob.renderRollBackResidualPessimisticLockJob(job, currentLocation, this, true);
            // 3、run rollBackResidualPessimisticLock
            Iterator<Object[]> iterator = jobManager.createIterator(job, null);
            while (iterator.hasNext()) {
                Object[] next = iterator.next();
            }
            this.status = TransactionStatus.ROLLBACK_RESIDUAL_PESSIMISTIC_LOCK;
        } catch (Throwable t) {
            log.info(t.getMessage(), t);
            this.status = TransactionStatus.ROLLBACK_RESIDUAL_PESSIMISTIC_LOCK_FAIL;
            throw new RuntimeException(t);
        } finally {
            log.info("{} {}  RollBackResidualPessimisticLock End Status:{}, Cost:{}ms",
                txnId, transactionOf(), status, (System.currentTimeMillis() - rollBackStart));
            jobManager.removeJob(jobId);
        }
    }

    @Override
    public void cleanUp(JobManager jobManager) {
        super.cleanUp(jobManager);
        // PessimisticRollback
    }

    public CacheToObject primaryLockTo() {
        Object[] objects = ByteUtils.decodePessimisticExtraKey(primaryKeyLock);
        TxnLocalData txnLocalData = (TxnLocalData) objects[0];
        CommonId.CommonType type = txnLocalData.getDataType();
        CommonId tableId = txnLocalData.getTableId();
        CommonId newPartId = txnLocalData.getPartId();
        int op = txnLocalData.getOp().getCode();
        byte[] key = txnLocalData.getKey();
        byte[] insertKey = ByteUtils.getKeyByOp(CommonId.CommonType.TXN_CACHE_DATA, Op.PUTIFABSENT, primaryKeyLock);
        byte[] deleteKey = Arrays.copyOf(insertKey, insertKey.length);
        deleteKey[deleteKey.length - 2] = (byte) Op.DELETE.getCode();
        byte[] updateKey = Arrays.copyOf(insertKey, insertKey.length);
        updateKey[updateKey.length - 2] = (byte) Op.PUT.getCode();
        byte[] noneKey  = ByteUtils.getKeyByOp(CommonId.CommonType.TXN_CACHE_RESIDUAL_LOCK, Op.DELETE, updateKey);
        List<byte[]> bytes = new ArrayList<>(4);
        bytes.add(insertKey);
        bytes.add(deleteKey);
        bytes.add(updateKey);
        bytes.add(noneKey);
        List<KeyValue> keyValues = cache.getKeys(bytes);
        cache.deletePrefix(noneKey);
        if (keyValues != null && keyValues.size() > 0) {
            if (keyValues.size() > 1) {
                throw new RuntimeException(txnId + " PrimaryKey is not existed than two in local store");
            }
            KeyValue value = keyValues.get(0);
            KeyValue keyValue = cache.get(primaryKeyLock);
            Long forUpdateTs = PrimitiveCodec.decodeLong(keyValue.getValue());
            return new CacheToObject(TransactionCacheToMutation.cacheToMutation(
                value.getKey()[value.getKey().length - 2],
                key,
                value.getValue(),
                forUpdateTs,
                tableId,
                newPartId), tableId, newPartId
            );
        } else {
            throw new RuntimeException(txnId + " PrimaryKey is not existed local store");
        }
    }
    @Override
    public CacheToObject preWritePrimaryKey() {
        // 1、get first key from cache
        CacheToObject cacheToObject = primaryLockTo();
        primaryKey = cacheToObject.getMutation().getKey();
        // 2、call sdk preWritePrimaryKey
        TxnPreWrite txnPreWrite = TxnPreWrite.builder()
            .isolationLevel(IsolationLevel.of(
                isolationLevel
            ))
            .mutations(Collections.singletonList(cacheToObject.getMutation()))
            .primaryLock(primaryKey)
            .startTs(startTs)
            .lockTtl(TransactionManager.lockTtlTm())
            .txnSize(1L)
            .tryOnePc(false)
            .maxCommitTs(0L)
            .pessimisticChecks(Collections.singletonList(PessimisticCheck.DO_PESSIMISTIC_CHECK))
            .forUpdateTsChecks(Collections.singletonList(new ForUpdateTsCheck(0,
                cacheToObject.getMutation().getForUpdateTs())
            ))
            .lockExtraDatas(
                TransactionUtil.toLockExtraDataList(
                cacheToObject.getTableId(),
                cacheToObject.getPartId(),
                txnId,
                TransactionType.PESSIMISTIC.getCode(),
                1))
            .build();
        try {
            StoreInstance store = Services.KV_STORE.getInstance(
                cacheToObject.getTableId(),
                cacheToObject.getPartId()
            );
            boolean result = store.txnPreWrite(txnPreWrite, getLockTimeOut());
            if (!result) {
                throw new RuntimeException(txnId + " " + cacheToObject.getPartId()
                    + ",preWritePrimaryKey false,PrimaryKey:" + primaryKey.toString());
            }
        } catch (RegionSplitException e) {
            log.error(e.getMessage(), e);
            CommonId regionId = TransactionUtil.singleKeySplitRegionId(
                cacheToObject.getTableId(),
                txnId,
                cacheToObject.getMutation().getKey()
            );
            StoreInstance store = Services.KV_STORE.getInstance(cacheToObject.getTableId(), regionId);
            boolean result = store.txnPreWrite(txnPreWrite, getLockTimeOut());
            if (!result) {
                throw new RuntimeException(txnId + " " + regionId + ",preWritePrimaryKey false,PrimaryKey:"
                    + primaryKey.toString());
            }
        }
        return cacheToObject;
    }

    @Override
    public void resolveWriteConflict(JobManager jobManager, Location currentLocation, RuntimeException e) {
        rollback(jobManager);
        throw e;
    }
}
