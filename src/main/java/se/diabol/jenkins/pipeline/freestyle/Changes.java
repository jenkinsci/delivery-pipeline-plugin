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
package se.diabol.jenkins.pipeline.freestyle;

import hudson.model.Run;
import hudson.model.User;
import hudson.scm.ChangeLogSet;
import hudson.scm.RepositoryBrowser;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.scm.RunWithSCM;
import se.diabol.jenkins.pipeline.model.Change;
import se.diabol.jenkins.pipeline.model.Contributor;

/** Reads the change log of a build. */
public final class Changes {

    private static final Logger LOG = Logger.getLogger(Changes.class.getName());

    private Changes() {
    }

    public static List<Change> of(Run<?, ?> run) {
        List<Change> result = new ArrayList<>();
        if (!(run instanceof RunWithSCM<?, ?> withScm)) {
            return result;
        }
        for (ChangeLogSet<? extends ChangeLogSet.Entry> changeSet : withScm.getChangeSets()) {
            RepositoryBrowser<?> browser = changeSet.getBrowser();
            for (ChangeLogSet.Entry entry : changeSet) {
                User author = entry.getAuthor();
                Contributor contributor = new Contributor(author.getDisplayName(), author.getUrl());
                result.add(new Change(contributor, entry.getMsg(), entry.getCommitId(), linkOf(browser, entry, run)));
            }
        }
        return result;
    }

    /** The distinct authors of the changes, in order of first appearance. */
    public static List<Contributor> contributorsOf(List<Change> changes) {
        Map<String, Contributor> result = new LinkedHashMap<>();
        for (Change change : changes) {
            result.putIfAbsent(change.author().name(), change.author());
        }
        return new ArrayList<>(result.values());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String linkOf(RepositoryBrowser<?> browser, ChangeLogSet.Entry entry, Run<?, ?> run) {
        if (browser == null) {
            return null;
        }
        try {
            URL link = ((RepositoryBrowser) browser).getChangeSetLink(entry);
            return link == null ? null : link.toExternalForm();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not get the change set link for " + run.getFullDisplayName(), e);
            return null;
        }
    }
}
