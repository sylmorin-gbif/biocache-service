package au.org.ala.biocache.util.thread;

import au.org.ala.biocache.dto.DownloadDetailsDTO;
import au.org.ala.biocache.dto.DownloadRequestParams;
import au.org.ala.biocache.writer.RecordWriterError;
import au.org.ala.biocache.writer.RecordWriterException;
import org.apache.log4j.Logger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A single thread that consumes elements put onto the queue until it sees the sentinel, finalising after the
 * sentinel or an interrupt.
 */
public class StreamWriterThread extends Thread {
    private static final Logger logger = Logger.getLogger(StreamWriterThread.class);

    AtomicBoolean interruptFound;
    String[] sentinel;
    BlockingQueue<String[]> queue;
    Integer resultsQueueLength;
    RecordWriterError recordWriter;
    DownloadRequestParams downloadParams;
    DownloadDetailsDTO downloadDetails;

    public StreamWriterThread(AtomicBoolean interruptFound, String[] sentinel, BlockingQueue<String[]> queue,
                              Integer resultsQueueLength, RecordWriterError recordWriter,
                              DownloadRequestParams downloadParams, DownloadDetailsDTO downloadDetails) {
        this.interruptFound = interruptFound;
        this.sentinel = sentinel;
        this.queue = queue;
        this.resultsQueueLength = resultsQueueLength;
        this.recordWriter = recordWriter;
        this.downloadParams = downloadParams;
        this.downloadDetails = downloadDetails;
    }

    @Override
    public void run() {
        try {
            long counter = 0;
            while (true) {
                counter = counter + 1;

                if (Thread.currentThread().isInterrupted() || interruptFound.get()) {
                    break;
                }

                String[] take = queue.take();
                // Sentinel object equality check to see if we are done
                if (take == sentinel || Thread.currentThread().isInterrupted() || interruptFound.get()) {
                    break;
                }
                // Otherwise write to the wrapped record writer
                recordWriter.write(take);

                //test for errors. This can contain a flush so only test occasionally
                if (counter % resultsQueueLength == 0 && recordWriter.hasError()) {
                    throw RecordWriterException.newRecordWriterException(downloadDetails, true, recordWriter);
                }

            }
        } catch (RecordWriterException e) {
            //no trace information is available to print for these errors
            logger.error(e.getMessage());
            interruptFound.set(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interruptFound.set(true);
        } catch (Exception e) {
            // Reuse interruptFound variable to signal that the writer had issues
            interruptFound.set(true);
            logger.error("Download writer failed.", e);
        } finally {
            recordWriter.finalise();
        }
    }
};
