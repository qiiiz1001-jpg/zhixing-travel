package cn.zhixing.travel;

import cn.zhixing.travel.utils.BCryptUtil;

import jakarta.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

@SpringBootTest
class ZhiXingTravelApplicationTest {

    @Resource private BCryptUtil bCryptUtil;

    public static void main(String[] args) {
        System.out.println(UUID.randomUUID());
    }

    @Test
    public void testBCryptUtil() {
        System.out.println(bCryptUtil.encode("123456"));
    }
}
