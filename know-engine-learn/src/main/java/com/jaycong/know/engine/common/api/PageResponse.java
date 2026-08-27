package com.jaycong.know.engine.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * 分页查询的统一数据结构。
 *
 * @param <T> 分页记录类型
 */
public class PageResponse<T> {
    /**
     * 当前页记录。
     */
    private final List<T> records;
    /**
     * 当前页码，从 1 开始。
     */
    private final long pageNumber;
    /**
     * 每页记录数。
     */
    private final long pageSize;
    /**
     * 满足查询条件的总记录数。
     */
    private final long total;
    /**
     * 总页数。
     */
    private final long totalPages;

    /**
     * 创建分页响应对象。
     *
     * @param records    当前页记录
     * @param pageNumber 当前页码
     * @param pageSize   每页记录数
     * @param total      总记录数
     * @param totalPages 总页数
     */
    private PageResponse(List<T> records, long pageNumber, long pageSize, long total, long totalPages) {
        this.records = records;
        this.pageNumber = pageNumber;
        this.pageSize = pageSize;
        this.total = total;
        this.totalPages = totalPages;
    }

    /**
     * 根据分页参数创建统一分页响应。
     *
     * @param records    当前页记录
     * @param pageNumber 当前页码
     * @param pageSize   每页记录数
     * @param total      总记录数
     * @param <T>        分页记录类型
     * @return 统一分页响应
     */
    public static <T> PageResponse<T> of(List<T> records, long pageNumber, long pageSize, long total) {
        long totalPages = pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new PageResponse<>(records, pageNumber, pageSize, total, totalPages);
    }

    /**
     * 将 MyBatis-Plus 分页结果转换为统一分页响应。
     *
     * @param page MyBatis-Plus 分页结果
     * @param <T>  分页记录类型
     * @return 统一分页响应
     */
    public static <T> PageResponse<T> from(IPage<T> page) {
        return of(page.getRecords(), page.getCurrent(), page.getSize(), page.getTotal());
    }

    /**
     * 获取当前页记录。
     *
     * @return 当前页记录
     */
    public List<T> getRecords() {
        return records;
    }

    /**
     * 获取当前页码。
     *
     * @return 当前页码
     */
    public long getPageNumber() {
        return pageNumber;
    }

    /**
     * 获取每页记录数。
     *
     * @return 每页记录数
     */
    public long getPageSize() {
        return pageSize;
    }

    /**
     * 获取总记录数。
     *
     * @return 总记录数
     */
    public long getTotal() {
        return total;
    }

    /**
     * 获取总页数。
     *
     * @return 总页数
     */
    public long getTotalPages() {
        return totalPages;
    }
}
