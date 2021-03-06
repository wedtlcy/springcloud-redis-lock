package com.redis.aspect;

import com.redis.annotation.RedisLock;
import com.redis.util.RedisLockKeyUtil;
import com.redis.constants.RedisConstants;
import com.redis.exception.RedisLockFailException;
import com.redis.lock.api.DistributeLock;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

@Component
@Aspect
public class RedisLockAspect {

    private static final Logger logger = LoggerFactory.getLogger(RedisLockAspect.class);

    private final RequestIDMap REQUEST_ID_MAP = new RequestIDMap();

    @Autowired
    private Environment environment;

    @Autowired
    private DistributeLock distributeLock;

    /**
     * 将ThreadLocal包装成一个对象方便使用
     */
    private class RequestIDMap {
        private ThreadLocal<Map<String, String>> innerThreadLocal = new ThreadLocal<>();

        private void setRequestID(String redisLockKey, String requestID) {
            Map<String, String> requestIDMap = innerThreadLocal.get();
            if (requestIDMap == null) {
                Map<String, String> newMap = new HashMap<>();
                newMap.put(redisLockKey, requestID);
                innerThreadLocal.set(newMap);
            } else {
                requestIDMap.put(redisLockKey, requestID);
            }
        }

        private String getRequestID(String redisLockKey) {
            Map<String, String> requestIDMap = innerThreadLocal.get();
            if (requestIDMap == null) {
                return null;
            } else {
                return requestIDMap.get(redisLockKey);
            }
        }

        private void removeRequestID(String redisLockKey) {
            Map<String, String> requestIDMap = innerThreadLocal.get();
            if (requestIDMap != null) {
                requestIDMap.remove(redisLockKey);
                // 如果requestIDMap为空，说明当前重入锁 最外层已经解锁
                if (requestIDMap.isEmpty()) {
                    // 清空threadLocal避免内存泄露
                    innerThreadLocal.remove();
                }
            }
        }
    }

    @Pointcut("@annotation(com.redis.annotation.RedisLock)")
    public void annotationPointcut() {
    }

    @Around("annotationPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method method = methodSignature.getMethod();
        RedisLock annotation = method.getAnnotation(RedisLock.class);

        // 方法执行前，先尝试加锁
        boolean lockSuccess = lock(annotation, joinPoint);
        // 如果加锁成功
        if (lockSuccess) {
            // 执行方法
            Object result = joinPoint.proceed();
            // 方法执行后，进行解锁
            unlock(annotation, joinPoint);
            return result;
        } else {
            throw new RedisLockFailException("redis分布式锁加锁失败，method= " + method.getName());
        }
    }

    /**
     * 加锁
     */
    private boolean lock(RedisLock annotation, ProceedingJoinPoint joinPoint) {
        int retryCount = annotation.retryCount();

        // 拼接redisLock的key
        String redisLockKey = getFinallyKeyLock(annotation, joinPoint);
        String requestID = REQUEST_ID_MAP.getRequestID(redisLockKey);
        if (requestID != null) {
            // 当前线程 已经存在requestID
            distributeLock.lockAndRetry(redisLockKey, requestID, annotation.expireTime(), retryCount);
            logger.info("重入加锁成功 redisLockKey= " + redisLockKey);

            return true;
        } else {
            // 当前线程 不存在requestID
            String newRequestID = distributeLock.lockAndRetry(redisLockKey, annotation.expireTime(), retryCount);

            if (newRequestID != null) {
                // 加锁成功，设置新的requestID
                REQUEST_ID_MAP.setRequestID(redisLockKey, newRequestID);
                logger.info("加锁成功 redisLockKey= " + redisLockKey);
                return true;
            } else {
                logger.info("加锁失败，超过重试次数，直接返回 retryCount= {}", retryCount);
                return false;
            }
        }
    }

    /**
     * 解锁
     */
    private void unlock(RedisLock annotation, ProceedingJoinPoint joinPoint) {
        // 拼接redisLock的key
        String redisLockKey = getFinallyKeyLock(annotation, joinPoint);
        String requestID = REQUEST_ID_MAP.getRequestID(redisLockKey);
        if (requestID != null) {
            // 解锁成功
            boolean unLockSuccess = distributeLock.unLock(redisLockKey, requestID);
            if (unLockSuccess) {
                // 移除 ThreadLocal中的数据，防止内存泄漏
                REQUEST_ID_MAP.removeRequestID(redisLockKey);
                logger.info("解锁成功 redisLockKey= " + redisLockKey);
            }
        } else {
            logger.info("解锁失败 redisLockKey= " + redisLockKey);
        }
    }

    /**
     * 拼接redisLock的key
     */
    private String getFinallyKeyLock(RedisLock annotation, ProceedingJoinPoint joinPoint) {
        String applicationName = environment.getProperty("spring.application.name");
        if (applicationName == null) {
            applicationName = "";
        }

        // applicationName在前
        String finallyKey = applicationName + RedisConstants.KEY_SEPARATOR + RedisLockKeyUtil.getFinallyLockKey(annotation, joinPoint);

        if (finallyKey.length() > RedisConstants.FINALLY_KEY_LIMIT) {
            throw new RuntimeException("finallyLockKey is too long finallyKey=" + finallyKey);
        } else {
            return finallyKey;
        }
    }
}
