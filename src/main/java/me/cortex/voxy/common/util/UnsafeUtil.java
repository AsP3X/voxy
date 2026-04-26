package me.cortex.voxy.common.util;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class UnsafeUtil {
    private static final Unsafe UNSAFE;
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe)field.get(null);
        } catch (Exception e) {throw new RuntimeException(e);}
    }

    private static final long BYTE_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
    private static final long SHORT_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(short[].class);
    private static final long LONG_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(long[].class);

    private static final long BUFFER_ADDRESS_OFFSET;
    private static final long BUFFER_CAPACITY_OFFSET;
    static {
        try {
            BUFFER_ADDRESS_OFFSET = UNSAFE.objectFieldOffset(Buffer.class.getDeclaredField("address"));
            BUFFER_CAPACITY_OFFSET = UNSAFE.objectFieldOffset(Buffer.class.getDeclaredField("capacity"));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static long allocateMemory(long size) {
        return UNSAFE.allocateMemory(size);
    }

    public static void freeMemory(long address) {
        UNSAFE.freeMemory(address);
    }

    public static void memSet(long address, long bytes, byte value) {
        UNSAFE.setMemory(address, bytes, value);
    }

    /**
     * Returns a {@link ByteBuffer} view over the given native address.
     * <p>
     * Allocates a 1-byte direct buffer to obtain a properly constructed
     * {@code DirectByteBuffer} instance, immediately invokes its cleaner
     * (freeing that 1 byte and detaching the GC-time deallocator), then
     * rewires the buffer's address and capacity fields via Unsafe so that
     * the returned buffer views our native memory without any JVM-version-
     * specific constructor look-up.
     */
    public static ByteBuffer memByteBuffer(long address, int capacity) {
        ByteBuffer buf = ByteBuffer.allocateDirect(1);
        // Free the 1-byte allocation and detach the cleaner so that
        // GC of 'buf' will not try to free 'address'.
        UNSAFE.invokeCleaner(buf);
        UNSAFE.putLong(buf, BUFFER_ADDRESS_OFFSET, address);
        UNSAFE.putInt(buf, BUFFER_CAPACITY_OFFSET, capacity);
        // clear() sets position=0, limit=capacity using the new capacity
        return ((ByteBuffer) buf.clear()).order(ByteOrder.nativeOrder());
    }

    public static long memAddress(ByteBuffer buffer) {
        return UNSAFE.getLong(buffer, BUFFER_ADDRESS_OFFSET);
    }

    public static void memcpy(long src, long dst, long length) {
        UNSAFE.copyMemory(src, dst, length);
    }



    //Copy the entire length of src to the dst memory where dst is a byte array (source length from dst)
    public static void memcpy(long src, byte[] dst) {
        UNSAFE.copyMemory(null, src, dst, BYTE_ARRAY_BASE_OFFSET, dst.length);
    }

    public static void memcpy(long src, int length, byte[] dst) {
        UNSAFE.copyMemory(null, src, dst, BYTE_ARRAY_BASE_OFFSET, length);
    }

    public static void memcpy(long src, int length, byte[] dst, int offset) {
        UNSAFE.copyMemory(null, src, dst, BYTE_ARRAY_BASE_OFFSET+offset, length);
    }

    //Copy the entire length of src to the dst memory where src is a byte array (source length from src)
    public static void memcpy(byte[] src, long dst) {
        UNSAFE.copyMemory(src, BYTE_ARRAY_BASE_OFFSET, null, dst, src.length);
    }

    public static void memcpy(byte[] src, int len, long dst) {
        UNSAFE.copyMemory(src, BYTE_ARRAY_BASE_OFFSET, null, dst, len);
    }
    public static void memcpy(short[] src, long dst) {
        UNSAFE.copyMemory(src, SHORT_ARRAY_BASE_OFFSET, null, dst, (long) src.length <<1);
    }
    public static void memcpy(long[] src, long dst) {
        UNSAFE.copyMemory(src, LONG_ARRAY_BASE_OFFSET, null, dst, (long) src.length <<3);
    }


    //Cause lwjgl is being a clown with var handles, we need to do things ourselves


    public static boolean memGetBoolean(long ptr) { return UNSAFE.getByte(null, ptr) != 0; }
    public static byte memGetByte(long ptr)       { return UNSAFE.getByte(null, ptr); }
    public static short memGetShort(long ptr)     { return UNSAFE.getShort(null, ptr); }
    public static int memGetInt(long ptr)         { return UNSAFE.getInt(null, ptr); }
    public static long memGetLong(long ptr)       { return UNSAFE.getLong(null, ptr); }
    public static float memGetFloat(long ptr)     { return UNSAFE.getFloat(null, ptr); }
    public static double memGetDouble(long ptr)   { return UNSAFE.getDouble(null, ptr); }

    public static void memPutByte(long ptr, byte value)     { UNSAFE.putByte(null, ptr, value); }
    public static void memPutShort(long ptr, short value)   { UNSAFE.putShort(null, ptr, value); }
    public static void memPutInt(long ptr, int value)       { UNSAFE.putInt(null, ptr, value); }
    public static void memPutLong(long ptr, long value)     { UNSAFE.putLong(null, ptr, value); }
    public static void memPutFloat(long ptr, float value)   { UNSAFE.putFloat(null, ptr, value); }
    public static void memPutDouble(long ptr, double value) { UNSAFE.putDouble(null, ptr, value); }

}
