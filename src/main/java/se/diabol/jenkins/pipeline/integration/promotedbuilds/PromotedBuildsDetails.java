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
package se.diabol.jenkins.pipeline.integration.promotedbuilds;

import hudson.Extension;
import hudson.model.BooleanParameterValue;
import hudson.model.FileParameterValue;
import hudson.model.ParameterValue;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.plugins.promoted_builds.PromotedBuildAction;
import hudson.plugins.promoted_builds.Status;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import se.diabol.jenkins.pipeline.details.TaskDetailsContributor;
import se.diabol.jenkins.pipeline.model.Promotion;

/** Promotions of a build, from the Promoted Builds plugin. */
@Extension(optional = true)
public class PromotedBuildsDetails extends TaskDetailsContributor {

    @SuppressWarnings("unused")
    private static final Class<?> REQUIRED = PromotedBuildAction.class;

    @Override
    public List<Promotion> promotions(Run<?, ?> build) {
        PromotedBuildAction action = build.getAction(PromotedBuildAction.class);
        if (action == null) {
            return List.of();
        }
        List<Promotion> result = new ArrayList<>();
        for (Status status : action.getPromotions()) {
            for (hudson.plugins.promoted_builds.Promotion promotion : status.getPromotionBuilds()) {
                result.add(new Promotion(status.getName(), promotion.getStartTimeInMillis(),
                        promotion.getTime().getTime() - build.getTimeInMillis(), promotion.getUserName(),
                        status.getIcon(), parametersOf(promotion)));
            }
        }
        result.sort(Comparator.comparingLong(Promotion::startTime).reversed());
        return result;
    }

    private static List<String> parametersOf(hudson.plugins.promoted_builds.Promotion promotion) {
        List<String> result = new ArrayList<>();
        for (ParameterValue value : promotion.getParameterValues()) {
            if (value instanceof StringParameterValue string) {
                String text = string.getValue();
                if (text != null && !text.isBlank()) {
                    result.add(value.getName() + ": " + text);
                }
            } else if (value instanceof FileParameterValue file) {
                result.add(value.getName() + ": " + file.getLocation());
            } else if (value instanceof BooleanParameterValue flag && Boolean.TRUE.equals(flag.getValue())) {
                result.add(value.getName() + ": true");
            }
        }
        return result;
    }
}
