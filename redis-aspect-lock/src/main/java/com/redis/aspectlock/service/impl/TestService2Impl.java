package com.redis.aspectlock.service.impl;

import com.redis.aspectlock.annotation.RedisLock;
import com.redis.aspectlock.annotation.RedisLockKey;
import com.redis.aspectlock.enums.RedisLockKeyType;
import com.redis.aspectlock.service.api.TestService2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TestService2Impl implements TestService2 {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestService2Impl.class);

    @Override
    @RedisLock(lockKey = "lockKey", expireTime = 100, retryCount = 3)
    public String method2(@RedisLockKey(type = RedisLockKeyType.ALL) String num) throws InterruptedException {
        int sleepMS = 1000;
        Thread.sleep(sleepMS);
        LOGGER.info("method2 ... 休眠{}ms num={}",sleepMS,num);
        return "method2";
    }
}
