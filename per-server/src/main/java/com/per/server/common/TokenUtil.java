package com.per.server.common;

import java.security.SecureRandom;

/**
 * 随机 Token 生成工具：基于 SecureRandom 生成十六进制随机串，
 * 用于节点注册 token 等场景
 */
public final class TokenUtil {

    /** 十六进制字符表 */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 安全随机数生成器 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 工具类禁止实例化 */
    private TokenUtil() {
    }

    /**
     * 生成指定长度的随机十六进制字符串
     *
     * @param length 目标字符长度（偶数，如 32）
     * @return 随机十六进制字符串
     */
    public static String randomHex(int length) {
        byte[] bytes = new byte[length / 2];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(length);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }
}
