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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import se.diabol.jenkins.pipeline.model.Component;

@WithJenkins
class ModelCacheTest {

    @Test
    void aModelIsComputedOncePerKeyUntilSomethingChanges(JenkinsRule jenkins) throws Exception {
        ModelCache cache = ModelCache.get();
        AtomicInteger computed = new AtomicInteger();
        List<Component> first = cache.get("k", () -> {
            computed.incrementAndGet();
            return List.of(Component.failed("a", 1, "x"));
        });
        List<Component> second = cache.get("k", () -> {
            computed.incrementAndGet();
            return List.of();
        });
        assertThat(second, sameInstance(first));
        assertThat(computed.get(), is(1));
        cache.get("other", () -> {
            computed.incrementAndGet();
            return List.of();
        });
        assertThat(computed.get(), is(2));

        jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject("job"));
        List<Component> afterBuild = cache.get("k", () -> {
            computed.incrementAndGet();
            return List.of();
        });
        assertThat("a build clears the cache", afterBuild, not(sameInstance(first)));
        assertThat(computed.get(), is(3));
    }

    @Test
    void theTimeToLiveComesFromSystemPropertiesReadOnEveryRequest(JenkinsRule jenkins) {
        ModelCache cache = ModelCache.get();
        AtomicInteger computed = new AtomicInteger();
        Supplier<List<Component>> loader = () -> {
            computed.incrementAndGet();
            return List.of(Component.failed("a", 1, "x"));
        };
        String before = System.getProperty(ModelCache.IDLE_SECONDS_PROPERTY);
        try {
            System.setProperty(ModelCache.IDLE_SECONDS_PROPERTY, "0");
            cache.get("ttl", loader);
            cache.get("ttl", loader);
            assertThat("an idle time to live of zero turns the cache off", computed.get(), is(2));
            System.setProperty(ModelCache.IDLE_SECONDS_PROPERTY, "300");
            cache.get("ttl", loader);
            cache.get("ttl", loader);
            assertThat("the new value applies without a restart", computed.get(), is(3));
        } finally {
            if (before == null) {
                System.clearProperty(ModelCache.IDLE_SECONDS_PROPERTY);
            } else {
                System.setProperty(ModelCache.IDLE_SECONDS_PROPERTY, before);
            }
            cache.clear();
        }
    }
}
