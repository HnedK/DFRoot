#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

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

    *soft_reboot    = pref_true(buf, "soft_reboot");
    *disable_modules = pref_true(buf, "disable_modules");

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
 * Shell mode: restart adbd as root so that 'adb shell' gives UID 0.
 *
 * Requires ADB debugging to be enabled on the device. After this call
 * returns, the user can connect via 'adb shell' and will land directly
 * as root without needing to type 'su'.
 */
static int launch_shell_mode(void)
{
    /* service.adb.root=1  →  adbd will fork a root shell for each client */
    char *argv_root[]    = { "/system/bin/setprop", "service.adb.root",  "1",    NULL };
    char *argv_persist[] = { "/system/bin/setprop", "persist.adb.root",  "1",    NULL };
    char *argv_restart[] = { "/system/bin/setprop", "ctl.restart",       "adbd", NULL };

    run(argv_root);
    run(argv_persist);
    run(argv_restart);

    /* Give adbd a moment to restart before we exit. */
    usleep(500000);

    touch("/dev/dfm6_shell");
    return 0;
}

int main(void)
{
    touch("/dev/dfm1");
    char su_manager[256];
    char run_mode[32];
    int soft_reboot, disable_mods;
    if (read_prefs(su_manager, sizeof(su_manager),
                   &soft_reboot, &disable_mods,
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
        return launch_shell_mode();
    }

    /* ── KernelSU mode (default) ───────────────────────────────────────── */
    if (disable_mods) {
        touch("/dev/dfm4");
        if (disable_modules()) {
            touch("/dev/dfme1");
            return 1;
        }
    }

    touch("/dev/dfm5");
    char **late_load;
    if (soft_reboot)
        late_load = (char *[]){ KSUD, "late-load", "--package-name", su_manager, "--soft-reboot", NULL };
    else
        late_load = (char *[]){ KSUD, "late-load", "--package-name", su_manager, NULL };
    if (run(late_load) == 0)
        touch("/dev/dfm6");
    else
        touch("/dev/dfme2");

    return 0;
}
