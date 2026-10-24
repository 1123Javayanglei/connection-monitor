package com.connectionmonitor.starter.support;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 基于类名的类型匹配工具：只比较类名字符串，不加载目标类，
 * 因此 Druid / HikariCP / spring-jdbc 不在 classpath 时也不会抛 {@link NoClassDefFoundError}。
 *
 * <p>这是可选依赖方案的核心：识别阶段绝不 {@code Class.forName}，
 * 只有确认目标类存在后（由 Spring {@code @ConditionalOnClass} 或调用链保证）才进入直接 API 调用。</p>
 */
public final class ClassNameMatcher {

    /** 防御性上限，避免异常代理类导致死循环。 */
    private static final int MAX_VISIT = 256;

    private ClassNameMatcher() {
    }

    /**
     * 判断对象是否为指定类的实例（含父类与接口链，按类名比较）。
     */
    public static boolean isInstanceOf(Object target, String targetClassName) {
        if (target == null || targetClassName == null) {
            return false;
        }
        Map<Class<?>, Boolean> visited = new IdentityHashMap<Class<?>, Boolean>();
        return classChainMatches(target.getClass(), targetClassName, visited);
    }

    private static boolean classChainMatches(Class<?> clazz, String targetClassName, Map<Class<?>, Boolean> visited) {
        Class<?> current = clazz;
        while (current != null) {
            if (current.getName().equals(targetClassName)) {
                return true;
            }
            for (Class<?> iface : current.getInterfaces()) {
                if (interfaceMatches(iface, targetClassName, visited)) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static boolean interfaceMatches(Class<?> iface, String targetClassName, Map<Class<?>, Boolean> visited) {
        if (iface == null || visited.containsKey(iface) || visited.size() > MAX_VISIT) {
            return false;
        }
        visited.put(iface, Boolean.TRUE);
        if (iface.getName().equals(targetClassName)) {
            return true;
        }
        for (Class<?> parent : iface.getInterfaces()) {
            if (interfaceMatches(parent, targetClassName, visited)) {
                return true;
            }
        }
        return false;
    }
}
