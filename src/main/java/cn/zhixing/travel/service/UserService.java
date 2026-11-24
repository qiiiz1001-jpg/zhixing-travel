package cn.zhixing.travel.service;

import cn.zhixing.travel.model.dto.LoginRequest;
import cn.zhixing.travel.model.dto.RegisterRequest;
import cn.zhixing.travel.model.dto.UserInfo;

/** */
public interface UserService {
    void register(RegisterRequest req);

    UserInfo login(LoginRequest req);

    void logout(String userId);
}
