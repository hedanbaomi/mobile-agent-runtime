// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

#include <errno.h>
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

#define MAR_NAME_BYTES 255
#define MAR_RENAME_NOREPLACE 1U

/* JNI's Modified UTF-8 does not encode supplementary filename characters as Linux UTF-8.
 * Decode UTF-16 explicitly, reject malformed names, and never accept a path/AT_FDCWD. */
static int copy_entry_name(JNIEnv *env, jstring value, char output[MAR_NAME_BYTES + 1]) {
    if (value == NULL) return EINVAL;
    jsize length = (*env)->GetStringLength(env, value);
    if (length < 1 || length > MAR_NAME_BYTES) return EINVAL;
    const jchar *units = (*env)->GetStringChars(env, value, NULL);
    if (units == NULL) return ENOMEM;
    size_t written = 0;
    int result = 0;
    for (jsize i = 0; i < length; i++) {
        uint32_t cp = units[i];
        if (cp >= 0xd800 && cp <= 0xdbff) {
            if (++i >= length || units[i] < 0xdc00 || units[i] > 0xdfff) { result = EINVAL; break; }
            cp = 0x10000 + ((cp - 0xd800) << 10) + (units[i] - 0xdc00);
        } else if (cp >= 0xdc00 && cp <= 0xdfff) { result = EINVAL; break; }
        if (cp < 32 || cp == 127 || cp == '/' || cp == 0xfffd) { result = EINVAL; break; }
        size_t bytes = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
        if (written + bytes > MAR_NAME_BYTES) { result = ENAMETOOLONG; break; }
        if (bytes == 1) output[written++] = (char)cp;
        else {
            unsigned shift = (unsigned)(6 * (bytes - 1));
            output[written++] = (char)((bytes == 2 ? 0xc0 : bytes == 3 ? 0xe0 : 0xf0) | (cp >> shift));
            while (shift > 0) {
                shift -= 6;
                output[written++] = (char)(0x80 | ((cp >> shift) & 0x3f));
            }
        }
    }
    (*env)->ReleaseStringChars(env, value, units);
    if (result != 0) return result;
    output[written] = '\0';
    return strcmp(output, ".") == 0 || strcmp(output, "..") == 0 ? EINVAL : 0;
}

JNIEXPORT jint JNICALL
Java_runtime_mobileagent_privileged_RootNativeSupervisorJni_nativeRenameAtNoReplace(
    JNIEnv *env, jclass clazz, jint source_parent, jstring source_name,
    jint destination_parent, jstring destination_name
) {
    (void)clazz;
    if (source_parent < 0 || destination_parent < 0) return -EINVAL;
    char source[MAR_NAME_BYTES + 1], destination[MAR_NAME_BYTES + 1];
    int result = copy_entry_name(env, source_name, source);
    if (result != 0) return -result;
    result = copy_entry_name(env, destination_name, destination);
    if (result != 0) return -result;
#ifdef SYS_renameat2
    int rc = (int)syscall(SYS_renameat2, (int)source_parent, source,
                         (int)destination_parent, destination, MAR_RENAME_NOREPLACE);
    return rc == 0 ? 0 : -errno;
#else
    return -ENOSYS;
#endif
}
