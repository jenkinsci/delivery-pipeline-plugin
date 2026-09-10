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

import hudson.model.Api;
import hudson.model.View;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/** The view's {@code api/json}, which the page polls; responses must never be served from a cache. */
public class PipelineApi extends Api {

    private final DeliveryPipelineView view;

    public PipelineApi(DeliveryPipelineView view) {
        super(view);
        this.view = view;
    }

    /** A read of the view's model, hence GET and no POST protection; the view's read permission is checked. */
    @Override
    @SuppressWarnings("lgtm[jenkins/csrf]")
    public void doJson(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        view.checkPermission(View.READ);
        rsp.setHeader("Cache-Control", "no-store, must-revalidate");
        super.doJson(req, rsp);
    }
}
