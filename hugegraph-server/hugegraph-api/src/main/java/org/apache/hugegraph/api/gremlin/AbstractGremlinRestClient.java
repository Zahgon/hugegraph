/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You under
 * the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a copy of the
 * License at
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;

/**
 * The client the gremlin and cypher resources forward a query with.
 *
 * It is a plain HTTP client rather than a client of the specification, because the serving layer
 * no longer brings one; what it answers with is still the specification's own answer type, so
 * the resources that read it are unchanged.
 */
public abstract class AbstractGremlinRestClient {

    private final URI url;
    private final HttpClient client;
    private final Duration timeout;

    protected AbstractGremlinRestClient(String url, int timeout, int maxTotal, int maxPerRoute) {
        this.url = URI.create(url);
        this.timeout = Duration.ofMillis(timeout <= 0 ? Long.MAX_VALUE / 2 : timeout);
        this.client = HttpClient.newBuilder()
                                .connectTimeout(this.timeout)
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .build();
    }

    /** The address the queries are forwarded to. */
    protected URI url() {
        return this.url;
    }

    /** Sends one request on and reads the whole answer. */
    protected Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<byte[]> answer = this.client.send(
                    builder.timeout(this.timeout).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            Response.ResponseBuilder response = Response.status(answer.statusCode());
            for (Map.Entry<String, List<String>> header : answer.headers().map().entrySet()) {
                for (String value : header.getValue()) {
                    response.header(header.getKey(), value);
                }
            }
            return response.entity(answer.body()).build();
        } catch (java.io.IOException e) {
            throw new ProcessingException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingException(e);
        }
    }

    public void close() {
        // The client holds no pool of its own that has to be released.
    }
}
