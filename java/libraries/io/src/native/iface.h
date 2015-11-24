/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class processing_io_NativeInterface */

#ifndef _Included_processing_io_NativeInterface
#define _Included_processing_io_NativeInterface
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     processing_io_NativeInterface
 * Method:    openDevice
 * Signature: (Ljava/lang/String;)I
 */
JNIEXPORT jint JNICALL Java_processing_io_NativeInterface_openDevice
  (JNIEnv *, jclass, jstring);

/*
 * Class:     processing_io_NativeInterface
 * Method:    getError
 * Signature: (I)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_processing_io_NativeInterface_getError
  (JNIEnv *, jclass, jint);

/*
 * Class:     processing_io_NativeInterface
 * Method:    closeDevice
 * Signature: (I)I
 */
JNIEXPORT jint JNICALL Java_processing_io_NativeInterface_closeDevice
  (JNIEnv *, jclass, jint);

/*
 * Class:     processing_io_NativeInterface
 * Method:    readFile
 * Signature: (Ljava/lang/String;[B)I
 */
JNIEXPORT jint JNICALL Java_processing_io_NativeInterface_readFile
  (JNIEnv *, jclass, jstring, jbyteArray);

/*
 * Class:     processing_io_NativeInterface
 * Method:    writeFile
 * Signature: (Ljava/lang/String;[B)I
 */
JNIEXPORT jint JNICALL Java_processing_io_NativeInterface_writeFile
  (JNIEnv *, jclass, jstring, jbyteArray);

/*
 * Class:     processing_io_NativeInterface
 * Method:    pollDevice
 * Signature: (Ljava/lang/String;I)I
 */
JNIEXPORT jint JNICALL Java_processing_io_NativeInterface_pollDevice
  (JNIEnv *, jclass, jstring, jint);

/*
 * Class:     processing_io_NativeInterface
 * Method:    transferI2c
 * Signature: (II[B[B)I
 */
JNIEXPORT jint JNICALL Java_processing_io_NativeInterface_transferI2c
  (JNIEnv *, jclass, jint, jint, jbyteArray, jbyteArray);

/*
 * Class:     processing_io_NativeInterface
 * Method:    setSpiSettings
 * Signature: (IIII)I
 */
JNIEXPORT jint JNICALL Java_processing_io_NativeInterface_setSpiSettings
  (JNIEnv *, jclass, jint, jint, jint, jint);

/*
 * Class:     processing_io_NativeInterface
 * Method:    transferSpi
 * Signature: (I[B[B)I
 */
JNIEXPORT jint JNICALL Java_processing_io_NativeInterface_transferSpi
  (JNIEnv *, jclass, jint, jbyteArray, jbyteArray);

#ifdef __cplusplus
}
#endif
#endif
