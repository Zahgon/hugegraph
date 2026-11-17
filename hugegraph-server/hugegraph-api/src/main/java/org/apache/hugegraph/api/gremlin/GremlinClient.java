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

package org.apache.hugegraph.api.gremlin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.apache.hugegraph.api.filter.CompressInterceptor;
import org.apache.hugegraph.util.E;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

/**
 * GremlinClient is a client for interacting with a Gremlin server.
 * It provides methods for sending GET and POST requests.
 */
public class GremlinClient extends AbstractGremlinRestClient {

    /**
     * Constructs a GremlinClient with the specified URL, timeout, maxTotal, and maxPerRoute.
     *
     * @param url         The URL of the Gremlin server this client will interact with.
     * @param timeout     The timeout for the client.
     * @param maxTotal    The maximum total connections for the client.
     * @param maxPerRoute The maximum connections per route for the client.
     */
    public GremlinClient(String url, int timeout, int maxTotal, int maxPerRoute) {
        super(url, timeout, maxTotal, maxPerRoute);
    }

    /**
     * Sends a POST request to the Gremlin server.
     *
     * @param auth The authorization token for the request.
     * @param req  The body of the request.
     * @return The response from the server.
     */
    public Response doPostRequest(String auth, String req) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(this.url())
                .POST(HttpRequest.BodyPublishers.ofString(req, StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
        return decoded(this.send(headers(builder, auth)));
    }

    /**
     * Sends a GET request to the Gremlin server.
     *
     * @param auth   The authorization token for the request.
     * @param params The query parameters for the request.
     * @return The response from the server.
     */
    public Response doGetRequest(String auth, MultivaluedMap<String, String> params) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            E.checkArgument(entry.getValue().size() == 1,
                            "Invalid query param '%s', can only accept one value, but got %s",
                            entry.getKey(), entry.getValue());
            query.append(query.length() == 0 ? '?' : '&')
                 .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                 .append('=')
                 .append(URLEncoder.encode(entry.getValue().get(0), StandardCharsets.UTF_8));
        }
        URI target = URI.create(this.url().toString() + query);
        return decoded(this.send(headers(HttpRequest.newBuilder(target).GET(), auth)));
    }

    private static HttpRequest.Builder headers(HttpRequest.Builder builder, String auth) {
        if (auth != null) {
            builder.header(HttpHeaders.AUTHORIZATION, auth);
        }
        return builder.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                      .header(HttpHeaders.ACCEPT_ENCODING, CompressInterceptor.GZIP);
    }

    /** Unpacks an answer the gremlin server compressed, which the client asked it for. */
    private static Response decoded(Response response) {
        if (!CompressInterceptor.GZIP.equalsIgnoreCase(
                response.getHeaderString(HttpHeaders.CONTENT_ENCODING))) {
            return response;
        }
        Object entity = response.getEntity();
        if (!(entity instanceof byte[])) {
            return response;
        }
        try (GZIPInputStream input =
                     new GZIPInputStream(new java.io.ByteArrayInputStream((byte[]) entity))) {
            ByteArrayOutputStream unpacked = new ByteArrayOutputStream();
            input.transferTo(unpacked);
            Response.ResponseBuilder builder = Response.status(response.getStatus());
            response.getMetadata().forEach((name, values) -> {
                if (!HttpHeaders.CONTENT_ENCODING.equalsIgnoreCase(name) &&
                    !HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
                    values.forEach(value -> builder.header(name, value));
                }
            });
            return builder.entity(unpacked.toByteArray()).build();
        } catch (IOException e) {
            throw new ProcessingException(e);
        }
    }
}
