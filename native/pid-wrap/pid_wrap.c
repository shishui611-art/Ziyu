/* SPDX-License-Identifier: Apache-2.0
 * PID wrap integration inspired by youfeng11/soft_restart_fix v1.2.2.
 * This bounded implementation does not alter existing process parentage or
 * seccomp, and never falls back to an unbounded clone loop.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static long read_max(int fd) {
    char b[64];
    ssize_t n = pread(fd, b, sizeof(b)-1, 0);
    if (n <= 0) return -1;
    b[n] = 0;
    char *end;
    long v = strtol(b, &end, 10);
    return end == b ? -1 : v;
}
static int write_max(int fd, long value) {
    char b[32];
    int n = snprintf(b, sizeof(b), "%ld\n", value);
    return pwrite(fd, b, (size_t)n, 0) == n && read_max(fd) == value ? 0 : -1;
}
static long long milliseconds(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t)) return -1;
    return (long long)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
static pid_t probe(void) {
    pid_t p = fork();
    if (p == 0) _exit(0);
    if (p < 0) return -1;
    int status;
    pid_t w;
    do { w = waitpid(p, &status, 0); } while (w < 0 && errno == EINTR);
    return w == p ? p : -1;
}
static int same_namespace(const char *self_path, const char *init_path) {
    struct stat self, init;
    return stat(self_path, &self) == 0 && stat(init_path, &init) == 0 &&
        self.st_ino == init.st_ino && self.st_dev == init.st_dev;
}
static int pid_namespace_matches_or_unsupported(void) {
    struct stat self, init;
    errno = 0;
    int self_result = stat("/proc/self/ns/pid", &self);
    int self_error = errno;
    errno = 0;
    int init_result = stat("/proc/1/ns/pid", &init);
    int init_error = errno;
    if (self_result == 0 && init_result == 0)
        return self.st_ino == init.st_ino && self.st_dev == init.st_dev;
    /* Android kernels built without CONFIG_PID_NS expose neither symlink.
     * If exactly one is missing, or another error occurred, fail closed. */
    return self_result != 0 && init_result != 0 &&
        self_error == ENOENT && init_error == ENOENT;
}
static int quiescent(void) {
    /* The shell proves KernelSU stopped Android. Keep the init mount namespace;
     * require matching PID namespace handles when this kernel supports them. */
    return same_namespace("/proc/self/ns/mnt", "/proc/1/ns/mnt") &&
        pid_namespace_matches_or_unsupported();
}
int main(int argc, char **argv) {
    if (argc != 2 || strcmp(argv[1], "--managed-reload") || geteuid() != 0 || !quiescent()) {
        fprintf(stderr, "pid-wrap: managed init-namespace reload required\n");
        return 2;
    }
    int lock = open("/data/adb/ziyu/pid-wrap.lock", O_CREAT|O_RDWR|O_CLOEXEC|O_NOFOLLOW, 0600);
    struct stat ls;
    if (lock < 0 || fstat(lock, &ls) || !S_ISREG(ls.st_mode) || ls.st_uid != 0 ||
        (ls.st_mode & 0022) || flock(lock, LOCK_EX|LOCK_NB)) return 3;
    signal(SIGCHLD, SIG_DFL);
    pid_t first = probe();
    if (first < 0) return 4;
    if (first < 1280) {
        printf("state=already-low\nobserved_pid=%d\n", first);
        return 0;
    }
    int fd = open("/proc/sys/kernel/pid_max", O_RDWR|O_CLOEXEC);
    if (fd < 0) { perror("pid_max"); return 5; }
    long original = read_max(fd);
    if (original < 2048 || original > 4194304) return 5;
    int channel[2];
    if (pipe2(channel, O_CLOEXEC)) return 6;
    /* Independent recovery survives parent SIGKILL. Pipe EOF also restores the
     * value; no claim that any userspace guard survives a kernel/power failure. */
    pid_t watchdog = fork();
    if (watchdog < 0) return 6;
    if (watchdog == 0) {
        close(channel[1]);
        struct pollfd p = {channel[0], POLLIN, 0};
        int ready;
        do { ready = poll(&p, 1, 1000); } while (ready < 0 && errno == EINTR);
        char ack = 0;
        if (ready <= 0 || read(channel[0], &ack, 1) != 1 || ack != 'R') {
            _exit(write_max(fd, original) == 0 ? 0 : 12);
        }
        _exit(0);
    }
    close(channel[0]);
    sigset_t blocked, old;
    sigfillset(&blocked);
    if (sigprocmask(SIG_BLOCK, &blocked, &old)) { close(channel[1]); waitpid(watchdog, NULL, 0); return 7; }
    pid_t last = probe(), final = last;
    long temporary = last > 1984 ? (long)last + 64 : 2048;
    int changed = 0, wrapped = last > 0 && last < 1280;
    long long start = milliseconds();
    if (last > 0 && !wrapped && start >= 0 && temporary < original && write_max(fd, temporary) == 0) {
        changed = 1;
        for (int n = 0; n < 256 && milliseconds() - start < 200; ++n) {
            pid_t next = probe();
            if (next < 0) break;
            final = next;
            if (next < last) { wrapped = next < 1280; break; }
            last = next;
        }
    }
    int restored = write_max(fd, original) == 0;
    if (restored) { char ack = 'R'; (void)write(channel[1], &ack, 1); }
    close(channel[1]);
    int status = 0;
    pid_t waited;
    do { waited = waitpid(watchdog, &status, 0); } while (waited < 0 && errno == EINTR);
    restored = read_max(fd) == original && waited == watchdog && WIFEXITED(status) && WEXITSTATUS(status) == 0;
    sigprocmask(SIG_SETMASK, &old, NULL);
    printf("state=%s\ninitial_pid=%d\nobserved_pid=%d\npid_max=%ld\nrestored=%d\nchanged=%d\n",
        !restored ? "restore-failed" : wrapped ? "wrapped" : "unavailable", first, final, original, restored, changed);
    return !restored ? 12 : wrapped ? 0 : 8;
}
