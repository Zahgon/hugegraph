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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Turning the text of a path segment, a query parameter or a header into what a resource method
 * declares.
 *
 * The rules are the ones the original runtime applied, and two of them decide most of the
 * answers: a parameter that takes a single value reads only the first value a request repeats,
 * and a value that cannot be converted falls back to the declared default only when it is empty,
 * otherwise it fails the request.
 */
public final class ParamConverters {

    private ParamConverters() {
    }

    /**
     * Converts the values read for one parameter.
     *
     * @param values       every value the request carried for the parameter, in order
     * @param model        the parameter being filled
     * @return the converted value
     */
    public static Object convert(List<String> values, ResourceModel.ParamModel model) {
        Class<?> raw = model.rawType();
        if (List.class.isAssignableFrom(raw) || Set.class.isAssignableFrom(raw) ||
            SortedSet.class.isAssignableFrom(raw)) {
            return convertCollection(values, model);
        }
        String value = values.isEmpty() ? null : values.get(0);
        /*
         * A value that holds nothing but blanks is read as no value at all when the parameter is a
         * primitive, so the declared default answers instead of the conversion failing.
         */
        if (raw.isPrimitive() && (value == null || value.trim().isEmpty())) {
            value = model.defaultValue();
            return value == null ? nullValue(raw) : fromString(value, raw);
        }
        if (value == null) {
            value = model.defaultValue();
        }
        if (value == null) {
            return nullValue(raw);
        }
        Object converted = fromString(value, raw);
        if (converted == null) {
            // The value was empty and the type could not read it: the declared default answers.
            String fallback = model.defaultValue();
            if (fallback == null) {
                return nullValue(raw);
            }
            Object defaulted = fromString(fallback, raw);
            return defaulted == null ? nullValue(raw) : defaulted;
        }
        return converted;
    }

    private static Object convertCollection(List<String> values,
                                            ResourceModel.ParamModel model) {
        List<String> text = values;
        if (text.isEmpty() && model.defaultValue() != null) {
            text = Collections.singletonList(model.defaultValue());
        }
        Class<?> element = elementType(model.type());
        List<Object> converted = new ArrayList<>(text.size());
        for (String value : text) {
            Object item = fromString(value, element);
            converted.add(item);
        }
        Class<?> raw = model.rawType();
        if (SortedSet.class.isAssignableFrom(raw)) {
            return new TreeSet<>(converted);
        }
        if (Set.class.isAssignableFrom(raw)) {
            return new LinkedHashSet<>(converted);
        }
        return converted;
    }

    private static Class<?> elementType(Type type) {
        if (type instanceof ParameterizedType) {
            Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
            if (arguments.length == 1 && arguments[0] instanceof Class) {
                return (Class<?>) arguments[0];
            }
        }
        return String.class;
    }

    /** Zero for a primitive that was never given a value, nothing for everything else. */
    private static Object nullValue(Class<?> raw) {
        if (!raw.isPrimitive()) {
            return null;
        }
        if (raw == boolean.class) {
            return Boolean.FALSE;
        }
        if (raw == char.class) {
            return (char) 0;
        }
        if (raw == byte.class) {
            return (byte) 0;
        }
        if (raw == short.class) {
            return (short) 0;
        }
        if (raw == int.class) {
            return 0;
        }
        if (raw == long.class) {
            return 0L;
        }
        if (raw == float.class) {
            return 0f;
        }
        return 0d;
    }

    /**
     * Reads one value as one type.
     *
     * @return null when the value is empty and the type could not read it, which is what makes a
     *         declared default answer instead
     */
    private static Object fromString(String value, Class<?> raw) {
        Class<?> type = boxed(raw);
        if (type == String.class) {
            return value;
        }
        if (type == Character.class) {
            if (value.length() == 1) {
                return value.charAt(0);
            }
            if (value.isEmpty()) {
                return null;
            }
            throw new ParamException.ExtractorException(
                    new IllegalArgumentException("A single character expected, got: " + value));
        }
        if (type == Boolean.class) {
            return Boolean.valueOf(value);
        }
        if (type.isEnum()) {
            return fromEnum(value, type);
        }
        if (Number.class.isAssignableFrom(type)) {
            return fromNumber(value, type);
        }
        Object built = fromStringMethod(value, type);
        if (built != NOT_HANDLED) {
            return built;
        }
        built = fromConstructor(value, type);
        if (built != NOT_HANDLED) {
            return built;
        }
        return value;
    }

    private static final Object NOT_HANDLED = new Object();

    /**
     * A number is read by the value-of method of its own class. The failure keeps the number
     * format failure as the cause, which is what the answer reports.
     */
    private static Object fromNumber(String value, Class<?> type) {
        try {
            Method valueOf = type.getMethod("valueOf", String.class);
            return valueOf.invoke(null, value);
        } catch (InvocationTargetException e) {
            if (value.isEmpty()) {
                return null;
            }
            throw asRuntime(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * An enumerated value is read by name, and the failure is wrapped once more than a number's
     * is, which the answer shows.
     */
    private static Object fromEnum(String value, Class<?> type) {
        try {
            Method valueOf = type.getMethod("valueOf", String.class);
            return valueOf.invoke(null, value);
        } catch (InvocationTargetException e) {
            if (value.isEmpty()) {
                return null;
            }
            throw new ParamException.ExtractorException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object fromStringMethod(String value, Class<?> type) {
        Method fromString;
        try {
            fromString = type.getMethod("fromString", String.class);
        } catch (NoSuchMethodException e) {
            return NOT_HANDLED;
        }
        try {
            return fromString.invoke(null, value);
        } catch (InvocationTargetException e) {
            if (value.isEmpty()) {
                return null;
            }
            throw new ParamException.ExtractorException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object fromConstructor(String value, Class<?> type) {
        Constructor<?> constructor;
        try {
            constructor = type.getConstructor(String.class);
        } catch (NoSuchMethodException e) {
            return NOT_HANDLED;
        }
        try {
            return constructor.newInstance(value);
        } catch (InvocationTargetException e) {
            if (value.isEmpty()) {
                return null;
            }
            throw new ParamException.ExtractorException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RuntimeException asRuntime(Throwable cause) {
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        return new ParamException.ExtractorException(cause);
    }

    private static Class<?> boxed(Class<?> raw) {
        if (!raw.isPrimitive()) {
            return raw;
        }
        if (raw == boolean.class) {
            return Boolean.class;
        }
        if (raw == char.class) {
            return Character.class;
        }
        if (raw == byte.class) {
            return Byte.class;
        }
        if (raw == short.class) {
            return Short.class;
        }
        if (raw == int.class) {
            return Integer.class;
        }
        if (raw == long.class) {
            return Long.class;
        }
        if (raw == float.class) {
            return Float.class;
        }
        return Double.class;
    }
}
