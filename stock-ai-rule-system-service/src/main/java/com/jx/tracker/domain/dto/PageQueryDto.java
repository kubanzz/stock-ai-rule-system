package com.jx.tracker.domain.dto;

import lombok.Data;

@Data
public class PageQueryDto {

    private Integer pageNum = 1;

    private Integer pageSize = 20;
}
