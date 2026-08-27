package com.jaycong.know.engine.document.dto;


/**
 * 文档切分请求参数封装。
 *
 * @param splitType     切分类型
 * @param chunkSize     块大小
 * @param overlap       重叠大小
 * @param titleLevel    标题级别
 * @param separator     分隔符
 * @param regex         正则表达式
 * @author pyc
 * @since 2026-08-27 21:58
 */
public record DocumentSplitParam(
        String splitType,
        Integer chunkSize,
        Integer overlap,
        Integer titleLevel,
        String separator,
        String regex) {
}
