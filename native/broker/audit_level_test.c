/* audit_level.c on the build machine: cc -Wall -Werror -pthread audit_level.c audit_level_test.c && ./a.out */
#include "audit_level.h"

#include <errno.h>
#include <stdio.h>

static int failures;

static void check(enum audit_level got, enum audit_level want, const char *what) {
    if (got != want) {
        fprintf(stderr, "FAIL %s: got %d, expected %d\n", what, got, want);
        failures++;
    }
}

int main(void) {
    const char *chunk = "/aria2/downloads/steam_chunk_731_0a1b";
    const char *control = "/aria2/downloads/steam_chunk_731_0a1b.aria2";
    check(audit_level(chunk, 0), AUDIT_SILENT, "a call that worked");
    check(audit_level(control, -ENOENT), AUDIT_DEBUG, "the first miss of a download");
    check(audit_level(control, -ENOENT), AUDIT_SILENT, "the same miss again");
    check(audit_level(chunk, -ENOENT), AUDIT_SILENT, "the download's own file, after its control file");
    check(audit_level("/aria2/downloads/steam_chunk_731_ffff", -ENOENT), AUDIT_DEBUG, "another download's miss");
    check(audit_level(chunk, -EACCES), AUDIT_WARN, "a refusal is a warning");
    check(audit_level(chunk, -EACCES), AUDIT_WARN, "every time");
    check(audit_level(NULL, -ENOENT), AUDIT_DEBUG, "a call with no path");
    for (int i = 0; i < 300; i++) {
        char name[64];
        snprintf(name, sizeof name, "/aria2/downloads/f%d", i);
        audit_level(name, -ENOENT);
    }
    check(audit_level(control, -ENOENT), AUDIT_DEBUG, "an old download is forgotten after many others");
    if (failures) return 1;
    puts("audit_level: all cases pass");
    return 0;
}
