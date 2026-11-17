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

import jakarta.ws.rs.NotFoundException;

/**
 * The failure a parameter that cannot be converted is answered with.
 *
 * The original runtime answered such a request as "not found" rather than "bad request", named
 * the place the value came from in the class it reported, and carried the conversion failure as
 * the cause. All three are part of the answer, so all three are reproduced, including the names
 * the original reported.
 */
public abstract class ParamException extends NotFoundException implements ReportedThrowable {

    private static final long serialVersionUID = 1L;

    private final String reportedName;

    ParamException(String reportedName, Throwable cause) {
        super(cause);
        this.reportedName = reportedName;
    }

    @Override
    public String reportedName() {
        return this.reportedName;
    }

    /** A path segment that could not be converted. */
    public static final class PathParamException extends ParamException {

        private static final long serialVersionUID = 1L;

        public PathParamException(Throwable cause) {
            super("org.glassfish.jersey.server.ParamException$PathParamException", cause);
        }
    }

    /** A query parameter that could not be converted. */
    public static final class QueryParamException extends ParamException {

        private static final long serialVersionUID = 1L;

        public QueryParamException(Throwable cause) {
            super("org.glassfish.jersey.server.ParamException$QueryParamException", cause);
        }
    }

    /** A header value that could not be converted. */
    public static final class HeaderParamException extends ParamException {

        private static final long serialVersionUID = 1L;

        public HeaderParamException(Throwable cause) {
            super("org.glassfish.jersey.server.ParamException$HeaderParamException", cause);
        }
    }

    /** The wrapper the original runtime put round a conversion that threw. */
    public static final class ExtractorException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ExtractorException(Throwable cause) {
            super(cause);
        }

        @Override
        public String toString() {
            String name = "org.glassfish.jersey.internal.inject.ExtractorException";
            String message = this.getCause() == null ? null : this.getCause().toString();
            return message == null ? name : name + ": " + message;
        }
    }
}
