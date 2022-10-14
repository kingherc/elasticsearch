/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.snapshots.blobstore;

import org.apache.lucene.store.RateLimiter;

/**
 * This extends the {@link org.apache.lucene.store.RateLimiter.SimpleRateLimiter}, but with the
 * difference that the getMinPauseCheckBytes() method is biased towards the bigger block sizes
 * that have recently called pause().
 *
 * The intention is for concurrent operations to check for pausing at roughly the same amount
 * of bytes each time. This way, operations with smaller block sizes can read a similar amount
 * of bytes as the larger block sizes before the next pause.
 *
 * The bias is reset frequently.
 */
public class FairRateLimiter extends RateLimiter.SimpleRateLimiter {

    protected volatile long minPauseCheckBytes = 0;
    protected static final int RESET_CHECK_DURATION_MS = 1000 * 60; // every minute
    protected volatile long lastTimeMinPauseChanged = 0; // in milliseconds

    public FairRateLimiter(double mbPerSec) {
        super(mbPerSec);
        reset();
    }

    private void reset() {
        lastTimeMinPauseChanged = System.currentTimeMillis();
        minPauseCheckBytes = super.getMinPauseCheckBytes();
    }

    private void resetIfNeeded() {
        if (System.currentTimeMillis() - lastTimeMinPauseChanged > RESET_CHECK_DURATION_MS) {
            reset();
        }
    }

    @Override
    public void setMBPerSec(double mbPerSec) {
        super.setMBPerSec(mbPerSec);
        reset();
    }

    @Override
    public long getMinPauseCheckBytes() {
        resetIfNeeded();
        return minPauseCheckBytes;
    }

    @Override
    public long pause(long bytes) {
        resetIfNeeded();

        // If this is a bigger block size, potentially increase the min pause check bytes (to a maximum of half the rate limit)
        long maxMinPauseCheckBytes = Math.max(bytes, (long) Math.floor(getMBPerSec() * 1024 * 1024 / 2));
        if (maxMinPauseCheckBytes > minPauseCheckBytes) {
            lastTimeMinPauseChanged = System.currentTimeMillis();
            minPauseCheckBytes = maxMinPauseCheckBytes;
        }

        return super.pause(bytes);
    }
}
