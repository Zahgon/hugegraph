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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import jakarta.ws.rs.core.MediaType;

/** How header values are written on the wire. */
public final class Headers {

    private static final String HTTP_DATE = "EEE, dd MMM yyyy HH:mm:ss zzz";

    private Headers() {
    }

    /** Writes one header value the way the answers carry it. */
    public static String toText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof MediaType) {
            return MediaTypes.format((MediaType) value);
        }
        if (value instanceof Date) {
            return httpDate((Date) value);
        }
        return value.toString();
    }

    /** A date in the form the answers carry, in the time zone the original wrote. */
    public static String httpDate(Date date) {
        SimpleDateFormat format = new SimpleDateFormat(HTTP_DATE, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(date);
    }
}
