package com.connectionmonitor.starter.discovery;

import com.connectionmonitor.starter.support.ClassNameMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.Method;

/**
 * 安全、有限的数据源解包器：识别事务代理、懒加载代理等包装器并还原底层连接池。
 *
 * <p>约束：</p>
 * <ul>
 *   <li>只识别明确约定的包装器（Spring DelegatingDataSource 体系），不做盲反射；</li>
 *   <li>反射调用仅限三个白名单方法：{@code getTargetDataSource()}、
 *       {@code getResolvedDataSources()}、{@code getResolvedDefaultDataSource()}；</li>
 *   <li>递归解包深度上限 {@link #MAX_DEPTH}，禁止无限递归；</li>
 *   <li>路由数据源不在此处解包（它有多个目标，由发现服务单独处理），避免误取单个目标。</li>
 * </ul>
 */
public class DataSourceUnwrapper {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceUnwrapper.class);

    /** 递归解包深度上限。 */
    public static final int MAX_DEPTH = 8;

    private static final String DELEGATING_DATASOURCE_CLASS =
            "org.springframework.jdbc.datasource.DelegatingDataSource";
    private static final String ROUTING_DATASOURCE_CLASS =
            "org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource";

    /** 是否为 Spring DelegatingDataSource 体系的包装器（懒加载代理、事务代理等）。 */
    public boolean isDelegatingWrapper(DataSource dataSource) {
        return dataSource != null
                && !isRoutingDataSource(dataSource)
                && ClassNameMatcher.isInstanceOf(dataSource, DELEGATING_DATASOURCE_CLASS);
    }

    /** 是否为路由数据源（含 baomidou dynamic-datasource 等所有子类）。 */
    public boolean isRoutingDataSource(DataSource dataSource) {
        return ClassNameMatcher.isInstanceOf(dataSource, ROUTING_DATASOURCE_CLASS);
    }

    /**
     * 逐层剥开包装器返回真实连接池；路由数据源原样返回（多目标场景由发现服务处理）。
     * 任一层失败即停止并返回当前已解到的数据源，绝不抛异常。
     */
    public DataSource unwrap(DataSource dataSource) {
        DataSource current = dataSource;
        int depth = 0;
        while (current != null && depth < MAX_DEPTH && isDelegatingWrapper(current)) {
            DataSource target = invokeWhitelisted(current, "getTargetDataSource", DataSource.class);
            if (target == null || target == current) {
                break;
            }
            current = target;
            depth++;
        }
        return current != null ? current : dataSource;
    }

    /**
     * 白名单反射：仅调用无参、返回 DataSource 的指定方法。失败返回 null 并记录有限日志。
     */
    @SuppressWarnings("unchecked")
    <T> T invokeWhitelisted(Object target, String methodName, Class<T> expectedType) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            if (!expectedType.isAssignableFrom(method.getReturnType())) {
                return null;
            }
            Object result = method.invoke(target);
            return expectedType.cast(result);
        } catch (Throwable t) {
            LOG.debug("解包方法 [{}.{}] 调用失败: {}", target.getClass().getName(), methodName, t.toString());
            return null;
        }
    }
}
