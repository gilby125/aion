/*******************************************************************************
 * Copyright (c) 2017-2018 Aion foundation.
 *
 *     This file is part of the aion network project.
 *
 *     The aion network project is free software: you can redistribute it
 *     and/or modify it under the terms of the GNU General Public License
 *     as published by the Free Software Foundation, either version 3 of
 *     the License, or any later version.
 *
 *     The aion network project is distributed in the hope that it will
 *     be useful, but WITHOUT ANY WARRANTY; without even the implied
 *     warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *     See the GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with the aion network project source files.
 *     If not, see <https://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     Aion foundation.
 *     
 ******************************************************************************/

package org.aion.txpool.common;

import org.aion.base.type.Address;
import org.aion.base.type.ITransaction;
import org.aion.base.util.ByteArrayWrapper;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.slf4j.Logger;
import org.spongycastle.pqc.math.linearalgebra.ByteUtils;

import java.math.BigInteger;
import java.util.AbstractMap.SimpleEntry;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AbstractTxPool<TX extends ITransaction> {

    protected static final AtomicLong blkNrgLimit = new AtomicLong(10_000_000L);
    protected static final int multiplyM = 1_000_000;
    protected static final Logger LOG = AionLoggerFactory.getLogger(LogEnum.TXPOOL.toString());
    protected static final int SEQUENTAILTXNCOUNT_MAX = 25;
    protected static long txn_timeout = 86_400; // 1 day by second
    protected static int blkSizeLimit = 16_000_000; // 16MB
    protected final long TXN_TIMEOUT_MIN = 10; // 10s
    protected final long TXN_TIMEOUT_MAX = 86_400; // 1 day
    protected final int BLK_SIZE_MAX = 16_000_000; // 16MB
    protected final int BLK_SIZE_MIN = 1_000_000; // 1MB
    protected final long BLK_NRG_MAX = 50_000_000;
    protected final long BLK_NRG_MIN = 1_000_000;
    /**
     * mainMap : Map<ByteArrayWrapper, TXState>
     *
     * @ByteArrayWrapper transaction hash
     * @TXState transaction data and sort status
     */
    // TODO : should limit size
    private final Map<ByteArrayWrapper, TXState> mainMap = new ConcurrentHashMap<>();
    /**
     * timeView : SortedMap<Long, LinkedHashSet<ByteArrayWrapper>>
     *
     * @Long transaction timestamp
     * @LinkedHashSet<ByteArrayWrapper> the hashSet of the transaction hash*
     */
    private final SortedMap<Long, LinkedHashSet<ByteArrayWrapper>> timeView = Collections
            .synchronizedSortedMap(new TreeMap<>());
    /**
     * feeView : SortedMap<BigInteger,
     * LinkedHashSet<TxPoolList<ByteArrayWrapper>>>
     *
     * @BigInteger energy cost = energy consumption * energy price
     * @LinkedHashSet<TxPoolList<ByteArrayWrapper>> the TxPoolList of the first
     *                                              transaction hash
     */
    private final SortedMap<BigInteger, Map<ByteArrayWrapper, TxDependList<ByteArrayWrapper>>> feeView = Collections
            .synchronizedSortedMap(new TreeMap<>(Collections.reverseOrder()));
    /**
     * accountView : Map<ByteArrayWrapper, AccountState>
     *
     * @ByteArrayWrapper account address
     * @AccountState
     */
    private final Map<Address, AccountState> accountView = new ConcurrentHashMap<>();
    /**
     * poolStateView : Map<ByteArrayWrapper, List<PoolState>>
     *
     * @ByteArrayWrapper account address
     * @PoolState continuous transaction state including starting nonce
     */
    private final Map<Address, List<PoolState>> poolStateView = new ConcurrentHashMap<>();
    private final List<TX> outDated = new ArrayList<>();

    public abstract List<TX> add(List<TX> txl);

    public abstract boolean add(TX tx);

    public abstract List<TX> remove(List<TX> txl);

    public abstract int size();

    public abstract List<TX> snapshot();

    public abstract Map.Entry<BigInteger, BigInteger> bestNonceSet(Address address);

    protected Map<ByteArrayWrapper, TXState> getMainMap() {
        return this.mainMap;
    }

    protected SortedMap<BigInteger, Map<ByteArrayWrapper, TxDependList<ByteArrayWrapper>>> getFeeView() {
        return this.feeView;
    }

    protected AccountState getAccView(Address acc) {

        this.accountView.computeIfAbsent(acc, k -> new AccountState());
        return this.accountView.get(acc);
    }

    protected List<PoolState> getPoolStateView(Address acc) {

        if (this.accountView.get(acc) == null) {
            this.poolStateView.put(acc, new LinkedList<>());
        }
        return this.poolStateView.get(acc);
    }

    protected synchronized List<TX> getOutdatedListImpl() {
        List<TX> rtn = new ArrayList<>(this.outDated);
        this.outDated.clear();

        return rtn;
    }

    protected synchronized void addOutDatedList(List<TX> txl) {
        this.outDated.addAll(txl);
    }

    public synchronized void clear() {
        this.mainMap.clear();
        this.timeView.clear();
        this.feeView.clear();
        this.accountView.clear();
        this.poolStateView.clear();
        this.outDated.clear();
    }

    protected void sortTxn() {

        Map<Address, Map<BigInteger, SimpleEntry<ByteArrayWrapper, BigInteger>>> accMap = new HashMap<>();
        SortedMap<Long, LinkedHashSet<ByteArrayWrapper>> timeMap = new TreeMap<>();

        this.mainMap.entrySet().parallelStream().forEach(e -> {
            TXState ts = e.getValue();

            if (ts.sorted()) {
                return;
            }

            ITransaction tx = ts.getTx();

            // Gen temp timeMap
            LinkedHashSet<ByteArrayWrapper> lhs = new LinkedHashSet<>();
            long timestamp = new BigInteger(1, tx.getTimeStamp()).longValue()/ multiplyM;

            synchronized (timeMap) {
                if (timeMap.get(timestamp) != null) {
                    lhs = timeMap.get(timestamp);
                }

                lhs.add(e.getKey());

                if (LOG.isTraceEnabled()) {
                    LOG.trace("AbstractTxPool.sortTxn Put txHash into timeMap: ts:[{}] size:[{}]", timestamp, lhs.size());
                }

                timeMap.put(timestamp, lhs);
            }

            Map<BigInteger, SimpleEntry<ByteArrayWrapper, BigInteger>> nonceMap;

            synchronized (accMap) {
                if (accMap.get(tx.getFrom()) != null) {
                    nonceMap = accMap.get(tx.getFrom());
                } else {
                    nonceMap = Collections.synchronizedSortedMap(new TreeMap<>());
                }

                // considering refactor later
                BigInteger nonce = new BigInteger(tx.getNonce());

                BigInteger nrgCharge = BigInteger.valueOf(tx.getNrgPrice())
                        .multiply(BigInteger.valueOf(tx.getNrgConsume()));

                if (LOG.isTraceEnabled()) {
                    LOG.trace("AbstractTxPool.sortTxn Put tx into nonceMap: nonce:[{}] ts:[{}] nrgCharge:[{}]", nonce,
                            ByteUtils.toHexString(e.getKey().getData()), nrgCharge.toString());
                }

                nonceMap.put(nonce, new SimpleEntry<>(e.getKey(), nrgCharge));

                if (LOG.isTraceEnabled()) {
                    LOG.trace("AbstractTxPool.sortTxn Put tx into accMap: acc:[{}] mapsize[{}] ", tx.getFrom().toString(), nonceMap.size());
                }

                accMap.put(tx.getFrom(), nonceMap);
            }
            ts.setSorted();
        });

        if (accMap.size() > 0) {

            timeMap.entrySet().parallelStream().forEach(e -> {
                if (this.timeView.get(e.getKey()) == null) {
                    this.timeView.put(e.getKey(), e.getValue());
                } else {
                    Set<ByteArrayWrapper> lhs = this.getTimeView().get(e.getKey());
                    lhs.addAll(e.getValue());

                    this.timeView.put(e.getKey(), (LinkedHashSet<ByteArrayWrapper>) lhs);
                }
            });

            accMap.entrySet().parallelStream().forEach(e -> {
                this.accountView.computeIfAbsent(e.getKey(), k -> new AccountState());

                this.accountView.get(e.getKey()).updateMap(e.getValue());
            });

            updateAccPoolState();
            updateFeeMap();
        }
    }

    protected SortedMap<Long, LinkedHashSet<ByteArrayWrapper>> getTimeView() {
        return this.timeView;
    }

    protected void updateAccPoolState() {

        // iterate tx by account
        for (Entry<Address, AccountState> e : this.accountView.entrySet()) {
            AccountState as = e.getValue();
            if (as.isDirty()) {
                // checking AccountState given by account
                List<PoolState> psl = this.poolStateView.get(e.getKey());
                if (psl == null) {
                    psl = new LinkedList<>();
                }

                List<PoolState> newPoolState = new LinkedList<>();
                // Checking new tx has been include into old pools.
                // BigInteger txNonceStart = null;
                BigInteger txNonceStart = as.getFirstNonce();

                if (txNonceStart != null) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("AbstractTxPool.updateAccPoolState fn [{}]", txNonceStart.toString());
                    }
                    for (PoolState ps : psl) {
                        // check the previous txn status in the old PoolState
                        if (ps.firstNonce.equals(txNonceStart) && ps.combo == SEQUENTAILTXNCOUNT_MAX) {
                            newPoolState.add(ps);

                            if (LOG.isTraceEnabled()) {
                                LOG.trace("AbstractTxPool.updateAccPoolState add fn [{}]", ps.firstNonce.toString());
                            }

                            txNonceStart = txNonceStart.add(BigInteger.valueOf(SEQUENTAILTXNCOUNT_MAX));
                        } else {
                            // remove old poolState in the feeMap
                            Map<ByteArrayWrapper, TxDependList<ByteArrayWrapper>> txDp = this.feeView.get(ps.getFee());
                            if (txDp != null) {
                                ByteArrayWrapper bw = e.getValue().getMap().get(ps.firstNonce).getKey();
                                txDp.remove(bw);

                                if (LOG.isTraceEnabled()) {
                                    LOG.trace("AbstractTxPool.updateAccPoolState remove fn [{}]", ps.firstNonce.toString());
                                }

                                if (txDp.isEmpty()) {
                                    this.feeView.remove(ps.getFee());
                                }
                            }
                        }
                    }
                }

                if (!this.poolStateView.isEmpty() && this.poolStateView.get(e.getKey()) != null) {
                    this.poolStateView.get(e.getKey()).clear();
                }

                int cnt = 0;
                BigInteger fee = BigInteger.ZERO;
                BigInteger totalFee = BigInteger.ZERO;

                for (Entry<BigInteger, SimpleEntry<ByteArrayWrapper, BigInteger>> en : as.getMap().entrySet()) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace(
                                "AbstractTxPool.updateAccPoolState mapsize[{}] nonce:[{}] cnt[{}] txNonceStart[{}]", as.getMap().size(), en.getKey().toString(), cnt, txNonceStart.toString());
                    }
                    if (en.getKey().equals(txNonceStart != null ? txNonceStart.add(BigInteger.valueOf(cnt)) : null)) {
                        if (en.getValue().getValue().compareTo(fee) > -1) {
                            fee = en.getValue().getValue();
                            totalFee = totalFee.add(fee);

                            if (++cnt == SEQUENTAILTXNCOUNT_MAX) {
                                if (LOG.isTraceEnabled()) {
                                    LOG.trace(
                                            "AbstractTxPool.updateAccPoolState case1 - nonce:[{}] totalFee:[{}] cnt:[{}]",
                                            txNonceStart, totalFee.toString(), cnt);
                                }
                                newPoolState.add(
                                        new PoolState(txNonceStart, totalFee.divide(BigInteger.valueOf(cnt)), cnt));

                                txNonceStart = en.getKey().add(BigInteger.ONE);
                                totalFee = BigInteger.ZERO;
                                fee = BigInteger.ZERO;
                                cnt = 0;
                            }
                        } else {
                            if (totalFee.signum() == 1) {
                                if (LOG.isTraceEnabled()) {
                                    LOG.trace(
                                            "AbstractTxPool.updateAccPoolState case2 - nonce:[{}] totalFee:[{}] cnt:[{}]",
                                            txNonceStart, totalFee.toString(), cnt);
                                }
                                newPoolState.add(
                                        new PoolState(txNonceStart, totalFee.divide(BigInteger.valueOf(cnt)), cnt));

                                // next PoolState
                                txNonceStart = en.getKey();
                                fee = en.getValue().getValue();
                                totalFee = fee;
                                cnt = 1;
                            }
                        }
                    }
                }

                if (totalFee.signum() == 1) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("AbstractTxPool.updateAccPoolState case3 - nonce:[{}] totalFee:[{}] cnt:[{}] bw:[{}]",
                                txNonceStart, totalFee.toString(), cnt, e.getKey().toString());
                    }

                    newPoolState.add(new PoolState(txNonceStart, totalFee.divide(BigInteger.valueOf(cnt)), cnt));
                }

                this.poolStateView.put(e.getKey(), newPoolState);

                if (LOG.isTraceEnabled()) {
                    this.poolStateView.forEach((k, v) -> v.forEach(l -> {
                        LOG.trace("AbstractTxPool.updateAccPoolState - the first nonce of the poolState list:[{}]",
                                l.firstNonce);
                    }));
                }

                as.sorted();
            }
        }
    }

    protected void updateFeeMap() {
        for (Entry<Address, List<PoolState>> e : this.poolStateView.entrySet()) {
            ByteArrayWrapper dependTx = null;
            for (PoolState ps : e.getValue()) {

                if (LOG.isTraceEnabled()) {
                    LOG.trace("updateFeeMap addr[{}] inFp[{}] fn[{}] cb[{}] fee[{}]", e.getKey().toString(), ps.isInFeePool(), ps.getFirstNonce().toString(), ps.getCombo(), ps.getFee().toString());
                }

                if (ps.isInFeePool()) {
                    dependTx = this.accountView.get(e.getKey()).getMap().get(ps.getFirstNonce()).getKey();
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("updateFeeMap isInFeePool [{}]", dependTx.toString());
                    }
                } else {

                    TxDependList<ByteArrayWrapper> txl = new TxDependList<>();
                    for (BigInteger i = ps.firstNonce; i.compareTo(
                            ps.firstNonce.add(BigInteger.valueOf(ps.combo))) < 0; i = i.add(BigInteger.ONE)) {
                        txl.addTx(this.accountView.get(e.getKey()).getMap().get(i).getKey());
                    }

                    if (!txl.isEmpty()) {
                        txl.setDependTx(dependTx);
                        dependTx = txl.getTxList().get(0);
                        txl.setAddress(e.getKey());
                    }

                    if (this.feeView.get(ps.fee) == null) {
                        Map<ByteArrayWrapper, TxDependList<ByteArrayWrapper>> set = new LinkedHashMap<>();
                        set.put(txl.getTxList().get(0), txl);

                        if (LOG.isTraceEnabled()) {
                            LOG.trace("updateFeeMap new feeView put fee[{}]", ps.fee);
                        }

                        this.feeView.put(ps.fee, set);
                    } else {

                        if (LOG.isTraceEnabled()) {
                            LOG.trace("updateFeeMap update feeView put fee[{}]", ps.fee);
                        }

                        Map<ByteArrayWrapper, TxDependList<ByteArrayWrapper>> preset = this.feeView.get(ps.fee);

                        preset.put(txl.getTxList().get(0), txl);
                        this.feeView.put(ps.fee, preset);
                    }

                    ps.setInFeePool();
                }
            }
        }
    }

    protected class TXState {
        private boolean sorted = false;
        private TX tx = null;

        public TXState(TX tx) {
            this.tx = tx;
        }

        public TX getTx() {
            return this.tx;
        }

        public boolean sorted() {
            return this.sorted;
        }

        public void setSorted() {
            this.sorted = true;
        }
    }

    protected class PoolState {
        private final AtomicBoolean inFeePool = new AtomicBoolean(false);
        private BigInteger fee = BigInteger.ZERO;
        private BigInteger firstNonce = BigInteger.ZERO;
        private int combo = 0;

        protected PoolState(BigInteger nonce, BigInteger fee, int combo) {
            this.firstNonce = nonce;
            this.combo = combo;
            this.fee = fee;
        }

        public boolean contains(BigInteger bi) {
            return (bi.compareTo(firstNonce) > -1) && (bi.compareTo(firstNonce.add(BigInteger.valueOf(combo))) < 0);
        }

        public BigInteger getFee() {
            return fee;
        }

        public BigInteger getFirstNonce() {
            return firstNonce;
        }

        public int getCombo() {
            return combo;
        }

        public boolean isInFeePool() {
            return inFeePool.get();
        }

        public void setInFeePool() {
            inFeePool.set(true);
        }
    }
}
