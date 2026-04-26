package me.cortex.voxy.common.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Bit-manipulation utilities for integer compress/expand operations.
 * Placed in common so server-side code can use it without a client dependency.
 * The client package version (me.cortex.voxy.client.core.util.ExpansionUtil)
 * delegates here.
 */
public class ExpansionUtil {
    private static final MethodHandle INT_COMPRESS;
    private static final MethodHandle INT_EXPAND;
    private static final MethodHandle LONG_COMPRESS;
    private static final MethodHandle LONG_EXPAND;

    static {
        INT_COMPRESS = getMethodHandle(Integer.class, "compress", MethodType.methodType(int.class, int.class, int.class));
        INT_EXPAND = getMethodHandle(Integer.class, "expand", MethodType.methodType(int.class, int.class, int.class));
        LONG_COMPRESS = getMethodHandle(Long.class, "compress", MethodType.methodType(long.class, long.class, long.class));
        LONG_EXPAND = getMethodHandle(Long.class, "expand", MethodType.methodType(long.class, long.class, long.class));
    }

    private static MethodHandle getMethodHandle(Class<?> clazz, String name, MethodType methodType) {
        try {
            return MethodHandles.lookup().findStatic(clazz, name, methodType);
        } catch (Throwable ignored) {}
        return null;
    }

    private static int parallelSuffix(int maskCount) {
        int maskPrefix = maskCount ^ (maskCount << 1);
        maskPrefix = maskPrefix ^ (maskPrefix << 2);
        maskPrefix = maskPrefix ^ (maskPrefix << 4);
        maskPrefix = maskPrefix ^ (maskPrefix << 8);
        maskPrefix = maskPrefix ^ (maskPrefix << 16);
        return maskPrefix;
    }

    public static int expand(int i, int mask) {
        if (INT_EXPAND != null) {
            try {
                return (int) INT_EXPAND.invokeExact(i, mask);
            } catch (Throwable ignored) {}
        }
        int originalMask = mask;
        int maskCount = ~mask << 1;
        int maskPrefix = parallelSuffix(maskCount);
        int maskMove1 = maskPrefix & mask;
        mask = (mask ^ maskMove1) | (maskMove1 >>> (1 << 0));
        maskCount = maskCount & ~maskPrefix;
        maskPrefix = parallelSuffix(maskCount);
        int maskMove2 = maskPrefix & mask;
        mask = (mask ^ maskMove2) | (maskMove2 >>> (1 << 1));
        maskCount = maskCount & ~maskPrefix;
        maskPrefix = parallelSuffix(maskCount);
        int maskMove3 = maskPrefix & mask;
        mask = (mask ^ maskMove3) | (maskMove3 >>> (1 << 2));
        maskCount = maskCount & ~maskPrefix;
        maskPrefix = parallelSuffix(maskCount);
        int maskMove4 = maskPrefix & mask;
        mask = (mask ^ maskMove4) | (maskMove4 >>> (1 << 3));
        maskCount = maskCount & ~maskPrefix;
        maskPrefix = parallelSuffix(maskCount);
        int maskMove5 = maskPrefix & mask;
        int t = i << (1 << 4);
        i = (i & ~maskMove5) | (t & maskMove5);
        t = i << (1 << 3);
        i = (i & ~maskMove4) | (t & maskMove4);
        t = i << (1 << 2);
        i = (i & ~maskMove3) | (t & maskMove3);
        t = i << (1 << 1);
        i = (i & ~maskMove2) | (t & maskMove2);
        t = i << (1 << 0);
        i = (i & ~maskMove1) | (t & maskMove1);
        return i & originalMask;
    }

    public static int compress(int i, int mask) {
        if (INT_COMPRESS != null) {
            try {
                return (int) INT_COMPRESS.invokeExact(i, mask);
            } catch (Throwable ignored) {}
        }
        i = i & mask;
        int maskCount = ~mask << 1;
        for (int j = 0; j < 5; j++) {
            int maskPrefix = parallelSuffix(maskCount);
            int maskMove = maskPrefix & mask;
            mask = (mask ^ maskMove) | (maskMove >>> (1 << j));
            int t = i & maskMove;
            i = (i ^ t) | (t >>> (1 << j));
            maskCount = maskCount & ~maskPrefix;
        }
        return i;
    }

    private static long parallelSuffix(long maskCount) {
        long maskPrefix = maskCount ^ (maskCount << 1);
        maskPrefix = maskPrefix ^ (maskPrefix << 2);
        maskPrefix = maskPrefix ^ (maskPrefix << 4);
        maskPrefix = maskPrefix ^ (maskPrefix << 8);
        maskPrefix = maskPrefix ^ (maskPrefix << 16);
        maskPrefix = maskPrefix ^ (maskPrefix << 32);
        return maskPrefix;
    }

    public static long expand(long i, long mask) {
        if (LONG_EXPAND != null) {
            try {
                return (long) LONG_EXPAND.invokeExact(i, mask);
            } catch (Throwable ignored) {}
        }
        long originalMask = mask;
        long maskCount = ~mask << 1;
        long maskPrefix = parallelSuffix(maskCount);
        long maskMove1 = maskPrefix & mask;
        mask = (mask ^ maskMove1) | (maskMove1 >>> (1 << 0));
        maskCount = maskCount & ~maskPrefix;
        maskPrefix = parallelSuffix(maskCount);
        long maskMove2 = maskPrefix & mask;
        mask = (mask ^ maskMove2) | (maskMove2 >>> (1 << 1));
        maskCount = maskCount & ~maskPrefix;
        maskPrefix = parallelSuffix(maskCount);
        long maskMove3 = maskPrefix & mask;
        mask = (mask ^ maskMove3) | (maskMove3 >>> (1 << 2));
        maskCount = maskCount & ~maskPrefix;
        maskPrefix = parallelSuffix(maskCount);
        long maskMove4 = maskPrefix & mask;
        mask = (mask ^ maskMove4) | (maskMove4 >>> (1 << 3));
        maskCount = maskCount & ~maskPrefix;
        maskPrefix = parallelSuffix(maskCount);
        long maskMove5 = maskPrefix & mask;
        mask = (mask ^ maskMove5) | (maskMove5 >>> (1 << 4));
        maskCount = maskCount & ~maskPrefix;
        maskPrefix = parallelSuffix(maskCount);
        long maskMove6 = maskPrefix & mask;
        long t = i << (1 << 5);
        i = (i & ~maskMove6) | (t & maskMove6);
        t = i << (1 << 4);
        i = (i & ~maskMove5) | (t & maskMove5);
        t = i << (1 << 3);
        i = (i & ~maskMove4) | (t & maskMove4);
        t = i << (1 << 2);
        i = (i & ~maskMove3) | (t & maskMove3);
        t = i << (1 << 1);
        i = (i & ~maskMove2) | (t & maskMove2);
        t = i << (1 << 0);
        i = (i & ~maskMove1) | (t & maskMove1);
        return i & originalMask;
    }

    public static long compress(long i, long mask) {
        if (LONG_COMPRESS != null) {
            try {
                return (long) LONG_COMPRESS.invokeExact(i, mask);
            } catch (Throwable ignored) {}
        }
        i = i & mask;
        long maskCount = ~mask << 1;
        for (int j = 0; j < 6; j++) {
            long maskPrefix = parallelSuffix(maskCount);
            long maskMove = maskPrefix & mask;
            mask = (mask ^ maskMove) | (maskMove >>> (1 << j));
            long t = i & maskMove;
            i = (i ^ t) | (t >>> (1 << j));
            maskCount = maskCount & ~maskPrefix;
        }
        return i;
    }

    public static boolean isJava21() {
        return INT_COMPRESS != null;
    }
}
