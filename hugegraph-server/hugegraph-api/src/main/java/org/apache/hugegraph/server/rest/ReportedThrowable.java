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
 * A failure that reports a class name of its own in the error entity.
 *
 * The answers of the original runtime name the class the failure came from, and several of those
 * classes belonged to the runtime rather than to a resource. The names are part of what a client
 * reads, so the serving layer keeps reporting them rather than its own class names.
 */
public interface ReportedThrowable {

    /** The class name the error entity reports for this failure. */
    String reportedName();
}
