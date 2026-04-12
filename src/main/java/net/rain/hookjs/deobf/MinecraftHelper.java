package net.rain.hookjs.deobf;

import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MinecraftHelper {
    public static final Logger LOGGER = LogManager.getLogger();
    public static final Map<String, Field> fieldCache = new ConcurrentHashMap<>();
    public static final Map<String, Method> methodCache = new ConcurrentHashMap<>();

    public static final Map<String, String> fieldMappings = new ConcurrentHashMap<>();
    public static final Map<String, String> methodMappings = new ConcurrentHashMap<>();

    public static final Map<String, String> classNameMappings = new ConcurrentHashMap<>();

    public static final Map<String, String> methodReturnTypes = new ConcurrentHashMap<>();

    public static final Map<String, String> methodMappingsByDescriptor = new ConcurrentHashMap<>();

    public static final Map<
            String, String> methodReturnTypesByDescriptor = new ConcurrentHashMap<>();

    public static final Map<String, String> superClassCache = new ConcurrentHashMap<>();

    public static final String MAPPING_RESOURCE_PATH = "/assets/mapping/map/mappings.tsrg";
    public static boolean mappingLoaded = false;
    public static Path mappingFilePath = null;

    public static String normalizeClassName(String className) {
        if (className == null) return null;
        return className.replace('$', '.').replace('/', '.');
    }

    public static void loadMappingsFromResource() {
        if (mappingLoaded) return;
        mappingLoaded = true;

        String[] possiblePaths = {
                "/assets/mappings/map/mappings.tsrg",
                "assets/mappings/map/mappings.tsrg",
                "/mappings.tsrg",
                "mappings.tsrg"
        };

        for (String path : possiblePaths) {
            try (InputStream is = MinecraftHelper.class.getResourceAsStream(path)) {
                if (is != null) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                        parseMappingFile(reader);
                        LOGGER.info("Loaded: {} classes, {} fields, {} methods from {}",
                                classNameMappings.size() / 2, fieldMappings.size(), methodMappings.size(), path);
                        return;
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Class.getResourceAsStream failed ({}): {}", path, e.getMessage());
            }

            String stripped = path.startsWith("/") ? path.substring(1) : path;
            try (InputStream is = MinecraftHelper.class.getClassLoader().getResourceAsStream(stripped)) {
                if (is != null) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                        parseMappingFile(reader);
                        LOGGER.info("Loaded via ClassLoader from {}", path);
                        return;
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("ClassLoader failed ({}): {}", path, e.getMessage());
            }

            try {
                ClassLoader ctx = Thread.currentThread().getContextClassLoader();
                if (ctx != null) {
                    try (InputStream is = ctx.getResourceAsStream(stripped)) {
                        if (is != null) {
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                                parseMappingFile(reader);
                                LOGGER.info("Loaded via ContextClassLoader from {}", path);
                                return;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("ContextClassLoader failed ({}): {}", path, e.getMessage());
            }
        }

        LOGGER.warn("Mapping file not found in resources, will rely on ObfuscationReflectionHelper");
    }

    public static InputStream getResourceAsStream(String resourcePath) {
        InputStream is = MinecraftHelper.class.getResourceAsStream(resourcePath);
        if (is != null) return is;
        String stripped = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        is = MinecraftHelper.class.getClassLoader().getResourceAsStream(stripped);
        if (is != null) return is;
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) {
            is = ctx.getResourceAsStream(stripped);
            if (is != null) return is;
        }
        LOGGER.warn("Resource not found: {}", resourcePath);
        return null;
    }

    public static boolean resourceExists(String resourcePath) {
        try (InputStream is = getResourceAsStream(resourcePath)) {
            return is != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static void findMappingFile() {
        if (mappingFilePath != null) return;
        String[] possiblePaths = {
                "config/forge/mappings.tsrg",
                "mappings/mappings.tsrg",
                ".gradle/caches/forge_gradle/mcp_mappings/mappings.tsrg"
        };
        for (String pathStr : possiblePaths) {
            Path path = Paths.get(pathStr);
            if (Files.exists(path)) {
                mappingFilePath = path;
                LOGGER.info("Found mapping file: {}", path);
                return;
            }
        }
        LOGGER.debug("Mapping file not found in file system");
    }

    public static void parseMappingFile(BufferedReader reader) throws Exception {
        String line;
        String currentClass = null;
        boolean isFirstLine = true;
        int lineNumber = 0;

        while ((line = reader.readLine()) != null) {
            lineNumber++;
            String originalLine = line;
            line = line.trim();

            if (line.isEmpty() || line.startsWith("#")) continue;
            if (isFirstLine && line.startsWith("tsrg2")) {
                isFirstLine = false;
                continue;
            }
            isFirstLine = false;

            int tabCount = 0;
            for (char c : originalLine.toCharArray()) {
                if (c == '\t') tabCount++;
                else break;
            }

            if (tabCount == 0) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    currentClass = normalizeClassName(parts[1]);

                    String simpleName = currentClass.contains(".")
                            ? currentClass.substring(currentClass.lastIndexOf('.') + 1)
                            : currentClass;
                }

            } else if (currentClass != null && tabCount == 1) {
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;

                String mcpName = parts[0];

                if (parts[1].startsWith("(")) {
                    if (parts.length >= 3) {
                        String fullDescriptor = parts[1];
                        String srgName = parts[2];

                        if (srgName.startsWith("m_") || srgName.equals("<init>") || srgName.equals("<clinit>")) {
                            String keySimple = currentClass + "." + mcpName;
                            methodMappings.put(keySimple, srgName);

                            String returnDesc = extractReturnTypeDescriptor(fullDescriptor);
                            if (returnDesc != null) {
                                String returnType = jvmDescriptorToClassName(returnDesc);
                                if (returnType != null) {
                                    methodReturnTypes.put(keySimple, returnType);
                                    String paramDesc = extractParamDescriptor(fullDescriptor);
                                    if (paramDesc != null) {
                                        String keyWithDesc = keySimple + paramDesc;
                                        methodMappingsByDescriptor.put(keyWithDesc, srgName);
                                        methodReturnTypesByDescriptor.put(keyWithDesc, returnType);
                                    }
                                }
                            }
                        }
                    }
                } else {
                    String srgName = parts[1];
                    if (srgName.startsWith("f_")) {
                        fieldMappings.put(currentClass + "." + mcpName, srgName);
                    }
                }
            }
        }

        LOGGER.info("Parsed: {} classes, {} fields, {} methods (simple), {} methods (desc)",
                classNameMappings.size() / 2, fieldMappings.size(),
                methodMappings.size(), methodMappingsByDescriptor.size());
    }

    private static String extractParamDescriptor(String full) {
        int close = full.indexOf(')');
        return close < 0 ? null : full.substring(0, close + 1);
    }

    private static String extractReturnTypeDescriptor(String full) {
        int close = full.indexOf(')');
        if (close < 0 || close + 1 >= full.length()) return null;
        return full.substring(close + 1);
    }

    public static String jvmDescriptorToClassName(String desc) {
        if (desc == null || desc.isEmpty()) return null;
        if (desc.startsWith("[")) {
            String comp = jvmDescriptorToClassName(desc.substring(1));
            return comp != null ? comp + "[]" : null;
        }
        switch (desc.charAt(0)) {
            case 'V':
                return "void";
            case 'I':
                return "int";
            case 'J':
                return "long";
            case 'F':
                return "float";
            case 'D':
                return "double";
            case 'Z':
                return "boolean";
            case 'C':
                return "char";
            case 'B':
                return "byte";
            case 'S':
                return "short";
            case 'L':
                return desc.endsWith(";") ? normalizeClassName(desc.substring(1, desc.length() - 1)) : null;
            default:
                return null;
        }
    }

    public static String findFullClassName(String name) {
        if (name == null || name.isEmpty()) return null;
        if (!mappingLoaded) loadMappingsFromResource();
        return classNameMappings.get(normalizeClassName(name));
    }

    public static String findSuperClassName(String className) {
        if (className == null || className.isEmpty()) return null;

        String normalized = normalizeClassName(className);

        String cached = superClassCache.get(normalized);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }

        String result = reflectSuperClassName(normalized);

        superClassCache.put(normalized, result != null ? result : "");

        if (result != null) {
            LOGGER.debug("Resolved super class via reflection: {} -> {}", normalized, result);
        } else {
            LOGGER.debug("No meaningful super class found for: {}", normalized);
        }
        return result;
    }

    private static String reflectSuperClassName(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return extractMeaningfulSuperName(clazz);
        } catch (ClassNotFoundException ignored) {
        }

        try {
            ClassLoader mcl = Thread.currentThread().getContextClassLoader();
            if (mcl != null) {
                Class<?> clazz = mcl.loadClass(className);
                return extractMeaningfulSuperName(clazz);
            }
        } catch (ClassNotFoundException ignored) {
        }

        try {
            ClassLoader forgeLoader = MinecraftHelper.class.getClassLoader();
            if (forgeLoader != null) {
                Class<?> clazz = forgeLoader.loadClass(className);
                return extractMeaningfulSuperName(clazz);
            }
        } catch (ClassNotFoundException ignored) {
            LOGGER.debug("Cannot load class for super-class resolution: {}", className);
        }

        return null;
    }

    private static String extractMeaningfulSuperName(Class<?> clazz) {
        if (clazz == null || clazz.isInterface() || clazz.isArray() || clazz.isPrimitive())
            return null;
        Class<?> superClass = clazz.getSuperclass();
        if (superClass == null || superClass == Object.class) return null;
    }

    public static String findSrgFieldName(String className, String mcpName) {
        className = normalizeClassName(className);
        String key = className + "." + mcpName;
        if (fieldMappings.containsKey(key)) return fieldMappings.get(key);
        if (!mappingLoaded) {
            loadMappingsFromResource();
            if (fieldMappings.containsKey(key)) return fieldMappings.get(key);
        }

        findMappingFile();
        if (mappingFilePath != null && Files.exists(mappingFilePath)) {
            try (BufferedReader reader = new BufferedReader(new FileReader(mappingFilePath.toFile()))) {
                String line;
                boolean inTarget = false;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (!line.startsWith("\t") && !line.startsWith("    ")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2)
                            inTarget = normalizeClassName(parts[1]).equals(className);
                    } else if (inTarget) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2 && parts[1].startsWith("f_") && parts[
                                0].equals(mcpName)) {
                            fieldMappings.put(key, parts[1]);
                            return parts[1];
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Error mapping field {}.{}: {}", className, mcpName, e.getMessage());
            }
        }
        return null;
    }

    public static String findSrgMethodName(String className, String mcpName) {
        className = normalizeClassName(className);
        String key = className + "." + mcpName;
        if (methodMappings.containsKey(key)) return methodMappings.get(key);
        if (!mappingLoaded) {
            loadMappingsFromResource();
            if (methodMappings.containsKey(key)) return methodMappings.get(key);
        }

        findMappingFile();
        if (mappingFilePath != null && Files.exists(mappingFilePath)) {
            try (BufferedReader reader = new BufferedReader(new FileReader(mappingFilePath.toFile()))) {
                String line;
                String curClass = null;
                while ((line = reader.readLine()) != null) {
                    String orig = line;
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int tabs = 0;
                    for (char c : orig.toCharArray()) {
                        if (c == '\t') tabs++;
                        else break;
                    }
                    if (tabs == 0) {
                        String[] p = line.split("\\s+");
                        if (p.length >= 2) curClass = normalizeClassName(p[1]);
                    } else if (curClass != null && curClass.equals(className) && tabs == 1) {
                        String[] p = line.split("\\s+");
                        if (p.length >= 3 && p[1].startsWith("(") && p[0].equals(mcpName)) {
                            String srg = p[2];
                            if (srg.startsWith("m_") || srg.equals("<init>") || srg.equals("<clinit>")) {
                                methodMappings.put(key, srg);
                                return srg;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Error mapping method {}.{}: {}", className, mcpName, e.getMessage());
            }
        }
        return null;
    }

    public static String findSrgMethodName(String className, String mcpName, String descriptor) {
        if (descriptor == null) return findSrgMethodName(className, mcpName);
        className = normalizeClassName(className);
        if (!mappingLoaded) loadMappingsFromResource();
        String result = methodMappingsByDescriptor.get(className + "." + mcpName + descriptor);
        return result != null ? result : findSrgMethodName(className, mcpName);
    }

    public static String findMethodReturnType(String className, String mcpName) {
        className = normalizeClassName(className);
        if (!mappingLoaded) loadMappingsFromResource();
        return methodReturnTypes.get(className + "." + mcpName);
    }

    public static String findMethodReturnType(String className, String mcpName, String descriptor) {
        if (descriptor == null) return findMethodReturnType(className, mcpName);
        className = normalizeClassName(className);
        if (!mappingLoaded) loadMappingsFromResource();
        String result = methodReturnTypesByDescriptor.get(className + "." + mcpName + descriptor);
        return result != null ? result : findMethodReturnType(className, mcpName);
    }

    public static String findFieldType(String className, String fieldName) {
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getStaticField(Class<?> clazz, String mcpName) {
        String cacheKey = clazz.getName() + "." + mcpName;
        Field field = fieldCache.computeIfAbsent(cacheKey, k -> {
            try {
                Field f = clazz.getDeclaredField(mcpName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
            try {
                Field f = ObfuscationReflectionHelper.findField(clazz, mcpName);
                f.setAccessible(true);
                return f;
            } catch (Exception e) {
                LOGGER.debug("ObfuscationReflectionHelper failed for {}.{}: {}", clazz.getName(), mcpName, e.getMessage());
            }
            String srgName = findSrgFieldName(clazz.getName(), mcpName);
            if (srgName != null) {
                try {
                    Field f = clazz.getDeclaredField(srgName);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                }
            }
            Class<?> cur = clazz;
            while (cur != null && cur != Object.class) {
                for (Field f : cur.getDeclaredFields()) {
                    if (f.getName().equals(mcpName) || (srgName != null && f.getName().equals(srgName))) {
                        f.setAccessible(true);
                        return f;
                    }
                }
                cur = cur.getSuperclass();
            }
            LOGGER.warn("Failed to find field {}. Available in {}:", mcpName, clazz.getName());
            for (Field f : clazz.getDeclaredFields())
                LOGGER.warn("  - {} ({})", f.getName(), f.getType().getSimpleName());
            throw new RuntimeException("Field not found: " + mcpName + " in " + clazz.getName()
                    + (srgName != null ? " (tried SRG: " + srgName + ")" : ""));
        });
        try {
            return (T) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get static field: " + mcpName, e);
        }
    }

    public static void setStaticField(Class<?> clazz, String mcpName, Object value) {
        String cacheKey = clazz.getName() + "." + mcpName;
        Field field = fieldCache.computeIfAbsent(cacheKey, k -> {
            try {
                Field f = clazz.getDeclaredField(mcpName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
            try {
                Field f = ObfuscationReflectionHelper.findField(clazz, mcpName);
                f.setAccessible(true);
                return f;
            } catch (Exception ignored) {
            }
            String srg = findSrgFieldName(clazz.getName(), mcpName);
            if (srg != null) {
                try {
                    Field f = clazz.getDeclaredField(srg);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                }
            }
            throw new RuntimeException("Field not found: " + mcpName + " in " + clazz.getName());
        });
        try {
            field.set(null, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set static field: " + mcpName, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T getField(Object obj, String mcpName) {
        if (obj == null) throw new IllegalArgumentException("Object cannot be null");
        Class<?> clazz = obj.getClass();
        String cacheKey = clazz.getName() + "." + mcpName;
        Field field = fieldCache.computeIfAbsent(cacheKey, k -> {
            try {
                Field f = clazz.getDeclaredField(mcpName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
            try {
                Field f = ObfuscationReflectionHelper.findField(clazz, mcpName);
                f.setAccessible(true);
                return f;
            } catch (Exception ignored) {
            }
            String srg = findSrgFieldName(clazz.getName(), mcpName);
            if (srg != null) {
                try {
                    Field f = clazz.getDeclaredField(srg);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                }
            }
            throw new RuntimeException("Field not found: " + mcpName + " in " + clazz.getName());
        });
        try {
            return (T) field.get(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get field: " + mcpName, e);
        }
    }

    public static void setField(Object obj, String mcpName, Object value) {
        if (obj == null) throw new IllegalArgumentException("Object cannot be null");
        Class<?> clazz = obj.getClass();
        String cacheKey = clazz.getName() + "." + mcpName;
        Field field = fieldCache.computeIfAbsent(cacheKey, k -> {
            try {
                Field f = clazz.getDeclaredField(mcpName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
            try {
                Field f = ObfuscationReflectionHelper.findField(clazz, mcpName);
                f.setAccessible(true);
                return f;
            } catch (Exception ignored) {
            }
            String srg = findSrgFieldName(clazz.getName(), mcpName);
            if (srg != null) {
                try {
                    Field f = clazz.getDeclaredField(srg);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                }
            }
            throw new RuntimeException("Field not found: " + mcpName + " in " + clazz.getName());
        });
        try {
            field.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + mcpName, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T invokeStaticMethod(Class<?> clazz, String mcpName, Object... args) {
        String cacheKey = clazz.getName() + "." + mcpName;
        Method method = methodCache.computeIfAbsent(cacheKey, k -> {
            Class<?>[] paramTypes = getParameterTypes(args);
            try {
                Method m = clazz.getDeclaredMethod(mcpName, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
            String srg = findSrgMethodName(clazz.getName(), mcpName);
            if (srg != null) {
                try {
                    Method m = clazz.getDeclaredMethod(srg, paramTypes);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                }
            }
            for (Method m : clazz.getDeclaredMethods()) {
                if ((m.getName().equals(mcpName) || (srg != null && m.getName().equals(srg))) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m;
                }
            }
            throw new RuntimeException("Method not found: " + mcpName + " in " + clazz.getName());
        });
        try {
            return (T) method.invoke(null, args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke static method: " + mcpName, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T invokeMethod(Object obj, String mcpName, Object... args) {
        if (obj == null) throw new IllegalArgumentException("Object cannot be null");
        Class<?> clazz = obj.getClass();
        String cacheKey = clazz.getName() + "." + mcpName;
        Method method = methodCache.computeIfAbsent(cacheKey, k -> {
            Class<?>[] paramTypes = getParameterTypes(args);
            try {
                Method m = clazz.getDeclaredMethod(mcpName, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
            String srg = findSrgMethodName(clazz.getName(), mcpName);
            if (srg != null) {
                try {
                    Method m = clazz.getDeclaredMethod(srg, paramTypes);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                }
            }
            for (Method m : clazz.getDeclaredMethods()) {
                if ((m.getName().equals(mcpName) || (srg != null && m.getName().equals(srg))) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m;
                }
            }
            throw new RuntimeException("Method not found: " + mcpName + " in " + clazz.getName());
        });
        try {
            return (T) method.invoke(obj, args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke method: " + mcpName, e);
        }
    }

    public static Class<?>[] getParameterTypes(Object... args) {
        if (args == null || args.length == 0) return new Class<?>[0];
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++)
            types[i] = args[i] != null ? args[i].getClass() : Object.class;
        return types;
    }

    public static void clearCache() {
        fieldCache.clear();
        methodCache.clear();
        fieldMappings.clear();
        methodMappings.clear();
        classNameMappings.clear();
        methodReturnTypes.clear();
        methodMappingsByDescriptor.clear();
        methodReturnTypesByDescriptor.clear();
        mappingLoaded = false;
        mappingFilePath = null;
        LOGGER.info("MinecraftHelper cache cleared");
    }

    @SuppressWarnings("unchecked")
    public static <T> T getStaticField(String className, String fieldName) {
        try {
            return getStaticField(Class.forName(className), fieldName);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Class not found: " + className, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T invokeStaticMethod(String className, String methodName, Object... args) {
        try {
            return invokeStaticMethod(Class.forName(className), methodName, args);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Class not found: " + className, e);
        }
    }
}

class MC extends MinecraftHelper {}
