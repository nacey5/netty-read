/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

import java.util.concurrent.locks.ReentrantLock;

import static io.netty.buffer.PoolChunk.RUN_OFFSET_SHIFT;
import static io.netty.buffer.PoolChunk.SIZE_SHIFT;
import static io.netty.buffer.PoolChunk.IS_USED_SHIFT;
import static io.netty.buffer.PoolChunk.IS_SUBPAGE_SHIFT;

final class PoolSubpage<T> implements PoolSubpageMetric {

    //当前分配内存的chunk
    final PoolChunk<T> chunk;
    final int elemSize;
    private final int pageShifts;
    //当前page在chunk的memoryMap中的下标id
    private final int runOffset;
    private final int runSize;
    // poolSubPage每段内存的占用状态，采用二进制位来标识
    private final long[] bitmap;
    private final int bitmapLength;
    private final int maxNumElems;

    //前指针
    PoolSubpage<T> prev;
    //后指针
    PoolSubpage<T> next;

    boolean doNotDestroy;
    // 下一个可用的位置
    private int nextAvail;
    // 可用的段的数量
    private int numAvail;

    private final ReentrantLock lock;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage() {
        chunk = null;
        lock = new ReentrantLock();
        pageShifts = -1;
        runOffset = -1;
        elemSize = -1;
        runSize = -1;
        bitmap = null;
        bitmapLength = -1;
        maxNumElems = 0;
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int pageShifts, int runOffset, int runSize, int elemSize) {
        this.chunk = chunk;
        this.pageShifts = pageShifts;
        this.runOffset = runOffset;
        this.runSize = runSize;
        this.elemSize = elemSize;

        doNotDestroy = true;

        maxNumElems = numAvail = runSize / elemSize;
        int bitmapLength = maxNumElems >>> 6;
        if ((maxNumElems & 63) != 0) {
            bitmapLength ++;
        }
        this.bitmapLength = bitmapLength;
        bitmap = new long[bitmapLength];
        nextAvail = 0;

        lock = null;
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        // 获取PoolSubPage下一个可用的位置
        final int bitmapIdx = getNextAvail();
        if (bitmapIdx < 0) {
            removeFromPool(); // Subpage appear to be in an invalid state. Remove to prevent repeated errors.
            throw new AssertionError("No next available bitmap index found (bitmapIdx = " + bitmapIdx + "), " +
                    "even though there are supposed to be (numAvail = " + numAvail + ") " +
                    "out of (maxNumElems = " + maxNumElems + ") available indexes.");
        }
        // 获取该位置在bitMap数组对应的下标值
        int q = bitmapIdx >>> 6;
        //获取bitmap[q]上实际可用的位
        int r = bitmapIdx & 63;
        //确定该位没有被使用
        assert (bitmap[q] >>> r & 1) == 0;
        //将该位置为1，表示已被占用，此处1L<<r表示将r位设为1
        bitmap[q] |= 1L << r;

        //若没有可用的段，则说明已经分配满了，没有必须要继续放到PoolArena池中，应从Pool移除
        if (-- numAvail == 0) {
            removeFromPool();
        }

        //把当前page的索引和PoolSubPage的索引一起返回
        //低32位表示page的index，高32位表示PoolSubPage的index
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        //由于long是64位的，因此除以64就是下标
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        //将该位置设置为下一个可用的位置
        setNextAvail(bitmapIdx);

        //若之前没有可分配的内存，从池中移除了，则架构PoolSubPage继续添加到Arena的缓存池党章，以便下回分配
        if (numAvail ++ == 0) {
            addToPool(head);
            /* When maxNumElems == 1, the maximum numAvail is also 1.
             * Each of these PoolSubpages will go in here when they do free operation.
             * If they return true directly from here, then the rest of the code will be unreachable
             * and they will not actually be recycled. So return true only on maxNumElems > 1. */
            if (maxNumElems > 1) {
                return true;
            }
        }

        //还有未被回收的内存，直接返回
        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            //所有的内存都被回收了
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        //如果下一个位置大于等于0，则说明是第一次分配或已经被回收过了，可以直接返回
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        //继续找
        return findNextAvail();
    }

    //循环找到一个可用，人如果没有可用的，返回-1
    private int findNextAvail() {
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            // 如果当前long型标识位不全为1，则表示其中有未被使用的内存
            if (~bits != 0) {
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int baseVal = i << 6;
        //i表示bitmap的位置，由于每个bitmap每个值有64位
        //因此使用i*bitmap来表示bitmap[i]的第一位在PoolSubPage中的偏移量
        for (int j = 0; j < 64; j ++) {
            //判断第一位是否为0，为0表示空闲
            if ((bits & 1) == 0) {
                //i*bitmap 1未被占用位，得到空位在PoolSubPage的位置
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            //如果bits第一位不为0，右移一位，判断第二位
            bits >>>= 1;
        }
        //没有找到的话，直接返回-1
        return -1;
    }

    //低位和高位分别代表了两个不同的索引
    private long toHandle(int bitmapIdx) {
        int pages = runSize >> pageShifts;
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) pages << SIZE_SHIFT
               | 1L << IS_USED_SHIFT
               | 1L << IS_SUBPAGE_SHIFT
               | bitmapIdx;
    }

    @Override
    public String toString() {
        final int numAvail;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            numAvail = 0;
        } else {
            final boolean doNotDestroy;
            chunk.arena.lock();
            try {
                doNotDestroy = this.doNotDestroy;
                numAvail = this.numAvail;
            } finally {
                chunk.arena.unlock();
            }
            if (!doNotDestroy) {
                // Not used for creating the String.
                return "(" + runOffset + ": not in use)";
            }
        }

        return "(" + this.runOffset + ": " + (this.maxNumElems - numAvail) + '/' + this.maxNumElems +
                ", offset: " + this.runOffset + ", length: " + this.runSize + ", elemSize: " + this.elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        return maxNumElems;
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        chunk.arena.lock();
        try {
            return numAvail;
        } finally {
            chunk.arena.unlock();
        }
    }

    @Override
    public int elementSize() {
        return elemSize;
    }

    @Override
    public int pageSize() {
        return 1 << pageShifts;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }

    void lock() {
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }
}
