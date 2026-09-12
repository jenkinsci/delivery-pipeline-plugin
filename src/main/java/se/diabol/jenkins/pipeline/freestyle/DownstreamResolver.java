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

import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.AbstractProject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the projects a project triggers. Jenkins core knows about build triggers and anything that declares itself in
 * the dependency graph; other plugins contribute their own relations through this extension point.
 */
public abstract class DownstreamResolver implements ExtensionPoint {

    public abstract List<AbstractProject<?, ?>> downstreamOf(AbstractProject<?, ?> project);

    public static ExtensionList<DownstreamResolver> all() {
        return ExtensionList.lookup(DownstreamResolver.class);
    }

    /** The downstream projects reported by every resolver, without duplicates, resolvers in extension order. */
    public static List<AbstractProject<?, ?>> resolve(AbstractProject<?, ?> project) {
        Set<String> seen = new LinkedHashSet<>();
        List<AbstractProject<?, ?>> result = new ArrayList<>();
        for (DownstreamResolver resolver : all()) {
            for (AbstractProject<?, ?> downstream : resolver.downstreamOf(project)) {
                if (downstream != null && seen.add(downstream.getFullName())) {
                    result.add(downstream);
                }
            }
        }
        return result;
    }

    /** The relations Jenkins core keeps in its dependency graph: build triggers and dependency declarers. */
    @Extension(ordinal = 100)
    public static class Core extends DownstreamResolver {
        @Override
        public List<AbstractProject<?, ?>> downstreamOf(AbstractProject<?, ?> project) {
            List<AbstractProject<?, ?>> result = new ArrayList<>();
            for (AbstractProject<?, ?> downstream : project.getDownstreamProjects()) {
                result.add(downstream);
            }
            return result;
        }
    }
}
