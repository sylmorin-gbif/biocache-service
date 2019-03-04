package au.org.ala.biocache.util.thread;

import au.org.ala.biocache.RecordWriter;
import org.apache.log4j.Logger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamRecordWriter implements RecordWriter {

    private static final Logger logger = Logger.getLogger(StreamRecordWriter.class);

    private final AtomicBoolean finalised = new AtomicBoolean(false);
    private final AtomicBoolean finalisedComplete = new AtomicBoolean(false);


    AtomicBoolean interruptFound;
    String[] sentinel;
    BlockingQueue<String[]> queue;
    Long writerTimeoutWaitMillis;

    public StreamRecordWriter(AtomicBoolean interruptFound, String[] sentinel, BlockingQueue<String[]> queue, Long writerTimeoutWaitMillis) {
        this.interruptFound = interruptFound;
        this.sentinel = sentinel;
        this.queue = queue;
        this.writerTimeoutWaitMillis = writerTimeoutWaitMillis;
    }

    @Override
    public void write(String[] nextLine) {
        try {
            if (Thread.currentThread().isInterrupted() || interruptFound.get() || finalised.get()) {
                finalise();
                return;
            }
            while (!queue.offer(nextLine, writerTimeoutWaitMillis, TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted() || interruptFound.get() || finalised.get()) {
                    finalise();
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interruptFound.set(true);
            if (logger.isDebugEnabled()) {
                logger.debug("Queue failed to accept the next record due to a thread interrupt, calling finalise the cleanup: ", e);
            }
            // If we were interrupted then we should call finalise to cleanup
            finalise();
        }
    }

    @Override
    public void finalise() {
        if (finalised.compareAndSet(false, true)) {
            try {
                // Offer the sentinel at least once, even when the thread is interrupted
                while (!queue.offer(sentinel, writerTimeoutWaitMillis, TimeUnit.MILLISECONDS)) {
                    // If the thread is interrupted then the queue may not have any active consumers,
                    // so don't loop forever waiting for capacity in this case
                    // The hard shutdown phase will use queue.clear to ensure that the
                    // sentinel gets onto the queue at least once
                    if (Thread.currentThread().isInterrupted() || interruptFound.get()) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interruptFound.set(true);
                if (logger.isDebugEnabled()) {
                    logger.debug("Queue failed to accept the sentinel in finalise due to a thread interrupt: ", e);
                }
            } finally {
                finalisedComplete.set(true);
            }
        }
    }

    @Override
    public void initialise() {
        // No resources to create
    }

    @Override
    public boolean finalised() {
        return finalisedComplete.get();
    }
}
