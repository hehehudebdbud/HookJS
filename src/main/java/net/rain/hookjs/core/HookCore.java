package net.rain.hookjs.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.ASMifier;
import org.objectweb.asm.util.TraceClassVisitor;
import sun.misc.Unsafe;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static org.objectweb.asm.Opcodes.AASTORE;
import static org.objectweb.asm.Opcodes.ACC_BRIDGE;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_NATIVE;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_STRICT;
import static org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ACC_VARARGS;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ANEWARRAY;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.POP2;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.SIPUSH;
import static org.objectweb.asm.Opcodes.V1_8;

public class HookCore {

    private static final long KLASS_OFFSET = 8L;
    private static final long KLASS_OFFSET_32 = 4L;

    private static final Unsafe UNSAFE;
    private static final boolean IS_64_BIT;
    private static final long ACTUAL_KLASS_OFFSET;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);

            String arch = System.getProperty("sun.arch.data.model");
            IS_64_BIT = "64".equals(arch);

            boolean compressedOops = IS_64_BIT
                    && System.getProperty("java.vm.compressedOopsMode") != null;
            ACTUAL_KLASS_OFFSET = compressedOops || !IS_64_BIT ? KLASS_OFFSET : 8L;
        } catch (Exception e) {
            throw new RuntimeException("无法初始化 Unsafe", e);
        }
    }

    private static final HookCore INSTANCE = new HookCore();

    public static HookCore getInstance() {
        return INSTANCE;
    }

    private HookCore() {
    }

    public interface OverwriteHandler<T> {
        Object overwrite(T instance, Object[] args) throws Throwable;
    }

    public interface ReturnInterceptor<T> {
        Object intercept(T instance, Object[] args, Object originalReturn) throws Throwable;
    }

    private enum HookKind {
        HEAD,
        TAIL,
        OVERWRITE,
        RETURN
    }

    private static class HookConfig {
        final String targetMethodName;
        final String targetMethodDesc;
        final String hookClassName;
        final String hookMethodName;
        final String hookMethodDesc;
        final Object callback;
        final HookKind kind;

        HookConfig(String targetMethodName, String targetMethodDesc,
                String hookClassName, String hookMethodName,
                String hookMethodDesc, HookKind kind) {
            this.targetMethodName = Objects.requireNonNull(targetMethodName, "targetMethodName");
            this.targetMethodDesc = Objects.requireNonNull(targetMethodDesc, "targetMethodDesc");
            this.hookClassName = Objects.requireNonNull(hookClassName, "hookClassName");
            this.hookMethodName = Objects.requireNonNull(hookMethodName, "hookMethodName");
            this.hookMethodDesc = Objects.requireNonNull(hookMethodDesc, "hookMethodDesc");
            this.callback = null;
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        HookConfig(String targetMethodName, String targetMethodDesc,
                Object callback, HookKind kind) {
            this.targetMethodName = Objects.requireNonNull(targetMethodName, "targetMethodName");
            this.targetMethodDesc = Objects.requireNonNull(targetMethodDesc, "targetMethodDesc");
            this.hookClassName = null;
            this.hookMethodName = null;
            this.hookMethodDesc = null;
            this.callback = Objects.requireNonNull(callback, "callback");
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        boolean isCallback() {
            return callback != null;
        }

        String fieldKey() {
            return targetMethodName + '|' + targetMethodDesc + '|' + kind + '|'
                    + System.identityHashCode(callback);
        }

        String callbackFieldDesc() {
            if (kind == HookKind.HEAD || kind == HookKind.TAIL) {
                return Type.getDescriptor(BiConsumer.class);
            }
            if (kind == HookKind.OVERWRITE) {
                return Type.getDescriptor(OverwriteHandler.class);
            }
            if (kind == HookKind.RETURN) {
                return Type.getDescriptor(ReturnInterceptor.class);
            }
            throw new IllegalStateException("未知 HookKind: " + kind);
        }
    }

    private static class MethodPlan {
        final java.lang.reflect.Method method;
        final List<HookConfig> configs;

        MethodPlan(java.lang.reflect.Method method, List<HookConfig> configs) {
            this.method = method;
            this.configs = configs;
        }
    }

    private static class GeneratedClassInfo {
        final String subclassName;
        final Class<?> clazz;
        final byte[] bytecode;
        final Map<String, Object> callbackMap;

        GeneratedClassInfo(String subclassName, Class<?> clazz,
                byte[] bytecode, Map<String, Object> callbackMap) {
            this.subclassName = subclassName;
            this.clazz = clazz;
            this.bytecode = bytecode;
            this.callbackMap = callbackMap;
        }
    }

    private final Map<String, List<HookConfig>> hookConfigs = new ConcurrentHashMap<>();
    private final Map<String, GeneratedClassInfo> generatedClasses = new ConcurrentHashMap<>();
    private final DynamicClassLoader dynamicLoader = new DynamicClassLoader();

    public static void setKlass(Object target, Class<?> klass) {
        if (target == null) {
            throw new IllegalArgumentException("target 不能为 null");
        }
        if (klass == null) {
            throw new IllegalArgumentException("klass 不能为 null");
        }
        if (target.getClass() == klass) {
            return;
        }
        if (!target.getClass().isAssignableFrom(klass)) {
            throw new IllegalArgumentException("klass 必须是 target 当前类型的子类: target="
                    + target.getClass().getName() + ", klass=" + klass.getName());
        }

        try {
            Object proxy = UNSAFE.allocateInstance(klass);
            int newKlassWord = UNSAFE.getInt(proxy, ACTUAL_KLASS_OFFSET);
            UNSAFE.putInt(target, ACTUAL_KLASS_OFFSET, newKlassWord);
        } catch (InstantiationException e) {
            throw new RuntimeException("无法实例化目标类: " + klass.getName(), e);
        } catch (Exception e) {
            throw new RuntimeException("setKlass 失败", e);
        }
    }

    public static long getKlassWord(Object obj) {
        if (obj == null) {
            return 0;
        }
        return UNSAFE.getInt(obj, ACTUAL_KLASS_OFFSET) & 0xFFFFFFFFL;
    }

    public HookCore injectHead(String targetClassName, String targetMethodName,
            String targetMethodDesc,
            String injectMethodClassName, String injectMethodName,
            String injectMethodDesc) {
        return addHook(targetClassName, targetMethodName, targetMethodDesc,
                injectMethodClassName, injectMethodName, injectMethodDesc,
                null, HookKind.HEAD);
    }

    public HookCore injectHead(String targetClassName, String targetMethodName,
            String injectMethodClassName, String injectMethodName) {
        return injectHead(targetClassName, targetMethodName, "()V",
                injectMethodClassName, injectMethodName, "()V");
    }

    public <T> HookCore injectHead(String targetClassName, String targetMethodName,
            String targetMethodDesc, BiConsumer<T, Object[]> biConsumer) {
        return addHook(targetClassName, targetMethodName, targetMethodDesc,
                null, null, null, biConsumer, HookKind.HEAD);
    }

    public <T> HookCore injectHead(String targetClassName, String targetMethodName,
            BiConsumer<T, Object[]> biConsumer) {
        return injectHead(targetClassName, targetMethodName, "()V", biConsumer);
    }

    public HookCore injectTail(String targetClassName, String targetMethodName,
            String targetMethodDesc,
            String injectMethodClassName, String injectMethodName,
            String injectMethodDesc) {
        return addHook(targetClassName, targetMethodName, targetMethodDesc,
                injectMethodClassName, injectMethodName, injectMethodDesc,
                null, HookKind.TAIL);
    }

    public <T> HookCore injectTail(String targetClassName, String targetMethodName,
            String targetMethodDesc, BiConsumer<T, Object[]> biConsumer) {
        return addHook(targetClassName, targetMethodName, targetMethodDesc,
                null, null, null, biConsumer, HookKind.TAIL);
    }

    public HookCore overwrite(String targetClassName, String targetMethodName,
            String targetMethodDesc,
            String overwriteMethodClassName, String overwriteMethodName,
            String overwriteMethodDesc) {
        return addHook(targetClassName, targetMethodName, targetMethodDesc,
                overwriteMethodClassName, overwriteMethodName, overwriteMethodDesc,
                null, HookKind.OVERWRITE);
    }

    public HookCore overwrite(String targetClassName, String targetMethodName,
            String overwriteMethodClassName, String overwriteMethodName) {
        return overwrite(targetClassName, targetMethodName, "()V",
                overwriteMethodClassName, overwriteMethodName, "()V");
    }

    public <T> HookCore overwrite(String targetClassName, String targetMethodName,
            String targetMethodDesc, OverwriteHandler<T> overwriteHandler) {
        return addHook(targetClassName, targetMethodName, targetMethodDesc,
                null, null, null, overwriteHandler, HookKind.OVERWRITE);
    }

    public <T> HookCore overwrite(String targetClassName, String targetMethodName,
            OverwriteHandler<T> overwriteHandler) {
        return overwrite(targetClassName, targetMethodName, "()V", overwriteHandler);
    }

    public <T> HookCore injectReturn(String targetClassName, String targetMethodName,
            String targetMethodDesc, ReturnInterceptor<T> interceptor) {
        return addHook(targetClassName, targetMethodName, targetMethodDesc,
                null, null, null, interceptor, HookKind.RETURN);
    }

    private HookCore addHook(String targetClassName, String targetMethodName,
            String targetMethodDesc,
            String hookClassName, String hookMethodName,
            String hookMethodDesc,
            Object callback, HookKind kind) {

        String normalizedTargetClass = normalizeClassName(targetClassName);
        validateMethodDescriptor(targetMethodDesc, "targetMethodDesc");

        HookConfig config;
        if (callback != null) {
            config = new HookConfig(targetMethodName, targetMethodDesc, callback, kind);
        } else {
            validateMethodDescriptor(hookMethodDesc, "hookMethodDesc");
            config = new HookConfig(targetMethodName, targetMethodDesc,
                    hookClassName, hookMethodName, hookMethodDesc, kind);
        }

        hookConfigs.computeIfAbsent(normalizedTargetClass, k -> new ArrayList<>()).add(config);

        System.out.println("[HookCore] 注册 Hook: " + normalizedTargetClass + "." + targetMethodName
                + targetMethodDesc + " -> kind=" + kind + ", target="
                + (callback != null ? callback.getClass().getName()
                : hookClassName + "." + hookMethodName + hookMethodDesc));

        return this;
    }

    public HookCore apply() {
        for (String targetClassName : new ArrayList<>(hookConfigs.keySet())) {
            generateAndLoadSubclass(targetClassName);
        }
        return this;
    }

    private void generateAndLoadSubclass(String targetClassName) {
        try {
            if (generatedClasses.containsKey(targetClassName)) {
                System.out.println("[HookCore] 已生成过，跳过: " + targetClassName);
                return;
            }

            Class<?> targetClass = Class.forName(targetClassName, false,
                    Thread.currentThread().getContextClassLoader());
            List<HookConfig> configs = hookConfigs.get(targetClassName);
            if (configs == null || configs.isEmpty()) {
                return;
            }

            List<MethodPlan> methodPlans = buildMethodPlans(targetClass, configs);
            validateHooks(methodPlans);

            String subclassName = targetClassName + "$$ASMSubclass_"
                    + System.nanoTime() + "_" + UUID.randomUUID().toString().substring(0, 8);
            String subclassInternal = subclassName.replace('.', '/');
            String targetInternal = targetClassName.replace('.', '/');

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(V1_8, ACC_PUBLIC, subclassInternal, null, targetInternal, null);

            Map<String, Object> callbackMap = new LinkedHashMap<>();
            Map<String, String> callbackFieldNames = new HashMap<>();
            int callbackCount = 0;

            for (HookConfig config : configs) {
                if (!config.isCallback()) {
                    continue;
                }
                String key = config.fieldKey();
                if (callbackFieldNames.containsKey(key)) {
                    continue;
                }

                String fieldName = "HOOK_" + callbackCount++;
                callbackFieldNames.put(key, fieldName);
                callbackMap.put(fieldName, config.callback);

                FieldVisitor fv = cw.visitField(ACC_PRIVATE | ACC_STATIC,
                        fieldName,
                        config.callbackFieldDesc(),
                        null, null);
                fv.visitEnd();
            }

            if (!callbackMap.isEmpty()) {
                generateStaticInitializer(cw);
            }

            generateConstructors(cw, targetClass, targetInternal);

            for (MethodPlan plan : methodPlans) {
                generateOverriddenMethod(cw, targetInternal, subclassInternal,
                        plan.method, plan.configs, callbackFieldNames);
            }

            cw.visitEnd();
            byte[] bytecode = cw.toByteArray();

            Class<?> generatedClass = dynamicLoader.defineClass(subclassName, bytecode);
            for (Map.Entry<String, Object> entry : callbackMap.entrySet()) {
                Field field = generatedClass.getDeclaredField(entry.getKey());
                field.setAccessible(true);
                field.set(null, entry.getValue());
            }

            generatedClasses.put(targetClassName,
                    new GeneratedClassInfo(subclassName, generatedClass, bytecode, callbackMap));

            System.out.println("[HookCore] 成功生成 ASM 子类: " + subclassName);
        } catch (Exception e) {
            System.err.println("[HookCore] 生成 ASM 类失败: " + targetClassName);
            e.printStackTrace();
            throw new RuntimeException("生成失败: " + e.getMessage(), e);
        }
    }

    private void generateStaticInitializer(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void generateConstructors(ClassWriter cw, Class<?> targetClass, String targetInternal) {
        Constructor<?>[] constructors = targetClass.getDeclaredConstructors();
        boolean generatedAny = false;

        for (Constructor<?> ctor : constructors) {
            int modifiers = ctor.getModifiers();
            if (Modifier.isPrivate(modifiers)) {
                continue;
            }

            int access = constructorAccess(modifiers);
            String ctorDesc = Type.getConstructorDescriptor(ctor);
            String[] exceptions = toInternalNames(ctor.getExceptionTypes());

            MethodVisitor mv = cw.visitMethod(access, "<init>", ctorDesc, null, exceptions);
            GeneratorAdapter ga = new GeneratorAdapter(mv, access, "<init>", ctorDesc);
            ga.visitCode();
            ga.loadThis();
            for (int i = 0; i < ctor.getParameterTypes().length; i++) {
                ga.loadArg(i);
            }
            ga.visitMethodInsn(INVOKESPECIAL, targetInternal, "<init>", ctorDesc, false);
            ga.returnValue();
            ga.endMethod();
            generatedAny = true;
        }

        if (!generatedAny) {
            throw new IllegalStateException("目标类没有可继承的构造方法: " + targetClass.getName());
        }
    }

    private void generateOverriddenMethod(ClassWriter cw, String superInternal,
            String classInternal, java.lang.reflect.Method targetMethod,
            List<HookConfig> configs, Map<String, String> callbackFieldNames) {
        try {
            String methodName = targetMethod.getName();
            String methodDesc = Type.getMethodDescriptor(targetMethod);
            Type methodType = Type.getMethodType(methodDesc);
            Type[] argTypes = methodType.getArgumentTypes();
            Type returnType = methodType.getReturnType();
            String[] exceptions = toInternalNames(targetMethod.getExceptionTypes());
            int access = methodAccess(targetMethod.getModifiers());

            MethodNode mn = new MethodNode(access, methodName, methodDesc, null, exceptions);
            mn.visitCode();

            List<HookConfig> heads = filterByKind(configs, HookKind.HEAD);
            List<HookConfig> tails = filterByKind(configs, HookKind.TAIL);
            List<HookConfig> overwrites = filterByKind(configs, HookKind.OVERWRITE);
            List<HookConfig> returnHooks = filterByKind(configs, HookKind.RETURN);

            for (HookConfig config : heads) {
                generateVoidHookInvocation(mn, config, callbackFieldNames, classInternal, argTypes);
            }

            int returnLocal = -1;
            if (returnType.getSort() != Type.VOID) {
                returnLocal = newLocalIndex(argTypes);
            }

            if (!overwrites.isEmpty()) {
                HookConfig overwrite = overwrites.get(0);
                generateOverwriteInvocation(mn, overwrite, callbackFieldNames, classInternal,
                        argTypes, returnType, returnLocal);
            } else {
                mn.visitVarInsn(ALOAD, 0);
                loadMethodArguments(mn, argTypes);
                mn.visitMethodInsn(INVOKESPECIAL, superInternal, methodName, methodDesc, false);
                if (returnType.getSort() != Type.VOID) {
                    mn.visitVarInsn(returnType.getOpcode(org.objectweb.asm.Opcodes.ISTORE), returnLocal);
                }
            }

            for (HookConfig config : tails) {
                generateVoidHookInvocation(mn, config, callbackFieldNames, classInternal, argTypes);
            }

            for (HookConfig config : returnHooks) {
                generateReturnHookInvocation(mn, config, callbackFieldNames, classInternal,
                        argTypes, returnType, returnLocal);
            }

            if (returnType.getSort() == Type.VOID) {
                mn.visitInsn(RETURN);
            } else {
                mn.visitVarInsn(returnType.getOpcode(ILOAD), returnLocal);
                mn.visitInsn(returnType.getOpcode(IRETURN));
            }

            mn.visitEnd();
            mn.accept(cw);
        } catch (Exception e) {
            System.err.println("[HookCore] 生成覆写方法失败: "
                    + targetMethod.getName() + Type.getMethodDescriptor(targetMethod));
            e.printStackTrace();
            throw new RuntimeException("[HookCore] 生成覆写方法失败: " + e.getMessage(), e);
        }
    }

    private List<HookConfig> filterByKind(List<HookConfig> configs, HookKind kind) {
        List<HookConfig> result = new ArrayList<>();
        for (HookConfig config : configs) {
            if (config.kind == kind) {
                result.add(config);
            }
        }
        return result;
    }

    private void generateVoidHookInvocation(MethodNode mn, HookConfig config,
            Map<String, String> callbackFieldNames, String classInternal, Type[] argTypes) {
        if (config.isCallback()) {
            String fieldName = callbackFieldNames.get(config.fieldKey());
            if (fieldName == null) {
                throw new IllegalStateException("未找到回调字段: " + config.fieldKey());
            }
            mn.visitFieldInsn(GETSTATIC, classInternal, fieldName, config.callbackFieldDesc());
            mn.visitVarInsn(ALOAD, 0);
            generateArgsArray(mn, argTypes);
            mn.visitMethodInsn(INVOKEINTERFACE, "java/util/function/BiConsumer",
                    "accept", "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
            return;
        }

        loadMethodArguments(mn, argTypes);
        mn.visitMethodInsn(INVOKESTATIC, config.hookClassName.replace('.', '/'), config.hookMethodName,
                config.hookMethodDesc, false);
        Type hookReturnType = Type.getReturnType(config.hookMethodDesc);
        if (hookReturnType.getSort() != Type.VOID) {
            discardTopValue(mn, hookReturnType);
        }
    }

    private void generateOverwriteInvocation(MethodNode mn, HookConfig config,
            Map<String, String> callbackFieldNames, String classInternal,
            Type[] argTypes, Type returnType, int returnLocal) {
        if (config.isCallback()) {
            String fieldName = callbackFieldNames.get(config.fieldKey());
            if (fieldName == null) {
                throw new IllegalStateException("未找到回调字段: " + config.fieldKey());
            }
            mn.visitFieldInsn(GETSTATIC, classInternal, fieldName, config.callbackFieldDesc());
            mn.visitVarInsn(ALOAD, 0);
            generateArgsArray(mn, argTypes);
            mn.visitMethodInsn(INVOKEINTERFACE,
                    Type.getInternalName(OverwriteHandler.class),
                    "overwrite",
                    "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                    true);

            if (returnType.getSort() == Type.VOID) {
                mn.visitInsn(POP);
            } else {
                emitCastOrUnbox(mn, returnType);
                mn.visitVarInsn(returnType.getOpcode(org.objectweb.asm.Opcodes.ISTORE), returnLocal);
            }
            return;
        }

        loadMethodArguments(mn, argTypes);
        mn.visitMethodInsn(INVOKESTATIC, config.hookClassName.replace('.', '/'), config.hookMethodName,
                config.hookMethodDesc, false);
        if (returnType.getSort() == Type.VOID) {
            Type hookReturnType = Type.getReturnType(config.hookMethodDesc);
            if (hookReturnType.getSort() != Type.VOID) {
                discardTopValue(mn, hookReturnType);
            }
        } else {
            mn.visitVarInsn(returnType.getOpcode(org.objectweb.asm.Opcodes.ISTORE), returnLocal);
        }
    }

    private void generateReturnHookInvocation(MethodNode mn, HookConfig config,
            Map<String, String> callbackFieldNames, String classInternal,
            Type[] argTypes, Type returnType, int returnLocal) {
        if (returnType.getSort() == Type.VOID) {
            throw new IllegalStateException("void 方法不能使用 injectReturn: "
                    + config.targetMethodName + config.targetMethodDesc);
        }
        if (!config.isCallback()) {
            throw new IllegalArgumentException("injectReturn 目前仅支持 ReturnInterceptor 回调方式");
        }

        String fieldName = callbackFieldNames.get(config.fieldKey());
        if (fieldName == null) {
            throw new IllegalStateException("未找到回调字段: " + config.fieldKey());
        }

        mn.visitFieldInsn(GETSTATIC, classInternal, fieldName, config.callbackFieldDesc());
        mn.visitVarInsn(ALOAD, 0);
        generateArgsArray(mn, argTypes);
        mn.visitVarInsn(returnType.getOpcode(ILOAD), returnLocal);
        boxTree(mn, returnType);
        mn.visitMethodInsn(INVOKEINTERFACE,
                Type.getInternalName(ReturnInterceptor.class),
                "intercept",
                "(Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                true);
        emitCastOrUnbox(mn, returnType);
        mn.visitVarInsn(returnType.getOpcode(org.objectweb.asm.Opcodes.ISTORE), returnLocal);
    }

    private void loadMethodArguments(MethodNode mn, Type[] argTypes) {
        int localIndex = 1;
        for (Type argType : argTypes) {
            mn.visitVarInsn(argType.getOpcode(ILOAD), localIndex);
            localIndex += argType.getSize();
        }
    }

    private void generateArgsArray(MethodNode mn, Type[] argTypes) {
        pushInt(mn, argTypes.length);
        mn.visitTypeInsn(ANEWARRAY, "java/lang/Object");

        int localIndex = 1;
        for (int i = 0; i < argTypes.length; i++) {
            mn.visitInsn(DUP);
            pushInt(mn, i);
            Type argType = argTypes[i];
            mn.visitVarInsn(argType.getOpcode(ILOAD), localIndex);
            boxTree(mn, argType);
            mn.visitInsn(AASTORE);
            localIndex += argType.getSize();
        }
    }

    private void boxTree(MethodNode mn, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                mn.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                        "(Z)Ljava/lang/Boolean;", false);
                break;
            case Type.BYTE:
                mn.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf",
                        "(B)Ljava/lang/Byte;", false);
                break;
            case Type.CHAR:
                mn.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf",
                        "(C)Ljava/lang/Character;", false);
                break;
            case Type.SHORT:
                mn.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf",
                        "(S)Ljava/lang/Short;", false);
                break;
            case Type.INT:
                mn.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf",
                        "(I)Ljava/lang/Integer;", false);
                break;
            case Type.LONG:
                mn.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf",
                        "(J)Ljava/lang/Long;", false);
                break;
            case Type.FLOAT:
                mn.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf",
                        "(F)Ljava/lang/Float;", false);
                break;
            case Type.DOUBLE:
                mn.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf",
                        "(D)Ljava/lang/Double;", false);
                break;
            default:
                break;
        }
    }


    private void discardTopValue(MethodNode mn, Type type) {
        if (type.getSize() == 2) {
            mn.visitInsn(POP2);
        } else {
            mn.visitInsn(POP);
        }
    }

    private void emitCastOrUnbox(MethodNode mn, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                mn.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                mn.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                break;
            case Type.BYTE:
                mn.visitTypeInsn(CHECKCAST, "java/lang/Byte");
                mn.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false);
                break;
            case Type.CHAR:
                mn.visitTypeInsn(CHECKCAST, "java/lang/Character");
                mn.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                break;
            case Type.SHORT:
                mn.visitTypeInsn(CHECKCAST, "java/lang/Short");
                mn.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false);
                break;
            case Type.INT:
                mn.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                mn.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                break;
            case Type.LONG:
                mn.visitTypeInsn(CHECKCAST, "java/lang/Long");
                mn.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
                break;
            case Type.FLOAT:
                mn.visitTypeInsn(CHECKCAST, "java/lang/Float");
                mn.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false);
                break;
            case Type.DOUBLE:
                mn.visitTypeInsn(CHECKCAST, "java/lang/Double");
                mn.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
                break;
            case Type.ARRAY:
            case Type.OBJECT:
                mn.visitTypeInsn(CHECKCAST, type.getInternalName());
                break;
            default:
                throw new IllegalArgumentException("不支持的返回类型: " + type);
        }
    }

    private void validateHooks(List<MethodPlan> methodPlans)
            throws ClassNotFoundException, NoSuchMethodException {
        for (MethodPlan plan : methodPlans) {
            int overwriteCount = 0;
            for (HookConfig config : plan.configs) {
                if (config.kind == HookKind.OVERWRITE) {
                    overwriteCount++;
                }
                if (config.kind == HookKind.RETURN
                        && Type.getReturnType(config.targetMethodDesc).getSort() == Type.VOID) {
                    throw new IllegalArgumentException("void 方法不能使用 injectReturn: "
                            + plan.method.getDeclaringClass().getName() + "."
                            + plan.method.getName() + config.targetMethodDesc);
                }

                if (config.isCallback()) {
                    continue;
                }

                Class<?> hookClass = Class.forName(config.hookClassName, false,
                        Thread.currentThread().getContextClassLoader());
                java.lang.reflect.Method hookMethod = findDeclaredMethodByNameAndDesc(
                        hookClass, config.hookMethodName, config.hookMethodDesc);
                if (hookMethod == null) {
                    throw new NoSuchMethodException("未找到 Hook 方法: " + config.hookClassName + "."
                            + config.hookMethodName + config.hookMethodDesc);
                }
                if (!Modifier.isStatic(hookMethod.getModifiers())) {
                    throw new IllegalArgumentException("Hook 方法必须是 static: " + hookMethod);
                }

                Type targetMethodType = Type.getMethodType(config.targetMethodDesc);
                Type hookMethodType = Type.getMethodType(config.hookMethodDesc);
                if (!targetMethodType.equals(hookMethodType)) {
                    throw new IllegalArgumentException("静态 Hook 方法签名必须与目标方法一致: target="
                            + config.targetMethodDesc + ", hook=" + config.hookMethodDesc);
                }
            }

            if (overwriteCount > 1) {
                throw new IllegalArgumentException("同一方法最多只能注册一个 overwrite: " + plan.method);
            }
        }
    }

    private List<MethodPlan> buildMethodPlans(Class<?> targetClass, List<HookConfig> configs) {
        Map<String, List<HookConfig>> methodGroups = new LinkedHashMap<>();
        for (HookConfig config : configs) {
            String key = config.targetMethodName + config.targetMethodDesc;
            methodGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(config);
        }

        List<MethodPlan> plans = new ArrayList<>();
        for (Map.Entry<String, List<HookConfig>> entry : methodGroups.entrySet()) {
            HookConfig sample = entry.getValue().get(0);
            java.lang.reflect.Method targetMethod = findOverrideableMethod(targetClass,
                    sample.targetMethodName, sample.targetMethodDesc);
            if (targetMethod == null) {
                throw new IllegalArgumentException("未找到目标方法: " + targetClass.getName() + "."
                        + sample.targetMethodName + sample.targetMethodDesc);
            }
            int modifiers = targetMethod.getModifiers();
            if (Modifier.isPrivate(modifiers) || Modifier.isStatic(modifiers)
                    || Modifier.isFinal(modifiers)) {
                throw new IllegalArgumentException("目标方法不可覆写: " + targetMethod);
            }
            plans.add(new MethodPlan(targetMethod, entry.getValue()));
        }
        return plans;
    }

    private java.lang.reflect.Method findOverrideableMethod(Class<?> targetClass,
            String methodName, String methodDesc) {
        Class<?> current = targetClass;
        while (current != null) {
            java.lang.reflect.Method method = findDeclaredMethodByNameAndDesc(current, methodName, methodDesc);
            if (method != null) {
                return method;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private java.lang.reflect.Method findDeclaredMethodByNameAndDesc(Class<?> clazz,
            String methodName, String methodDesc) {
        for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)
                    && Type.getMethodDescriptor(method).equals(methodDesc)) {
                return method;
            }
        }
        return null;
    }

    private int constructorAccess(int modifiers) {
        if (Modifier.isPublic(modifiers)) {
            return ACC_PUBLIC;
        }
        if (Modifier.isProtected(modifiers)) {
            return ACC_PROTECTED;
        }
        return 0;
    }

    private int methodAccess(int modifiers) {
        int access = 0;
        if (Modifier.isPublic(modifiers)) {
            access |= ACC_PUBLIC;
        } else if (Modifier.isProtected(modifiers)) {
            access |= ACC_PROTECTED;
        }
        if (Modifier.isSynchronized(modifiers)) {
            access |= ACC_SYNCHRONIZED;
        }
        if (Modifier.isStrict(modifiers)) {
            access |= ACC_STRICT;
        }
        if ((modifiers & 0x00000040) != 0) {
            access |= ACC_BRIDGE;
        }
        if ((modifiers & 0x00000080) != 0) {
            access |= ACC_VARARGS;
        }
        if ((modifiers & 0x00001000) != 0) {
            access |= ACC_SYNTHETIC;
        }
        access &= ~(ACC_NATIVE | ACC_FINAL | ACC_PRIVATE | ACC_STATIC);
        return access;
    }

    private String[] toInternalNames(Class<?>[] exceptionTypes) {
        if (exceptionTypes == null || exceptionTypes.length == 0) {
            return null;
        }
        String[] result = new String[exceptionTypes.length];
        for (int i = 0; i < exceptionTypes.length; i++) {
            result[i] = exceptionTypes[i].getName().replace('.', '/');
        }
        return result;
    }

    private int newLocalIndex(Type[] argTypes) {
        int index = 1;
        for (Type argType : argTypes) {
            index += argType.getSize();
        }
        return index;
    }

    private void pushInt(MethodNode mn, int value) {
        switch (value) {
            case -1:
                mn.visitInsn(org.objectweb.asm.Opcodes.ICONST_M1);
                return;
            case 0:
                mn.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
                return;
            case 1:
                mn.visitInsn(org.objectweb.asm.Opcodes.ICONST_1);
                return;
            case 2:
                mn.visitInsn(org.objectweb.asm.Opcodes.ICONST_2);
                return;
            case 3:
                mn.visitInsn(org.objectweb.asm.Opcodes.ICONST_3);
                return;
            case 4:
                mn.visitInsn(org.objectweb.asm.Opcodes.ICONST_4);
                return;
            case 5:
                mn.visitInsn(org.objectweb.asm.Opcodes.ICONST_5);
                return;
            default:
                break;
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mn.visitIntInsn(org.objectweb.asm.Opcodes.BIPUSH, value);
            return;
        }
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mn.visitIntInsn(SIPUSH, value);
            return;
        }
        mn.visitLdcInsn(value);
    }

    private void validateMethodDescriptor(String desc, String name) {
        try {
            Type.getMethodType(desc);
        } catch (Exception e) {
            throw new IllegalArgumentException(name + " 不是合法的方法描述符: " + desc, e);
        }
    }

    private String normalizeClassName(String className) {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("targetClassName 不能为空");
        }
        return className.replace('/', '.');
    }

    public void applyTo(Object target) {
        if (target == null) {
            return;
        }

        String targetClassName = target.getClass().getName();
        GeneratedClassInfo info = generatedClasses.get(targetClassName);
        if (info == null) {
            throw new IllegalStateException("未为 " + targetClassName + " 生成增强类，请先注册 hook 再调用 apply()");
        }

        setKlass(target, info.clazz);
        System.out.println("[HookCore] 已应用 ASM 子类到对象: " + target
                + " (新类: " + info.subclassName + ")");
    }

    @SuppressWarnings("unchecked")
    public <T> T createInstance(Class<T> targetClass) {
        String targetClassName = targetClass.getName();
        GeneratedClassInfo info = generatedClasses.get(targetClassName);
        if (info == null) {
            throw new IllegalStateException("未为 " + targetClassName + " 生成增强类，请先注册 hook 再调用 apply()");
        }

        try {
            return (T) UNSAFE.allocateInstance(info.clazz);
        } catch (InstantiationException e) {
            throw new RuntimeException("无法创建增强实例", e);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T createInstance(Class<T> targetClass, Object... args) {
        String targetClassName = targetClass.getName();
        GeneratedClassInfo info = generatedClasses.get(targetClassName);
        if (info == null) {
            throw new IllegalStateException("未为 " + targetClassName + " 生成增强类");
        }

        try {
            Constructor<?> ctor = findCompatibleConstructor(info.clazz, args);
            if (ctor == null) {
                throw new NoSuchMethodException("未找到匹配的构造方法: " + info.clazz.getName());
            }
            ctor.setAccessible(true);
            return (T) ctor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("构造增强实例时目标构造方法抛出异常", e.getTargetException());
        } catch (Exception e) {
            throw new RuntimeException("无法创建增强实例", e);
        }
    }

    private Constructor<?> findCompatibleConstructor(Class<?> clazz, Object[] args) {
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        List<Constructor<?>> candidates = new ArrayList<>();
        Collections.addAll(candidates, constructors);
        candidates.sort(Comparator.comparingInt(c -> c.getParameterTypes().length));

        for (Constructor<?> ctor : candidates) {
            Class<?>[] paramTypes = ctor.getParameterTypes();
            if (paramTypes.length != args.length) {
                continue;
            }
            boolean matched = true;
            for (int i = 0; i < paramTypes.length; i++) {
                if (!isParameterCompatible(paramTypes[i], args[i])) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return ctor;
            }
        }
        return null;
    }

    private boolean isParameterCompatible(Class<?> parameterType, Object arg) {
        if (arg == null) {
            return !parameterType.isPrimitive();
        }
        Class<?> argType = arg.getClass();
        if (parameterType.isPrimitive()) {
            return primitiveToWrapper(parameterType).isAssignableFrom(argType);
        }
        return parameterType.isAssignableFrom(argType);
    }

    private Class<?> primitiveToWrapper(Class<?> type) {
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return type;
    }

    public byte[] getBytecode(String targetClassName) {
        GeneratedClassInfo info = generatedClasses.get(targetClassName);
        return info != null ? info.bytecode : null;
    }

    public String traceClass(String targetClassName) {
        byte[] bytecode = getBytecode(targetClassName);
        if (bytecode == null) {
            return "未找到生成的类: " + targetClassName;
        }

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ClassReader cr = new ClassReader(bytecode);
        ClassVisitor cv = new TraceClassVisitor(pw);
        cr.accept(cv, 0);
        return sw.toString();
    }

    public String toASMifier(String targetClassName) {
        byte[] bytecode = getBytecode(targetClassName);
        if (bytecode == null) {
            return "未找到生成的类: " + targetClassName;
        }

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ClassReader cr = new ClassReader(bytecode);
        ClassVisitor cv = new TraceClassVisitor(null, new ASMifier(), pw);
        cr.accept(cv, 0);
        return sw.toString();
    }

    public static Unsafe getUnsafe() {
        return UNSAFE;
    }

    public DynamicClassLoader getClassLoader() {
        return dynamicLoader;
    }

    public void clear() {
        hookConfigs.clear();
        generatedClasses.clear();
        dynamicLoader.clearLoadedClasses();
    }

    public static class DynamicClassLoader extends ClassLoader {
        private final Map<String, Class<?>> loadedClasses = new ConcurrentHashMap<>();

        public DynamicClassLoader() {
            super(Thread.currentThread().getContextClassLoader());
        }

        public DynamicClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            Class<?> loadedClass = loadedClasses.get(name);
            if (loadedClass != null) {
                return loadedClass;
            }

            Class<?> existing = findLoadedClass(name);
            if (existing != null) {
                return existing;
            }

            if (name.contains("$$ASMSubclass")) {
                throw new ClassNotFoundException("ASM 生成类尚未定义: " + name);
            }

            return super.loadClass(name);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            Class<?> clazz = loadedClasses.get(name);
            if (clazz != null) {
                return clazz;
            }
            return super.findClass(name);
        }

        public synchronized Class<?> defineClass(String name, byte[] bytecode) {
            Class<?> existing = loadedClasses.get(name);
            if (existing != null) {
                return existing;
            }

            Class<?> alreadyLoaded = findLoadedClass(name);
            if (alreadyLoaded != null) {
                loadedClasses.put(name, alreadyLoaded);
                return alreadyLoaded;
            }

            Class<?> clazz = defineClass(name, bytecode, 0, bytecode.length);
            loadedClasses.put(name, clazz);
            System.out.println("[DynamicClassLoader] 已定义类: " + name
                    + " (字节码大小: " + bytecode.length + " bytes)");
            return clazz;
        }

        public Collection<Class<?>> getLoadedClasses() {
            return Collections.unmodifiableCollection(loadedClasses.values());
        }

        public Set<String> getLoadedClassNames() {
            return Collections.unmodifiableSet(loadedClasses.keySet());
        }

        public void clearLoadedClasses() {
            loadedClasses.clear();
        }

        public boolean isLoaded(String className) {
            return loadedClasses.containsKey(className);
        }
    }
}
