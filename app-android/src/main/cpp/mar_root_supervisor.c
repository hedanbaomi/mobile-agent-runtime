// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define MAR_SETTLE_BUDGET_MS 2000L
#define MAR_MAX_PROC_SNAPSHOT 2048

typedef struct {
    int32_t pid;
    int32_t ppid;
    int32_t pgrp;
    int64_t start_ticks;
    char state;
} mar_proc_stat_t;

typedef struct {
    int32_t leader_exited;
    int32_t exit_code;
    int32_t timed_out;
    int32_t cancelled;
    int32_t leaked_descendants;
    int32_t settled;
    int32_t cmd_pgid;
    int32_t unreaped_pid;
    int64_t unreaped_start_ticks;
} mar_supervisor_report_t;

static int g_sig_write_fd = -1;
static volatile sig_atomic_t g_parent_term_received = 0;
static volatile sig_atomic_t g_cached_probe_pid = -1;
static volatile sig_atomic_t g_cached_probe_ok = 0;
static mar_proc_stat_t g_proc_snap[MAR_MAX_PROC_SNAPSHOT];

static int64_t now_monotonic_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return (int64_t)ts.tv_sec * 1000LL + (int64_t)(ts.tv_nsec / 1000000L);
}

static void close_quietly(int *fd) {
    if (fd != NULL && *fd >= 0) {
        int saved = errno;
        close(*fd);
        *fd = -1;
        errno = saved;
    }
}

static int read_proc_stat(pid_t pid, mar_proc_stat_t *out) {
    if (pid <= 0 || out == NULL) return -1;
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/stat", (int)pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    char buf[1024];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';

    char *rparen = strrchr(buf, ')');
    if (rparen == NULL || rparen[1] != ' ') return -1;
    char *p = rparen + 2;

    char state = '\0';
    int ppid = -1;
    int pgrp = -1;
    if (sscanf(p, "%c %d %d", &state, &ppid, &pgrp) != 3) {
        return -1;
    }

    // Field 3 is token 0 after ') '; field 22 (starttime) is token 19.
    char *saveptr = NULL;
    char *tok = strtok_r(p, " ", &saveptr);
    int idx = 0;
    int64_t start_ticks = -1;
    while (tok != NULL) {
        if (idx == 19) {
            char *end = NULL;
            errno = 0;
            long long val = strtoll(tok, &end, 10);
            if (errno == 0 && end != tok && val > 0) {
                start_ticks = (int64_t)val;
            }
            break;
        }
        tok = strtok_r(NULL, " ", &saveptr);
        idx++;
    }
    if (start_ticks <= 0) return -1;

    out->pid = (int32_t)pid;
    out->ppid = (int32_t)ppid;
    out->pgrp = (int32_t)pgrp;
    out->start_ticks = start_ticks;
    out->state = state;
    return 0;
}

static void sup_sig_handler(int sig) {
    int saved = errno;
    if (sig == SIGTERM || sig == SIGHUP || sig == SIGINT || sig == SIGQUIT) {
        g_parent_term_received = 1;
    }
    if (g_sig_write_fd >= 0) {
        char c = 'S';
        ssize_t unused = write(g_sig_write_fd, &c, 1);
        (void)unused;
    }
    errno = saved;
}

static void close_extra_fds_keep4(int keep1, int keep2, int keep3, int keep4) {
    DIR *dir = opendir("/proc/self/fd");
    if (dir != NULL) {
        int dir_fd = dirfd(dir);
        int to_close[1024];
        size_t count = 0;
        struct dirent *de;
        while ((de = readdir(dir)) != NULL) {
            if (!isdigit((unsigned char)de->d_name[0])) continue;
            int fd = atoi(de->d_name);
            if (fd <= 2 || fd == dir_fd || fd == keep1 || fd == keep2 || fd == keep3 || fd == keep4) {
                continue;
            }
            if (count < sizeof(to_close) / sizeof(to_close[0])) {
                to_close[count++] = fd;
            } else {
                close(fd);
            }
        }
        closedir(dir);
        for (size_t i = 0; i < count; i++) {
            close(to_close[i]);
        }
        return;
    }
    long max_fd = sysconf(_SC_OPEN_MAX);
    if (max_fd < 0 || max_fd > 4096) max_fd = 1024;
    for (int fd = 3; fd < (int)max_fd; fd++) {
        if (fd == keep1 || fd == keep2 || fd == keep3 || fd == keep4) continue;
        close(fd);
    }
}

static int decode_wait_status(int wstatus) {
    if (WIFEXITED(wstatus)) {
        return WEXITSTATUS(wstatus);
    }
    if (WIFSIGNALED(wstatus)) {
        return 128 + WTERMSIG(wstatus);
    }
    return -1;
}

static int is_descendant_of_sup(
    int idx,
    const mar_proc_stat_t *snap,
    size_t count,
    pid_t sup_pid,
    pid_t cmd_pgid
) {
    int cur_ppid = snap[idx].ppid;
    if (cur_ppid == (int)sup_pid) return 1;
    if (cmd_pgid > 1 && snap[idx].pgrp == (int)cmd_pgid) return 1;
    for (int depth = 0; depth < 32 && cur_ppid > 1; depth++) {
        if (cur_ppid == (int)sup_pid) return 1;
        int found = 0;
        for (size_t j = 0; j < count; j++) {
            if (snap[j].pid == cur_ppid) {
                cur_ppid = snap[j].ppid;
                found = 1;
                break;
            }
        }
        if (!found) break;
    }
    return 0;
}

static void signal_supervisor_tree(
    pid_t sup_pid,
    pid_t parent_pid,
    pid_t cmd_pid,
    int64_t cmd_start_ticks,
    int sig,
    int32_t *out_survivor_pid,
    int64_t *out_survivor_ticks
) {
    pid_t sup_pgrp = getpgrp();
    if (cmd_pid > 1 && cmd_start_ticks > 0) {
        mar_proc_stat_t leader_st;
        if (read_proc_stat(cmd_pid, &leader_st) == 0 && leader_st.start_ticks == cmd_start_ticks) {
            if (leader_st.pgrp > 1 && leader_st.pgrp != sup_pgrp) {
                kill(-leader_st.pgrp, sig);
            }
            kill(cmd_pid, sig);
        }
    }

    size_t count = 0;
    DIR *dir = opendir("/proc");
    if (dir != NULL) {
        struct dirent *de;
        while ((de = readdir(dir)) != NULL && count < MAR_MAX_PROC_SNAPSHOT) {
            if (!isdigit((unsigned char)de->d_name[0])) continue;
            pid_t pid = (pid_t)atoi(de->d_name);
            if (pid <= 1 || pid == sup_pid || pid == parent_pid) continue;
            if (read_proc_stat(pid, &g_proc_snap[count]) == 0) {
                count++;
            }
        }
        closedir(dir);
    }

    int survivor_count = 0;
    int32_t single_pid = 0;
    int64_t single_ticks = 0;

    for (size_t i = 0; i < count; i++) {
        if (!is_descendant_of_sup((int)i, g_proc_snap, count, sup_pid, cmd_pid)) continue;
        pid_t target_pid = (pid_t)g_proc_snap[i].pid;
        mar_proc_stat_t verify_st;
        if (read_proc_stat(target_pid, &verify_st) != 0) continue;
        if (verify_st.start_ticks != g_proc_snap[i].start_ticks) continue;
        if (verify_st.state != 'Z' && verify_st.state != 'X') {
            survivor_count++;
            if (survivor_count == 1) {
                single_pid = verify_st.pid;
                single_ticks = verify_st.start_ticks;
            }
        }
        if (verify_st.pgrp > 1 && verify_st.pgrp != (int32_t)sup_pgrp) {
            kill(-verify_st.pgrp, sig);
        }
        kill(target_pid, sig);
    }

    if (out_survivor_pid != NULL && out_survivor_ticks != NULL) {
        if (survivor_count == 1) {
            *out_survivor_pid = single_pid;
            *out_survivor_ticks = single_ticks;
        } else {
            // 0 observed due to race or >1 survivors outside cmd_pgid: fail closed (0).
            *out_survivor_pid = 0;
            *out_survivor_ticks = 0;
        }
    }
}

static void drain_sig_pipe(int fd) {
    char buf[64];
    while (read(fd, buf, sizeof(buf)) > 0) {
    }
}

static int reap_available_children(
    pid_t cmd_pid,
    int *leader_exited,
    int *leader_exit_code,
    int *leaked_descendants
) {
    while (1) {
        int wstatus = 0;
        pid_t wpid = waitpid(-1, &wstatus, WNOHANG);
        if (wpid > 0) {
            if (wpid == cmd_pid) {
                if (!(*leader_exited)) {
                    *leader_exited = 1;
                    *leader_exit_code = decode_wait_status(wstatus);
                }
            } else {
                *leaked_descendants = 1;
            }
            continue;
        }
        if (wpid == 0) {
            return 0; // Still live children in subreaper tree
        }
        if (errno == EINTR) {
            continue;
        }
        if (errno == ECHILD) {
            return 1; // Subreaper tree is completely empty
        }
        return -1;
    }
}

static void run_supervisor_process(
    pid_t parent_pid,
    int64_t parent_start_ticks,
    const char *command,
    const char *cwd,
    char *const *envp,
    int64_t grace_ms,
    int stdout_write_fd,
    int stderr_write_fd,
    int ctrl_read_fd,
    int status_write_fd
) {
    close_extra_fds_keep4(stdout_write_fd, stderr_write_fd, ctrl_read_fd, status_write_fd);

    int sup_null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
    if (sup_null_fd >= 0) {
        for (int sfd = 0; sfd <= 2; sfd++) {
            if (sfd != stdout_write_fd && sfd != stderr_write_fd &&
                sfd != ctrl_read_fd && sfd != status_write_fd && sfd != sup_null_fd) {
                dup2(sup_null_fd, sfd);
            }
        }
        if (sup_null_fd > 2 && sup_null_fd != stdout_write_fd && sup_null_fd != stderr_write_fd &&
            sup_null_fd != ctrl_read_fd && sup_null_fd != status_write_fd) {
            close(sup_null_fd);
        }
    }

    int sig_pipe[2] = {-1, -1};
    if (pipe2(sig_pipe, O_CLOEXEC | O_NONBLOCK) != 0) {
        _exit(120);
    }
    g_sig_write_fd = sig_pipe[1];
    g_parent_term_received = 0;

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = sup_sig_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESTART;
    sigaction(SIGCHLD, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGQUIT, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    if (prctl(PR_SET_PDEATHSIG, SIGTERM, 0, 0, 0) != 0) {
        _exit(121);
    }
    mar_proc_stat_t pstat;
    if (getppid() != parent_pid ||
        read_proc_stat(parent_pid, &pstat) != 0 ||
        pstat.start_ticks != parent_start_ticks) {
        _exit(122);
    }
    if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) != 0) {
        _exit(123);
    }

    pid_t cmd_pid = fork();
    if (cmd_pid < 0) {
        _exit(124);
    }
    if (cmd_pid == 0) {
        struct sigaction dfl;
        memset(&dfl, 0, sizeof(dfl));
        dfl.sa_handler = SIG_DFL;
        sigaction(SIGCHLD, &dfl, NULL);
        sigaction(SIGTERM, &dfl, NULL);
        sigaction(SIGHUP, &dfl, NULL);
        sigaction(SIGINT, &dfl, NULL);
        sigaction(SIGQUIT, &dfl, NULL);
        sigaction(SIGPIPE, &dfl, NULL);
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, NULL);

        prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);
        if (setsid() < 0) {
            _exit(125);
        }
        if (cwd != NULL && cwd[0] != '\0') {
            if (chdir(cwd) != 0) {
                _exit(126);
            }
        }
        int devnull = open("/dev/null", O_RDONLY | O_CLOEXEC);
        if (devnull < 0 || dup2(devnull, STDIN_FILENO) < 0) {
            _exit(125);
        }
        if (devnull > STDIN_FILENO) close(devnull);
        if (dup2(stdout_write_fd, STDOUT_FILENO) < 0 || dup2(stderr_write_fd, STDERR_FILENO) < 0) {
            _exit(125);
        }
        close_extra_fds_keep4(-1, -1, -1, -1);

        char *const argv[] = {"/system/bin/sh", "-c", (char *)command, NULL};
        execve("/system/bin/sh", argv, envp);
        _exit(127);
    }

    close_quietly(&stdout_write_fd);
    close_quietly(&stderr_write_fd);

    mar_proc_stat_t cmd_st;
    int64_t cmd_start_ticks = 0;
    if (read_proc_stat(cmd_pid, &cmd_st) == 0) {
        cmd_start_ticks = cmd_st.start_ticks;
    }

    int leader_exited = 0;
    int leader_exit_code = -1;
    int timed_out = 0;
    int cancelled = 0;
    int leaked_descendants = 0;
    int terminate_triggered = 0;
    int parent_died = 0;

    while (!leader_exited && !terminate_triggered) {
        if (g_parent_term_received) {
            parent_died = 1;
            cancelled = 1;
            terminate_triggered = 1;
            break;
        }
        int empty_now = reap_available_children(
            cmd_pid, &leader_exited, &leader_exit_code, &leaked_descendants);
        if (leader_exited || empty_now == 1) {
            break;
        }

        struct pollfd pfds[2];
        pfds[0].fd = ctrl_read_fd;
        pfds[0].events = POLLIN;
        pfds[1].fd = sig_pipe[0];
        pfds[1].events = POLLIN;

        int pr = poll(pfds, 2, 250);
        if (pr > 0) {
            if (pfds[1].revents & POLLIN) {
                drain_sig_pipe(sig_pipe[0]);
            }
            if (pfds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
                char ctrl_byte = 0;
                ssize_t nr = read(ctrl_read_fd, &ctrl_byte, 1);
                if (nr == 1) {
                    if (ctrl_byte == 'T') {
                        timed_out = 1;
                    } else {
                        cancelled = 1;
                    }
                    terminate_triggered = 1;
                } else if (nr == 0) {
                    parent_died = 1;
                    cancelled = 1;
                    terminate_triggered = 1;
                }
            }
        }
    }

    // Reap anything already exited when leader exited or terminate triggered.
    int tree_empty = reap_available_children(
        cmd_pid, &leader_exited, &leader_exit_code, &leaked_descendants);

    if (leader_exited && !terminate_triggered && tree_empty != 1) {
        // Leader exited, yet at least one descendant in the subreaper tree is still alive!
        leaked_descendants = 1;
    }

    int32_t d_state_pid = 0;
    int64_t d_state_ticks = 0;
    pid_t sup_pid = getpid();

    if (tree_empty != 1) {
        // Phase 1: SIGTERM + grace period (skipped if parent died).
        if (!parent_died && grace_ms > 0) {
            signal_supervisor_tree(sup_pid, parent_pid, cmd_pid, cmd_start_ticks, SIGTERM, NULL, NULL);
            int64_t grace_deadline = now_monotonic_ms() + grace_ms;
            while (now_monotonic_ms() < grace_deadline) {
                if (g_parent_term_received) {
                    parent_died = 1;
                    break;
                }
                tree_empty = reap_available_children(
                    cmd_pid, &leader_exited, &leader_exit_code, &leaked_descendants);
                if (tree_empty == 1) break;
                struct pollfd pfd = {.fd = sig_pipe[0], .events = POLLIN};
                if (poll(&pfd, 1, 20) > 0) {
                    drain_sig_pipe(sig_pipe[0]);
                }
            }
        }

        // Phase 2: SIGKILL + settle budget until waitpid(-1) == -1 && errno == ECHILD.
        if (tree_empty != 1) {
            int64_t kill_deadline = now_monotonic_ms() + MAR_SETTLE_BUDGET_MS;
            while (1) {
                signal_supervisor_tree(
                    sup_pid, parent_pid, cmd_pid, cmd_start_ticks, SIGKILL, &d_state_pid, &d_state_ticks);
                tree_empty = reap_available_children(
                    cmd_pid, &leader_exited, &leader_exit_code, &leaked_descendants);
                if (tree_empty == 1) break;
                if (now_monotonic_ms() >= kill_deadline) break;
                struct pollfd pfd = {.fd = sig_pipe[0], .events = POLLIN};
                if (poll(&pfd, 1, 15) > 0) {
                    drain_sig_pipe(sig_pipe[0]);
                }
            }
        }
    }

    if (tree_empty != 1) {
        // Final check and record any surviving D-state or un-reaped descendant.
        signal_supervisor_tree(
            sup_pid, parent_pid, cmd_pid, cmd_start_ticks, SIGKILL, &d_state_pid, &d_state_ticks);
        tree_empty = reap_available_children(
            cmd_pid, &leader_exited, &leader_exit_code, &leaked_descendants);
    }

    mar_supervisor_report_t report;
    memset(&report, 0, sizeof(report));
    report.leader_exited = leader_exited ? 1 : 0;
    report.exit_code = leader_exit_code;
    report.timed_out = timed_out ? 1 : 0;
    report.cancelled = cancelled ? 1 : 0;
    report.leaked_descendants = leaked_descendants ? 1 : 0;
    report.settled = (tree_empty == 1) ? 1 : 0;
    report.cmd_pgid = (int32_t)cmd_pid;
    report.unreaped_pid = (tree_empty == 1) ? 0 : d_state_pid;
    report.unreaped_start_ticks = (tree_empty == 1) ? 0 : d_state_ticks;

    ssize_t written = write(status_write_fd, &report, sizeof(report));
    (void)written;
    close_quietly(&status_write_fd);
    close_quietly(&ctrl_read_fd);
    close_quietly(&sig_pipe[0]);
    close_quietly(&sig_pipe[1]);
    _exit(0);
}

static int probe_kernel_support_internal(void) {
    if (geteuid() != 0 || getuid() != 0) {
        return 0;
    }
    if (access("/system/bin/sh", X_OK) != 0) {
        return 0;
    }
    pid_t self_pid = getpid();
    mar_proc_stat_t self_st;
    if (read_proc_stat(self_pid, &self_st) != 0 || self_st.start_ticks <= 0) {
        return 0;
    }
    if (g_cached_probe_pid == (sig_atomic_t)self_pid && g_cached_probe_ok == 1) {
        return 1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        return 0;
    }
    if (pid == 0) {
        if (prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0) != 0) _exit(10);
        if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) != 0) _exit(11);
        int sub = 0;
        if (prctl(PR_GET_CHILD_SUBREAPER, &sub, 0, 0, 0) != 0 || sub != 1) _exit(12);
        if (setsid() < 0) _exit(13);
        pid_t gchild = fork();
        if (gchild < 0) _exit(14);
        if (gchild == 0) {
            _exit(0);
        }
        int gst = 0;
        if (waitpid(gchild, &gst, 0) != gchild || !WIFEXITED(gst) || WEXITSTATUS(gst) != 0) {
            _exit(15);
        }
        if (waitpid(-1, &gst, WNOHANG) != -1 || errno != ECHILD) {
            _exit(16);
        }
        _exit(0);
    }

    int st = 0;
    pid_t waited;
    do {
        waited = waitpid(pid, &st, 0);
    } while (waited < 0 && errno == EINTR);
    if (waited == pid && WIFEXITED(st) && WEXITSTATUS(st) == 0) {
        g_cached_probe_pid = (sig_atomic_t)self_pid;
        g_cached_probe_ok = 1;
        return 1;
    }
    return 0;
}

typedef struct {
    uint8_t *data;
    size_t len;
    size_t cap;
    int truncated;
} mar_byte_buf_t;

/*
 * Bounded non-blocking drain.
 *
 * A continuously writable child (`cat /dev/zero`) used to keep this loop reading forever, so the
 * outer poll loop never got a chance to service the status pipe, the cancel pipe or the deadline.
 * Every call now stops after MAR_DRAIN_MAX_READS reads or MAR_DRAIN_BUDGET_MS, whichever comes
 * first, and reports whether bytes may still be pending so the caller can poll again.
 */
#define MAR_DRAIN_MAX_READS 8
#define MAR_DRAIN_BUDGET_MS 5L

static int drain_pipe_nonblocking(int *fd, mar_byte_buf_t *buf) {
    if (fd == NULL || *fd < 0) return 0;
    uint8_t scratch[8192];
    int attempts = 0;
    const int64_t budget_deadline = now_monotonic_ms() + MAR_DRAIN_BUDGET_MS;
    while (attempts < MAR_DRAIN_MAX_READS) {
        /* EINTR counts as an attempt and the time budget is checked before every read, so a
         * signal storm cannot hold a single drain past its budget. */
        if (now_monotonic_ms() >= budget_deadline) return 1;
        attempts++;
        ssize_t n = read(*fd, scratch, sizeof(scratch));
        if (n > 0) {
            size_t rem = (buf->cap > buf->len) ? (buf->cap - buf->len) : 0;
            if (rem > 0) {
                size_t to_copy = ((size_t)n < rem) ? (size_t)n : rem;
                memcpy(buf->data + buf->len, scratch, to_copy);
                buf->len += to_copy;
                if ((size_t)n > to_copy) {
                    buf->truncated = 1;
                }
            } else {
                buf->truncated = 1;
            }
            /* A full buffer keeps draining (bytes must leave the pipe) but the time budget the
             * loop checks bounds this single call. */
            continue;
        }
        if (n == 0) {
            close_quietly(fd);
            return 0;
        }
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
        close_quietly(fd);
        return 0;
    }
    return 1;
}

JNIEXPORT jboolean JNICALL
Java_runtime_mobileagent_privileged_RootNativeSupervisorJni_nativeProbeKernelSupport(
    JNIEnv *env,
    jclass clazz
) {
    (void)env;
    (void)clazz;
    return probe_kernel_support_internal() ? JNI_TRUE : JNI_FALSE;
}

/*
 * linkat(AT_EMPTY_PATH): link the inode behind `sourceFd` into `parentFd/name` without ever
 * re-resolving a staged file name. A directory owner that swaps the staged name cannot substitute
 * its own bytes; only the inode the service wrote can be committed.
 *
 * Returns 0 on success and a negative errno on failure (EOPNOTSUPP when the kernel lacks it).
 */
JNIEXPORT jint JNICALL
Java_runtime_mobileagent_privileged_RootNativeSupervisorJni_nativeLinkAtEmptyPath(
    JNIEnv *env,
    jclass clazz,
    jint parent_fd,
    jint source_fd,
    jstring name
) {
    (void)clazz;
    if (name == NULL) return -EINVAL;
    const char *chars = (*env)->GetStringUTFChars(env, name, NULL);
    if (chars == NULL) return -EINVAL;
    int rc;
#ifdef AT_EMPTY_PATH
    rc = linkat((int)source_fd, "", (int)parent_fd, chars, AT_EMPTY_PATH);
#else
    rc = linkat((int)source_fd, "", (int)parent_fd, chars, 0x1000);
#endif
    int result = (rc == 0) ? 0 : -errno;
    (*env)->ReleaseStringUTFChars(env, name, chars);
    return (jint)result;
}

/*
 * unlinkat(AT_REMOVEDIR): removes the (empty) protected staging directory we created. Returns 0
 * on success and a negative errno on failure.
 */
JNIEXPORT jint JNICALL
Java_runtime_mobileagent_privileged_RootNativeSupervisorJni_nativeRemoveDirectoryAt(
    JNIEnv *env,
    jclass clazz,
    jint parent_fd,
    jstring name
) {
    (void)clazz;
    if (name == NULL) return -EINVAL;
    const char *chars = (*env)->GetStringUTFChars(env, name, NULL);
    if (chars == NULL) return -EINVAL;
    int rc = unlinkat((int)parent_fd, chars, AT_REMOVEDIR);
    int result = (rc == 0) ? 0 : -errno;
    (*env)->ReleaseStringUTFChars(env, name, chars);
    return (jint)result;
}

JNIEXPORT jlong JNICALL
Java_runtime_mobileagent_privileged_RootNativeSupervisorJni_nativeCreateCancelToken(
    JNIEnv *env,
    jclass clazz
) {
    (void)env;
    (void)clazz;
    int fds[2] = {-1, -1};
    if (pipe2(fds, O_CLOEXEC | O_NONBLOCK) != 0) {
        return (jlong)-1;
    }
    uint64_t packed = ((uint64_t)(uint32_t)fds[0] << 32) | (uint64_t)(uint32_t)fds[1];
    return (jlong)packed;
}

JNIEXPORT jboolean JNICALL
Java_runtime_mobileagent_privileged_RootNativeSupervisorJni_nativeSignalCancelToken(
    JNIEnv *env,
    jclass clazz,
    jint write_fd
) {
    (void)env;
    (void)clazz;
    if (write_fd < 0) return JNI_FALSE;
    char c = 'C';
    ssize_t n = write((int)write_fd, &c, 1);
    return (n == 1 || errno == EAGAIN || errno == EWOULDBLOCK) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_runtime_mobileagent_privileged_RootNativeSupervisorJni_nativeCloseCancelToken(
    JNIEnv *env,
    jclass clazz,
    jint read_fd,
    jint write_fd
) {
    (void)env;
    (void)clazz;
    int r = (int)read_fd;
    int w = (int)write_fd;
    close_quietly(&r);
    close_quietly(&w);
}

JNIEXPORT jobjectArray JNICALL
Java_runtime_mobileagent_privileged_RootNativeSupervisorJni_nativeRunSupervised(
    JNIEnv *env,
    jclass clazz,
    jstring command_obj,
    jstring cwd_obj,
    jobjectArray env_array,
    jlong timeout_ms,
    jlong grace_ms,
    jint max_output_bytes,
    jint cancel_read_fd,
    jlongArray out_meta
) {
    (void)clazz;
    if (command_obj == NULL || out_meta == NULL || (*env)->GetArrayLength(env, out_meta) < 11) {
        return NULL;
    }
    jlong meta[11];
    memset(meta, 0, sizeof(meta));

    if (!probe_kernel_support_internal()) {
        meta[0] = -1;
        (*env)->SetLongArrayRegion(env, out_meta, 0, 11, meta);
        return NULL;
    }

    const char *command_utf = (*env)->GetStringUTFChars(env, command_obj, NULL);
    if (command_utf == NULL) {
        meta[0] = -1;
        (*env)->SetLongArrayRegion(env, out_meta, 0, 11, meta);
        return NULL;
    }
    char *command_copy = strdup(command_utf);
    (*env)->ReleaseStringUTFChars(env, command_obj, command_utf);
    if (command_copy == NULL) {
        meta[0] = -1;
        (*env)->SetLongArrayRegion(env, out_meta, 0, 11, meta);
        return NULL;
    }

    char *cwd_copy = NULL;
    if (cwd_obj != NULL) {
        const char *cwd_utf = (*env)->GetStringUTFChars(env, cwd_obj, NULL);
        if (cwd_utf != NULL) {
            if (cwd_utf[0] != '\0') {
                cwd_copy = strdup(cwd_utf);
            }
            (*env)->ReleaseStringUTFChars(env, cwd_obj, cwd_utf);
        }
    }

    if (cwd_copy != NULL) {
        struct stat st;
        if (stat(cwd_copy, &st) != 0 || !S_ISDIR(st.st_mode) || access(cwd_copy, X_OK) != 0) {
            free(command_copy);
            free(cwd_copy);
            meta[0] = -2;
            (*env)->SetLongArrayRegion(env, out_meta, 0, 11, meta);
            return NULL;
        }
    }

    jsize env_count = (env_array != NULL) ? (*env)->GetArrayLength(env, env_array) : 0;
    if (env_count < 0 || env_count > 128) env_count = 0;
    char **envp = (char **)calloc((size_t)env_count + 1, sizeof(char *));
    if (envp == NULL) {
        free(command_copy);
        free(cwd_copy);
        meta[0] = -1;
        (*env)->SetLongArrayRegion(env, out_meta, 0, 11, meta);
        return NULL;
    }
    size_t env_idx = 0;
    for (jsize i = 0; i < env_count; i++) {
        jstring entry = (jstring)(*env)->GetObjectArrayElement(env, env_array, i);
        if (entry == NULL) continue;
        const char *entry_utf = (*env)->GetStringUTFChars(env, entry, NULL);
        if (entry_utf != NULL) {
            char *dup = strdup(entry_utf);
            if (dup != NULL) {
                envp[env_idx++] = dup;
            }
            (*env)->ReleaseStringUTFChars(env, entry, entry_utf);
        }
        (*env)->DeleteLocalRef(env, entry);
    }
    envp[env_idx] = NULL;

    int stdout_pipe[2] = {-1, -1};
    int stderr_pipe[2] = {-1, -1};
    int ctrl_pipe[2] = {-1, -1};
    int status_pipe[2] = {-1, -1};

    if (pipe2(stdout_pipe, O_CLOEXEC | O_NONBLOCK) != 0 ||
        pipe2(stderr_pipe, O_CLOEXEC | O_NONBLOCK) != 0 ||
        pipe2(ctrl_pipe, O_CLOEXEC | O_NONBLOCK) != 0 ||
        pipe2(status_pipe, O_CLOEXEC | O_NONBLOCK) != 0) {
        close_quietly(&stdout_pipe[0]);
        close_quietly(&stdout_pipe[1]);
        close_quietly(&stderr_pipe[0]);
        close_quietly(&stderr_pipe[1]);
        close_quietly(&ctrl_pipe[0]);
        close_quietly(&ctrl_pipe[1]);
        close_quietly(&status_pipe[0]);
        close_quietly(&status_pipe[1]);
        for (size_t i = 0; i < env_idx; i++) free(envp[i]);
        free(envp);
        free(command_copy);
        free(cwd_copy);
        meta[0] = -1;
        (*env)->SetLongArrayRegion(env, out_meta, 0, 11, meta);
        return NULL;
    }

    // Clear O_NONBLOCK on child stdout/stderr write ends so standard shell tools see normal blocking writes.
    fcntl(stdout_pipe[1], F_SETFL, 0);
    fcntl(stderr_pipe[1], F_SETFL, 0);

    pid_t parent_pid = getpid();
    mar_proc_stat_t parent_st;
    int64_t parent_start_ticks = 0;
    if (read_proc_stat(parent_pid, &parent_st) == 0) {
        parent_start_ticks = parent_st.start_ticks;
    }

    int64_t clamped_timeout = (timeout_ms < 50L) ? 50L : timeout_ms;
    int64_t clamped_grace = (grace_ms < 0L) ? 0L : ((grace_ms > 5000L) ? 5000L : grace_ms);
    size_t max_bytes = (max_output_bytes < 1024) ? 1024U :
                       ((max_output_bytes > 262144) ? 262144U : (size_t)max_output_bytes);

    pid_t sup_pid = fork();
    if (sup_pid < 0) {
        close_quietly(&stdout_pipe[0]);
        close_quietly(&stdout_pipe[1]);
        close_quietly(&stderr_pipe[0]);
        close_quietly(&stderr_pipe[1]);
        close_quietly(&ctrl_pipe[0]);
        close_quietly(&ctrl_pipe[1]);
        close_quietly(&status_pipe[0]);
        close_quietly(&status_pipe[1]);
        for (size_t i = 0; i < env_idx; i++) free(envp[i]);
        free(envp);
        free(command_copy);
        free(cwd_copy);
        meta[0] = -1;
        (*env)->SetLongArrayRegion(env, out_meta, 0, 11, meta);
        return NULL;
    }

    if (sup_pid == 0) {
        close_quietly(&stdout_pipe[0]);
        close_quietly(&stderr_pipe[0]);
        close_quietly(&ctrl_pipe[1]);
        close_quietly(&status_pipe[0]);
        run_supervisor_process(
            parent_pid,
            parent_start_ticks,
            command_copy,
            cwd_copy,
            envp,
            clamped_grace,
            stdout_pipe[1],
            stderr_pipe[1],
            ctrl_pipe[0],
            status_pipe[1]
        );
        _exit(127);
    }

    close_quietly(&stdout_pipe[1]);
    close_quietly(&stderr_pipe[1]);
    close_quietly(&ctrl_pipe[0]);
    close_quietly(&status_pipe[1]);

    for (size_t i = 0; i < env_idx; i++) free(envp[i]);
    free(envp);
    free(command_copy);
    free(cwd_copy);

    mar_byte_buf_t out_buf = {
        .data = (uint8_t *)malloc(max_bytes),
        .len = 0,
        .cap = max_bytes,
        .truncated = 0,
    };
    mar_byte_buf_t err_buf = {
        .data = (uint8_t *)malloc(max_bytes),
        .len = 0,
        .cap = max_bytes,
        .truncated = 0,
    };
    if (out_buf.data == NULL || err_buf.data == NULL) {
        out_buf.cap = 0;
        err_buf.cap = 0;
    }

    int64_t deadline_ms = now_monotonic_ms() + clamped_timeout;
    int64_t hard_kill_deadline_ms = 0;
    int ctrl_sent = 0;
    mar_supervisor_report_t report;
    memset(&report, 0, sizeof(report));
    size_t report_bytes = 0;

    while (status_pipe[0] >= 0) {
        int64_t now = now_monotonic_ms();
        if (!ctrl_sent && now >= deadline_ms) {
            char t = 'T';
            ssize_t w = write(ctrl_pipe[1], &t, 1);
            (void)w;
            ctrl_sent = 1;
            hard_kill_deadline_ms = now + clamped_grace + MAR_SETTLE_BUDGET_MS + 1000L;
        }
        if (ctrl_sent && hard_kill_deadline_ms > 0 && now >= hard_kill_deadline_ms) {
            kill(sup_pid, SIGKILL);
            break;
        }

        struct pollfd pfds[4];
        nfds_t nfds = 0;
        int out_idx = -1, err_idx = -1, st_idx = -1, cancel_idx = -1;

        if (stdout_pipe[0] >= 0) {
            out_idx = (int)nfds;
            pfds[nfds].fd = stdout_pipe[0];
            pfds[nfds].events = POLLIN;
            nfds++;
        }
        if (stderr_pipe[0] >= 0) {
            err_idx = (int)nfds;
            pfds[nfds].fd = stderr_pipe[0];
            pfds[nfds].events = POLLIN;
            nfds++;
        }
        if (status_pipe[0] >= 0) {
            st_idx = (int)nfds;
            pfds[nfds].fd = status_pipe[0];
            pfds[nfds].events = POLLIN;
            nfds++;
        }
        if (!ctrl_sent && cancel_read_fd >= 0) {
            cancel_idx = (int)nfds;
            pfds[nfds].fd = (int)cancel_read_fd;
            pfds[nfds].events = POLLIN;
            nfds++;
        }

        int64_t wait_ms = 50;
        if (!ctrl_sent) {
            int64_t rem = deadline_ms - now;
            if (rem < wait_ms) wait_ms = (rem > 0) ? rem : 1;
        }
        int pr = poll(pfds, nfds, (int)wait_ms);
        if (pr > 0) {
            if (out_idx >= 0 && (pfds[out_idx].revents & (POLLIN | POLLHUP | POLLERR))) {
                drain_pipe_nonblocking(&stdout_pipe[0], &out_buf);
            }
            if (err_idx >= 0 && (pfds[err_idx].revents & (POLLIN | POLLHUP | POLLERR))) {
                drain_pipe_nonblocking(&stderr_pipe[0], &err_buf);
            }
            if (cancel_idx >= 0 && (pfds[cancel_idx].revents & (POLLIN | POLLHUP | POLLERR))) {
                char cbuf[16];
                while (read((int)cancel_read_fd, cbuf, sizeof(cbuf)) > 0) {
                }
                if (!ctrl_sent) {
                    char c = 'C';
                    ssize_t w = write(ctrl_pipe[1], &c, 1);
                    (void)w;
                    ctrl_sent = 1;
                    hard_kill_deadline_ms = now_monotonic_ms() + clamped_grace + MAR_SETTLE_BUDGET_MS + 1000L;
                }
            }
            if (st_idx >= 0 && (pfds[st_idx].revents & (POLLIN | POLLHUP | POLLERR))) {
                while (report_bytes < sizeof(report)) {
                    ssize_t nr = read(
                        status_pipe[0],
                        ((uint8_t *)&report) + report_bytes,
                        sizeof(report) - report_bytes
                    );
                    if (nr > 0) {
                        report_bytes += (size_t)nr;
                        continue;
                    }
                    if (nr == 0) {
                        close_quietly(&status_pipe[0]);
                        break;
                    }
                    if (errno == EINTR) continue;
                    if (errno == EAGAIN || errno == EWOULDBLOCK) break;
                    close_quietly(&status_pipe[0]);
                    break;
                }
                if (report_bytes == sizeof(report)) {
                    close_quietly(&status_pipe[0]);
                }
            }
        }
    }

    drain_pipe_nonblocking(&stdout_pipe[0], &out_buf);
    drain_pipe_nonblocking(&stderr_pipe[0], &err_buf);
    close_quietly(&stdout_pipe[0]);
    close_quietly(&stderr_pipe[0]);
    close_quietly(&ctrl_pipe[1]);
    close_quietly(&status_pipe[0]);

    int sup_status = 0;
    while (waitpid(sup_pid, &sup_status, 0) < 0) {
        if (errno != EINTR) break;
    }

    if (report_bytes == sizeof(report)) {
        meta[0] = 0;
        meta[1] = report.leader_exited;
        meta[2] = report.exit_code;
        meta[3] = report.timed_out;
        meta[4] = report.cancelled;
        meta[5] = report.leaked_descendants;
        meta[6] = report.settled;
        meta[7] = out_buf.truncated ? 1 : 0;
        meta[8] = err_buf.truncated ? 1 : 0;
        meta[9] = ((int64_t)(uint32_t)report.cmd_pgid << 32) |
                  (int64_t)(uint32_t)report.unreaped_pid;
        meta[10] = report.unreaped_start_ticks;
    } else {
        meta[0] = -3;
        meta[1] = 0;
        meta[2] = -1;
        meta[3] = ctrl_sent ? 1 : 0;
        meta[4] = 0;
        meta[5] = 1;
        meta[6] = 0;
        meta[7] = out_buf.truncated ? 1 : 0;
        meta[8] = err_buf.truncated ? 1 : 0;
        meta[9] = 0;
        meta[10] = 0;
    }
    (*env)->SetLongArrayRegion(env, out_meta, 0, 11, meta);

    jclass byte_array_cls = (*env)->FindClass(env, "[B");
    if (byte_array_cls == NULL) {
        free(out_buf.data);
        free(err_buf.data);
        return NULL;
    }
    jobjectArray result = (*env)->NewObjectArray(env, 2, byte_array_cls, NULL);
    if (result == NULL) {
        free(out_buf.data);
        free(err_buf.data);
        return NULL;
    }
    jbyteArray j_out = (*env)->NewByteArray(env, (jsize)out_buf.len);
    jbyteArray j_err = (*env)->NewByteArray(env, (jsize)err_buf.len);
    if (j_out != NULL && out_buf.len > 0 && out_buf.data != NULL) {
        (*env)->SetByteArrayRegion(env, j_out, 0, (jsize)out_buf.len, (const jbyte *)out_buf.data);
    }
    if (j_err != NULL && err_buf.len > 0 && err_buf.data != NULL) {
        (*env)->SetByteArrayRegion(env, j_err, 0, (jsize)err_buf.len, (const jbyte *)err_buf.data);
    }
    if (j_out != NULL) (*env)->SetObjectArrayElement(env, result, 0, j_out);
    if (j_err != NULL) (*env)->SetObjectArrayElement(env, result, 1, j_err);

    free(out_buf.data);
    free(err_buf.data);
    return result;
}
