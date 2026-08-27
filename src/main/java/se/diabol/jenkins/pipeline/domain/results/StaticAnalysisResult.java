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
package se.diabol.jenkins.pipeline.domain.results;

import hudson.model.AbstractBuild;
import hudson.model.Action;
import io.jenkins.plugins.analysis.core.model.AnalysisResult;
import io.jenkins.plugins.analysis.core.model.ResultAction;
import org.kohsuke.stapler.export.Exported;
import se.diabol.jenkins.pipeline.util.JenkinsUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StaticAnalysisResult extends Result {

    private static final String WARNINGS_NG_PLUGIN = "warnings-ng";

    private final int high;
    private final int normal;
    private final int low;

    public StaticAnalysisResult(String name, String url, int high, int normal, int low) {
        super(name, url);
        this.high = high;
        this.normal = normal;
        this.low = low;
    }

    @Exported
    public int getHigh() {
        return high;
    }

    @Exported
    public int getNormal() {
        return normal;
    }

    @Exported
    public int getLow() {
        return low;
    }

    public static List<StaticAnalysisResult> getResults(AbstractBuild<?, ?> build) {
        if (build != null) {
            if (JenkinsUtil.isPluginInstalled(WARNINGS_NG_PLUGIN)) {
                List<StaticAnalysisResult> result = new ArrayList<>();
                for (Action action : build.getAllActions()) {
                    if (action instanceof ResultAction resultAction) {
                        final AnalysisResult r = resultAction.getResult();
                        result.add(new StaticAnalysisResult(
                                action.getDisplayName(),
                                build.getUrl() + action.getUrlName(),
                                r.getTotalHighPrioritySize(),
                                r.getTotalNormalPrioritySize(),
                                r.getTotalLowPrioritySize()));
                    }
                }
                return result;
            }
        }
        return Collections.emptyList();
    }

}
