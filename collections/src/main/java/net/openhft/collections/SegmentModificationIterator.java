/*
 * Copyright 2014 Higher Frequency Trading
 * <p/>
 * http://www.higherfrequencytrading.com
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.collections;

import net.openhft.lang.collection.DirectBitSet;
import net.openhft.lang.collection.SingleThreadedDirectBitSet;
import net.openhft.lang.io.ByteBufferBytes;
import net.openhft.lang.io.Bytes;
import net.openhft.lang.io.NativeBytes;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Once a change occurs to a map, map replication requires that these changes are picked up by another thread,
 * this class provides an iterator like interface to poll for such changes.
 * In most cases the thread that adds data to the node is unlikely to be the same thread that replicates the data over to the other nodes,
 * so data will have to be marshalled between the main thread storing data to the map, and the thread running the replication.
 * One way to perform this marshalling, would be to pipe the data into a queue. However, This class takes another approach.
 * It uses a bit set, and marks bits which correspond to the indexes of the entries that have changed.
 * It then provides an iterator like interface to poll for such changes.
 *
 * @author Rob Austin.
 */
public class SegmentModificationIterator<K, V> implements SharedMapEventListener {


    private SegmentInfoProvider segmentInfoProvider;
    private SingleThreadedDirectBitSet changes;

    /**
     * {@inheritDoc}
     */
    @Override
    public Object onGetMissing(SharedHashMap map, Bytes keyBytes, Object key, Object usingValue) {
        // do nothing
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onGetFound(SharedHashMap map, Bytes entry, int metaDataBytes, Object key, Object value) {
        // do nothing
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPut(SharedHashMap map, Bytes entry, int metaDataBytes, boolean added, Object key, Object value, long pos, VanillaSharedHashMap.Segment segment) {
        final long bitIndex = (segment.getIndex() * segmentInfoProvider.getEntriesPerSegment()) + pos;
        changes.set(bitIndex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRemove(SharedHashMap map, Bytes entry, int metaDataBytes, Object key, Object value, int pos, VanillaSharedHashMap.Segment segment) {
        changes.set((segment.getIndex() * segmentInfoProvider.getEntriesPerSegment()) + pos);
    }

    private long offset = DirectBitSet.NOT_FOUND;

    /**
     * you can continue to poll hasNext() until data becomes available.
     *
     * @return true if there is an entry
     */
    public boolean hasNext() {

        long oldOffset = offset;

        final long offset0 = changes.nextSetBit(oldOffset + 1);

        if (offset0 == DirectBitSet.NOT_FOUND) {
            if (oldOffset == DirectBitSet.NOT_FOUND)
                return false;
            offset = -1;
            return hasNext();
        }
        return true;
    }


    public Map.Entry<K, V> nextEntry() {

        long oldOffset = offset;
        offset = changes.nextSetBit(offset + 1);

        if (offset != DirectBitSet.NOT_FOUND)
            changes.clear(offset);

        if (offset == DirectBitSet.NOT_FOUND) {
            if (oldOffset == DirectBitSet.NOT_FOUND)
                throw new IllegalArgumentException();
            return nextEntry();
        }

        final int segmentIndex = (int) (offset / segmentInfoProvider.getEntriesPerSegment());
        final SharedSegment segment = segmentInfoProvider.getSegments()[segmentIndex];

        final int segmentPos = (int) offset - (segmentInfoProvider.getEntriesPerSegment() * segmentIndex);

        segment.lock();
        try {
            return segment.getEntry(segmentPos);
        } finally {
            segment.unlock();
        }
    }

    public NativeBytes nextNativeBytes() {

        long oldOffset = offset;

        offset = changes.nextSetBit(offset + 1);
        if (offset != DirectBitSet.NOT_FOUND)
            changes.clear(offset);

        if (offset == DirectBitSet.NOT_FOUND) {
            if (oldOffset == DirectBitSet.NOT_FOUND)
                throw new IllegalArgumentException();
            return nextNativeBytes();
        }

        final int segmentIndex = (int) (offset / segmentInfoProvider.getEntriesPerSegment());
        final SharedSegment segment = segmentInfoProvider.getSegments()[segmentIndex];

        final int segmentPos = (int) offset - (segmentInfoProvider.getEntriesPerSegment() * segmentIndex);

        segment.lock();
        try {
            if (offset == DirectBitSet.NOT_FOUND)
                return null;
            final NativeBytes nativeBytes = segment.entry(segmentPos);
            nativeBytes.readStopBit();
            return nativeBytes;
        } finally {
            segment.unlock();
        }

    }

    /**
     * this must be called just after construction
     *
     * @param segmentInfoProvider information about the Segment
     */
    public void setSegmentInfoProvider(SegmentInfoProvider segmentInfoProvider) {
        this.segmentInfoProvider = segmentInfoProvider;
        changes = new SingleThreadedDirectBitSet(new ByteBufferBytes(
                ByteBuffer.allocate(1 + (int) ((segmentInfoProvider.getEntriesPerSegment() * segmentInfoProvider.getSegments().length) / 8.0))));
    }
}
