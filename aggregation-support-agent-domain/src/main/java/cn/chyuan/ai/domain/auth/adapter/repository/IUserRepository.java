package cn.chyuan.ai.domain.auth.adapter.repository;
import cn.chyuan.ai.domain.auth.model.entity.UserEntity;
import java.util.List;

public interface IUserRepository {
    UserEntity queryUserByUsername(String username);
    UserEntity queryUserById(Long id);
    void insertUser(UserEntity user);
    void updateUser(UserEntity user);
    List<UserEntity> queryUserList(int offset, int limit);
    int countUsers();
}
