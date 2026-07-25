package com.jx.tracker.risk.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("risk_object_exposure")
public class RiskObjectExposureEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String objectType;
    private String objectId;
    private String parentObjectType;
    private String parentObjectId;
    private String parentObjectName;
    private BigDecimal exposureWeight;
    private LocalDate validFrom;
    private LocalDate validTo;
    private LocalDateTime observedAt;
    private LocalDateTime availableAt;
    private String source;
    private String qualityStatus;
    private String metadataJson;
    private LocalDateTime createdAt;
}
