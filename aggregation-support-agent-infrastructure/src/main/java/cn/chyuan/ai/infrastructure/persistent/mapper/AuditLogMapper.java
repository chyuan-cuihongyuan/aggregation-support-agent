package cn.chyuan.ai.infrastructure.persistent.mapper;

import cn.chyuan.ai.domain.audit.model.valobj.AuditQueryVO;
import cn.chyuan.ai.domain.audit.model.valobj.AuditStatVO;
import cn.chyuan.ai.infrastructure.dao.po.AuditLogPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 审计日志 Mapper
 */
@Mapper
public interface AuditLogMapper {

    int insert(AuditLogPO record);

    List<AuditLogPO> selectByCondition(@Param("q") AuditQueryVO query);

    long countByCondition(@Param("q") AuditQueryVO query);

    List<AuditStatVO> statByAction(@Param("q") AuditQueryVO query);

    List<AuditStatVO> statByUser(@Param("q") AuditQueryVO query);
}
