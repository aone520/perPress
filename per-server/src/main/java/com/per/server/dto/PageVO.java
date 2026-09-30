package com.per.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用分页响应对象：records + total
 *
 * @param <T> 记录类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageVO<T> {

    /** 当前页数据 */
    private List<T> records;

    /** 总记录数 */
    private Long total;

    /**
     * 构造分页响应对象
     *
     * @param <T> 记录类型
     * @param records 当前页数据
     * @param total   总记录数
     * @return 分页响应对象
     */
    public static <T> PageVO<T> of(List<T> records, Long total) {
        return new PageVO<>(records, total);
    }
}
