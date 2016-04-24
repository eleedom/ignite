/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.pagemem.impl;


import org.apache.ignite.internal.pagemem.DirectMemoryUtils;
import org.apache.ignite.internal.mem.OutOfMemoryException;
import org.apache.ignite.internal.pagemem.FullPageId;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.U;

import static org.apache.ignite.IgniteSystemProperties.*;

/**
 *
 */
public class FullPageIdTable {
    /** Load factor. */
    private static final int LOAD_FACTOR = getInteger(IGNITE_LONG_LONG_HASH_MAP_LOAD_FACTOR, 2);

    /** */
    private static final int BYTES_PER_ENTRY = /*page ID*/8 + /*cache ID*/4 + /*pointer*/8;

    /** */
    private static final FullPageId EMPTY_FULL_PAGE_ID = new FullPageId(0, 0);

    /** */
    private static final long EMPTY_PAGE_ID = EMPTY_FULL_PAGE_ID.pageId();

    /** */
    private static final int EMPTY_CACHE_ID = EMPTY_FULL_PAGE_ID.cacheId();

    /** */
    public static final FullPageId REMOVED_FULL_PAGE_ID = new FullPageId(0x8000000000000000L, 0);

    /** */
    private static final long REMOVED_PAGE_ID = REMOVED_FULL_PAGE_ID.pageId();

    /** */
    private static final int REMOVED_CACHE_ID = REMOVED_FULL_PAGE_ID.cacheId();

    /** */
    private static final int EQUAL = 0;

    /** */
    private static final int EMPTY = 1;

    /** */
    private static final int REMOVED = -1;

    /** */
    private static final int NOT_EQUAL = 2;

    /** Max size, in elements. */
    protected int capacity;

    /** Maximum number of steps to try before failing. */
    protected int maxSteps;

    /** Pointer to the values array. */
    protected long valPtr;

    /** */
    protected DirectMemoryUtils mem;

    /**
     * @return Estimated memory size required for this map to store the given number of elements.
     */
    public static long requiredMemory(long elementCnt) {
        assert LOAD_FACTOR != 0;

        return elementCnt * BYTES_PER_ENTRY * LOAD_FACTOR + 4;
    }

    /**
     * @param mem Memory interface.
     * @param addr Base address.
     * @param len Allocated memory length.
     * @param clear If {@code true}, then memory is considered dirty and will be cleared. Otherwise,
     *      map will assume that the given memory region is in valid state.
     */
    public FullPageIdTable(DirectMemoryUtils mem, long addr, long len, boolean clear) {
        valPtr = addr;
        capacity = (int)((len - 4) / BYTES_PER_ENTRY);
        maxSteps = (int)Math.sqrt(capacity);
        this.mem = mem;

        if (clear)
            clear();
    }

    /**
     * @return Current number of entries in the map.
     */
    public final int size() {
        return mem.readInt(valPtr);
    }

    /**
     * @return Maximum number of entries in the map. This maximum can not be always reached.
     */
    public final int capacity() {
        return capacity;
    }

    /**
     * Gets value associated with the given key.
     *
     * @param fullId Key to get value for. Key cannot be equal to {@code EMPTY_PAGE_ID} and key cannot be equal
     * to {@code 0x8000000000000000}.
     *
     * @return A value associated with the given key.
     */
    public long get(FullPageId fullId, long absent) {
        assert assertKey(fullId);

        int index = getKey(fullId);

        if (index < 0)
            return absent;

        return valueAt(index);
    }

    /**
     * Associates the given key with the given value.
     *
     * @param key Key to set value for. Key cannot be equal to {@code 0} and key cannot be equal
     *      to {@code 0x8000000000000000}.
     * @param value Value to set.
     */
    public void put(FullPageId key, long value) {
        assert assertKey(key);

        int index = putKey(key);

        setValueAt(index, value);
    }

    /**
     * Removes key-value association for the given key.
     *
     * @param key Key to remove from the map.
     */
    public void remove(FullPageId key) {
        assert assertKey(key);

        int index = removeKey(key);

        if (index >= 0)
            setValueAt(index, 0);
    }

    /**
     * @param key Key.
     * @return Key index.
     */
    private int putKey(FullPageId key) {
        int step = 1;

        int index = U.safeAbs(key.hashCode()) % capacity;

        do {
            int res = testKeyAt(index, key);

            if (res == EMPTY) {
                setKeyAt(index, key);

                incrementSize();

                return index;
            }
            else if (res == EQUAL)
                return index;
            else
                assert res == REMOVED || res == NOT_EQUAL;

            if ((index += step) >= capacity)
                index -= capacity;
        }
        while (++step <= maxSteps);

        throw new OutOfMemoryException("No room for a new key");
    }

    /**
     * @param key Key.
     * @return Key index.
     */
    private int getKey(FullPageId key) {
        int step = 1;

        int index = U.safeAbs(key.hashCode()) % capacity;

        do {
            long res = testKeyAt(index, key);

            if (res == EQUAL)
                return index;
            else if (res == EMPTY)
                return -1;
            else
                assert res == REMOVED || res == NOT_EQUAL;

            if ((index += step) >= capacity)
                index -= capacity;
        } while (++step <= maxSteps);

        return -1;
    }

    /**
     * @param key Key.
     * @return Key index.
     */
    private int removeKey(FullPageId key) {
        int step = 1;

        int index = U.safeAbs(key.hashCode()) % capacity;

        do {
            long res = testKeyAt(index, key);

            if (res == EQUAL) {
                setKeyAt(index, REMOVED_FULL_PAGE_ID);

                decrementSize();

                return index;
            }
            else if (res == EMPTY)
                return -1;
            else
                assert res == REMOVED || res == NOT_EQUAL;

            if ((index += step) >= capacity)
                index -= capacity;
        }
        while (++step <= maxSteps);

        return -1;
    }

    /**
     * @param index Entry index.
     * @return Key value.
     */
    @SuppressWarnings("IfStatementWithIdenticalBranches")
    private int testKeyAt(int index, FullPageId fullId) {
        long base = valPtr + 4 + (long)index * BYTES_PER_ENTRY;

        long pageId = mem.readLong(base);
        int cacheId = mem.readInt(base + 8);

        if (pageId == REMOVED_PAGE_ID && cacheId == REMOVED_CACHE_ID)
            return REMOVED;
        else if (pageId == fullId.pageId() && cacheId == fullId.cacheId())
            return EQUAL;
        else if(pageId == EMPTY_PAGE_ID && cacheId == EMPTY_CACHE_ID)
            return EMPTY;
        else
            return NOT_EQUAL;
    }

    /**
     * @param fullId Full page ID to check.
     * @return {@code True} if checks succeeded.
     */
    private boolean assertKey(FullPageId fullId) {
        assert !F.eq(fullId, EMPTY_FULL_PAGE_ID) : "fullId != EMPTY";
        assert !F.eq(fullId, REMOVED_FULL_PAGE_ID) : "fullId != REMOVED";

        return true;
    }

    /**
     * @param index Entry index.
     * @param val Value to write.
     */
    private void setKeyAt(int index, FullPageId val) {
        long base = valPtr + 4 + (long)index * BYTES_PER_ENTRY;

        mem.writeLong(base, val.pageId());
        mem.writeLong(base + 8, val.cacheId());
    }

    /**
     * @param index Entry index.
     * @return Value.
     */
    private long valueAt(int index) {
        return mem.readLong(valPtr + 4 + (long)index * BYTES_PER_ENTRY + 12);
    }

    /**
     * @param index Entry index.
     * @param value Value.
     */
    private void setValueAt(int index, long value) {
        mem.writeLong(valPtr + 4 + (long)index * BYTES_PER_ENTRY + 12, value);
    }

    /**
     *
     */
    public void clear() {
        mem.setMemory(valPtr, capacity * BYTES_PER_ENTRY + 4, (byte)0);
    }

    /**
     *
     */
    private void incrementSize() {
        mem.writeInt(valPtr, mem.readInt(valPtr) + 1);
    }

    /**
     *
     */
    private void decrementSize() {
        mem.writeInt(valPtr, mem.readInt(valPtr) + 1);
    }
}