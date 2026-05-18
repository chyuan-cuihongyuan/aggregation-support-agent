package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 告警持久化对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AlertPO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String alertId;
    private String severity;
    private String name;
    private String summary;
    private String host;
    private String status;
    private String source;
    private String metricsJson;
    private String labelsJson;
    private String description;
    private Date alertTime;
    private Date createTime;
    private Date updateTime;
}
