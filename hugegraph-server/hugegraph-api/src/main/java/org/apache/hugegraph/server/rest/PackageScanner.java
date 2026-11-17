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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.hugegraph.HugeException;

/**
 * Finds the classes of a package on the class path.
 *
 * The serving layer is told which packages hold resources and providers, exactly as the old
 * wiring was, so the classes are looked up rather than listed one by one. Names are collected in
 * sorted order so that the set is the same on every start.
 */
public final class PackageScanner {

    private PackageScanner() {
    }

    /** Every class of a package and the packages below it. */
    public static List<Class<?>> scan(String packageName) {
        String resource = packageName.replace('.', '/');
        TreeSet<String> names = new TreeSet<>();
        try {
            Enumeration<URL> roots = Thread.currentThread().getContextClassLoader()
                                           .getResources(resource);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if ("jar".equals(root.getProtocol())) {
                    collectFromJar(root, resource, names);
                } else if ("file".equals(root.getProtocol())) {
                    String path = URLDecoder.decode(root.getPath(), StandardCharsets.UTF_8);
                    collectFromDirectory(new File(path), packageName, names);
                }
            }
        } catch (IOException e) {
            throw new HugeException("Failed to scan package '%s'", e, packageName);
        }
        List<Class<?>> classes = new ArrayList<>(names.size());
        for (String name : names) {
            try {
                classes.add(Class.forName(name, false,
                                          Thread.currentThread().getContextClassLoader()));
            } catch (Throwable e) {
                // A class that cannot be loaded holds no resource and no provider.
                continue;
            }
        }
        return classes;
    }

    private static void collectFromJar(URL root, String resource, TreeSet<String> names)
            throws IOException {
        String path = root.getPath();
        int separator = path.indexOf("!/");
        String jarPath = URLDecoder.decode(path.substring("file:".length(), separator),
                                           StandardCharsets.UTF_8);
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(resource) && name.endsWith(".class")) {
                    names.add(name.substring(0, name.length() - ".class".length())
                                  .replace('/', '.'));
                }
            }
        }
    }

    private static void collectFromDirectory(File directory, String packageName,
                                             TreeSet<String> names) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectFromDirectory(child, packageName + "." + child.getName(), names);
            } else if (child.getName().endsWith(".class")) {
                String simple = child.getName()
                                     .substring(0, child.getName().length() - ".class".length());
                names.add(packageName + "." + simple);
            }
        }
    }
}
