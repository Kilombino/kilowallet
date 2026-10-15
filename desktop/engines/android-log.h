/* Android's log on a desktop JVM: to stderr. */
#ifndef DESKTOP_ANDROID_LOG_H
#define DESKTOP_ANDROID_LOG_H
#include <stdio.h>
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_FATAL 7
#define __android_log_print(prio, tag, ...) (fprintf(stderr, "%s: ", tag), fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif
