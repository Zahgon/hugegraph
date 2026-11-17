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

/**
 * The request a thread is answering, for the parts of the module that are handed it rather than
 * asking for it.
 *
 * The scope is opened when a request starts being answered and closed once the answer has been
 * written, in a finally, so that a thread that goes back into the pool carries nothing of the
 * request it last answered.
 */
public final class RequestScope {

    private static final ThreadLocal<RestRequestContext> CURRENT = new ThreadLocal<>();

    private RequestScope() {
    }

    /** Opens the scope for the request this thread is about to answer. */
    public static void open(RestRequestContext request) {
        CURRENT.set(request);
    }

    /** The request this thread is answering, or null outside a request. */
    public static RestRequestContext current() {
        return CURRENT.get();
    }

    /** Closes the scope, leaving nothing of the request behind. */
    public static void close() {
        CURRENT.remove();
    }
}
