package cn.chyuan.ai.infrastructure.persistent.mapper;
import cn.chyuan.ai.infrastructure.dao.po.UserPO;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface UserMapper {
    UserPO selectByUsername(@Param("username") String username);
    UserPO selectById(@Param("id") Long id);
    void insert(UserPO user);
    void update(UserPO user);
    List<UserPO> selectList(@Param("offset") int offset, @Param("limit") int limit);
    int count();
}
