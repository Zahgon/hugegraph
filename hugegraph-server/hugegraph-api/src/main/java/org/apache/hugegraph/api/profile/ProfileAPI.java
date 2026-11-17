/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.api.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.config.ServerOptions;
import org.apache.hugegraph.server.rest.ResourceModel;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.InsertionOrderUtil;
import org.apache.hugegraph.util.JsonUtil;
import org.apache.hugegraph.version.CoreVersion;
import org.apache.tinkerpop.shaded.jackson.annotation.JsonProperty;

import com.codahale.metrics.annotation.Timed;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

@Path("/")
@Singleton
@Tag(name = "ProfileAPI")
public class ProfileAPI {

    private static final String SERVICE = "hugegraph";
    private static final String DOC = "https://hugegraph.apache.org/docs/";
    private static final String API_DOC = DOC + "clients/";
    private static final String SWAGGER_UI = "/swagger-ui/index.html";

    private static String SERVER_PROFILES = null;
    private static String API_PROFILES = null;

    @GET
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public String getProfile(@Context HugeConfig conf, @Context ResourceModel model) {
        // May init multi times by multi threads, but no effect on the results
        if (SERVER_PROFILES != null) {
            return SERVER_PROFILES;
        }

        Map<String, Object> profiles = InsertionOrderUtil.newMap();
        profiles.put("service", SERVICE);
        profiles.put("version", CoreVersion.VERSION.toString());
        profiles.put("doc", DOC);
        profiles.put("api_doc", API_DOC);
        profiles.put("swagger_ui", conf.get(ServerOptions.REST_SERVER_URL) + SWAGGER_UI);
        Set<String> apis = new TreeSet<>();
        for (ResourceModel.RootResource resource : model.resources()) {
            APICategory apiCategory = APICategory.parse(resource.handler().getName());
            apis.add(apiCategory.dir);
        }
        profiles.put("apis", apis);
        SERVER_PROFILES = JsonUtil.toJson(profiles);
        return SERVER_PROFILES;
    }

    @GET
    @Path("apis")
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    public String showAllAPIs(@Context ResourceModel model) {
        if (API_PROFILES != null) {
            return API_PROFILES;
        }

        APIProfiles apiProfiles = new APIProfiles();
        for (ResourceModel.RootResource resource : model.resources()) {
            APICategory apiCategory = APICategory.parse(resource.handler().getName());

            String url = resource.path();
            // List all methods of this resource
            for (ResourceModel.MethodModel rm : resource.methods()) {
                apiProfiles.put(apiCategory, APIProfile.parse(url, rm));
            }
            // List all methods of this resource's child resources
            for (ResourceModel.ChildResource child : resource.children()) {
                String childUrl = url + "/" + child.path();
                for (ResourceModel.MethodModel rm : child.methods()) {
                    apiProfiles.put(apiCategory, APIProfile.parse(childUrl, rm));
                }
            }
        }
        API_PROFILES = JsonUtil.toJson(apiProfiles);
        return API_PROFILES;
    }

    private static class APIProfiles {

        @JsonProperty("apis")
        private final Map<String, Map<String, List<APIProfile>>> apis;

        public APIProfiles() {
            this.apis = new TreeMap<>();
        }

        public void put(APICategory category, APIProfile profile) {
            Map<String, List<APIProfile>> categories;
            categories = this.apis.computeIfAbsent(category.dir,
                                                   k -> new TreeMap<>());
            List<APIProfile> profiles = categories.computeIfAbsent(
                    category.category,
                    k -> new ArrayList<>());
            profiles.add(profile);
        }
    }

    private static class APIProfile {

        @JsonProperty("url")
        private final String url;
        @JsonProperty("method")
        private final String method;
        @JsonProperty("parameters")
        private final List<ParamInfo> parameters;

        public APIProfile(String url, String method,
                          List<ParamInfo> parameters) {
            this.url = url;
            this.method = method;
            this.parameters = parameters;
        }

        public static APIProfile parse(String url, ResourceModel.MethodModel resource) {
            String method = resource.httpMethod();
            List<ParamInfo> params = new ArrayList<>();
            for (ResourceModel.ParamModel param : resource.params()) {
                if (param.source() == ResourceModel.ParamModel.Source.QUERY) {
                    String name = param.sourceName();
                    String type = param.typeName();
                    String defaultValue = param.defaultValue();
                    params.add(new ParamInfo(name, type, defaultValue));
                } else if (param.source() == ResourceModel.ParamModel.Source.ENTITY) {
                    String type = param.typeName();
                    params.add(new ParamInfo("body", type));
                }
            }
            return new APIProfile(url, method, params);
        }

        private static class ParamInfo {

            @JsonProperty("name")
            private final String name;
            @JsonProperty("type")
            private final String type;
            @JsonProperty("default_value")
            private final String defaultValue;

            public ParamInfo(String name, String type) {
                this(name, type, null);
            }

            public ParamInfo(String name, String type, String defaultValue) {
                this.name = name;
                this.type = type;
                this.defaultValue = defaultValue;
            }
        }
    }

    private static class APICategory {

        private final String dir;
        private final String category;

        public APICategory(String dir, String category) {
            this.dir = dir;
            this.category = category;
        }

        public static APICategory parse(String fullName) {
            String[] parts = StringUtils.split(fullName, ".");
            E.checkState(parts.length >= 2, "Invalid api name");
            String dir = parts[parts.length - 2];
            String category = parts[parts.length - 1];
            return new APICategory(dir, category);
        }
    }
}
