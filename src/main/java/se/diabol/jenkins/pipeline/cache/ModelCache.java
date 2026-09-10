/*
This file is part of Delivery Pipeline Plugin.

Delivery Pipeline Plugin is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Delivery Pipeline Plugin is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Delivery Pipeline Plugin.
If not, see <http://www.gnu.org/licenses/>.
*/
package se.diabol.jenkins.pipeline.cache;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.RunListener;
import hudson.model.queue.QueueListener;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import se.diabol.jenkins.pipeline.model.Component;
import se.diabol.jenkins.pipeline.model.Pipeline;
import se.diabol.jenkins.pipeline.model.Stage;
import se.diabol.jenkins.pipeline.model.Task;

/**
 * Remembers computed view models for a short while, so that many browsers polling the same view do not each walk
 * the build history. An entry remembers the jobs its model shows: when a build of one of them starts, ends or is
 * deleted, or one of them enters or leaves the queue, only the entries showing that job are dropped, so that a busy
 * controller does not recompute every board on every event. A job being created, reconfigured, renamed or deleted
 * empties the cache, because that can change which jobs belong to which pipeline. A model that contains a running
 * or queued build expires quickly on its own as well, because stages of a Pipeline run come and go without any of
 * those events.
 * <p>Two system properties tune how long an entry may be served, in seconds, and are read on every request so that
 * they can be changed at runtime as well as set at startup:
 * <ul>
 * <li>{@code se.diabol.jenkins.pipeline.cache.ModelCache.idleSeconds} (default 30) for a model in which nothing is
 * running or queued;</li>
 * <li>{@code se.diabol.jenkins.pipeline.cache.ModelCache.activeSeconds} (default 2) for a model with a running or
 * queued build, which bounds how stale a progress bar or a Pipeline stage can be.</li>
 * </ul>
 * Zero turns the cache off.
 */
@Extension
public class ModelCache {

    static final String IDLE_SECONDS_PROPERTY = ModelCache.class.getName() + ".idleSeconds";
    static final String ACTIVE_SECONDS_PROPERTY = ModelCache.class.getName() + ".activeSeconds";
    private static final int MAX_ENTRIES = 1000;

    /** A cached model with the full names of the jobs it shows. */
    private record Entry(List<Component> components, long expiresAt, Set<String> jobs) {
        /** Whether the model shows the job, or something inside it such as a matrix configuration or a promotion. */
        boolean shows(String jobFullName) {
            for (String job : jobs) {
                if (jobFullName.equals(job) || jobFullName.startsWith(job + "/")) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public static ModelCache get() {
        return ExtensionList.lookupSingleton(ModelCache.class);
    }

    /** The cached model for the key, or the loader's result which is then cached. */
    public List<Component> get(String key, Supplier<List<Component>> loader) {
        long now = System.currentTimeMillis();
        Entry entry = entries.compute(key, (k, old) -> {
            if (old != null && old.expiresAt() > now) {
                return old;
            }
            List<Component> components = loader.get();
            return new Entry(components, now + (isActive(components) ? activeTtlMillis() : idleTtlMillis()),
                    jobsOf(components));
        });
        if (entries.size() > MAX_ENTRIES) {
            entries.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
        }
        return entry.components();
    }

    public void clear() {
        entries.clear();
    }

    /** Drops the models that show the job, or something inside it such as a matrix configuration or a promotion. */
    public void invalidate(String jobFullName) {
        if (jobFullName == null) {
            clear();
            return;
        }
        entries.values().removeIf(entry -> entry.shows(jobFullName));
    }

    static Set<String> jobsOf(List<Component> components) {
        Set<String> result = new HashSet<>();
        for (Component component : components) {
            if (component.firstJob() != null) {
                result.add(component.firstJob().fullName());
            }
            for (Pipeline pipeline : component.pipelines()) {
                if (pipeline.jobFullName() != null) {
                    result.add(pipeline.jobFullName());
                }
                for (Stage stage : pipeline.stages()) {
                    for (Task task : stage.tasks()) {
                        if (task.jobFullName() != null) {
                            result.add(task.jobFullName());
                        }
                    }
                }
            }
        }
        return result;
    }

    private static void invalidate(Run<?, ?> run) {
        get().invalidate(run.getParent().getFullName());
    }

    /** A queue item's task is the job itself, or for a Pipeline's executor placeholder a task owned by the job. */
    private static void invalidate(Queue.Item item) {
        if (item.task.getOwnerTask() instanceof Item job) {
            get().invalidate(job.getFullName());
        } else {
            get().clear();
        }
    }

    static long idleTtlMillis() {
        return 1000L * Long.getLong(IDLE_SECONDS_PROPERTY, 30);
    }

    static long activeTtlMillis() {
        return 1000L * Long.getLong(ACTIVE_SECONDS_PROPERTY, 2);
    }

    private static boolean isActive(List<Component> components) {
        for (Component component : components) {
            if (component.isActive()) {
                return true;
            }
        }
        return false;
    }

    @Extension
    public static class RunChanges extends RunListener<Run<?, ?>> {
        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            invalidate(run);
        }

        @Override
        public void onCompleted(Run<?, ?> run, TaskListener listener) {
            invalidate(run);
        }

        @Override
        public void onFinalized(Run<?, ?> run) {
            invalidate(run);
        }

        @Override
        public void onDeleted(Run<?, ?> run) {
            invalidate(run);
        }
    }

    @Extension
    public static class QueueChanges extends QueueListener {
        @Override
        public void onEnterWaiting(Queue.WaitingItem item) {
            invalidate(item);
        }

        @Override
        public void onLeft(Queue.LeftItem item) {
            invalidate(item);
        }
    }

    @Extension
    public static class ItemChanges extends ItemListener {
        @Override
        public void onCreated(Item item) {
            get().clear();
        }

        @Override
        public void onUpdated(Item item) {
            get().clear();
        }

        @Override
        public void onDeleted(Item item) {
            get().clear();
        }

        @Override
        public void onLocationChanged(Item item, String oldFullName, String newFullName) {
            get().clear();
        }
    }
}
