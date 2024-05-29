/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.xpack.kv;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.ReferenceManager;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.lucene.index.ElasticsearchDirectoryReader;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.util.Maps;
import org.elasticsearch.core.Assertions;
import org.elasticsearch.index.cache.query.TrivialQueryCachingPolicy;
import org.elasticsearch.index.engine.CommitStats;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.EngineConfig;
import org.elasticsearch.index.engine.EngineException;
import org.elasticsearch.index.engine.SafeCommitInfo;
import org.elasticsearch.index.engine.Segment;
import org.elasticsearch.index.engine.TranslogDirectoryReader;
import org.elasticsearch.index.mapper.DocumentParser;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.index.seqno.SeqNoStats;
import org.elasticsearch.index.shard.ShardLongFieldRange;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.index.translog.TranslogStats;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.search.lookup.Source;
import org.elasticsearch.search.suggest.completion.CompletionStats;
import org.rocksdb.FlushOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public class RocksEngine extends Engine {
    public static final Setting<Boolean> INDEX_KV = Setting.boolSetting("index.kv", false, Setting.Property.IndexScope);

    private final Logger logger = LogManager.getLogger(RocksEngine.class);
    private final RocksDB db;
    private final AtomicLong currentSeqNo = new AtomicLong();

    public RocksEngine(EngineConfig engineConfig) {
        super(engineConfig);
        RocksDB.loadLibrary();
        try {
            try (Stream<Path> pathStream = Files.walk(Path.of(engineConfig.getShardId().toString()))) {
                pathStream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(f -> f.delete());
            }
        } catch (Exception e) {
            logger.warn("Could not delete key value directory", e);
        }
        try {

            this.db = RocksDB.open(engineConfig.getShardId().toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SegmentInfos getLastCommittedSegmentInfos() {
        return new SegmentInfos(6);
    }

    @Override
    public CommitStats commitStats() {
        return new CommitStats(Map.of(), 1, "", 1);
    }

    @Override
    public String getHistoryUUID() {
        return "hola";
    }

    @Override
    public long getWritingBytes() {
        return 0;
    }

    @Override
    public CompletionStats completionStats(String... fieldNamePatterns) {
        return null;
    }

    @Override
    public long getIndexThrottleTimeInMillis() {
        return 0;
    }

    @Override
    public boolean isThrottled() {
        return false;
    }

    @Override
    public void trimOperationsFromTranslog(long belowTerm, long aboveSeqNo) throws EngineException {

    }

    public static byte[] intToBytes(int n) {
        return new byte[] { (byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n };
    }

    public static int intFromBytes(byte[] b) {
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | ((b[3] & 0xFF) << 0);
    }

    @Override
    public IndexResult index(Index index) throws IOException {
        byte[] key = intToBytes(Integer.parseInt(index.id()));
        byte[] value = index.source().array();
        // var s = new SourceToParse(index.id(), BytesReference.fromByteBuffer(ByteBuffer.wrap(value)), XContentType.JSON);
        var s = Source.fromBytes(BytesReference.fromByteBuffer(ByteBuffer.wrap(value)));
        try {
            db.put(key, value);
            if (Assertions.ENABLED) {
                logger.info(
                    "--> added for id {}: key {} source {} value {} contents {}",
                    index.id(),
                    Arrays.toString(key),
                    s.source(),
                    Arrays.toString(value),
                    Arrays.toString(db.get(key))
                );
            }
        } catch (RocksDBException e) {
            throw new IOException(e);
        }
        var seqNo = currentSeqNo.incrementAndGet();
        IndexResult indexResult = new IndexResult(1, 1, seqNo, true, index.id());
        indexResult.setTranslogLocation(new Translog.Location(0, seqNo, 0));
        return indexResult;
    }

    @Override
    public DeleteResult delete(Delete delete) throws IOException {
        try {
            db.delete(intToBytes(Integer.parseInt(delete.id())));
        } catch (RocksDBException e) {
            throw new IOException(e);
        }
        return new DeleteResult(1, 1, 1, true, "id");
    }

    @Override
    public NoOpResult noOp(NoOp noOp) throws IOException {
        return new NoOpResult(1, 1);
    }

    @Override
    public GetResult get(
        Get get,
        MappingLookup mappingLookup,
        DocumentParser documentParser,
        Function<Searcher, Searcher> searcherWrapper
    ) {
        try {
            var s = getSourceOf(get.id());

            var sourceToParse = new SourceToParse(get.id(), s.internalSourceRef(), s.sourceContentType());
            ParsedDocument parsedDocument = documentParser.parseDocument(sourceToParse, mappingLookup);

            Term uid = new Term(IdFieldMapper.NAME, Uid.encodeId(get.id()));
            Index fakeIndexOp = new Index(uid, 1, parsedDocument);
            IndexResult indexResult = new IndexResult(1, 1, 1, true, get.id());
            indexResult.setTranslogLocation(new Translog.Location(0, 1, 0));
            Translog.Index fakeTranslogIndexOp = new Translog.Index(fakeIndexOp, indexResult);

            final TranslogDirectoryReader inMemoryReader = new TranslogDirectoryReader(
                shardId,
                fakeTranslogIndexOp,
                mappingLookup,
                documentParser,
                config(),
                () -> {}
            );
            final Engine.Searcher searcher = new Engine.Searcher(
                "kv_get",
                ElasticsearchDirectoryReader.wrap(inMemoryReader, shardId),
                config().getSimilarity(),
                null /*query cache disabled*/,
                TrivialQueryCachingPolicy.NEVER,
                inMemoryReader
            );
            final Searcher wrappedSearcher = searcherWrapper.apply(searcher);
            return getFromSearcher(get, wrappedSearcher, true);
        } catch (IOException e) {
            throw new EngineException(shardId, "failed to read operation from kv", e);
        }
    }

    public byte[] getBytes(byte[] id) {
        try {
            return db.get(id);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    public RocksDB getDb() {
        return db;
    }

    @Override
    public Source getSourceOf(String id) {
        byte[] key = intToBytes(Integer.parseInt(id));
        byte[] value = getBytes(key);
        var s = Source.fromBytes(BytesReference.fromByteBuffer(ByteBuffer.wrap(value)));
        if (Assertions.ENABLED) {
            logger.info("--> fetched for id {} map {} value {}", id, s.source(), Arrays.toString(value));
        }
        return s;
    }

    @Override
    public Map<Integer, Source> getSourcesOf(int[] ids) {
        try {
            List<byte[]> keys = Arrays.stream(ids).mapToObj(i -> intToBytes(i)).toList();
            List<byte[]> values = db.multiGetAsList(keys);
            Map<Integer, Source> map = Maps.newMapWithExpectedSize(ids.length);
            for (int i = 0; i < ids.length; i++) {
                var s = Source.fromBytes(BytesReference.fromByteBuffer(ByteBuffer.wrap(values.get(i))));
                map.put(ids[i], s);
            }
            if (Assertions.ENABLED) {
                logger.info(
                    "--> fetched multiple map {}",
                    map.entrySet().stream().map(e -> e.getKey() + " " + e.getValue().source()).toList()
                );
            }
            return map;
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int[] getAllKeys() {
        var list = new ArrayList<Integer>();
        var it = db.newIterator();
        it.seekToFirst();
        while (it.isValid()) {
            byte[] key = it.key();
            int id = intFromBytes(key);
            list.add(id);
            it.next();
        }
        it.close();
        int[] result = list.stream().mapToInt(i -> i).toArray();
        if (Assertions.ENABLED) {
            logger.info("--> got all keys {}", result);
        }
        return result;
    }

    @Override
    protected ReferenceManager<ElasticsearchDirectoryReader> getReferenceManager(SearcherScope scope) {
        var rm = new ReferenceManager<ElasticsearchDirectoryReader>() {
            public void changeCurrent(ElasticsearchDirectoryReader reference) {
                this.current = reference;
            }

            @Override
            protected void decRef(ElasticsearchDirectoryReader reference) throws IOException {

            }

            @Override
            protected ElasticsearchDirectoryReader refreshIfNeeded(ElasticsearchDirectoryReader referenceToRefresh) throws IOException {
                return referenceToRefresh;
            }

            @Override
            protected boolean tryIncRef(ElasticsearchDirectoryReader reference) throws IOException {
                return true;
            }

            @Override
            protected int getRefCount(ElasticsearchDirectoryReader reference) {
                return 1;
            }
        };

        try {
            rm.changeCurrent(ElasticsearchDirectoryReader.wrap(DirectoryReader.open(store.directory()), shardId));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return rm;
    }

    @Override
    public boolean isTranslogSyncNeeded() {
        return false;
    }

    @Override
    public void asyncEnsureTranslogSynced(Translog.Location location, Consumer<Exception> listener) {
        try {
            db.syncWal();
            listener.accept(null);
        } catch (RocksDBException e) {
            listener.accept(e);
        }
    }

    @Override
    public void asyncEnsureGlobalCheckpointSynced(long globalCheckpoint, Consumer<Exception> listener) {

    }

    @Override
    public void syncTranslog() throws IOException {

    }

    @Override
    public Closeable acquireHistoryRetentionLock() {
        return () -> {};
    }

    @Override
    public int countChanges(String source, long fromSeqNo, long toSeqNo) throws IOException {
        return 0;
    }

    @Override
    public Translog.Snapshot newChangesSnapshot(
        String source,
        long fromSeqNo,
        long toSeqNo,
        boolean requiredFullRange,
        boolean singleConsumer,
        boolean accessStats
    ) throws IOException {
        return Translog.Snapshot.EMPTY;
    }

    @Override
    public boolean hasCompleteOperationHistory(String reason, long startingSeqNo) {
        return true;
    }

    @Override
    public long getMinRetainedSeqNo() {
        return 0;
    }

    @Override
    public TranslogStats getTranslogStats() {
        return null;
    }

    @Override
    public Translog.Location getTranslogLastWriteLocation() {
        return Translog.Location.EMPTY;
    }

    @Override
    public long getMaxSeqNo() {
        return 0;
    }

    @Override
    public long getProcessedLocalCheckpoint() {
        return 0;
    }

    @Override
    public long getPersistedLocalCheckpoint() {
        return 0;
    }

    @Override
    public SeqNoStats getSeqNoStats(long globalCheckpoint) {
        return new SeqNoStats(1, 1, 1);
    }

    @Override
    public long getLastSyncedGlobalCheckpoint() {
        return 0;
    }

    @Override
    public long getIndexBufferRAMBytesUsed() {
        return 0;
    }

    @Override
    public List<Segment> segments() {
        return List.of();
    }

    @Override
    public List<Segment> segments(boolean includeVectorFormatsInfo) {
        return List.of();
    }

    @Override
    public RefreshResult refresh(String source) throws EngineException {
        return new RefreshResult(true);
    }

    @Override
    public void maybeRefresh(String source, ActionListener<RefreshResult> listener) throws EngineException {

    }

    @Override
    public void writeIndexingBuffer() throws IOException {
        try {
            db.flush(new FlushOptions().setWaitForFlush(false));
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean shouldPeriodicallyFlush() {
        return false;
    }

    @Override
    protected void flushHoldingLock(boolean force, boolean waitIfOngoing, ActionListener<FlushResult> listener) throws EngineException {
        try {
            db.flush(new FlushOptions().setWaitForFlush(true));
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void trimUnreferencedTranslogFiles() throws EngineException {

    }

    @Override
    public boolean shouldRollTranslogGeneration() {
        return false;
    }

    @Override
    public void rollTranslogGeneration() throws EngineException {

    }

    @Override
    public void forceMerge(boolean flush, int maxNumSegments, boolean onlyExpungeDeletes, String forceMergeUUID) throws EngineException,
        IOException {
        try {
            db.compactRange();
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public IndexCommitRef acquireLastIndexCommit(boolean flushFirst) throws EngineException {
        return null;
    }

    @Override
    public IndexCommitRef acquireSafeIndexCommit() throws EngineException {
        return null;
    }

    @Override
    public SafeCommitInfo getSafeCommitInfo() {
        return new SafeCommitInfo(1, 1);
    }

    @Override
    protected void closeNoLock(String reason, CountDownLatch closedLatch) {
        db.close();
    }

    @Override
    public void activateThrottling() {

    }

    @Override
    public void deactivateThrottling() {

    }

    @Override
    public int restoreLocalHistoryFromTranslog(TranslogRecoveryRunner translogRecoveryRunner) throws IOException {
        return 0;
    }

    @Override
    public int fillSeqNoGaps(long primaryTerm) throws IOException {
        return 0;
    }

    @Override
    public void recoverFromTranslog(TranslogRecoveryRunner translogRecoveryRunner, long recoverUpToSeqNo, ActionListener<Void> listener) {
        listener.onResponse(null);
    }

    @Override
    public void skipTranslogRecovery() {

    }

    @Override
    public void maybePruneDeletes() {

    }

    @Override
    public void updateMaxUnsafeAutoIdTimestamp(long newTimestamp) {

    }

    @Override
    public long getMaxSeqNoOfUpdatesOrDeletes() {
        return 0;
    }

    @Override
    public void advanceMaxSeqNoOfUpdatesOrDeletes(long maxSeqNoOfUpdatesOnPrimary) {

    }

    @Override
    public ShardLongFieldRange getRawFieldRange(String field) throws IOException {
        return null;
    }
}
