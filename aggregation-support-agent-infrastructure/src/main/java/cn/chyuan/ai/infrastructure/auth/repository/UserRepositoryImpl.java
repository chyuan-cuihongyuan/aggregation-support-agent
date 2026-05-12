package cn.chyuan.ai.infrastructure.auth.repository;
import cn.chyuan.ai.domain.auth.adapter.repository.IUserRepository;
import cn.chyuan.ai.domain.auth.model.entity.UserEntity;
import cn.chyuan.ai.infrastructure.dao.po.UserPO;
import cn.chyuan.ai.infrastructure.persistent.mapper.UserMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class UserRepositoryImpl implements IUserRepository {

    private final UserMapper userMapper;

    public UserRepositoryImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserEntity queryUserByUsername(String username) {
        UserPO po = userMapper.selectByUsername(username);
        return po != null ? toEntity(po) : null;
    }

    @Override
    public UserEntity queryUserById(Long id) {
        UserPO po = userMapper.selectById(id);
        return po != null ? toEntity(po) : null;
    }

    @Override
    public void insertUser(UserEntity user) {
        userMapper.insert(toPO(user));
    }

    @Override
    public void updateUser(UserEntity user) {
        userMapper.update(toPO(user));
    }

    @Override
    public List<UserEntity> queryUserList(int offset, int limit) {
        return userMapper.selectList(offset, limit).stream()
                .map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    public int countUsers() {
        return userMapper.count();
    }

    private UserEntity toEntity(UserPO po) {
        UserEntity e = new UserEntity();
        e.setId(po.getId());
        e.setUsername(po.getUsername());
        e.setPasswordHash(po.getPasswordHash());
        e.setNickname(po.getNickname());
        e.setEmail(po.getEmail());
        e.setAvatar(po.getAvatar());
        e.setRole(po.getRole());
        e.setStatus(po.getStatus());
        e.setCreateTime(po.getCreateTime());
        e.setUpdateTime(po.getUpdateTime());
        return e;
    }

    private UserPO toPO(UserEntity e) {
        UserPO po = new UserPO();
        po.setId(e.getId());
        po.setUsername(e.getUsername());
        po.setPasswordHash(e.getPasswordHash());
        po.setNickname(e.getNickname());
        po.setEmail(e.getEmail());
        po.setAvatar(e.getAvatar());
        po.setRole(e.getRole());
        po.setStatus(e.getStatus());
        return po;
    }
}
