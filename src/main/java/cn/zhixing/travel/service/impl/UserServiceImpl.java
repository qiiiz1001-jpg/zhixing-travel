package cn.zhixing.travel.service.impl;

import cn.zhixing.travel.cache.UserCache;
import cn.zhixing.travel.enums.CommonError;
import cn.zhixing.travel.exception.BusinessException;
import cn.zhixing.travel.mapper.UserMapper;
import cn.zhixing.travel.model.User;
import cn.zhixing.travel.model.dto.LoginRequest;
import cn.zhixing.travel.model.dto.RegisterRequest;
import cn.zhixing.travel.model.dto.UserInfo;
import cn.zhixing.travel.service.UserService;
import cn.zhixing.travel.utils.AuthUtil;
import cn.zhixing.travel.utils.BCryptUtil;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Autowired private AuthUtil authUtil;

    @Autowired private UserCache userCache;

    @Override
    public void register(RegisterRequest req) {
        // 查找是否已经注册
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getEmail, req.getEmail());
        User one = this.getOne(queryWrapper);
        if (one != null) {
            throw new BusinessException(CommonError.USER_ALREADY_EXISTS.getCode(), "用户已存在");
        }

        // 注册
        User user =
                User.builder()
                        .id(UUID.randomUUID().toString())
                        .email(req.getEmail())
                        .username(req.getUsername())
                        .passwordHash(BCryptUtil.encode(req.getPassword())) // 密码加密
                        .build();

        this.save(user);
    }

    @Override
    public UserInfo login(LoginRequest req) {
        // 查找是否已经注册
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getEmail, req.getEmail());
        User one = this.getOne(queryWrapper);
        if (one == null) {
            throw new BusinessException(CommonError.USER_NOT_EXISTS.getCode(), "用户不存在");
        }

        if (!BCryptUtil.check(req.getPassword(), one.getPasswordHash())) {
            throw new BusinessException(CommonError.LOGIN_ERROR.getCode(), "密码错误");
        }

        // 生成登录凭证
        String token = authUtil.createToken(one.getId());
        // 缓存
        UserInfo userInfo =
                new UserInfo(
                        one.getId(),
                        one.getUsername(),
                        one.getEmail(),
                        one.getPreferences(),
                        token);
        userCache.saveUserInfo(userInfo);

        return userInfo;
    }

    @Override
    public void logout(String userId) {
        userCache.removeUserInfo(userId);
    }
}
