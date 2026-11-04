/*
 * How loudly the broker logs one path call's result (docs/design.md, "Files"). A refusal is a warning. A miss
 * ("No such file or directory") is aria2 looking for a file that is not there yet: its own `.aria2` control file
 * beside a download it starts, or the download itself. That is expected, so it is logged once per download at debug
 * level and not again: a Steam download asks for each chunk's file and control file a dozen times, and as warnings
 * those pushed every other line out of logcat (Droidtop/tracker#862).
 */
#ifndef ARIA2ENGINE_AUDIT_LEVEL_H
#define ARIA2ENGINE_AUDIT_LEVEL_H

enum audit_level { AUDIT_SILENT, AUDIT_DEBUG, AUDIT_WARN };

/* The level for a path call on [path] (may be NULL) that ended with [result] (0 or more, or a negative errno). */
enum audit_level audit_level(const char *path, int result);

#endif
