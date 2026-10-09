#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <sys/mount.h>
#include <sys/utsname.h>

#define BLKROSET   0x125d
#define KSUD       "/data/user_de/0/com.hnedk.dfroot/ksud"
#define PREFS_PATH "/data/user_de/0/com.hnedk.dfroot/shared_prefs/dfroot.xml"
#define MODULES_DIR "/data/adb/modules"

static int pref_true(const char *buf, const char *key)
{
    char needle[64];
    snprintf(needle, sizeof(needle), "name=\"%s\"", key);
    char *p = strstr(buf, needle);
    if (!p) return 0;
    char *tag_end = strchr(p, '>');
    char *v = strstr(p, "value=\"true\"");
    return v && tag_end && v < tag_end;
}

static int pref_bool_default(const char *buf, const char *key, int def)
{
    char needle[64];
    snprintf(needle, sizeof(needle), "name=\"%s\"", key);
    char *p = strstr(buf, needle);
    if (!p) return def;
    char *tag_end = strchr(p, '>');
    if (strstr(p, "value=\"false\"") && tag_end && strstr(p, "value=\"false\"") < tag_end)
        return 0;
    if (strstr(p, "value=\"true\"") && tag_end && strstr(p, "value=\"true\"") < tag_end)
        return 1;
    return def;
}

/**
 * Read all relevant prefs from dfroot.xml.
 *
 * Extracts:
 *   su_manager    — package name of the KSU manager (for KernelSU mode)
 *   soft_reboot   — boolean flag
 *   disable_modules — boolean flag
 *   run_mode      — "ksu" (default) or "shell"
 */
static int read_prefs(char *su_manager, size_t su_manager_size,
                      int *soft_reboot, int *disable_modules,
                      int *auto_shizuku,
                      char *run_mode, size_t run_mode_size)
{
    int fd = open(PREFS_PATH, O_RDONLY);
    if (fd < 0) return -1;

    char buf[4096];
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';

    /* su_manager (string value) */
    char *p = strstr(buf, "name=\"su_manager\">");
    if (p) {
        p += strlen("name=\"su_manager\">");
        char *end = strchr(p, '<');
        if (!end) return -1;
        size_t len = end - p;
        if (len == 0 || len >= su_manager_size) return -1;
        memcpy(su_manager, p, len);
        su_manager[len] = '\0';
    } else {
        su_manager[0] = '\0';
    }

    *soft_reboot     = pref_true(buf, "soft_reboot");
    *disable_modules = pref_true(buf, "disable_modules");
    *auto_shizuku    = pref_bool_default(buf, "auto_start_shizuku", 1);

    /* run_mode (string value, default "ksu") */
    strncpy(run_mode, "ksu", run_mode_size - 1);
    run_mode[run_mode_size - 1] = '\0';
    char *m = strstr(buf, "name=\"run_mode\">");
    if (m) {
        m += strlen("name=\"run_mode\">");
        char *end = strchr(m, '<');
        if (end) {
            size_t len = end - m;
            if (len > 0 && len < run_mode_size) {
                memcpy(run_mode, m, len);
                run_mode[len] = '\0';
            }
        }
    }

    return 0;
}

static int adopt_zygote_env(void)
{
    FILE *f = popen("pidof zygote64 zygote", "r");
    if (!f) return -1;
    int pid = 0;
    fscanf(f, "%d", &pid);
    pclose(f);
    if (!pid) return -1;

    char path[32];
    snprintf(path, sizeof(path), "/proc/%d/environ", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    static char buf[16384];
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    for (char *p = buf, *end = buf + n; p < end; p += strlen(p) + 1)
        putenv(p);
    return 0;
}

static int should_ro(const char *name)
{
    size_t len = strlen(name);
    if (!strcmp(name, "super"))  return 1;
    if (!strcmp(name, "misc"))   return 1;
    if (!strcmp(name, "steady")) return 1;
    if (len >= 2 && name[len - 2] == '_' &&
        (name[len - 1] == 'a' || name[len - 1] == 'b'))
        return 1;
    return 0;
}

static int set_partitions_ro(void)
{
    DIR *dir = opendir("/dev/block/by-name");
    if (!dir)
        return -1;

    struct dirent *ent;
    while ((ent = readdir(dir))) {
        if (!should_ro(ent->d_name))
            continue;

        char path[128];
        snprintf(path, sizeof(path), "/dev/block/by-name/%s", ent->d_name);

        int fd = open(path, O_RDONLY);
        if (fd < 0)
            continue;

        struct stat st;
        if (fstat(fd, &st) == 0 && S_ISBLK(st.st_mode)) {
            int on = 1;
            ioctl(fd, BLKROSET, &on);
        }
        close(fd);
    }

    closedir(dir);
    return 0;
}

static int run(char *const argv[])
{
    pid_t pid = fork();
    if (pid < 0)
        return -1;
    if (pid == 0) {
        execv(argv[0], argv);
        _exit(127);
    }
    int status;
    waitpid(pid, &status, 0);
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

static int copy_file(const char *src, const char *dst)
{
    int sfd = open(src, O_RDONLY);
    if (sfd < 0) return -1;
    int dfd = open(dst, O_WRONLY | O_CREAT | O_TRUNC, 0755);
    if (dfd < 0) { close(sfd); return -1; }
    char buf[8192];
    ssize_t n;
    while ((n = read(sfd, buf, sizeof(buf))) > 0) {
        if (write(dfd, buf, n) != n) {
            close(sfd);
            close(dfd);
            return -1;
        }
    }
    close(sfd);
    close(dfd);
    chmod(dst, 0755);
    return 0;
}

static int is_ksu_active(void)
{
    if (access("/sys/module/kernelsu", F_OK) == 0) return 1;
    if (access("/data/adb/ksud", F_OK) == 0) return 1;
    if (access("/data/adb/ksu", F_OK) == 0) return 1;
    return 0;
}

static int run_ksud(char *argv[])
{
    /* 1. Stage ksud to /data/local/tmp/.ksud-stage (expected by ksud late-load logic) */
    copy_file(KSUD, "/data/local/tmp/.ksud-stage");

    /* 2. Samsung DEFEX bypass: bind-mount .ksud-stage over /system/bin/atrace */
    const char *stage = "/data/local/tmp/.ksud-stage";
    mount(stage, "/system/bin/atrace", NULL, MS_BIND, NULL);

    /* Use /system/bin/atrace to bypass Samsung DEFEX execution restrictions */
    const char *bin = (access("/system/bin/atrace", X_OK) == 0) ? "/system/bin/atrace"
                    : (access(stage, X_OK) == 0) ? stage : KSUD;
    argv[0] = (char *)bin;

    pid_t pid = fork();
    if (pid < 0)
        return -1;
    if (pid == 0) {
        int lfd = open("/data/local/tmp/ksud.log", O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (lfd >= 0) {
            dup2(lfd, 1);
            dup2(lfd, 2);
            close(lfd);
        }
        execv(argv[0], argv);
        _exit(127);
    }

    /* 3. Non-blocking monitor loop: poll up to 15 seconds (150 x 100ms) */
    for (int i = 0; i < 150; i++) {
        /* Pulse sys.boot_completed = 1 so ksud does not wait indefinitely */
        char *argv_bc[] = { "/system/bin/setprop", "sys.boot_completed", "1", NULL };
        run(argv_bc);

        /* Check if KernelSU is already active in kernel or filesystem */
        if (is_ksu_active()) {
            return 0;
        }

        int status;
        pid_t r = waitpid(pid, &status, WNOHANG);
        if (r == pid) {
            if (WIFEXITED(status) && WEXITSTATUS(status) == 0)
                return 0;
            if (is_ksu_active())
                return 0;
            return -1;
        }

        usleep(100000); /* 100 ms */
    }

    return is_ksu_active() ? 0 : -1;
}

static void touch(const char *path)
{
    int fd = open(path, O_CREAT | O_WRONLY, 0666);
    if (fd >= 0)
        close(fd);
}

/* Mark every installed module disabled before ksud runs. A broken module
 * otherwise loads on the next boot and bootloops the device. */
static int disable_modules(void)
{
    DIR *dir = opendir(MODULES_DIR);
    if (!dir)
        return -1;

    struct dirent *ent;
    while ((ent = readdir(dir))) {
        if (ent->d_name[0] == '.')
            continue;
        char path[256];
        snprintf(path, sizeof(path), MODULES_DIR "/%s/disable", ent->d_name);
        touch(path);
    }
    closedir(dir);
    return 0;
}

/**
 * Shell mode daemon: listens on 127.0.0.1:1337 and spawns /system/bin/sh as UID 0 (root).
 * When a client connects (via adb forward or adb shell nc), dup2 binds stdin/stdout/stderr
 * directly to the socket, granting an unrestricted root shell.
 */
static void run_root_shell_daemon(void)
{
    int sfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sfd < 0) return;

    int opt = 1;
    setsockopt(sfd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(1337);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK); /* 127.0.0.1 */

    if (bind(sfd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(sfd);
        return;
    }

    if (listen(sfd, 5) < 0) {
        close(sfd);
        return;
    }

    while (1) {
        int client_fd = accept(sfd, NULL, NULL);
        if (client_fd < 0) continue;

        pid_t p = fork();
        if (p == 0) {
            close(sfd);
            dup2(client_fd, 0);
            dup2(client_fd, 1);
            dup2(client_fd, 2);
            close(client_fd);

            /* Set environment */
            setenv("PATH", "/data/local/tmp:/system/bin:/system/xbin:/vendor/bin", 1);
            setenv("USER", "root", 1);
            setenv("HOME", "/data/local/tmp", 1);
            setenv("PS1", "FragSim:# ", 1);

            /* Spawn interactive root shell with PS1 prompt */
            char *const sh_argv[] = { "/system/bin/sh", "-i", NULL };
            execv("/system/bin/sh", sh_argv);
            _exit(127);
        }
        close(client_fd);
    }
}

static void setup_root_environment(int auto_shizuku, int is_shell_mode)
{
    /* Try adbd root restart in case device allows it */
    char *argv_root[]    = { "/system/bin/setprop", "service.adb.root",  "1",    NULL };
    char *argv_restart[] = { "/system/bin/setprop", "ctl.restart",       "adbd", NULL };
    run(argv_root);
    run(argv_restart);

    /* Deploy convenient /data/local/tmp/su wrapper that connects to the root daemon */
    int su_fd = open("/data/local/tmp/su", O_WRONLY | O_CREAT | O_TRUNC, 0777);
    if (su_fd >= 0) {
        const char script[] =
            "#!/system/bin/sh\n"
            "# DFRoot Shell Mode Connect Helper\n"
            "exec toybox nc 127.0.0.1 1337\n";
        write(su_fd, script, sizeof(script) - 1);
        close(su_fd);
        chmod("/data/local/tmp/su", 0777);
    }

    /* Bind mount /data/local/tmp/su directly to system PATH locations in shell mode */
    if (is_shell_mode) {
        mount("/data/local/tmp/su", "/system/bin/su", NULL, MS_BIND, NULL);
        mount("/data/local/tmp/su", "/system/xbin/su", NULL, MS_BIND, NULL);
        mount("/data/local/tmp/su", "/bin/su", NULL, MS_BIND, NULL);
        mount("/data/local/tmp/su", "/apex/com.android.runtime/bin/su", NULL, MS_BIND, NULL);
    }

    /* Auto-start Shizuku if enabled (runs in background as user shell UID 2000) */
    if (auto_shizuku) {
        const char *shizuku_candidates[] = {
            "/data/local/tmp/shizuku_starter",
            "/sdcard/Android/data/moe.shizuku.privileged.api/start.sh",
            "/sdcard/Android/data/moe.shizuku.privileged.api/files/start.sh",
            "/data/user_de/0/moe.shizuku.privileged.api/files/start.sh",
            "/data/data/moe.shizuku.privileged.api/files/start.sh",
            NULL
        };
        const char *found_shizuku = NULL;
        for (int i = 0; shizuku_candidates[i]; i++) {
            if (access(shizuku_candidates[i], F_OK) == 0) {
                found_shizuku = shizuku_candidates[i];
                break;
            }
        }
        if (found_shizuku) {
            pid_t sp = fork();
            if (sp == 0) {
                setsid();
                setgid(2000); /* gid: shell */
                setuid(2000); /* uid: shell */
                char *const argv_shizuku[] = { "/system/bin/sh", (char *)found_shizuku, NULL };
                execv("/system/bin/sh", argv_shizuku);
                _exit(127);
            }
        }
    }

    if (is_shell_mode) {
        touch("/dev/dfm6_shell");
    }

    /* Fork background root daemon */
    pid_t pid = fork();
    if (pid == 0) {
        /* Child daemon */
        setsid();
        run_root_shell_daemon();
        _exit(0);
    }
}

static int launch_shell_mode(int auto_shizuku)
{
    setup_root_environment(auto_shizuku, 1);
    return 0;
}

int main(void)
{
    touch("/dev/dfm1");
    char su_manager[256];
    char run_mode[32];
    int soft_reboot, disable_mods, auto_shizuku;
    if (read_prefs(su_manager, sizeof(su_manager),
                   &soft_reboot, &disable_mods, &auto_shizuku,
                   run_mode, sizeof(run_mode)) != 0) {
        touch("/dev/dfme0");
        return 1;
    }

    touch("/dev/dfm2");
    if (adopt_zygote_env())
        touch("/dev/dfmw0");

    touch("/dev/dfm3");
    if (set_partitions_ro())
        touch("/dev/dfmw1");

    /* ── Shell mode ────────────────────────────────────────────────────── */
    if (!strcmp(run_mode, "shell")) {
        touch("/dev/dfm5");
        return launch_shell_mode(auto_shizuku);
    }

    /* ── KernelSU mode (default) ───────────────────────────────────────── */
    /* Ensure root daemon, in-app terminal, and su wrapper are always active */
    setup_root_environment(auto_shizuku, 0);

    if (disable_mods) {
        touch("/dev/dfm4");
        if (disable_modules()) {
            touch("/dev/dfme1");
            return 1;
        }
    }

    touch("/dev/dfm5");

    /* Auto-detect KMI from kernel release so ksud does not need to parse boot partition */
    struct utsname u;
    char kmi[64] = "android15-6.6";
    if (uname(&u) == 0) {
        if (strstr(u.release, "6.6")) strcpy(kmi, "android15-6.6");
        else if (strstr(u.release, "6.1")) strcpy(kmi, "android14-6.1");
        else if (strstr(u.release, "5.15")) strcpy(kmi, "android14-5.15");
        else if (strstr(u.release, "5.10")) strcpy(kmi, "android12-5.10");
    }

    char *late_load[16];
    int k_idx = 0;
    late_load[k_idx++] = (char *)KSUD;
    late_load[k_idx++] = "late-load";
    late_load[k_idx++] = "--kmi";
    late_load[k_idx++] = kmi;
    late_load[k_idx++] = "--allow-shell";
    if (su_manager[0] != '\0') {
        late_load[k_idx++] = "--package-name";
        late_load[k_idx++] = su_manager;
    }
    if (soft_reboot) {
        late_load[k_idx++] = "--soft-reboot";
    }
    late_load[k_idx] = NULL;

    if (run_ksud(late_load) == 0)
        touch("/dev/dfm6");
    else
        touch("/dev/dfme2");

    return 0;
}
