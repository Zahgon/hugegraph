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

import java.net.URI;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The answer to a request asking what a path allows.
 *
 * Three answers are possible, and which one a client gets is decided by the media types it will
 * take: a description document of the resource the request reached, the allowed methods as plain
 * text, or an answer with no entity at all carrying the type the client asked for. The document
 * names the methods the resource answers, the query, header and template parameters they read and
 * the media types they read and write.
 */
public final class Wadl {

    /** The media type the description document is written as. */
    public static final MediaType WADL_TYPE =
            new MediaType("application", "vnd.sun.wadl+xml");

    private static final MediaType TEXT_TYPE = MediaType.TEXT_PLAIN_TYPE;
    private static final String NAMESPACE = "http://wadl.dev.java.net/2009/02";
    private static final String SCHEMA = "http://www.w3.org/2001/XMLSchema";
    private static final String GRAMMAR = "application.wadl/xsd0.xsd";

    private Wadl() {
    }

    /** Builds the answer to a request asking what a path allows. */
    public static Response options(RestUriInfo uriInfo, Set<String> allowed,
                                   ResourceModel.RootResource root,
                                   ResourceModel.ChildResource child,
                                   List<MediaTypes.Accepted> accepted) {
        Response.ResponseBuilder builder = new RestResponseBuilder().status(200).allow(allowed);
        for (MediaTypes.Accepted wanted : accepted) {
            if (MediaTypes.compatible(WADL_TYPE, wanted.type())) {
                return builder.header("Last-modified", new Date())
                              .type(WADL_TYPE).entity(describe(uriInfo, root, child)).build();
            }
            if (MediaTypes.compatible(TEXT_TYPE, wanted.type())) {
                return builder.type(TEXT_TYPE).entity(String.join(", ", allowed)).build();
            }
            // Anything else a client will take is answered with that type and no entity at all.
            return builder.type(wanted.type()).build();
        }
        return builder.header("Last-modified", new Date())
                      .type(WADL_TYPE).entity(describe(uriInfo, root, child)).build();
    }

    private static String describe(RestUriInfo uriInfo, ResourceModel.RootResource root,
                                   ResourceModel.ChildResource child) {
        URI base = uriInfo.getBaseUri();
        URI grammar = base.resolve(GRAMMAR);
        String href = uriInfo.getRequestUri().relativize(grammar).toString();
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        xml.append("<application xmlns=\"").append(NAMESPACE).append("\">\n");
        xml.append("    <doc xmlns:hugegraph=\"https://hugegraph.apache.org/\"")
           .append(" hugegraph:generatedBy=\"HugeGraph REST server\"/>\n");
        xml.append("    <grammars>\n");
        xml.append("        <include href=\"").append(escape(href)).append("\">\n");
        xml.append("            <doc title=\"Generated\" xml:lang=\"en\"/>\n");
        xml.append("        </include>\n");
        xml.append("    </grammars>\n");
        xml.append("    <resources base=\"").append(escape(base.toString())).append("\">\n");
        xml.append("        <resource path=\"").append(escape(uriInfo.matchPath())).append("\">\n");
        if (child == null) {
            for (ResourceModel.MethodModel model : root.methods()) {
                appendMethod(xml, model, "            ");
            }
            for (ResourceModel.ChildResource below : root.children()) {
                appendChild(xml, root, below);
            }
        } else {
            for (ResourceModel.MethodModel model : child.methods()) {
                appendMethod(xml, model, "            ");
            }
        }
        xml.append("        </resource>\n");
        xml.append("    </resources>\n");
        xml.append("</application>\n");
        return xml.toString();
    }

    private static void appendChild(StringBuilder xml, ResourceModel.RootResource resource,
                                    ResourceModel.ChildResource child) {
        xml.append("            <resource path=\"").append(escape(child.path())).append("\">\n");
        /*
         * The template names the methods of this sub-path actually read, held as a set of names
         * rather than in the order the path writes them, which is the order they are listed in.
         */
        Set<String> templates = new HashSet<>();
        for (ResourceModel.MethodModel model : child.methods()) {
            for (ResourceModel.ParamModel param : model.params()) {
                if (param.source() == ResourceModel.ParamModel.Source.PATH) {
                    templates.add(param.sourceName());
                }
            }
        }
        for (String name : templates) {
            appendParam(xml, name, "template", typeOf(templateType(resource, child, name)),
                        null, "                ");
        }
        for (ResourceModel.MethodModel model : child.methods()) {
            appendMethod(xml, model, "                ");
        }
        xml.append("            </resource>\n");
    }

    /** The type a resource method declares for a template name, so the document can name it. */
    private static Class<?> templateType(ResourceModel.RootResource resource,
                                         ResourceModel.ChildResource child, String name) {
        for (ResourceModel.MethodModel model : child.methods()) {
            for (ResourceModel.ParamModel param : model.params()) {
                if (param.source() == ResourceModel.ParamModel.Source.PATH &&
                    name.equals(param.sourceName())) {
                    return param.rawType();
                }
            }
        }
        for (ResourceModel.MethodModel model : resource.methods()) {
            for (ResourceModel.ParamModel param : model.params()) {
                if (param.source() == ResourceModel.ParamModel.Source.PATH &&
                    name.equals(param.sourceName())) {
                    return param.rawType();
                }
            }
        }
        return String.class;
    }

    private static void appendMethod(StringBuilder xml, ResourceModel.MethodModel model,
                                     String indent) {
        List<ResourceModel.ParamModel> params = model.params();
        boolean reads = false;
        boolean asks = false;
        for (ResourceModel.ParamModel param : params) {
            if (param.source() == ResourceModel.ParamModel.Source.QUERY ||
                param.source() == ResourceModel.ParamModel.Source.HEADER) {
                asks = true;
            } else if (param.source() == ResourceModel.ParamModel.Source.ENTITY) {
                reads = true;
            }
        }
        boolean hasRequest = asks || (reads && !model.consumes().isEmpty());
        // A method that answers nothing describes no answer, which is how a void method reads.
        boolean hasResponse = model.method().getReturnType() != void.class;
        String head = indent + "<method id=\"" + escape(model.method().getName())
                      + "\" name=\"" + model.httpMethod() + "\"";
        if (!hasRequest && !hasResponse) {
            xml.append(head).append("/>\n");
            return;
        }
        xml.append(head).append(">\n");
        if (hasRequest) {
            xml.append(indent).append("    <request>\n");
            for (ResourceModel.ParamModel param : params) {
                if (param.source() == ResourceModel.ParamModel.Source.QUERY) {
                    appendParam(xml, param.sourceName(), "query", typeOf(param.rawType()),
                                param.defaultValue(), indent + "        ");
                } else if (param.source() == ResourceModel.ParamModel.Source.HEADER) {
                    appendParam(xml, param.sourceName(), "header", typeOf(param.rawType()),
                                param.defaultValue(), indent + "        ");
                }
            }
            if (reads) {
                for (MediaType consumed : model.consumes()) {
                    appendRepresentation(xml, MediaTypes.format(consumed), indent + "        ");
                }
            }
            xml.append(indent).append("    </request>\n");
        }
        if (hasResponse) {
            xml.append(indent).append("    <response>\n");
            if (model.produces().isEmpty()) {
                appendRepresentation(xml, "*/*", indent + "        ");
            } else {
                for (MediaType produced : model.produces()) {
                    appendRepresentation(xml, MediaTypes.format(produced), indent + "        ");
                }
            }
            xml.append(indent).append("    </response>\n");
        }
        xml.append(indent).append("</method>\n");
    }

    /** The schema type a Java type is named by in the document. */
    private static String typeOf(Class<?> raw) {
        if (raw == int.class || raw == Integer.class) {
            return "xs:int";
        }
        if (raw == long.class || raw == Long.class) {
            return "xs:long";
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return "xs:boolean";
        }
        if (raw == double.class || raw == Double.class) {
            return "xs:double";
        }
        if (raw == float.class || raw == Float.class) {
            return "xs:float";
        }
        if (raw == short.class || raw == Short.class) {
            return "xs:short";
        }
        if (raw == byte.class || raw == Byte.class) {
            return "xs:byte";
        }
        return "xs:string";
    }

    private static void appendParam(StringBuilder xml, String name, String style, String type,
                                    String defaultValue, String indent) {
        xml.append(indent).append("<param xmlns:xs=\"").append(SCHEMA).append("\" name=\"")
           .append(escape(name)).append("\" style=\"").append(style)
           .append("\" type=\"").append(type).append('"');
        if (defaultValue != null) {
            xml.append(" default=\"").append(escape(defaultValue)).append('"');
        }
        xml.append("/>\n");
    }

    private static void appendRepresentation(StringBuilder xml, String type, String indent) {
        xml.append(indent).append("<representation mediaType=\"").append(escape(type))
           .append("\"/>\n");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;");
    }
}
