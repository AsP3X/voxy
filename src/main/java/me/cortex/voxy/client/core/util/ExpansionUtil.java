package me.cortex.voxy.client.core.util;

/** Delegates to the common implementation. */
public class ExpansionUtil {
    public static int expand(int i, int mask) {
        return me.cortex.voxy.common.util.ExpansionUtil.expand(i, mask);
    }
    public static int compress(int i, int mask) {
        return me.cortex.voxy.common.util.ExpansionUtil.compress(i, mask);
    }
    public static long expand(long i, long mask) {
        return me.cortex.voxy.common.util.ExpansionUtil.expand(i, mask);
    }
    public static long compress(long i, long mask) {
        return me.cortex.voxy.common.util.ExpansionUtil.compress(i, mask);
    }
    public static boolean isJava21() {
        return me.cortex.voxy.common.util.ExpansionUtil.isJava21();
    }
}
