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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import se.diabol.jenkins.pipeline.model.Component;

/**
 * Remembers computed view models for a short while, so that many browsers polling the same view do not each walk
 * the build history. Entries are dropped whenever a build starts, ends or is deleted, the queue changes or a job is
 * reconfigured; a model that contains a running or queued build expires quickly on its own as well, because stages
 * of a Pipeline run come and go without any of those events.
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

    private record Entry(List<Component> components, long expiresAt) {
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
            return new Entry(components, now + (isActive(components) ? activeTtlMillis() : idleTtlMillis()));
        });
        if (entries.size() > MAX_ENTRIES) {
            entries.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
        }
        return entry.components();
    }

    public void clear() {
        entries.clear();
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
            get().clear();
        }

        @Override
        public void onCompleted(Run<?, ?> run, TaskListener listener) {
            get().clear();
        }

        @Override
        public void onFinalized(Run<?, ?> run) {
            get().clear();
        }

        @Override
        public void onDeleted(Run<?, ?> run) {
            get().clear();
        }
    }

    @Extension
    public static class QueueChanges extends QueueListener {
        @Override
        public void onEnterWaiting(Queue.WaitingItem item) {
            get().clear();
        }

        @Override
        public void onLeft(Queue.LeftItem item) {
            get().clear();
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
