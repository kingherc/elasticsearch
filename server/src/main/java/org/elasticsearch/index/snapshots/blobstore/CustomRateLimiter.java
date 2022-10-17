/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.snapshots.blobstore;

import org.apache.lucene.store.RateLimiter;

public class CustomRateLimiter extends RateLimiter.SimpleRateLimiter {

    public CustomRateLimiter(double mbPerSec) {
        super(mbPerSec);
    }

    @Override
    public long getMinPauseCheckBytes() {
        return super.getMinPauseCheckBytes() * 2;
    }

}
