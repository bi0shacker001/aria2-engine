/* See audit_level.h. Plain C, so CI runs its cases on the runner (audit_level_test.c). */
#include "audit_level.h"

#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>

/* The downloads whose miss was logged, as hashes; the oldest is forgotten first. */
#define SEEN 256
static uint64_t seen[SEEN];
static unsigned next;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

/* A download's key: its path without aria2's ".aria2", so a file and its control file are one download. */
static uint64_t key_of(const char *path) {
    size_t length = strlen(path);
    static const char control[] = ".aria2";
    size_t suffix = sizeof control - 1;
    if (length > suffix && strcmp(path + length - suffix, control) == 0) length -= suffix;
    uint64_t hash = 1469598103934665603ULL;
    for (size_t i = 0; i < length; i++) hash = (hash ^ (unsigned char) path[i]) * 1099511628211ULL;
    return hash ? hash : 1;
}

enum audit_level audit_level(const char *path, int result) {
    if (result >= 0) return AUDIT_SILENT;
    if (result != -ENOENT) return AUDIT_WARN;
    uint64_t key = key_of(path ? path : "");
    enum audit_level level = AUDIT_DEBUG;
    pthread_mutex_lock(&lock);
    for (unsigned i = 0; i < SEEN; i++) {
        if (seen[i] == key) {
            level = AUDIT_SILENT;
            break;
        }
    }
    if (level == AUDIT_DEBUG) {
        seen[next] = key;
        next = (next + 1) % SEEN;
    }
    pthread_mutex_unlock(&lock);
    return level;
}
