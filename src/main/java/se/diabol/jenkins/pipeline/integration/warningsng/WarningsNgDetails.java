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
package se.diabol.jenkins.pipeline.integration.warningsng;

import hudson.Extension;
import hudson.model.Run;
import io.jenkins.plugins.analysis.core.model.AnalysisResult;
import io.jenkins.plugins.analysis.core.model.ResultAction;
import java.util.ArrayList;
import java.util.List;
import se.diabol.jenkins.pipeline.details.TaskDetailsContributor;
import se.diabol.jenkins.pipeline.model.AnalysisSummary;

/** Static analysis warning counts of a build, from the Warnings Next Generation plugin. */
@Extension(optional = true)
public class WarningsNgDetails extends TaskDetailsContributor {

    @SuppressWarnings("unused")
    private static final Class<?> REQUIRED = ResultAction.class;

    @Override
    public List<AnalysisSummary> analysis(Run<?, ?> build) {
        List<AnalysisSummary> result = new ArrayList<>();
        for (ResultAction action : build.getActions(ResultAction.class)) {
            AnalysisResult analysis = action.getResult();
            result.add(new AnalysisSummary(action.getDisplayName(), build.getUrl() + action.getUrlName(),
                    analysis.getTotalHighPrioritySize(), analysis.getTotalNormalPrioritySize(),
                    analysis.getTotalLowPrioritySize()));
        }
        return result;
    }
}
