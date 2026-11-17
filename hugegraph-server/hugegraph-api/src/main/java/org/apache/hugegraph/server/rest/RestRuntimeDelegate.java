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

import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;

/**
 * The runtime behind the static entry points of the specification's own types.
 *
 * Building an answer, building a URI and writing a media type all ask the runtime for the
 * implementation to use; with the serving layer no longer coming from a specification runtime,
 * the service supplies its own.
 */
public final class RestRuntimeDelegate extends RuntimeDelegate {

    /** Makes this the runtime the specification types build with. */
    public static void install() {
        if (!(RuntimeDelegate.getInstance() instanceof RestRuntimeDelegate)) {
            RuntimeDelegate.setInstance(new RestRuntimeDelegate());
        }
    }

    @Override
    public UriBuilder createUriBuilder() {
        return new RestUriBuilder();
    }

    @Override
    public Response.ResponseBuilder createResponseBuilder() {
        return new RestResponseBuilder();
    }

    @Override
    public Link.Builder createLinkBuilder() {
        throw new UnsupportedOperationException("links");
    }

    @Override
    public Variant.VariantListBuilder createVariantListBuilder() {
        throw new UnsupportedOperationException("variant lists");
    }

    @Override
    public <T> T createEndpoint(Application application, Class<T> endpointType) {
        throw new UnsupportedOperationException("endpoints");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HeaderDelegate<T> createHeaderDelegate(Class<T> type) {
        if (type == MediaType.class) {
            return (HeaderDelegate<T>) new MediaTypeDelegate();
        }
        if (type == Date.class) {
            return (HeaderDelegate<T>) new DateDelegate();
        }
        return (HeaderDelegate<T>) new TextDelegate();
    }

    private static final class MediaTypeDelegate implements HeaderDelegate<MediaType> {

        @Override
        public MediaType fromString(String value) {
            return MediaTypes.parse(value);
        }

        @Override
        public String toString(MediaType value) {
            return MediaTypes.format(value);
        }
    }

    private static final class DateDelegate implements HeaderDelegate<Date> {

        @Override
        public Date fromString(String value) {
            throw new UnsupportedOperationException("reading a date header");
        }

        @Override
        public String toString(Date value) {
            return Headers.httpDate(value);
        }
    }

    private static final class TextDelegate implements HeaderDelegate<Object> {

        @Override
        public Object fromString(String value) {
            return value;
        }

        @Override
        public String toString(Object value) {
            return String.valueOf(value);
        }
    }
}
