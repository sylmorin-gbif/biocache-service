package au.org.ala.biocache.util.thread;

import au.org.ala.biocache.dto.DownloadDetailsDTO;
import org.apache.commons.lang3.StringUtils;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This queue is used by the UserBalancedThreadPoolExecutor that signals it a UserBalancedDownload before and after
 * the associated UserBalancedTask is executed.
 */
class UserBalancedBlockingQueue extends AbstractQueue<Runnable> implements BlockingQueue<Runnable> {
    final int DEFAULT_USER_DELAY = 5000;

    // number of running tasks by user
    Map<String, AtomicLong> userRunningTasks = new ConcurrentHashMap<>();

    // time of that last task finished by user
    Map<String, Long> userLastTask = new ConcurrentHashMap<>();

    LinkedBlockingQueue<Runnable> baseQueue = new LinkedBlockingQueue<>();

    // used to operate consistently with the baseQueue
    private final ReentrantLock takeLock = new ReentrantLock();
    private final Condition notEmpty = takeLock.newCondition();

    private void signalNotEmpty() {
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
    }


    public UserBalancedBlockingQueue() {
        super();
    }

    /**
     * Get a user identifier.
     * <p>
     * User identifier is an email address, otherwise IP address.
     *
     * @param runnable
     * @return
     */
    private String getUser(UserBalancedDownload runnable) {
        DownloadDetailsDTO details = runnable.getDetails();

        if (details != null) {
            if (StringUtils.isNotEmpty(details.getEmail())) {
                return details.getEmail();
            } else if (StringUtils.isNotEmpty(details.getIpAddress())) {
                return details.getIpAddress();
            }
        }

        // when there are no identifying details, treat as the same user
        return "";
    }

    /**
     * Maintain the list of users by number of running tasks.
     *
     * @param r
     */
    public void startRunnable(UserBalancedDownload r) {
        if (r == null) return;

        String user = getUser(r);

        // increment count of running tasks for this user
        userRunningTasks.putIfAbsent(user, new AtomicLong(0));
        AtomicLong count = userRunningTasks.get(user);
        count.incrementAndGet();
    }

    /**
     * Maintain the list of users by number of running tasks.
     *
     * @param r
     */
    public void endRunnable(UserBalancedDownload r) {
        if (r == null) return;

        String user = getUser(r);

        // decrement count of running tasks for this user
        userRunningTasks.get(user).decrementAndGet();

        // record the end time of the last finished task for this user
        userLastTask.put(user, System.currentTimeMillis());
    }

    @Override
    public Runnable take() throws InterruptedException {
        Runnable next = null;
        takeLock.lockInterruptibly();
        try {
            while (baseQueue.size() == 0) {
                notEmpty.await();
            }

            next = getNextPriorityTask();

            if (next == null) {
                next = baseQueue.take();
            }

            if (baseQueue.size() > 0)
                notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
        return next;
    }

    @Override
    public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
        Runnable next = null;
        long nanos = unit.toNanos(timeout);
        takeLock.lockInterruptibly();
        try {
            while (nanos > 0 && baseQueue.size() == 0) {
                nanos = notEmpty.awaitNanos(nanos);
            }

            next = getNextPriorityTask();

            if (next == null) {
                next = baseQueue.take();
            }

            if (baseQueue.size() > 0)
                notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
        return next;
    }

    @Override
    public Runnable poll() {
        Runnable next = null;
        takeLock.lock();
        try {
            next = getNextPriorityTask();

            if (next == null) {
                // no high priority tasks found, use the default
                next = baseQueue.poll();
            }

            if (baseQueue.size() > 0)
                notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
        return next;
    }

    /**
     * Get the next tasks after balancing task priority by user.
     * <p>
     * Usage of getNextPriorityTask requires the calling method to lock `takeLock`.
     *
     * @return
     */
    private Runnable getNextPriorityTask() {
        Runnable minEntryPriority = null;
        AtomicLong defaultCount = new AtomicLong(0);
        long currentTime = System.currentTimeMillis();

        // search all queued tasks
        Iterator<Runnable> i = baseQueue.iterator();
        while (i.hasNext() && minEntryPriority == null) {
            Runnable next = i.next();
            UserBalancedDownload r = ((UserBalancedTask) i.next()).getSchedulingDownload();

            String user = getUser(r);

            // find user with no running tasks
            if (userRunningTasks.getOrDefault(user, defaultCount).intValue() == 0) {
                // Exclude users with tasks that finished more than DEFAULT_USER_DELAY time ago.
                // This is sufficient to exclude users whose task just finish.
                if (userLastTask.getOrDefault(user, 0L) + DEFAULT_USER_DELAY < currentTime) {
                    minEntryPriority = next;
                }
            }
        }

        if (minEntryPriority != null) {
            baseQueue.remove(minEntryPriority);
            return minEntryPriority;
        }

        return null;
    }

    @Override
    public Iterator<Runnable> iterator() {
        return baseQueue.iterator();
    }

    @Override
    public int size() {
        return baseQueue.size();
    }

    @Override
    public void put(Runnable runnable) throws InterruptedException {
        int size = baseQueue.size();
        baseQueue.put(runnable);
        if (size == 0) {
            signalNotEmpty();
        }
    }

    @Override
    public boolean offer(Runnable runnable, long timeout, TimeUnit unit) throws InterruptedException {
        int size = baseQueue.size();
        boolean result = baseQueue.offer(runnable, timeout, unit);
        if (size == 0) {
            signalNotEmpty();
        }
        return result;
    }

    @Override
    public int remainingCapacity() {
        return baseQueue.remainingCapacity();
    }

    @Override
    public int drainTo(Collection<? super Runnable> c) {
        return baseQueue.drainTo(c);
    }

    @Override
    public int drainTo(Collection<? super Runnable> c, int maxElements) {
        return baseQueue.drainTo(c, maxElements);
    }

    @Override
    public boolean offer(Runnable runnable) {
        int size = baseQueue.size();
        boolean result = baseQueue.offer(runnable);
        if (size == 0) {
            signalNotEmpty();
        }
        return result;
    }

    @Override
    public Runnable peek() {
        takeLock.lock();
        try {
            return baseQueue.peek();
        } finally {
            takeLock.unlock();
        }
    }
}
