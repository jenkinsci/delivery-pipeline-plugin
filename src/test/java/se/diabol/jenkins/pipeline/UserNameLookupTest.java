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
package se.diabol.jenkins.pipeline;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static se.diabol.jenkins.pipeline.PageTestSupport.body;
import static se.diabol.jenkins.pipeline.PageTestSupport.staticClient;

import hudson.ExtensionList;
import hudson.model.Cause;
import hudson.model.FreeStyleProject;
import hudson.model.User;
import hudson.security.AbstractPasswordBasedSecurityRealm;
import hudson.security.GroupDetails;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FakeChangeLogSCM;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import se.diabol.jenkins.pipeline.cache.ModelCache;

/**
 * JENKINS-47899: building the view's model must not look users up in the security realm. A realm such as LDAP
 * answers each lookup with a round trip to the directory, and the model is built again whenever its cache entry
 * expires, which on a busy board is every few seconds. This test stands in for LDAP with a realm and an id resolver
 * that count the lookups, and rebuilds the model before every poll.
 */
@WithJenkins
class UserNameLookupTest {

    private static final int POLLS = 20;

    /** Lookups by user name or id that reached the realm or an id resolver, as an LDAP-backed one would. */
    static final AtomicInteger LOOKUPS = new AtomicInteger();

    @Test
    void rebuildingTheModelDoesNotLookUsersUp(JenkinsRule jenkins) throws Exception {
        jenkins.getInstance().setSecurityRealm(new CountingRealm());
        User.getById("alice", true).setFullName("Alice Example");
        FreeStyleProject job = jenkins.createFreeStyleProject("build");
        FakeChangeLogSCM scm = new FakeChangeLogSCM();
        // bob has no user record yet, so the first lookup of his name goes through the resolvers
        scm.addChange().withAuthor("bob").withMsg("Fix the thing");
        job.setScm(scm);
        jenkins.assertBuildStatusSuccess(job.scheduleBuild2(0, new Cause.UserIdCause("alice")));
        DeliveryPipelineView view = PageTestSupport.view(jenkins, "Build", "build");
        view.setShowChanges(true);
        assertThat("the counting resolver is installed",
                ExtensionList.lookup(User.CanonicalIdResolver.class).get(CountingResolver.class), notNullValue());

        LOOKUPS.set(0);
        try (JenkinsRule.WebClient client = staticClient(jenkins)) {
            for (int i = 0; i < POLLS; i++) {
                ModelCache.get().clear();
                String json = body(jenkins, client, view.getViewUrl() + "api/json");
                assertThat(json, containsString("Alice Example"));
                assertThat(json, containsString("bob"));
            }
        }
        assertThat("lookups in " + POLLS + " rebuilds of the model; one is bob's first", LOOKUPS.get(),
                lessThanOrEqualTo(1));
    }

    /** An id resolver that counts its calls and resolves nothing, so that the lookup carries on to the others. */
    @TestExtension("rebuildingTheModelDoesNotLookUsersUp")
    public static class CountingResolver extends User.CanonicalIdResolver {
        @Override
        public String resolveCanonicalId(String idOrFullName, Map<String, ?> context) {
            LOOKUPS.incrementAndGet();
            return null;
        }

        @Override
        public int getPriority() {
            return Integer.MAX_VALUE;
        }
    }

    /** A realm in which every user exists, counting how often it is asked about one. */
    static class CountingRealm extends AbstractPasswordBasedSecurityRealm {
        @Override
        protected UserDetails authenticate2(String username, String password) {
            return loadUserByUsername2(username);
        }

        @Override
        public UserDetails loadUserByUsername2(String username) throws UsernameNotFoundException {
            LOOKUPS.incrementAndGet();
            return new org.springframework.security.core.userdetails.User(username, "", List.of());
        }

        @Override
        public GroupDetails loadGroupByGroupname2(String groupname, boolean fetchMembers) {
            throw new UsernameNotFoundException(groupname);
        }
    }
}
