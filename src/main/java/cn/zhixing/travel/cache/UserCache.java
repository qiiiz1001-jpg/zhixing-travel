package cn.zhixing.travel.cache;

import cn.zhixing.travel.mapper.UserMapper;
import cn.zhixing.travel.model.dto.UserInfo;
import cn.zhixing.travel.utils.RedisKeys;
import cn.zhixing.travel.utils.RedisUtil;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** */
@Component
public class UserCache {

    @Autowired private UserMapper userMapper;

    /** map中获取个人信息 */
    public UserInfo getUserInfo(String uid) {
        String key = RedisKeys.getUserAuthToken(uid);
        return RedisUtil.get(key, UserInfo.class);
    }

    public void saveUserInfo(UserInfo userInfo) {
        String key = RedisKeys.getUserAuthToken(userInfo.getId());
        RedisUtil.set(key, userInfo, RedisKeys.USER_AUTH_TOKEN_TIMEOUT);
    }

    public void removeUserInfo(String userId) {
        String key = RedisKeys.getUserAuthToken(userId);
        RedisUtil.del(key);
    }
}
