/*
 * Desktop stand-in for Android's <android/log.h>.
 *
 * The AprilTag JNI glue (AprilTagDetectorJNI.cpp) is compiled unchanged; its only Android
 * dependency is a few debug lines it writes to logcat ("Creating 36h11 tag family", ...).
 * On a PC there is no logcat, so those lines are dropped.
 */
#ifndef FTCSIM_ANDROID_LOG_SHIM_H
#define FTCSIM_ANDROID_LOG_SHIM_H

#define ANDROID_LOG_UNKNOWN 0
#define ANDROID_LOG_DEFAULT 1
#define ANDROID_LOG_VERBOSE 2
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_FATAL 7
#define ANDROID_LOG_SILENT 8

static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...)
{
    (void) prio;
    (void) tag;
    (void) fmt;
    return 0;
}

#endif
