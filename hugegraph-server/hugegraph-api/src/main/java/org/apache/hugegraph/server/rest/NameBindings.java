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

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.ws.rs.NameBinding;

/**
 * Which interceptors a resource method brings into play.
 *
 * An interceptor that carries a binding annotation runs only for the methods that carry the same
 * annotation, on the method itself or on its class; one that carries none runs for everything.
 * The compressing and decompressing interceptors of the module are bound this way, so the
 * answers of every other method are untouched by them.
 */
public final class NameBindings {

    private NameBindings() {
    }

    /** The interceptors that apply to one resource method. */
    public static <T> List<T> filter(List<T> providers, ResourceModel.MethodModel model) {
        Set<Class<? extends Annotation>> bound = new LinkedHashSet<>();
        for (Annotation annotation : model.method().getDeclaredAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(NameBinding.class)) {
                bound.add(annotation.annotationType());
            }
        }
        for (Annotation annotation : model.method().getDeclaringClass().getDeclaredAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(NameBinding.class)) {
                bound.add(annotation.annotationType());
            }
        }
        List<T> applied = new ArrayList<>();
        for (T provider : providers) {
            Set<Class<? extends Annotation>> required = bindingsOf(provider);
            if (required.isEmpty() || bound.containsAll(required)) {
                applied.add(provider);
            }
        }
        return applied;
    }

    /** The interceptors that carry no binding annotation, which apply to everything. */
    public static <T> List<T> unbound(List<T> providers) {
        List<T> applied = new ArrayList<>();
        for (T provider : providers) {
            if (bindingsOf(provider).isEmpty()) {
                applied.add(provider);
            }
        }
        return applied;
    }

    private static Set<Class<? extends Annotation>> bindingsOf(Object provider) {
        Set<Class<? extends Annotation>> bindings = new LinkedHashSet<>();
        for (Annotation annotation : provider.getClass().getDeclaredAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(NameBinding.class)) {
                bindings.add(annotation.annotationType());
            }
        }
        return bindings;
    }
}
