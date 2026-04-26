package me.cortex.voxy.common.util;

public class ThreadUtils {
    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    public static boolean isWindows = OS.contains("win");
    public static boolean isLinux = OS.contains("linux") || OS.contains("nix") || OS.contains("nux");
}
