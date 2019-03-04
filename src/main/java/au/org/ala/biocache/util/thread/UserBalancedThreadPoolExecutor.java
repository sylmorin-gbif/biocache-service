package au.org.ala.biocache.util.thread;

import java.util.concurrent.*;

/**
 * A ThreadPoolExecutor to prevent any one user from prioritizing the work queue.
 * <p>
 * The Callable or Runnable used by ThreadPoolExecutor.submit must also be an instance of UserBalancedDownload. This
 * enables the identification of the user associated with the download.
 */
public class UserBalancedThreadPoolExecutor extends ThreadPoolExecutor {
    UserBalancedBlockingQueue workQueue;

    public UserBalancedThreadPoolExecutor(int corePoolSize,
                                          int maximumPoolSize,
                                          long keepAliveTime,
                                          TimeUnit unit,
                                          ThreadFactory threadFactory) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, new UserBalancedBlockingQueue(), threadFactory);
        workQueue = (UserBalancedBlockingQueue) getQueue();
    }

    /**
     * Create a RunnableFuture that provides access to the ScheduledDownload interface of the Callable
     *
     * @param c
     * @param <T>
     * @return
     */
    @Override
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> c) {
        return new UserBalancedTask<T>(c);
    }

    /**
     * Create a RunnableFuture that provides access to the ScheduledDownload interface of the Runnable
     *
     * @param r
     * @param v
     * @param <T>
     * @return
     */
    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable r, T v) {
        return new UserBalancedTask<T>(r, v);
    }

    /**
     * Inform the workQueue when a Runnable with the UserBalancedTask interface is finished.
     *
     * @param r
     * @param t
     */
    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        super.afterExecute(r, t);

        if (r instanceof UserBalancedTask) {
            workQueue.endRunnable(((UserBalancedTask) r).getSchedulingDownload());
        }
    }

    /**
     * Inform the workQueue when a Runnable with the UserBalancedTask interface is started.
     *
     * @param t
     * @param r
     */
    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        super.beforeExecute(t, r);

        if (r instanceof UserBalancedTask) {
            workQueue.startRunnable(((UserBalancedTask) r).getSchedulingDownload());
        }
    }

}

