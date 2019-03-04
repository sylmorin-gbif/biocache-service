package au.org.ala.biocache.util.thread;

import au.org.ala.biocache.RecordWriter;
import au.org.ala.biocache.dao.SearchDAOImpl;
import au.org.ala.biocache.dto.DownloadDetailsDTO;
import au.org.ala.biocache.dto.DownloadHeadersDTO;
import au.org.ala.biocache.dto.DownloadRequestParams;
import au.org.ala.biocache.service.ListsService;
import au.org.ala.biocache.stream.ProcessDownload;
import au.org.ala.biocache.stream.ProcessInterface;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class SolrCallable implements Callable<Integer>, UserBalancedDownload {
    private final Logger logger = Logger.getLogger(SolrCallable.class);

    DownloadDetailsDTO downloadDetails;
    int throttle;
    Queue<SolrQuery> queryQueue;
    long maxDownloadSize;
    boolean threadCheckLimit;
    AtomicInteger resultsCount;
    int downloadBatchSize;
    SearchDAOImpl searchDAOImpl;
    AtomicBoolean interruptFound;
    ConcurrentLinkedQueue<Future<Integer>> futures;
    List<SolrQuery> sensitiveQ;
    ConcurrentMap<String, AtomicInteger> uidStats;
    DownloadHeadersDTO headers;
    ExecutorService nextExecutor;
    RecordWriter concurrentWrapper;
    ArrayList<String> miscFields;
    boolean checkLimit;

    // proc is maintained in subsequent threads so batching will work
    ProcessInterface procSensitive;
    ProcessInterface procNotSensitive;

    /**
     * Only usable by SolrCallable to maintain single thread use of procSensitive and procNotSensitive
     *
     * @param source
     */
    private SolrCallable(SolrCallable source) {
        this.downloadDetails = source.downloadDetails;
        this.throttle = source.throttle;
        this.queryQueue = source.queryQueue;
        this.maxDownloadSize = source.maxDownloadSize;
        this.threadCheckLimit = source.threadCheckLimit;
        this.resultsCount = source.resultsCount;
        this.downloadBatchSize = source.downloadBatchSize;
        this.searchDAOImpl = source.searchDAOImpl;
        this.interruptFound = source.interruptFound;
        this.futures = source.futures;
        this.sensitiveQ = source.sensitiveQ;
        this.uidStats = source.uidStats;
        this.headers = source.headers;
        this.nextExecutor = source.nextExecutor;
        this.concurrentWrapper = source.concurrentWrapper;
        this.miscFields = source.miscFields;

        this.procSensitive = source.procSensitive;
        this.procNotSensitive = source.procNotSensitive;
    }

    public SolrCallable(DownloadDetailsDTO downloadDetails, Integer throttle, ConcurrentLinkedQueue<SolrQuery> queryQueue,
                        long maxDownloadSize, boolean threadCheckLimit, AtomicInteger resultsCount, Integer downloadBatchSize,
                        SearchDAOImpl searchDAOImpl, AtomicBoolean interruptFound,
                        ConcurrentLinkedQueue<Future<Integer>> futures, List<SolrQuery> sensitiveQ,
                        ConcurrentMap<String, AtomicInteger> uidStats, DownloadHeadersDTO headers,
                        ExecutorService nextExecutor, RecordWriter concurrentWrapper, ArrayList<String> miscFields,
                        ListsService listsService) {
        this.downloadDetails = downloadDetails;
        this.throttle = throttle;
        this.queryQueue = queryQueue;
        this.maxDownloadSize = maxDownloadSize;
        this.threadCheckLimit = threadCheckLimit;
        this.resultsCount = resultsCount;
        this.downloadBatchSize = downloadBatchSize;
        this.searchDAOImpl = searchDAOImpl;
        this.interruptFound = interruptFound;
        this.futures = futures;
        this.sensitiveQ = sensitiveQ;
        this.uidStats = uidStats;
        this.headers = headers;
        this.nextExecutor = nextExecutor;
        this.concurrentWrapper = concurrentWrapper;
        this.miscFields = miscFields;

        this.procSensitive = new ProcessDownload(uidStats, headers, concurrentWrapper,
                downloadDetails, checkLimit, resultsCount, maxDownloadSize, miscFields, true, listsService);
        this.procNotSensitive = new ProcessDownload(uidStats, headers, concurrentWrapper,
                downloadDetails, checkLimit, resultsCount, maxDownloadSize, miscFields, false, listsService);
    }

    @Override
    public Integer call() throws Exception {
        DownloadRequestParams downloadParams = downloadDetails.getRequestParams();

        SolrQuery query = queryQueue.poll();

        if (query != null) {

            int startIndex = 0;

            String[] fq = downloadParams.getFormattedFq();
            if (query.getFilterQueries() != null && query.getFilterQueries().length > 0) {
                if (fq == null) {
                    fq = new String[0];
                }
                fq = ArrayUtils.addAll(fq, query.getFilterQueries());
            }

            query.setFilterQueries(fq);


            if (logger.isDebugEnabled()) {
                logger.debug("Start index: " + startIndex + ", " + query.getQuery());
            }
            if (sensitiveQ.contains(query)) {
                searchDAOImpl.streamingQuery(query, procSensitive, null);
            } else {
                // write non-sensitive values into sensitive fields when not authorised for their sensitive values
                searchDAOImpl.streamingQuery(query, procNotSensitive, null);
            }
        }

        // add another callable when this callable is finished when there are still queries to process
        if (!queryQueue.isEmpty()) {
            futures.add(nextExecutor.submit(new SolrCallable(this)));
        } else {
            // write any outstanding data that may have been batched by ProcessTupleQuery
            procSensitive.flush();
            procNotSensitive.flush();
        }
        return 1;
    }


    @Override
    public DownloadDetailsDTO getDetails() {
        return downloadDetails;
    }

    ;
}
