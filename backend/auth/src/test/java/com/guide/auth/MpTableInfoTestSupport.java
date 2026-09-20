package com.guide.auth;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 纯单测里的 MyBatis-Plus 表信息初始化。
 *
 * <p>为什么需要：{@code TableInfo}（含 lambda 列名缓存）由 MyBatis-Plus 在 Spring 启动、
 * 注册 mapper 时建立；纯单测（mock mapper、不起 Spring）里这份缓存是空的。
 *
 * <p>而 {@code Update.set(SFunction, val)} 与 {@code eq(...)} 的解析时机不同——
 * <b>set 会立即解析列名</b>（列名在调用当刻就算出来），eq 等条件的列名是延迟到生成 SQL 时才解析。
 * 所以只有「lambda 更新语句」在纯单测里会抛
 * {@code can not find lambda cache for this entity}，查询条件则不会。
 * 用 {@link #init} 把用到的实体登记一遍即可，不必为此把整个 Spring 上下文拉起来。
 */
final class MpTableInfoTestSupport {

    private MpTableInfoTestSupport() {
    }

    static void init(Class<?>... entities) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : entities) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }
}
