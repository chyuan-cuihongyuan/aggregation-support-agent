package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.auth.adapter.repository.IUserRepository;
import cn.chyuan.ai.domain.auth.model.entity.UserEntity;
import cn.chyuan.ai.infrastructure.dao.po.UserPO;
import cn.chyuan.ai.infrastructure.persistent.mapper.UserMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户仓储实现
 */
@Repository
public class UserRepository implements IUserRepository {

    @Resource
    private UserMapper userMapper;

    @Override
    public UserEntity queryByUsername(String username) {
        UserPO po = userMapper.queryByUsername(username);
        return po != null ? toEntity(po) : null;
    }

    @Override
    public UserEntity queryById(Long id) {
        UserPO po = userMapper.queryById(id);
        return po != null ? toEntity(po) : null;
    }

    @Override
    public void save(UserEntity entity) {
        java.util.Date now = new java.util.Date();
        UserPO po = UserPO.builder()
                .username(entity.getUsername())
                .password(entity.getPassword())
                .phone(entity.getPhone() != null ? entity.getPhone() : "")
                .email(entity.getEmail() != null ? entity.getEmail() : "")
                .nickname(entity.getNickname() != null ? entity.getNickname() : "")
                .avatar("")
                .role(entity.getRole() != null ? entity.getRole() : "user")
                .status(1)
                .createTime(now)
                .updateTime(now)
                .build();
        userMapper.insert(po);
    }

    @Override
    public List<UserEntity> queryList(int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<UserPO> poList = userMapper.queryList(offset, pageSize);
        return poList.stream().map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    public int countAll() {
        return userMapper.countAll();
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        userMapper.updateStatus(id, status);
    }

    @Override
    public void updateRole(Long id, String role) {
        userMapper.updateRole(id, role);
    }

    private UserEntity toEntity(UserPO po) {
        return UserEntity.builder()
                .id(po.getId())
                .username(po.getUsername())
                .password(po.getPassword())
                .phone(po.getPhone())
                .email(po.getEmail())
                .nickname(po.getNickname())
                .avatar(po.getAvatar())
                .role(po.getRole())
                .status(po.getStatus())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
