package com.guide.common.util;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Getter;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分页工具类：IPage → 统一分页返回体，支持 entity → dto 泛型转换。
 *
 * <p>泛型只为**调用方能拿到带类型的 records**（如管理端在分页结果上就地补聚合字段）：
 * 之前的 {@code List<?>} 逼调用方写强制转换，转换写错了要到运行期才发现。
 */
@Getter
public class PageUtil<T> {

    private final long total;
    private final long pages;
    private final long current;
    private final long size;
    private final List<T> records;

    private PageUtil(long total, long pages, long current, long size, List<T> records) {
        this.total = total;
        this.pages = pages;
        this.current = current;
        this.size = size;
        this.records = records;
    }

    /** 直接包装 IPage（记录不转换） */
    public static <T> PageUtil<T> of(IPage<T> page) {
        return new PageUtil<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(),
                page.getRecords());
    }

    /** 包装 IPage 并转换记录类型（entity → dto） */
    public static <E, D> PageUtil<D> of(IPage<E> page, Function<E, D> converter) {
        List<D> records = page.getRecords().stream().map(converter).collect(Collectors.toList());
        return new PageUtil<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), records);
    }

    /** 构造 MyBatis-Plus 分页参数 */
    public static <T> Page<T> page(long current, long size) {
        return new Page<>(current, size);
    }
}
