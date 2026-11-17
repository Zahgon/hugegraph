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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;

/**
 * The providers a feature of the module adds for one resource method.
 *
 * The module registers one such feature, which puts a filter in front of the methods that carry
 * its own annotation. Asking each feature about each method, rather than running every filter for
 * every request, is what keeps that filter in front of exactly the methods it was declared for.
 */
public final class DynamicFeatures {

    private DynamicFeatures() {
    }

    /** The provider classes the features of the module add for one resource method. */
    public static Set<Class<?>> providersFor(List<DynamicFeature> features,
                                             ResourceModel.MethodModel model) {
        Collector collector = new Collector();
        ResourceInfo info = new Info(model);
        for (DynamicFeature feature : features) {
            feature.configure(info, collector);
        }
        return collector.registered;
    }

    /** What a feature is told about the method it is being asked about. */
    private static final class Info implements ResourceInfo {

        private final ResourceModel.MethodModel model;

        Info(ResourceModel.MethodModel model) {
            this.model = model;
        }

        @Override
        public Method getResourceMethod() {
            return this.model.method();
        }

        @Override
        public Class<?> getResourceClass() {
            return this.model.method().getDeclaringClass();
        }
    }

    /** Collects what a feature registers, and ignores the rest of what it may configure. */
    private static final class Collector implements FeatureContext {

        private final Set<Class<?>> registered = new LinkedHashSet<>();
        private final List<Object> instances = new ArrayList<>();

        @Override
        public Configuration getConfiguration() {
            return new Empty();
        }

        @Override
        public FeatureContext property(String name, Object value) {
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass) {
            this.registered.add(componentClass);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass, int priority) {
            return this.register(componentClass);
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Class<?>... contracts) {
            return this.register(componentClass);
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
            return this.register(componentClass);
        }

        @Override
        public FeatureContext register(Object component) {
            this.instances.add(component);
            return this.register(component.getClass());
        }

        @Override
        public FeatureContext register(Object component, int priority) {
            return this.register(component);
        }

        @Override
        public FeatureContext register(Object component, Class<?>... contracts) {
            return this.register(component);
        }

        @Override
        public FeatureContext register(Object component, Map<Class<?>, Integer> contracts) {
            return this.register(component);
        }
    }

    /** The configuration a feature reads, which this service keeps nothing of. */
    private static final class Empty implements Configuration {

        @Override
        public RuntimeType getRuntimeType() {
            return RuntimeType.SERVER;
        }

        @Override
        public Map<String, Object> getProperties() {
            return java.util.Collections.emptyMap();
        }

        @Override
        public Object getProperty(String name) {
            return null;
        }

        @Override
        public java.util.Collection<String> getPropertyNames() {
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean isEnabled(jakarta.ws.rs.core.Feature feature) {
            return false;
        }

        @Override
        public boolean isEnabled(Class<? extends jakarta.ws.rs.core.Feature> featureClass) {
            return false;
        }

        @Override
        public boolean isRegistered(Object component) {
            return false;
        }

        @Override
        public boolean isRegistered(Class<?> componentClass) {
            return false;
        }

        @Override
        public Map<Class<?>, Integer> getContracts(Class<?> componentClass) {
            return java.util.Collections.emptyMap();
        }

        @Override
        public Set<Class<?>> getClasses() {
            return java.util.Collections.emptySet();
        }

        @Override
        public Set<Object> getInstances() {
            return java.util.Collections.emptySet();
        }
    }
}
