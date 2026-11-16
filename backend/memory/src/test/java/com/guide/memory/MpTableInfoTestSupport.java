package com.guide.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 纯单测里的 MyBatis-Plus 表信息初始化（memory 模块自用）。
 *
 * <p>auth 模块有一份同名工具，但它是**包私有**且测试类不进 test-jar，跨模块取不到；
 * 这里按同一口径复制一份，只为本模块的 {@code LambdaUpdateWrapper.set} 用例服务
 * （set 会立即解析列名，纯单测里没有 Spring 建立的 TableInfo 缓存会抛
 * {@code can not find lambda cache}）。
 */
public final class MpTableInfoTestSupport {

    private MpTableInfoTestSupport() {
    }

    public static void init(Class<?>... entities) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : entities) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }
}
