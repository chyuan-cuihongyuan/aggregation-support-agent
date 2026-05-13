package cn.chyuan.ai.infrastructure.persistent.mapper;

import cn.chyuan.ai.infrastructure.dao.po.UserPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户 MyBatis Mapper
 */
@Mapper
public interface UserMapper {

    void insert(UserPO record);

    UserPO queryByUsername(@Param("username") String username);

    UserPO queryById(@Param("id") Long id);

    List<UserPO> queryList(@Param("offset") int offset, @Param("pageSize") int pageSize);

    int countAll();

    void updateStatus(@Param("id") Long id, @Param("status") Integer status);

    void updateRole(@Param("id") Long id, @Param("role") String role);
}
