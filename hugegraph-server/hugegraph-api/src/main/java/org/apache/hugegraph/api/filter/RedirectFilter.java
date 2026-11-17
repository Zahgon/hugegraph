/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.api.filter;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.utils.URIBuilder;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.masterelection.GlobalMasterInfo;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

public class RedirectFilter implements ContainerRequestFilter {

    private static final Logger LOG = Log.logger(RedirectFilter.class);

    private static final String X_HG_REDIRECT = "x-hg-redirect";

    /**
     * Headers a forwarded request may not repeat, because the client that sends it on sets them
     * from the body it writes.
     */
    private static final Set<String> SET_BY_SENDER = Set.of("connection", "content-length",
                                                            "expect", "host", "upgrade");

    private static volatile HttpClient client = null;

    @Context
    private jakarta.inject.Provider<GraphManager> managerProvider;

    private static final Set<String> MUST_BE_NULL = new HashSet<>();

    static {
        MUST_BE_NULL.add("DELETE");
        MUST_BE_NULL.add("GET");
        MUST_BE_NULL.add("HEAD");
        MUST_BE_NULL.add("TRACE");
    }

    @Override
    public void filter(ContainerRequestContext context) throws IOException {
        GraphManager manager = this.managerProvider.get();
        E.checkState(manager != null, "Context GraphManager is absent");

        String redirectTag = context.getHeaderString(X_HG_REDIRECT);
        if (StringUtils.isNotEmpty(redirectTag)) {
            return;
        }

        GlobalMasterInfo globalNodeInfo = manager.globalNodeRoleInfo();
        if (globalNodeInfo == null || !globalNodeInfo.supportElection()) {
            return;
        }
        GlobalMasterInfo.NodeInfo masterInfo = globalNodeInfo.masterInfo();
        if (masterInfo == null || masterInfo.isMaster() ||
            StringUtils.isEmpty(masterInfo.nodeUrl())) {
            return;
        }
        String url = masterInfo.nodeUrl();

        URI redirectUri;
        try {
            URIBuilder redirectURIBuilder = new URIBuilder(context.getUriInfo().getRequestUri());
            URI masterURI = URI.create(url);
            redirectURIBuilder.setHost(masterURI.getHost());
            redirectURIBuilder.setPort(masterURI.getPort());
            redirectURIBuilder.setScheme(masterURI.getScheme());

            redirectUri = redirectURIBuilder.build();
        } catch (URISyntaxException e) {
            LOG.error("Redirect request exception occurred", e);
            return;
        }
        this.initClientIfNeeded();
        Response response = this.forwardRequest(context, redirectUri);
        context.abortWith(response);
    }

    private Response forwardRequest(ContainerRequestContext requestContext, URI redirectUri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(redirectUri);
        MultivaluedMap<String, String> headers = requestContext.getHeaders();
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (SET_BY_SENDER.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                for (String value : entry.getValue()) {
                    builder.header(entry.getKey(), value);
                }
            }
        }
        builder.header(X_HG_REDIRECT, String.valueOf(new Date().getTime()));
        String method = requestContext.getMethod();
        if (MUST_BE_NULL.contains(method)) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofInputStream(
                    requestContext::getEntityStream));
        }
        try {
            HttpResponse<byte[]> answer = client.send(builder.build(),
                                                      HttpResponse.BodyHandlers.ofByteArray());
            Response.ResponseBuilder forwarded = Response.status(answer.statusCode());
            answer.headers().map().forEach((name, values) -> {
                if (!SET_BY_SENDER.contains(name.toLowerCase(Locale.ROOT))) {
                    values.forEach(value -> forwarded.header(name, value));
                }
            });
            return forwarded.entity(answer.body()).build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to forward the request", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while forwarding the request", e);
        }
    }

    private void initClientIfNeeded() {
        if (client != null) {
            return;
        }

        synchronized (RedirectFilter.class) {
            if (client != null) {
                return;
            }
            client = HttpClient.newHttpClient();
        }
    }

    @NameBinding
    @Retention(RetentionPolicy.RUNTIME)
    public @interface RedirectMasterRole {

    }
}
