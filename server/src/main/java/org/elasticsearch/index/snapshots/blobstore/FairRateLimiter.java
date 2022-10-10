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
 * The intention is for operations with a bigger block size to pause more than operations
 * with a smaller block size, so that the latter get less time in pausing.
 *
 * The bias is reset in a frequent basis.
 */
public class FairRateLimiter extends RateLimiter.SimpleRateLimiter {

    protected volatile long minPauseCheckBytes = 0;

    private static final int RESET_CHECK_DURATION_MS = 1000 * 60 * 2; // every 2 minutes
    protected volatile long lastTimeMinPauseChanged = 0; // in milliseconds

    public FairRateLimiter(double mbPerSec) {
        super(mbPerSec);
    }

    @Override
    public void setMBPerSec(double mbPerSec) {
        super.setMBPerSec(mbPerSec);
        lastTimeMinPauseChanged = System.currentTimeMillis();
        minPauseCheckBytes = super.getMinPauseCheckBytes();
    }

    @Override
    public long getMinPauseCheckBytes() {
        return minPauseCheckBytes;
    }

    @Override
    public long pause(long bytes) {
        long now = System.currentTimeMillis();
        if (bytes > minPauseCheckBytes) {
            lastTimeMinPauseChanged = now;
            minPauseCheckBytes = bytes;
        }
        if (now - lastTimeMinPauseChanged > RESET_CHECK_DURATION_MS) {
            lastTimeMinPauseChanged = now;
            minPauseCheckBytes = super.getMinPauseCheckBytes();
        }
        return super.pause(bytes);
    }
}
