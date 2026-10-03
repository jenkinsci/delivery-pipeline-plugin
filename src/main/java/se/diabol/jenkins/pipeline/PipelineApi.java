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

import hudson.Util;
import hudson.model.Api;
import hudson.model.View;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import se.diabol.jenkins.pipeline.cache.ModelCache;
import se.diabol.jenkins.pipeline.model.Component;
import se.diabol.jenkins.pipeline.model.ViewModel;

/**
 * The view's {@code api/json}, which the page polls. The JSON is exported once per cached model and viewer and
 * served with an ETag made of the model's version and the viewer; a poll that sends it back and finds the model
 * unchanged is answered with 304 Not Modified and no body. Only the server time is written afresh into every
 * response. Requests with Stapler's own parameters ({@code tree}, {@code depth}, {@code pretty}, ...) are exported
 * on the spot as any {@code api/json} is. Responses must never be served from an HTTP cache.
 */
public class PipelineApi extends Api {

    private static final String[] STAPLER_PARAMETERS = {"tree", "depth", "xpath", "wrapper", "pretty", "jsonp"};

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
        for (String parameter : STAPLER_PARAMETERS) {
            if (req.getParameter(parameter) != null) {
                super.doJson(req, rsp);
                return;
            }
        }
        ModelCache.Cached cached = view.cached(req);
        String viewer = Util.getDigestOf(Jenkins.getAuthentication2().getName()).substring(0, 12);
        String etag = "W/\"" + cached.version() + "-" + viewer + "\"";
        rsp.setHeader("ETag", etag);
        if (etag.equals(req.getHeader("If-None-Match"))) {
            rsp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }
        byte[] json = cached.export(viewer, () -> exportOf(cached.components()));
        byte[] tail = (",\"serverTime\":" + System.currentTimeMillis() + "}").getBytes(StandardCharsets.UTF_8);
        rsp.setContentType("application/json;charset=UTF-8");
        try (OutputStream out = rsp.getOutputStream()) {
            out.write(json, 0, json.length - 1);
            out.write(tail);
        }
    }

    private byte[] exportOf(List<Component> components) {
        try {
            byte[] json = new ViewModel(components, view.getSettings()).toJson();
            if (json.length == 0 || json[json.length - 1] != '}') {
                throw new IOException("The exported model does not end with a brace");
            }
            return json;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
