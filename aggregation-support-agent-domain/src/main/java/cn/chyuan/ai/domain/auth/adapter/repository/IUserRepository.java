package cn.chyuan.ai.domain.auth.adapter.repository;

import cn.chyuan.ai.domain.auth.model.entity.UserEntity;

import java.util.List;

/**
 * 用户仓储接口
 */
public interface IUserRepository {

    /** 根据用户名查询 */
    UserEntity queryByUsername(String username);

    /** 根据 ID 查询 */
    UserEntity queryById(Long id);

    /** 保存用户 */
    void save(UserEntity entity);

    /** 查询用户列表（分页） */
    List<UserEntity> queryList(int page, int pageSize);

    /** 查询用户总数 */
    int countAll();

    /** 更新用户状态 */
    void updateStatus(Long id, Integer status);

    /** 更新用户角色 */
    void updateRole(Long id, String role);

    /** 更新用户信息 */
    void updateUser(UserEntity entity);
}
