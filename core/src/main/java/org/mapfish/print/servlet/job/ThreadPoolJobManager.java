/*
 * Copyright (C) 2014  Camptocamp
 *
 * This file is part of MapFish Print
 *
 * MapFish Print is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MapFish Print is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MapFish Print.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mapfish.print.servlet.job;

import com.google.common.base.Optional;
import org.json.JSONException;
import org.mapfish.print.servlet.registry.Registry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * A JobManager backed by a {@link java.util.concurrent.ThreadPoolExecutor}.
 *
 * @author jesseeichar on 3/18/14.
 */
public class ThreadPoolJobManager implements JobManager {
    /**
     * The prefix for looking up the uri a completed report in the registry.
     */
    private static final String REPORT_URI_PREFIX = "REPORT_URI_";
    /**
     * Key for storing the number of print jobs currently running.
     */
    private static final String NEW_PRINT_COUNT = "newPrintCount";
    /**
     * The number of print requests made. ???
     */
    private static final String LAST_PRINT_COUNT = "lastPrintCount";

    /**
     * Total time spent printing.
     */
    private static final String TOTAL_PRINT_TIME = "totalPrintTime";
    /**
     * Number of print jobs done.
     */
    private static final String NB_PRINT_DONE = "nbPrintDone";
    /**
     * A registry tracking when the last time a metadata was check to see if it is done.
     */
    private static final String LAST_POLL = "lastPoll_";
    private static final int DEFAULT_MAX_WAITING_JOBS = 5000;
    private static final long DEFAULT_THREAD_IDLE_TIME = 60L;

    /**
     * The maximum number of threads that will be used for print jobs, this is not the number of threads
     * used by the system because there can be more used by the {@link org.mapfish.print.processor.ProcessorDependencyGraph}
     * when actually doing the printing.
     */
    private int maxNumberOfRunningPrintJobs = Runtime.getRuntime().availableProcessors();
    /**
     * The maximum number of print job requests that are waiting to be executed.
     * <p/>
     * This prevents spikes in requests from completely destroying the server.
     */
    private int maxNumberOfWaitingJobs = DEFAULT_MAX_WAITING_JOBS;
    /**
     * The amount of time to let a thread wait before being shutdown.
     */
    private long maxIdleTime = DEFAULT_THREAD_IDLE_TIME;
    /**
     * A comparator for comparing {@link org.mapfish.print.servlet.job.SubmittedPrintJob}s and
     * prioritizing them.
     * <p/>
     * For example it could be that requests from certain users (like executive officers) are prioritized over requests from
     * other users.
     */
    private Comparator<PrintJob> jobPriorityComparator = new Comparator<PrintJob>() {
        @Override
        public int compare(final PrintJob o1, final PrintJob o2) {
            return 0;
        }
    };

    private ExecutorService executor;

    private final Collection<SubmittedPrintJob> runningTasksFutures = new ArrayList<SubmittedPrintJob>();
    @Autowired
    private Registry registry;
    private PriorityBlockingQueue<Runnable> queue;
    private Timer timer;

    public final void setMaxNumberOfRunningPrintJobs(final int maxNumberOfRunningPrintJobs) {
        this.maxNumberOfRunningPrintJobs = maxNumberOfRunningPrintJobs;
    }

    public final void setMaxNumberOfWaitingJobs(final int maxNumberOfWaitingJobs) {
        this.maxNumberOfWaitingJobs = maxNumberOfWaitingJobs;
    }

    public final void setJobPriorityComparator(final Comparator<PrintJob> jobPriorityComparator) {
        this.jobPriorityComparator = jobPriorityComparator;
    }

    /**
     * Called by spring after constructing the java bean.
     */
    @PostConstruct
    public final void init() {
        CustomizableThreadFactory threadFactory = new CustomizableThreadFactory();
        threadFactory.setDaemon(true);
        threadFactory.setThreadNamePrefix("PrintJobManager-");

        this.queue = new PriorityBlockingQueue<Runnable>(this.maxNumberOfWaitingJobs, new Comparator<Runnable>() {
            @Override
            public int compare(final Runnable o1, final Runnable o2) {
                if (o1 instanceof PrintJob) {
                    if (o2 instanceof PrintJob) {
                        return ThreadPoolJobManager.this.jobPriorityComparator.compare((PrintJob) o1, (PrintJob) o2);
                    }
                    return 1;
                } else if (o2 instanceof PrintJob) {
                    return -1;
                }
                return 0;
            }
        });
        this.executor = new ThreadPoolExecutor(0, this.maxNumberOfRunningPrintJobs, this.maxIdleTime, TimeUnit.SECONDS, this.queue,
                threadFactory);


        this.timer = new Timer("Post result to registry", true);
        this.timer.schedule(new PostResultToRegistryTask(), PostResultToRegistryTask.CHECK_INTERVAL,
                PostResultToRegistryTask.CHECK_INTERVAL);
    }

    /**
     * Called by spring when application context is being destroyed.
     */
    @PreDestroy
    public final void shutdown() {
        this.timer.cancel();
        this.executor.shutdownNow();
    }

    @Override
    public final void submit(final PrintJob job) {
        final int numberOfWaitingRequests = this.queue.size();
        if (numberOfWaitingRequests >= this.maxNumberOfWaitingJobs) {
            throw new RuntimeException("Max number of waiting print job requests exceeded.  Number of waiting requests are: " +
                                       numberOfWaitingRequests);
        }

        this.registry.incrementInt(NEW_PRINT_COUNT, 1);
        final Future<PrintJobStatus> future = this.executor.submit(job);
        this.runningTasksFutures.add(new SubmittedPrintJob(future, job.getReferenceId()));
        try {
            new PendingPrintJob(job.getReferenceId(), job.getAppId()).store(this.registry);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public final int getNumberOfRequestsMade() {
        return this.registry.opt(NEW_PRINT_COUNT, 0);
    }

    @Override
    public final boolean isDone(final String referenceId) throws NoSuchReferenceException {
        boolean done = getCompletedPrintJob(referenceId).isPresent();
        if (!done) {
            this.registry.put(LAST_POLL + referenceId, new Date().getTime());
        }
        return done;
    }

    @Override
    public final long timeSinceLastStatusCheck(final String referenceId) {
        return this.registry.opt(LAST_POLL + referenceId, System.currentTimeMillis());
    }

    @Override
    public final long getAverageTimeSpentPrinting() {
        return this.registry.opt(TOTAL_PRINT_TIME, 0L) / this.registry.opt(NB_PRINT_DONE, 1).longValue();
    }

    @Override
    public final int getLastPrintCount() {
        return this.registry.opt(LAST_PRINT_COUNT, 0);
    }

    @Override
    public final Optional<? extends PrintJobStatus> getCompletedPrintJob(final String referenceId)
            throws NoSuchReferenceException {
        try {
            Optional<? extends PrintJobStatus> jobStatus = PrintJobStatus.load(referenceId, this.registry);
            if (jobStatus.get() instanceof PendingPrintJob) {
                // not yet completed
                return Optional.absent();
            } else {
                return jobStatus;
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    private class PostResultToRegistryTask extends TimerTask {

        private static final int CHECK_INTERVAL = 500;

        @Override
        public void run() {
            if (ThreadPoolJobManager.this.executor.isShutdown()) {
                return;
            }
            Iterator<SubmittedPrintJob> iterator = ThreadPoolJobManager.this.runningTasksFutures.iterator();
            while (iterator.hasNext()) {
                SubmittedPrintJob next = iterator.next();
                if (next.getReportFuture().isDone()) {
                    iterator.remove();
                    final Registry registryRef = ThreadPoolJobManager.this.registry;
                    try {
                        next.getReportFuture().get().store(registryRef);
                        registryRef.incrementInt(NB_PRINT_DONE, 1);
                        registryRef.incrementLong(TOTAL_PRINT_TIME, next.getTimeSinceStart());
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (ExecutionException e) {
                        registryRef.incrementInt(LAST_PRINT_COUNT, 1);
                    } catch (JSONException e) {
                        registryRef.incrementInt(LAST_PRINT_COUNT, 1);
                    }
                }
            }
        }
    }
}