/*
 * Magma Server
 * Copyright (C) 2019-2023.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.magmafoundation.magma.remapping;

import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;
import io.izzel.arclight.api.Unsafe;
import io.izzel.tools.product.Product;
import io.izzel.tools.product.Product2;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import net.md_5.specialsource.RemappingClassAdapter;
import net.md_5.specialsource.repo.ClassRepo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.magmafoundation.magma.remapping.handlers.MagmaReflectionHandler;
import org.magmafoundation.magma.remapping.repos.ClassLoaderRepo;
import org.magmafoundation.magma.remapping.repos.GlobalClassRepo;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.service.MixinService;

import java.io.File;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ClassLoaderAdapter
 *
 * @author Mainly by IzzelAliz and modified Malcolm
 * @originalClassName ClassLoaderAdapter
 * @classFrom <a href="https://github.com/IzzelAliz/Arclight/blob/1.18/arclight-common/src/main/java/io/izzel/arclight/common/mod/util/remapper/ClassLoaderRepo.java">Click here to get to github</a>
 *
 * This classes is modified by Magma to support the Magma software.
 */
public class ClassLoaderRemapper extends LenientJarRemapper {

    private static final Logger LOGGER = LogManager.getLogger("Magma");
    private static final String PREFIX = "net/minecraft/";
    private static final String REPLACED_NAME = Type.getInternalName(MagmaReflectionHandler.class);

    private final JarMapping toBukkitMapping;
    private final JarRemapper toBukkitRemapper;
    private final ClassLoader classLoader;
    private final String generatedHandler;
    private final Class<?> generatedHandlerClass;
    private final GeneratedHandlerAdapter generatedHandlerAdapter;
    private final Map<String, Boolean> secureJarInfo = new ConcurrentHashMap<>();

    public ClassLoaderRemapper(JarMapping jarMapping, JarMapping toBukkitMapping, ClassLoader classLoader) {
        super(jarMapping);
        this.toBukkitMapping = toBukkitMapping;
        this.classLoader = classLoader;
        this.jarMapping.setInheritanceMap(MagmaRemapper.INSTANCE.inheritanceMap);
        this.jarMapping.setFallbackInheritanceProvider(GlobalClassRepo.inheritanceProvider());
        this.toBukkitMapping.setFallbackInheritanceProvider(GlobalClassRepo.inheritanceProvider());
        this.toBukkitRemapper = new LenientJarRemapper(this.toBukkitMapping);
        this.generatedHandlerClass = generateReflectionHandler();
        this.generatedHandler = Type.getInternalName(generatedHandlerClass);
        this.generatedHandlerAdapter = new GeneratedHandlerAdapter(REPLACED_NAME, generatedHandler);
        GlobalClassRepo.INSTANCE.addRepo(new ClassLoaderRepo(this.classLoader));
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public JarMapping toBukkitMapping() {
        return toBukkitMapping;
    }

    public JarMapping toNmsMapping() {
        return jarMapping;
    }

    public JarRemapper toBukkitRemapper() {
        return toBukkitRemapper;
    }

    public String getGeneratedHandler() {
        return generatedHandler;
    }

    public Class<?> getGeneratedHandlerClass() {
        return generatedHandlerClass;
    }

    // BiMap: srg -> bukkit
    private final Map<String, BiMap<Field, String>> cacheFields = new ConcurrentHashMap<>();
    private final Map<String, Map.Entry<Map<Method, String>, Map<WrappedMethod, Method>>> cacheMethods = new ConcurrentHashMap<>();
    private final Map<String, Boolean> cacheRemap = new ConcurrentHashMap<>();

    private Map.Entry<Map<Method, String>, Map<WrappedMethod, Method>> getMethods(Class<?> cl, String internalName) {
        return cacheMethods.computeIfAbsent(internalName, k -> this.tryGetMethods(cl));
    }

    private Map.Entry<Map<Method, String>, Map<WrappedMethod, Method>> tryGetMethods(Class<?> cl) {
        try {
            Map<Method, String> names = new HashMap<>();
            Map<WrappedMethod, Method> types = new HashMap<>();
            for (Method method : cl.getMethods()) {
                checkMethodTypes(method);
                String name = mapMethod(method);
                names.put(method, name);
                WrappedMethod wrapped = new WrappedMethod(name, method.getParameterTypes());
                types.put(wrapped, method);
            }
            for (Method method : cl.getDeclaredMethods()) {
                checkMethodTypes(method);
                String name = mapMethod(method);
                names.put(method, name);
                WrappedMethod wrapped = new WrappedMethod(name, method.getParameterTypes());
                types.put(wrapped, method);
            }
            return Maps.immutableEntry(names, types);
        } catch (TypeNotPresentException e) {
            if (e.getCause() instanceof ClassNotFoundException) {
                tryDefineClass(e.getCause().getMessage().replace('.', '/'));
                return tryGetMethods(cl);
            } else throw e;
        } catch (NoClassDefFoundError error) {
            tryDefineClass(error.getMessage());
            return tryGetMethods(cl);
        }
    }

    private BiMap<Field, String> getFields(Class<?> cl, String internalName) {
        return cacheFields.computeIfAbsent(internalName, k -> this.tryGetFields(cl));
    }

    private BiMap<Field, String> tryGetFields(Class<?> cl) {
        try {
            HashBiMap<Field, String> map = HashBiMap.create();
            for (Field field : cl.getFields()) {
                checkFieldTypes(field);
                map.forcePut(field, mapField(field));
            }
            for (Field field : cl.getDeclaredFields()) {
                checkFieldTypes(field);
                map.forcePut(field, mapField(field));
            }
            return map;
        } catch (TypeNotPresentException e) {
            if (e.getCause() instanceof ClassNotFoundException) {
                tryDefineClass(e.getCause().getMessage().replace('.', '/'));
                return tryGetFields(cl);
            } else throw e;
        } catch (NoClassDefFoundError error) {
            tryDefineClass(error.getMessage());
            return tryGetFields(cl);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void checkFieldTypes(Field field) throws TypeNotPresentException {
        field.getGenericType();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void checkMethodTypes(Method method) throws TypeNotPresentException {
        method.getGenericReturnType();
        method.getGenericParameterTypes();
    }

    public void tryDefineClass(String internalName) {
        if (!internalName.startsWith(PREFIX)) {
            throw new NoClassDefFoundError(internalName);
        }
        LOGGER.warn("Loading CLIENT side class: {}", internalName);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_DEPRECATED, internalName, null, "java/lang/Object", new String[]{});
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        Unsafe.defineClass(Type.getObjectType(internalName).getClassName(), bytes, 0, bytes.length, getClass().getClassLoader(), getClass().getProtectionDomain());
    }

    private String mapMethod(Method method) {
        String owner = Type.getInternalName(method.getDeclaringClass());
        String srgName = method.getName();
        String desc = Type.getMethodDescriptor(method);
        return toBukkitRemapper.mapMethodName(owner, srgName, desc, -1);
    }

    private String mapField(Field field) {
        String owner = Type.getInternalName(field.getDeclaringClass());
        String srgName = field.getName();
        String desc = Type.getDescriptor(field.getType());
        return toBukkitRemapper.mapFieldName(owner, srgName, desc, -1);
    }

    public String tryMapDecFieldToSrg(Class<?> cl, String bukkitName) {
        String internalName = Type.getInternalName(cl);
        if (internalName.startsWith(PREFIX)) {
            Field field = getFields(cl, internalName).inverse().get(bukkitName);
            return field == null ? bukkitName : field.getName();
        } else return bukkitName;
    }

    public String tryMapFieldToSrg(Class<?> cl, String bukkitName) {
        String internalName = Type.getInternalName(cl);
        if (shouldRemap(internalName)) {
            Field field = getFields(cl, internalName).inverse().get(bukkitName);
            return field == null ? bukkitName : field.getName();
        } else return bukkitName;
    }

    public String tryMapFieldToBukkit(Class<?> cl, String srgName, Field field) {
        String internalName = Type.getInternalName(cl);
        if (internalName.startsWith(PREFIX)) {
            BiMap<Field, String> fields = getFields(cl, internalName);
            return fields.getOrDefault(field, srgName);
        } else return srgName;
    }

    public Method tryMapMethodToSrg(Class<?> cl, String bukkitName, Class<?>[] pTypes) {
        String internalName = Type.getInternalName(cl);
        if (shouldRemap(internalName)) {
            return getMethods(cl, internalName).getValue().get(new WrappedMethod(bukkitName, pTypes));
        } else return null;
    }

    public String tryMapMethodToBukkit(Class<?> cl, Method method) {
        String internalName = Type.getInternalName(cl);
        if (shouldRemap(internalName)) {
            return getMethods(cl, internalName).getKey().getOrDefault(method, method.getName());
        } else return method.getName();
    }

    private boolean shouldRemap(String internalName) {
        Boolean b = cacheRemap.get(internalName);
        if (b != null) return b;
        for (String s : GlobalClassRepo.inheritanceProvider().getAll(internalName)) {
            if (s.startsWith(PREFIX)) {
                cacheRemap.put(internalName, true);
                return true;
            }
        }
        cacheRemap.put(internalName, false);
        return false;
    }

    public MethodInsnNode mapMethod(String owner, String name, String desc) {
        Map.Entry<String, String> entry = tryClimb(jarMapping.methods, owner, name + " " + desc, -1);
        if (entry == null) return null;
        return new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mapType(entry.getKey()), entry.getValue(), mapMethodDesc(desc), false);
    }

    public Map.Entry<String, String> tryClimb(Map<String, String> map, String owner, String name, int access) {
        String key = owner + "/" + name;

        String mapped = map.get(key);
        if (mapped == null && (access == -1 || (!Modifier.isPrivate(access) && !Modifier.isStatic(access)))) {
            Collection<String> parents;

            if (MagmaRemapper.INSTANCE.inheritanceMap.hasParents(owner)) {
                parents = MagmaRemapper.INSTANCE.inheritanceMap.getParents(owner);
            } else {
                parents = GlobalClassRepo.inheritanceProvider().getParents(owner);
                MagmaRemapper.INSTANCE.inheritanceMap.setParents(owner, parents);
            }

            if (parents != null) {
                // climb the inheritance tree
                for (String parent : parents) {
                    Map.Entry<String, String> entry = tryClimb(map, parent, name, access);
                    if (entry != null) {
                        return entry;
                    }
                }
            }
        }
        if (mapped == null) return null;
        return Maps.immutableEntry(owner, mapped);
    }

    public Product2<byte[], CodeSource> remapClass(String className, Callable<byte[]> byteSource, URLConnection connection) throws ClassNotFoundException {
        try {
                byte[] bytes = remapClassFile(byteSource.call(), GlobalClassRepo.INSTANCE);
                URL url;
                CodeSigner[] signers;
                if (connection instanceof JarURLConnection) {
                    url = ((JarURLConnection) connection).getJarFileURL();
                    signers = ((JarURLConnection) connection).getJarEntry().getCodeSigners();
                } else {
                    url = connection.getURL();
                    signers = null;
                }
                return Product.of(bytes, new CodeSource(url, signers));
        } catch (Exception e) {
            throw new ClassNotFoundException(className, e);
        }
    }

    @Override
    public byte[] remapClassFile(byte[] in, ClassRepo repo) {
        return remapClassFile(in, repo, false);
    }

    public byte[] remapClassFile(byte[] in, ClassRepo repo, boolean runtime) {
        if (runtime) GlobalClassRepo.runtimeRepo().put(in);
        return remapClassFile(new ClassReader(in), repo);
    }

    private byte[] remapClassFile(ClassReader reader, final ClassRepo repo) {
        ClassNode node = new ClassNode();
        RemappingClassAdapter mapper = new RemappingClassAdapter(node, this, repo);
        reader.accept(mapper, ClassReader.SKIP_FRAMES);

        for (PluginTransformer transformer : MagmaRemapper.INSTANCE.getTransformerList()) {
            transformer.handleClass(node, this);
        }

        ClassWriter wr = new PluginClassWriter(node.version == Opcodes.V1_5 ? ClassWriter.COMPUTE_MAXS : ClassWriter.COMPUTE_FRAMES);
        node.accept(wr);

        return dump(wr.toByteArray());
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private Class<?> generateReflectionHandler() {
        try {
            ClassNode node = MixinService.getService().getBytecodeProvider().getClassNode(Type.getInternalName(MagmaReflectionHandler.class));
            Preconditions.checkNotNull(node, "node");
            ClassWriter writer = new ClassWriter(0);
            String name = Type.getInternalName(MagmaReflectionHandler.class) + "_" + COUNTER.getAndIncrement();
            ClassVisitor visitor = new ClassRemapper(writer, new NameRemapper(name));
            node.accept(visitor);
            byte[] bytes = writer.toByteArray();
            dump(bytes);
            Class<?> cl = Unsafe.defineClass(name.replace('/', '.'), bytes, 0, bytes.length, getClass().getClassLoader(), getClass().getProtectionDomain());
            Unsafe.ensureClassInitialized(cl);

            VarHandle remapper = Unsafe.lookup().findStaticVarHandle(cl, "remapper", ClassLoaderRemapper.class);
            remapper.set(this);
            return cl;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class NameRemapper extends Remapper {

        private static final String ORIGIN = Type.getInternalName(MagmaReflectionHandler.class);

        private final String internal;

        private NameRemapper(String internal) {
            this.internal = internal;
        }

        @Override
        public String map(String internalName) {
            if (internalName.equals(ORIGIN)) {
                return internal;
            }
            return super.map(internalName);
        }
    }

    private static class PluginClassWriter extends ClassWriter {

        public PluginClassWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            Collection<String> parents = GlobalClassRepo.remappingProvider().getAll(type2);
            if (parents.contains(type1)) {
                return type1;
            }
            if (GlobalClassRepo.remappingProvider().getAll(type1).contains(type2)) {
                return type2;
            }
            do {
                type1 = getSuper(type1);
            } while (!parents.contains(type1));
            return type1;
        }

        private String getSuper(final String typeName) {
            ClassNode node = GlobalClassRepo.INSTANCE.findClass(typeName);
            if (node == null) {
                return "java/lang/Object";
            }
            return MagmaRemapper.getNmsMapper().map(node.superName);
        }
    }

    private static class GeneratedHandlerAdapter extends Remapper {

        private final String from, to;

        private GeneratedHandlerAdapter(String from, String to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public String map(String internalName) {
            if (from.equals(internalName)) {
                return to;
            } else {
                return internalName;
            }
        }
    }

    static class WrappedMethod {

        private final String name;
        private final Class<?>[] pTypes;

        public WrappedMethod(String name, Class<?>[] pTypes) {
            this.name = name;
            this.pTypes = pTypes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            WrappedMethod that = (WrappedMethod) o;
            return Objects.equals(name, that.name) &&
                Arrays.equals(pTypes, that.pTypes);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(name);
            result = 31 * result + Arrays.hashCode(pTypes);
            return result;
        }
    }

    private static byte[] dump(byte[] bytes) {
        try {
            if (MagmaRemapper.DUMP != null) {
                String className = new ClassReader(bytes).getClassName() + ".class";
                int index = className.lastIndexOf('/');
                if (index != -1) {
                    File file = new File(MagmaRemapper.DUMP, className.substring(0, index));
                    file.mkdirs();
                    Files.write(file.toPath().resolve(className.substring(index + 1)), bytes);
                } else {
                    Files.write(MagmaRemapper.DUMP.toPath().resolve(className), bytes);
                }
            }
        } catch (Exception e) {
        }
        return bytes;
    }
}
