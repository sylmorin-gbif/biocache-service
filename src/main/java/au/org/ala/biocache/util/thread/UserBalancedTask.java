package au.org.ala.biocache.util.thread;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * A FutureTask used by UserBalancedThreadPoolExecutor that exposes the source Runnable or Callable that is also
 * an instance of UserBalancedDownload.
 *
 * @param <T>
 */
class UserBalancedTask<T> extends FutureTask<T> {
    Object source = null;

    public UserBalancedTask(Callable<T> callable) {
        super(callable);
        source = callable;
    }

    public UserBalancedTask(Runnable runnable, T result) {
        super(runnable, result);
        source = runnable;
    }

    /**
     * Get the ScheduledDownload object of the source Runnable or Callable, if it exists.
     *
     * @return
     */
    public UserBalancedDownload getSchedulingDownload() {
        if (source instanceof UserBalancedDownload) {
            return (UserBalancedDownload) source;
        } else {
            return null;
        }
    }
}
