package com.eams.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审批结果 MQ 消息体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 申请单 id */
    private Long applicationId;

    /** 申请单编号 */
    private String applicationNo;

    /** 资产 id */
    private Long assetId;

    /** 审批结果：2通过 3拒绝 4已完成（归还） */
    private Integer status;

    /** 审批人 id */
    private Long approverId;

    /** 审批时间 */
    private LocalDateTime approveTime;

    /** 拒绝原因 */
    private String rejectReason;

    /** 事件类型：audit / complete */
    private String eventType;
}
