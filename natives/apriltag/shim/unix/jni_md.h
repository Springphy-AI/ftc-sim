/*
 * Machine-dependent JNI types for Linux and macOS (64-bit), used when cross-compiling from a
 * machine whose JDK only has its own platform's jni_md.h. Same definitions the JNI
 * specification gives for these platforms.
 */
#ifndef FTCSIM_JNI_MD_H
#define FTCSIM_JNI_MD_H

#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT __attribute__((visibility("default")))
#define JNICALL

typedef int jint;
#ifdef _LP64
typedef long jlong;
#else
typedef long long jlong;
#endif
typedef signed char jbyte;

#endif
