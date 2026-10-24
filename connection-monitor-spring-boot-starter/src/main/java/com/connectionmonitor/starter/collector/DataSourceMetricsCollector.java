package com.connectionmonitor.starter.collector;

import com.connectionmonitor.starter.model.DataSourceDescriptor;
import com.connectionmonitor.starter.model.PoolSnapshot;

import javax.sql.DataSource;

/**
 * 统一连接池采集适配器。所有连接池类型（Druid、HikariCP、未知类型降级）实现同一接口、
 * 输出同一 {@link PoolSnapshot} 结构，服务端不区分两套模型。
 *
 * <p>实现约束：</p>
 * <ul>
 *   <li>采集过程中不得执行 SQL、不得调用 {@code DataSource.getConnection()}；</li>
 *   <li>不得关闭连接池、不得修改连接池配置；</li>
 *   <li>每个适配器独立处理异常，单个连接池采集失败不得影响其他连接池；</li>
 *   <li>{@link #supports(javax.sql.DataSource)} 必须能在对应类库缺失时安全返回 false。</li>
 * </ul>
 */
public interface DataSourceMetricsCollector {

    /** 判断本适配器能否处理该数据源（基于已解包的真实连接池实例）。 */
    boolean supports(DataSource dataSource);

    /** 采集连接池指标，返回统一快照；实现内部需隔离单个指标读取失败。 */
    PoolSnapshot collect(DataSourceDescriptor descriptor, DataSource dataSource);

    /**
     * 识别连接池类型（DRUID/HIKARI），无法识别返回 null。
     * 用于发现阶段构建描述符，不触发任何连接创建。
     */
    default String detectPoolType(DataSource dataSource) {
        return null;
    }

    /** 读取连接池自身配置的名称，无法获取返回 null。 */
    default String detectPoolName(DataSource dataSource) {
        return null;
    }

    /** 读取连接池版本，无法获取返回 null。 */
    default String detectPoolVersion(DataSource dataSource) {
        return null;
    }
}
