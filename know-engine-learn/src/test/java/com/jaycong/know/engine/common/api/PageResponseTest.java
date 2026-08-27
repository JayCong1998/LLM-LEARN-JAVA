package com.jaycong.know.engine.common.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageResponseTest {

    @Test
    void calculatesTotalPagesFromTotalAndPageSize() {
        PageResponse<String> pageResponse = PageResponse.of(List.of("文档一"), 2, 10, 11);

        assertEquals(List.of("文档一"), pageResponse.getRecords());
        assertEquals(2, pageResponse.getPageNumber());
        assertEquals(10, pageResponse.getPageSize());
        assertEquals(11, pageResponse.getTotal());
        assertEquals(2, pageResponse.getTotalPages());
    }

    @Test
    void returnsZeroTotalPagesForEmptyResult() {
        PageResponse<String> pageResponse = PageResponse.of(List.of(), 1, 20, 0);

        assertEquals(0, pageResponse.getTotalPages());
    }
}
