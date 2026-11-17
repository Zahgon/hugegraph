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

package org.apache.hugegraph.server.rest;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;

/** The request headers as a resource method that asks for them sees them. */
public final class RestHttpHeaders implements HttpHeaders {

    private final RestRequestContext request;

    public RestHttpHeaders(RestRequestContext request) {
        this.request = request;
    }

    @Override
    public List<String> getRequestHeader(String name) {
        return this.request.getHeaders().get(name);
    }

    @Override
    public String getHeaderString(String name) {
        return this.request.getHeaderString(name);
    }

    @Override
    public MultivaluedMap<String, String> getRequestHeaders() {
        return this.request.getHeaders();
    }

    @Override
    public List<MediaType> getAcceptableMediaTypes() {
        return this.request.getAcceptableMediaTypes();
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
        return this.request.getAcceptableLanguages();
    }

    @Override
    public MediaType getMediaType() {
        return this.request.getMediaType();
    }

    @Override
    public Locale getLanguage() {
        return this.request.getLanguage();
    }

    @Override
    public Map<String, Cookie> getCookies() {
        return this.request.getCookies();
    }

    @Override
    public Date getDate() {
        return this.request.getDate();
    }

    @Override
    public int getLength() {
        return this.request.getLength();
    }
}
