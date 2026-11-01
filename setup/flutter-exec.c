// flutter-exec.c: a glibc LD_PRELOAD library so the Flutter tool (Dart, a glibc program) can start
// "#!/usr/bin/env bash" scripts such as a project's android/gradlew (decision 100, docs/flutter-apk-spike.md).
//
// Termux's termux-exec-glibc replaces execve() and maps /usr/bin/... and /bin/... interpreters into the prefix.
// But glibc's execvp() and execv() call glibc's internal __execve, not the exported execve, so a preload never sees
// them, and Dart starts every process with execvp. This library defines execvp, execvpe and execv on top of the
// exported execve, which resolves to termux-exec-glibc's (loaded before this one in LD_PRELOAD).
//
// No glibc headers and no libc at link time: execve, getenv, environ and __errno_location bind at load time to the
// glibc already in the process. The Flutter tool's install builds it with the C and C++ add-on's clang
// (pocketrocket-setup.sh, flutter_exec_build):
//
//   clang --target=aarch64-linux-gnu -O2 -fPIC -shared -nostdlib -fno-stack-protector -o <lib>.so flutter-exec.c
typedef unsigned long size_t;
extern char **environ;
extern int execve(const char *path, char *const argv[], char *const envp[]);
extern char *getenv(const char *name);
extern int *__errno_location(void);
#define ENOENT 2
#define EACCES 13
#define ENOEXEC 8

static size_t len(const char *s) { size_t n = 0; while (s[n]) n++; return n; }
static int has_slash(const char *s) { for (; *s; s++) if (*s == '/') return 1; return 0; }

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (!file || !*file) { *__errno_location() = ENOENT; return -1; }
    if (has_slash(file)) return execve(file, argv, envp);
    const char *path = getenv("PATH");
    if (!path) path = "/data/data/app.pocket/files/usr/bin";
    size_t flen = len(file);
    char buf[4096];
    int saw_eacces = 0;
    for (const char *p = path;; ) {
        const char *e = p; while (*e && *e != ':') e++;
        size_t dlen = (size_t)(e - p);
        if (dlen + 1 + flen + 1 <= sizeof buf) {
            size_t i = 0;
            if (dlen == 0) buf[i++] = '.'; else for (size_t k = 0; k < dlen; k++) buf[i++] = p[k];
            buf[i++] = '/';
            for (size_t k = 0; k <= flen; k++) buf[i++] = file[k];
            execve(buf, argv, envp);
            if (*__errno_location() == EACCES) saw_eacces = 1;
        }
        if (!*e) break;
        p = e + 1;
    }
    if (saw_eacces) *__errno_location() = EACCES;
    return -1;
}
int execvp(const char *file, char *const argv[]) { return execvpe(file, argv, environ); }
int execv(const char *path, char *const argv[]) { return execve(path, argv, environ); }
