package org.robolectric.shadows;

import static android.os.Build.VERSION_CODES.JELLY_BEAN_MR1;
import static android.os.Build.VERSION_CODES.JELLY_BEAN_MR2;
import static android.os.Build.VERSION_CODES.KITKAT_WATCH;
import static android.os.Build.VERSION_CODES.LOLLIPOP;
import static android.os.Build.VERSION_CODES.M;
import static android.os.Build.VERSION_CODES.O_MR1;
import static android.os.Build.VERSION_CODES.P;
import static org.robolectric.RuntimeEnvironment.castNativePtr;

import android.os.BadParcelableException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.util.Log;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.robolectric.annotation.HiddenApi;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.util.ReflectionHelpers;

@Implements(Parcel.class)
@SuppressWarnings("unchecked")
public class ShadowParcel {
  private static final String TAG = "Parcel";

  @RealObject private Parcel realObject;
  private static final Map<Long, ByteBuffer> NATIVE_PTR_TO_PARCEL = new LinkedHashMap<>();
  private static long nextNativePtr = 1; // this needs to start above 0, which is a magic number to Parcel

  @Implementation(maxSdk = JELLY_BEAN_MR1)
  @SuppressWarnings("TypeParameterUnusedInFormals")
  protected <T extends Parcelable> T readParcelable(ClassLoader loader) {
    // prior to JB MR2, readParcelableCreator() is inlined here.
    Parcelable.Creator<?> creator = readParcelableCreator(loader);
    if (creator == null) {
      return null;
    }

    if (creator instanceof Parcelable.ClassLoaderCreator<?>) {
      Parcelable.ClassLoaderCreator<?> classLoaderCreator =
          (Parcelable.ClassLoaderCreator<?>) creator;
      return (T) classLoaderCreator.createFromParcel(realObject, loader);
    }
    return (T) creator.createFromParcel(realObject);
  }

  @HiddenApi
  @Implementation(minSdk = JELLY_BEAN_MR2)
  public Parcelable.Creator<?> readParcelableCreator(ClassLoader loader) {
    //note: calling `readString` will also consume the string, and increment the data-pointer.
    //which is exactly what we need, since we do not call the real `readParcelableCreator`.
    final String name = realObject.readString();
    if (name == null) {
      return null;
    }

    Parcelable.Creator<?> creator;
    try {
      // If loader == null, explicitly emulate Class.forName(String) "caller
      // classloader" behavior.
      ClassLoader parcelableClassLoader =
          (loader == null ? getClass().getClassLoader() : loader);
      // Avoid initializing the Parcelable class until we know it implements
      // Parcelable and has the necessary CREATOR field. http://b/1171613.
      Class<?> parcelableClass = Class.forName(name, false /* initialize */,
          parcelableClassLoader);
      if (!Parcelable.class.isAssignableFrom(parcelableClass)) {
        throw new BadParcelableException("Parcelable protocol requires that the "
            + "class implements Parcelable");
      }
      Field f = parcelableClass.getField("CREATOR");

      // this is a fix for JDK8<->Android VM incompatibility:
      // Apparently, JDK will not allow access to a public field if its
      // class is not visible (private or package-private) from the call-site.
      f.setAccessible(true);

      if ((f.getModifiers() & Modifier.STATIC) == 0) {
        throw new BadParcelableException("Parcelable protocol requires "
            + "the CREATOR object to be static on class " + name);
      }
      Class<?> creatorType = f.getType();
      if (!Parcelable.Creator.class.isAssignableFrom(creatorType)) {
        // Fail before calling Field.get(), not after, to avoid initializing
        // parcelableClass unnecessarily.
        throw new BadParcelableException("Parcelable protocol requires a "
            + "Parcelable.Creator object called "
            + "CREATOR on class " + name);
      }
      creator = (Parcelable.Creator<?>) f.get(null);
    } catch (IllegalAccessException e) {
      Log.e(TAG, "Illegal access when unmarshalling: " + name, e);
      throw new BadParcelableException(
          "IllegalAccessException when unmarshalling: " + name);
    } catch (ClassNotFoundException e) {
      Log.e(TAG, "Class not found when unmarshalling: " + name, e);
      throw new BadParcelableException(
          "ClassNotFoundException when unmarshalling: " + name);
    } catch (NoSuchFieldException e) {
      throw new BadParcelableException("Parcelable protocol requires a "
          + "Parcelable.Creator object called "
          + "CREATOR on class " + name);
    }
    if (creator == null) {
      throw new BadParcelableException("Parcelable protocol requires a "
          + "non-null Parcelable.Creator object called "
          + "CREATOR on class " + name);
    }
    return creator;
  }

  @Implementation
  protected void writeByteArray(byte[] b, int offset, int len) {
    if (b == null) {
      realObject.writeInt(-1);
      return;
    }
    Number nativePtr = ReflectionHelpers.getField(realObject, "mNativePtr");
    nativeWriteByteArray(nativePtr.longValue(), b, offset, len);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static int nativeDataSize(int nativePtr) {
    return nativeDataSize((long) nativePtr);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static int nativeDataSize(long nativePtr) {
    return NATIVE_PTR_TO_PARCEL.get(nativePtr).dataSize();
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static int nativeDataAvail(int nativePtr) {
    return nativeDataAvail((long) nativePtr);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static int nativeDataAvail(long nativePtr) {
    return NATIVE_PTR_TO_PARCEL.get(nativePtr).dataAvailable();
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static int nativeDataPosition(int nativePtr) {
    return nativeDataPosition((long) nativePtr);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static int nativeDataPosition(long nativePtr) {
    return NATIVE_PTR_TO_PARCEL.get(nativePtr).dataPosition();
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static int nativeDataCapacity(int nativePtr) {
    return nativeDataCapacity((long) nativePtr);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static int nativeDataCapacity(long nativePtr) {
    return NATIVE_PTR_TO_PARCEL.get(nativePtr).dataCapacity();
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static void nativeSetDataSize(int nativePtr, int size) {
    nativeSetDataSize((long) nativePtr, size);
  }

  @Implementation(minSdk = LOLLIPOP)
  @SuppressWarnings("robolectric.ShadowReturnTypeMismatch")
  protected static void nativeSetDataSize(long nativePtr, int size) {
    NATIVE_PTR_TO_PARCEL.get(nativePtr).setDataSize(size);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static void nativeSetDataPosition(int nativePtr, int pos) {
    nativeSetDataPosition((long) nativePtr, pos);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static void nativeSetDataPosition(long nativePtr, int pos) {
    NATIVE_PTR_TO_PARCEL.get(nativePtr).setDataPosition(pos);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static void nativeSetDataCapacity(int nativePtr, int size) {
    nativeSetDataCapacity((long) nativePtr, size);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static void nativeSetDataCapacity(long nativePtr, int size) {
    NATIVE_PTR_TO_PARCEL.get(nativePtr).setDataCapacityAtLeast(size);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static void nativeWriteByteArray(int nativePtr, byte[] b, int offset, int len) {
    nativeWriteByteArray((long) nativePtr, b, offset, len);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static void nativeWriteByteArray(long nativePtr, byte[] b, int offset, int len) {
    NATIVE_PTR_TO_PARCEL.get(nativePtr).writeByteArray(b, offset, len);
  }

  // duplicate the writeBlob implementation from latest android, to avoid referencing the
  // non-existent-in-JDK java.util.Arrays.checkOffsetAndCount method.
  @Implementation(minSdk = M)
  protected void writeBlob(byte[] b, int offset, int len) {
    if (b == null) {
      realObject.writeInt(-1);
      return;
    }
    throwsIfOutOfBounds(b.length, offset, len);
    long nativePtr = ReflectionHelpers.getField(realObject, "mNativePtr");
    nativeWriteBlob(nativePtr, b, offset, len);
  }

  private static void throwsIfOutOfBounds(int len, int offset, int count) {
    if (len < 0) {
      throw new ArrayIndexOutOfBoundsException("Negative length: " + len);
    }

    if ((offset | count) < 0 || offset > len - count) {
      throw new ArrayIndexOutOfBoundsException();
    }
  }

  // nativeWriteBlob was introduced in lollipop, thus no need for a int nativePtr variant
  @Implementation(minSdk = LOLLIPOP)
  protected static void nativeWriteBlob(long nativePtr, byte[] b, int offset, int len) {
    nativeWriteByteArray(nativePtr, b, offset, len);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static void nativeWriteInt(int nativePtr, int val) {
    nativeWriteInt((long) nativePtr, val);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static void nativeWriteInt(long nativePtr, int val) {
    NATIVE_PTR_TO_PARCEL.get(nativePtr).writeInt(val);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static void nativeWriteLong(int nativePtr, long val) {
    nativeWriteLong((long) nativePtr, val);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static void nativeWriteLong(long nativePtr, long val) {
    NATIVE_PTR_TO_PARCEL.get(nativePtr).writeLong(val);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static void nativeWriteFloat(int nativePtr, float val) {
    nativeWriteFloat((long) nativePtr, val);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static void nativeWriteFloat(long nativePtr, float val) {
    NATIVE_PTR_TO_PARCEL.get(nativePtr).writeFloat(val);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static void nativeWriteDouble(int nativePtr, double val) {
    nativeWriteDouble((long) nativePtr, val);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static void nativeWriteDouble(long nativePtr, double val) {
    NATIVE_PTR_TO_PARCEL.get(nativePtr).writeDouble(val);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static void nativeWriteString(int nativePtr, String val) {
    nativeWriteString((long) nativePtr, val);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static void nativeWriteString(long nativePtr, String val) {
    NATIVE_PTR_TO_PARCEL.get(nativePtr).writeString(val);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  protected static void nativeWriteStrongBinder(int nativePtr, IBinder val) {
    nativeWriteStrongBinder((long) nativePtr, val);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static void nativeWriteStrongBinder(long nativePtr, IBinder val) {
    NATIVE_PTR_TO_PARCEL.get(nativePtr).writeStrongBinder(val);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static byte[] nativeCreateByteArray(int nativePtr) {
    return nativeCreateByteArray((long) nativePtr);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static byte[] nativeCreateByteArray(long nativePtr) {
    return NATIVE_PTR_TO_PARCEL.get(nativePtr).createByteArray();
  }

  // nativeReadBlob was introduced in lollipop, thus no need for a int nativePtr variant
  @Implementation(minSdk = LOLLIPOP)
  protected static byte[] nativeReadBlob(long nativePtr) {
    return nativeCreateByteArray(nativePtr);
  }

  @Implementation(minSdk = O_MR1)
  protected static boolean nativeReadByteArray(long nativePtr, byte[] dest, int destLen) {
    return NATIVE_PTR_TO_PARCEL.get(nativePtr).readByteArray(dest, destLen);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static int nativeReadInt(int nativePtr) {
    return nativeReadInt((long) nativePtr);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static int nativeReadInt(long nativePtr) {
    return NATIVE_PTR_TO_PARCEL.get(nativePtr).readInt();
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static long nativeReadLong(int nativePtr) {
    return nativeReadLong((long) nativePtr);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static long nativeReadLong(long nativePtr) {
    return NATIVE_PTR_TO_PARCEL.get(nativePtr).readLong();
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static float nativeReadFloat(int nativePtr) {
    return nativeReadFloat((long) nativePtr);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static float nativeReadFloat(long nativePtr) {
    return NATIVE_PTR_TO_PARCEL.get(nativePtr).readFloat();
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static double nativeReadDouble(int nativePtr) {
    return nativeReadDouble((long) nativePtr);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static double nativeReadDouble(long nativePtr) {
    return NATIVE_PTR_TO_PARCEL.get(nativePtr).readDouble();
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static String nativeReadString(int nativePtr) {
    return nativeReadString((long) nativePtr);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static String nativeReadString(long nativePtr) {
    return NATIVE_PTR_TO_PARCEL.get(nativePtr).readString();
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  protected static IBinder nativeReadStrongBinder(int nativePtr) {
    return nativeReadStrongBinder((long) nativePtr);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static IBinder nativeReadStrongBinder(long nativePtr) {
    return NATIVE_PTR_TO_PARCEL.get(nativePtr).readStrongBinder();
  }

  @Implementation @HiddenApi
  synchronized public static Number nativeCreate() {
    long nativePtr = nextNativePtr++;
    NATIVE_PTR_TO_PARCEL.put(nativePtr, new ByteBuffer());
    return castNativePtr(nativePtr);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static void nativeFreeBuffer(int nativePtr) {
    nativeFreeBuffer((long) nativePtr);
  }

  @Implementation(minSdk = LOLLIPOP)
  @SuppressWarnings("robolectric.ShadowReturnTypeMismatch")
  protected static void nativeFreeBuffer(long nativePtr) {
    NATIVE_PTR_TO_PARCEL.get(nativePtr).clear();
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static void nativeDestroy(int nativePtr) {
    nativeDestroy((long) nativePtr);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static void nativeDestroy(long nativePtr) {
    NATIVE_PTR_TO_PARCEL.remove(nativePtr);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static byte[] nativeMarshall(int nativePtr) {
    return nativeMarshall((long) nativePtr);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static byte[] nativeMarshall(long nativePtr) {
    return NATIVE_PTR_TO_PARCEL.get(nativePtr).toByteArray();
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static void nativeUnmarshall(int nativePtr, byte[] data, int offset, int length) {
    nativeUnmarshall((long) nativePtr, data, offset, length);
  }

  @Implementation(minSdk = LOLLIPOP)
  @SuppressWarnings("robolectric.ShadowReturnTypeMismatch")
  protected static void nativeUnmarshall(long nativePtr, byte[] data, int offset, int length) {
    NATIVE_PTR_TO_PARCEL.put(nativePtr, ByteBuffer.fromByteArray(data, offset, length));
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static void nativeAppendFrom(int thisNativePtr, int otherNativePtr, int offset, int length) {
    nativeAppendFrom((long) thisNativePtr, otherNativePtr, offset, length);
  }

  @Implementation(minSdk = LOLLIPOP)
  @SuppressWarnings("robolectric.ShadowReturnTypeMismatch")
  protected static void nativeAppendFrom(
      long thisNativePtr, long otherNativePtr, int offset, int length) {
    ByteBuffer thisByteBuffer = NATIVE_PTR_TO_PARCEL.get(thisNativePtr);
    ByteBuffer otherByteBuffer = NATIVE_PTR_TO_PARCEL.get(otherNativePtr);
    thisByteBuffer.appendFrom(otherByteBuffer, offset, length);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static void nativeWriteInterfaceToken(int nativePtr, String interfaceName) {
    nativeWriteInterfaceToken((long) nativePtr, interfaceName);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static void nativeWriteInterfaceToken(long nativePtr, String interfaceName) {
    // Write StrictMode.ThreadPolicy bits (assume 0 for test).
    nativeWriteInt(nativePtr, 0);
    nativeWriteString(nativePtr, interfaceName);
  }

  @HiddenApi
  @Implementation(maxSdk = KITKAT_WATCH)
  public static void nativeEnforceInterface(int nativePtr, String interfaceName) {
    nativeEnforceInterface((long) nativePtr, interfaceName);
  }

  @Implementation(minSdk = LOLLIPOP)
  protected static void nativeEnforceInterface(long nativePtr, String interfaceName) {
    // Consume StrictMode.ThreadPolicy bits (don't bother setting in test).
    nativeReadInt(nativePtr);
    String actualInterfaceName = nativeReadString(nativePtr);
    if (!Objects.equals(interfaceName, actualInterfaceName)) {
      throw new SecurityException("Binder invocation to an incorrect interface");
    }
  }

  private static class ByteBuffer {
    /** Number of bytes in Parcel used by an int, length, or anything smaller. */
    private static final int INT_SIZE_BYTES = 4;
    /** Number of bytes in Parcel used by a long or double. */
    private static final int LONG_OR_DOUBLE_SIZE_BYTES = 8;
    /** Immutable empty byte array. */
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    /** Representation for an item that has been serialized in a parcel. */
    private static class FakeEncodedItem implements Serializable {
      final int sizeBytes;
      final Object value;

      FakeEncodedItem(int sizeBytes, Object value) {
        this.sizeBytes = sizeBytes;
        this.value = value;
      }
    }

    /**
     * A type-safe simulation of the Parcel's data buffer.
     *
     * <p>Each index represents a byte of the parcel. However, to allow better error detection in
     * tests, each index points to a record containing both the original data (in its original Java
     * type) as well as the length. For example, if a single value occupies 24 bytes, then 24
     * consecutive indices will point to the same FakeEncodedItem instance.
     *
     * <p>There are two main fail-fast features in this type-safe buffer. First, objects may only be
     * read from the parcel as the same type they were stored with, enforced by casting. Second,
     * this fails fast when reading incomplete or partially overwritten items.
     *
     * <p>Even though writing a custom resizable array is a code smell vs ArrayList, arrays' fixed
     * capacity closely models Parcel's dataCapacity (which we emulate anyway), and bulk array
     * utilities are robust compared to ArrayList's bulk operations.
     */
    private FakeEncodedItem[] data;
    /** The read/write pointer. */
    private int dataPosition;
    /** The length of the buffer; the capacity is data.length. */
    private int dataSize;
    /** Whether the most recent write was not succeeded by a setDataPosition. */
    private boolean wroteEndWithoutSettingPosition;

    ByteBuffer() {
      clear();
    }

    /** Removes all elements from the byte buffer */
    public void clear() {
      data = new FakeEncodedItem[0];
      dataPosition = 0;
      dataSize = 0;
      wroteEndWithoutSettingPosition = false;
    }

    /** Reads a byte array from the byte buffer based on the current data position */
    public byte[] createByteArray() {
      // It would be simpler just to store the byte array without a separate length.  However, the
      // "non-native" code in Parcel short-circuits null to -1, so this must consistently write a
      // separate length field in all cases.
      int length = readInt();
      if (length == -1) {
        return null;
      }
      if (length == 0) {
        return EMPTY_BYTE_ARRAY;
      }
      byte[] result = readValue(EMPTY_BYTE_ARRAY, byte[].class);
      if (result.length != length) {
        // Looks like the length doesn't correspond to the array.
        throw new IllegalStateException(
            String.format(
                Locale.US,
                "Byte array's length prefix is %d but real length is %d",
                length,
                result.length));
      }
      return result;
    }

    /**
     * Reads a byte array from the byte buffer based on the current data position
     */
    public boolean readByteArray(byte[] dest, int destLen) {
      byte[] result = createByteArray();
      if (result == null || destLen != result.length) {
        // NOTE: This is more informative than "return false" which results in RuntimeException.
        throw new IllegalArgumentException(
            String.format(
                Locale.US, "Destination byte array has length %d but read %s", destLen, result));
      }
      System.arraycopy(result, 0, dest, 0, destLen);
      return true;
    }

    /**
     * Writes a byte to the byte buffer at the current data position
     */
    public void writeByte(byte b) {
      writeValue(INT_SIZE_BYTES, b);
    }

    /**
     * Writes a byte array starting at offset for length bytes to the byte buffer at the current
     * data position
     */
    public void writeByteArray(byte[] b, int offset, int length) {
      writeInt(length);
      // Native parcel writes a byte array as length plus the individual bytes.  But we can't write
      // bytes individually because each byte would take up 4 bytes due to Parcel's alignment
      // behavior.  Instead we write the length, and if non-empty, we write the array.
      if (length != 0) {
        writeValue(length, Arrays.copyOfRange(b, offset, length));
      }
    }

    /**
     * Reads a byte from the byte buffer based on the current data position
     */
    public byte readByte() {
      return readValue((byte) 0, Byte.class);
    }

    /**
     * Writes an int to the byte buffer at the current data position
     */
    public void writeInt(int i) {
      writeValue(INT_SIZE_BYTES, i);
    }

    /**
     * Reads a int from the byte buffer based on the current data position
     */
    public int readInt() {
      return readValue(0, Integer.class);
    }

    /**
     * Writes a long to the byte buffer at the current data position
     */
    public void writeLong(long l) {
      writeValue(LONG_OR_DOUBLE_SIZE_BYTES, l);
    }

    /**
     * Reads a long from the byte buffer based on the current data position
     */
    public long readLong() {
      return readValue(0L, Long.class);
    }

    /**
     * Writes a float to the byte buffer at the current data position
     */
    public void writeFloat(float f) {
      writeValue(INT_SIZE_BYTES, f);
    }

    /**
     * Reads a float from the byte buffer based on the current data position
     */
    public float readFloat() {
      return readValue(0f, Float.class);
    }

    /**
     * Writes a double to the byte buffer at the current data position
     */
    public void writeDouble(double d) {
      writeValue(LONG_OR_DOUBLE_SIZE_BYTES, d);
    }

    /**
     * Reads a double from the byte buffer based on the current data position
     */
    public double readDouble() {
      return readValue(0d, Double.class);
    }

    /** Writes a String to the byte buffer at the current data position */
    public void writeString(String s) {
      int nullTerminatedChars = (s != null) ? (s.length() + 1) : 0;
      // Android encodes strings as length plus a null-terminated array of 2-byte characters.
      // writeValue will pad to nearest 4 bytes.  Null is encoded as just -1.
      int sizeBytes = INT_SIZE_BYTES + (nullTerminatedChars * 2);
      writeValue(sizeBytes, s);
    }

    /**
     * Reads a String from the byte buffer based on the current data position
     */
    public String readString() {
      return readValue(null, String.class);
    }

    /**
     * Writes an IBinder to the byte buffer at the current data position
     */
    public void writeStrongBinder(IBinder b) {
      // Size of struct flat_binder_object in android/binder.h used to encode binders in the real
      // parceling code.
      int length = 5 * INT_SIZE_BYTES;
      writeValue(length, b);
    }

    /**
     * Reads an IBinder from the byte buffer based on the current data position
     */
    public IBinder readStrongBinder() {
      return readValue(null, IBinder.class);
    }

    /**
     * Appends the contents of the other byte buffer to this byte buffer starting at offset and
     * ending at length.
     *
     * @param other ByteBuffer to append to this one
     * @param offset number of bytes from beginning of byte buffer to start copy from
     * @param length number of bytes to copy
     */
    public void appendFrom(ByteBuffer other, int offset, int length) {
      int oldSize = dataSize;
      if (dataPosition != dataSize) {
        // Parcel.cpp will always expand the buffer by length even if it is overwriting existing
        // data, yielding extra uninitialized data at the end, in contrast to write methods that
        // won't increase the data length if they are overwriting in place.  This is surprising
        // behavior that production code should avoid.
        throw new IllegalStateException(
            "Real Android parcels behave unreliably if appendFrom is "
                + "called from any position other than the end");
      }
      setDataSize(oldSize + length);
      // Just blindly copy whatever happened to be in the buffer.  At read time it will be
      // validated if any of the objects were only incompletely copied.
      System.arraycopy(other.data, offset, data, dataPosition, length);
      dataPosition += length;
    }

    /**
     * Creates a Byte buffer from a raw byte array.
     *
     * @param array byte array to read from
     * @param offset starting position in bytes to start reading array at
     * @param length number of bytes to read from array
     */
    public static ByteBuffer fromByteArray(byte[] array, int offset, int length) {
      ByteBuffer byteBuffer = new ByteBuffer();

      try {
        ByteArrayInputStream bis = new ByteArrayInputStream(array, offset,
            length);
        ObjectInputStream ois = new ObjectInputStream(bis);
        int numElements = ois.readInt();
        for (int i = 0; i < numElements; i++) {
          int sizeOf = ois.readInt();
          Object value = ois.readObject();
          byteBuffer.writeValue(sizeOf, value);
        }
        byteBuffer.setDataPosition(0);
        return byteBuffer;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    /**
     * Converts a ByteBuffer to a raw byte array. This method should be symmetrical with
     * fromByteArray.
     */
    public byte[] toByteArray() {
      int oldDataPosition = dataPosition;
      try {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        // NOTE: Serializing the data array would be simpler, and serialization would actually
        // preserve reference equality between entries.  However, the length-encoded format here
        // preserves the previous format, which some tests appear to rely on.
        List<FakeEncodedItem> entries = new ArrayList<>();
        // NOTE: Use readNextItem to scan so the contents can be proactively validated.
        dataPosition = 0;
        while (dataPosition < dataSize) {
          entries.add(readNextItem(Object.class));
        }
        oos.writeInt(entries.size());
        for (FakeEncodedItem item : entries) {
          oos.writeInt(item.sizeBytes);
          oos.writeObject(item.value);
        }
        oos.flush();
        return bos.toByteArray();
      } catch (IOException e) {
        throw new RuntimeException(e);
      } finally {
        dataPosition = oldDataPosition;
      }
    }

    /**
     * Number of unused bytes in this byte buffer.
     */
    public int dataAvailable() {
      return dataSize() - dataPosition();
    }

    /**
     * Total buffer size in bytes of byte buffer included unused space.
     */
    public int dataCapacity() {
      return data.length;
    }

    /**
     * Current data position of byte buffer in bytes. Reads / writes are from this position.
     */
    public int dataPosition() {
      return dataPosition;
    }

    /**
     * Current amount of bytes currently written for ByteBuffer.
     */
    public int dataSize() {
      return dataSize;
    }

    /**
     * Sets the current data position.
     *
     * @param pos
     *          Desired position in bytes
     */
    public void setDataPosition(int pos) {
      if (pos > dataSize) {
        // NOTE: Real parcel ignores this until a write occurs.
        throw new IndexOutOfBoundsException(pos + " greater than dataSize " + dataSize);
      }
      dataPosition = pos;
      wroteEndWithoutSettingPosition = false;
    }

    public void setDataSize(int size) {
      if (size < dataSize) {
        // Clear all the bytes.  Note this might truncate something mid-object, which would be
        // handled at read time.
        Arrays.fill(data, size, dataSize, null);
      }
      // NOTE: Parcel only grows to the exact size specified, but never shrinks.
      setDataCapacityAtLeast(size);
      dataSize = size;
      dataPosition = Math.min(dataPosition, dataSize);
      wroteEndWithoutSettingPosition = false;
    }

    public void setDataCapacityAtLeast(int newCapacity) {
      // NOTE: Oddly, Parcel only every increases data capacity, and never decreases it, so this
      // really should have never been named setDataCapacity.
      if (newCapacity > data.length) {
        FakeEncodedItem[] newData = new FakeEncodedItem[newCapacity];
        dataSize = Math.min(dataSize, newCapacity);
        dataPosition = Math.min(dataPosition, dataSize);
        System.arraycopy(data, 0, newData, 0, dataSize);
        data = newData;
      }
    }

    /** Rounds to next 4-byte bounder similar to native Parcel. */
    private int alignToInt(int unpaddedSizeBytes) {
      return ((unpaddedSizeBytes + 3) / 4) * 4;
    }

    /**
     * Ensures that the next sizeBytes are all the initial value we read.
     *
     * <p>This detects:
     *
     * <ul>
     *   <li>Reading an item, but not starting at its start position
     *   <li>Reading items that were truncated by setSize
     *   <li>Reading items that were partially overwritten by another
     * </ul>
     */
    private void checkConsistentReadAndIncrementPosition(
        Class<?> clazz, FakeEncodedItem itemToExpect, int sizeBytes) {
      int endPosition = dataPosition + sizeBytes;
      for (int i = dataPosition; i < endPosition; i++) {
        FakeEncodedItem foundItemItem = i < dataSize ? data[i] : null;
        if (foundItemItem != itemToExpect) {
          throw new IllegalArgumentException(
              String.format(
                  Locale.US,
                  "Looking for %s at position %d, found [%s] taking %d bytes,"
                      + " but [%s] interrupts it at position %d",
                  clazz.getSimpleName(),
                  dataPosition,
                  itemToExpect.value,
                  sizeBytes,
                  foundItemItem == null
                      ? "uninitialized data or the end of the buffer"
                      : foundItemItem.value,
                  i));
        }
      }
      dataPosition = Math.min(dataSize, dataPosition + sizeBytes);
    }

    /**
     * Reads a complete item in the byte buffer.
     *
     * @param clazz this is the type that is being read, but not checked in this method
     * @return null if the default value should be returned, otherwise the item holding the data
     */
    private <T> FakeEncodedItem readNextItem(Class<T> clazz) {
      if (dataPosition >= dataSize) {
        // Normally, reading past the end is permitted, and returns the default values.  However,
        // writing to a parcel then reading without setting the position back to 0 is an incredibly
        // common error to make in tests, and should never really happen in production code, so
        // this shadow will fail in this condition.
        if (wroteEndWithoutSettingPosition) {
          throw new IllegalStateException(
              "Did you forget to setDataPosition(0) before reading the parcel?");
        }
        return null;
      }
      FakeEncodedItem item = data[dataPosition];
      if (item == null) {
        // While Parcel will treat these as zeros, in tests, this is almost always an error.
        throw new IllegalArgumentException(
            "Reading uninitialized data at position " + dataPosition);
      }
      checkConsistentReadAndIncrementPosition(clazz, item, item.sizeBytes);
      return item;
    }

    /**
     * Reads the next value in the byte buffer of a specified type.
     *
     * @param defaultValue if the current part of the buffer is initialized, this is what to return
     * @param clazz this is the type that is being read, but not checked in this method
     */
    private <T> T readValue(T defaultValue, Class<T> clazz) {
      FakeEncodedItem item = readNextItem(clazz);
      return item == null ? defaultValue : clazz.cast(item.value);
    }

    /**
     * Writes a value to the next range of bytes.
     *
     * <p>Writes are aligned to 4-byte regions.
     */
    private void writeValue(int unpaddedSizeBytes, Object o) {
      // Create the item to write into the buffer and get its final byte size.
      FakeEncodedItem item = new FakeEncodedItem(alignToInt(unpaddedSizeBytes), o);
      int endPosition = dataPosition + item.sizeBytes;
      if (endPosition > data.length) {
        // Parcel grows by 3/2 of the new size.
        setDataCapacityAtLeast(endPosition * 3 / 2);
      }
      if (endPosition > dataSize) {
        wroteEndWithoutSettingPosition = true;
        dataSize = endPosition;
      }
      Arrays.fill(data, dataPosition, endPosition, item);
      dataPosition = endPosition;
    }
  }

  @Implementation(maxSdk = P)
  protected static FileDescriptor openFileDescriptor(String file, int mode) throws IOException {
    RandomAccessFile randomAccessFile =
        new RandomAccessFile(file, mode == ParcelFileDescriptor.MODE_READ_ONLY ? "r" : "rw");
    return randomAccessFile.getFD();
  }
}
