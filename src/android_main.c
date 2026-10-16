/*
 * android_main.c -- Android entry for the NFS Most Wanted recompilation.
 *
 * SDLActivity loads libSDL2.so and libmain.so and calls SDL_main on its
 * own thread. This file only does what a phone lacks compared to a desktop:
 *
 *   logs      stdout/stderr go to logcat (tag "nfsmw") through a pipe, so
 *             every printf of the runtime shows up in `adb logcat`
 *   settings  nfsmw_env.txt in the app's external files directory,
 *             KEY=VALUE per line, '#' comments -- an app has no shell
 *             environment; an explicit setting there wins over defaults
 *   paths     the game directory defaults to <external files>/game and the
 *             process runs from <external files> (nfsmw_options.txt, saves)
 *   stack     the game runs on a thread with a 16 MB stack, like a desktop
 *             main thread (recompiled code is stack hungry)
 */
#if defined(__ANDROID__)

#include <android/log.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <SDL.h>
#include <SDL_main.h>

#define NFSMW_TAG "nfsmw"

int game_main(void);   /* main.c */

#include <jni.h>

/* ------------------------------------------------------------------ */
/* Java side (GameActivity, the touch overlay)                         */

extern void xbox_InputSetVirtual(int buttons, int face, int lt, int rt, int lx, int ly);
extern void xbox_InputSetVirtualRight(int rx, int ry);
extern void xbox_InputSetRumbleSink(void (*sink)(int left, int right));
extern volatile float nv2a_vk_fps;

/* Rumble with no physical pad: GameActivity.rumble(left, right), 0..65535,
 * on the game thread (SDL attaches it to the VM). The class is taken from
 * the first nativeSetPad call: FindClass on a native thread cannot see app
 * classes. Only changes are sent; the game re-sends every frame. */
static jclass    s_act_cls;
static jmethodID s_rumble_mid;

static void rumble_sink(int left, int right)
{
    static int last_l = -1, last_r = -1;
    JNIEnv *env;
    if (!s_act_cls || (left == last_l && right == last_r))
        return;
    last_l = left;
    last_r = right;
    env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    if (env)
        (*env)->CallStaticVoidMethod(env, s_act_cls, s_rumble_mid, left, right);
}

JNIEXPORT void JNICALL
Java_com_ryuna_nfsmw2005x_GameActivity_nativeSetPad(JNIEnv *env, jclass cls, jint buttons, jint face,
                                            jint lt, jint rt, jint lx, jint ly)
{
    if (!s_act_cls) {
        s_rumble_mid = (*env)->GetStaticMethodID(env, cls, "rumble", "(II)V");
        if (s_rumble_mid) {
            s_act_cls = (jclass)(*env)->NewGlobalRef(env, cls);
            xbox_InputSetRumbleSink(rumble_sink);
        } else {
            (*env)->ExceptionClear(env);
        }
    }
    xbox_InputSetVirtual(buttons, face, lt, rt, lx, ly);
}

JNIEXPORT void JNICALL
Java_com_ryuna_nfsmw2005x_GameActivity_nativeSetPadRight(JNIEnv *env, jclass cls, jint rx, jint ry)
{
    (void)env; (void)cls;
    xbox_InputSetVirtualRight(rx, ry);
}

JNIEXPORT jfloat JNICALL
Java_com_ryuna_nfsmw2005x_GameActivity_nativeGetFps(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return nv2a_vk_fps;
}

/* ------------------------------------------------------------------ */
/* CPU clocks: Android's performance hint (ADPF)                        */
/*
 * The Mali race log showed the frame rate stepping 55 -> 35 -> 55 with no
 * shader compiles or uploads behind it: schedutil only raises a core's
 * clock after it has seen it busy, so every heavier stretch of track first
 * ran at the lower clock for a few frames. A performance hint session
 * tells the governor, per frame, how long the work took against a 16.7 ms
 * target, and it sets the clocks (and the big cores) ahead of time.
 *
 * APerformanceHint is API 33 (the Infinix runs 13); the app's minSdk is
 * 30, so it is looked up with dlsym and skipped where it is missing. The
 * session covers the game's guest thread and the pushbuffer executor
 * (registered by xbox_perf_hint_thread). NFSMW_PERF_HINT=0 turns it off.
 */
#include <dlfcn.h>
#include <sys/syscall.h>

typedef struct APerformanceHintManager APerformanceHintManager;
typedef struct APerformanceHintSession APerformanceHintSession;
static APerformanceHintManager *(*p_hint_get_manager)(void);
static APerformanceHintSession *(*p_hint_create)(APerformanceHintManager *, const int32_t *,
                                                 size_t, int64_t);
static int (*p_hint_report)(APerformanceHintSession *, int64_t);
static int (*p_hint_set_threads)(APerformanceHintSession *, const pid_t *, size_t);  /* API 34 */

static pthread_mutex_t s_hint_lock = PTHREAD_MUTEX_INITIALIZER;
static APerformanceHintSession *s_hint;
static int32_t s_hint_tids[4];
static size_t s_hint_ntids;
static int s_hint_state = -1;            /* -1 untried, 0 off, 1 on */
#define HINT_TARGET_NS 16666667LL

static int hint_init(void)
{
    const char *e = getenv("NFSMW_PERF_HINT");
    void *lib;
    if (s_hint_state >= 0)
        return s_hint_state;
    s_hint_state = 0;
    if (e && *e == '0')
        return 0;
    lib = dlopen("libandroid.so", RTLD_NOW);
    if (!lib)
        return 0;
    p_hint_get_manager = dlsym(lib, "APerformanceHint_getManager");
    p_hint_create = dlsym(lib, "APerformanceHint_createSession");
    p_hint_report = dlsym(lib, "APerformanceHint_reportActualWorkDuration");
    p_hint_set_threads = dlsym(lib, "APerformanceHint_setThreads");
    if (!p_hint_get_manager || !p_hint_create || !p_hint_report) {
        printf("[perf] no performance hint API (Android 13+); clocks left to the governor\n");
        return 0;
    }
    s_hint_state = 1;
    return 1;
}

/* A thread whose busy time sets the frame rate joins the session. */
void xbox_perf_hint_thread(void)
{
    APerformanceHintManager *m;
    int32_t tid = (int32_t)syscall(SYS_gettid);
    pthread_mutex_lock(&s_hint_lock);
    if (!hint_init() || s_hint_ntids == sizeof s_hint_tids / sizeof s_hint_tids[0]) {
        pthread_mutex_unlock(&s_hint_lock);
        return;
    }
    s_hint_tids[s_hint_ntids++] = tid;
    if (s_hint && p_hint_set_threads) {
        if (p_hint_set_threads(s_hint, (const pid_t *)s_hint_tids, s_hint_ntids) == 0)
            printf("[perf] performance hint: %zu threads\n", s_hint_ntids);
    } else if (!s_hint && (m = p_hint_get_manager()) != NULL) {
        s_hint = p_hint_create(m, s_hint_tids, s_hint_ntids, HINT_TARGET_NS);
        printf("[perf] performance hint session %s (target 16.7 ms)\n",
               s_hint ? "on" : "refused by the system");
    }
    pthread_mutex_unlock(&s_hint_lock);
}

/* Once a frame, from the renderer's flip: wall time since the last flip,
 * capped so a loading screen does not read as a 2-second frame. */
void xbox_perf_hint_frame(void)
{
    static struct timespec last;
    struct timespec now;
    int64_t ns;
    if (s_hint_state <= 0 || !s_hint)
        return;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (last.tv_sec) {
        ns = (int64_t)(now.tv_sec - last.tv_sec) * 1000000000LL + (now.tv_nsec - last.tv_nsec);
        if (ns > 0) {
            if (ns > 4 * HINT_TARGET_NS)
                ns = 4 * HINT_TARGET_NS;
            p_hint_report(s_hint, ns);
        }
    }
    last = now;
}

/* ------------------------------------------------------------------ */
/* stdout/stderr -> logcat                                            */

static int s_log_pipe[2];
static FILE *s_log_file;     /* nfsmw_log.txt when NFSMW_LOG_FILE=1 */

static void *log_thread(void *arg)
{
    char buf[1024];
    size_t used = 0;
    (void)arg;
    for (;;) {
        ssize_t n = read(s_log_pipe[0], buf + used, sizeof(buf) - 1 - used);
        if (n <= 0)
            break;
        used += (size_t)n;
        buf[used] = 0;
        /* One logcat line per text line; a line longer than the buffer
         * goes out in pieces. */
        char *start = buf, *nl;
        while ((nl = strchr(start, '\n')) != NULL) {
            *nl = 0;
            if (s_log_file) {
                struct timespec t;
                clock_gettime(CLOCK_MONOTONIC, &t);
                fprintf(s_log_file, "%5ld.%03ld %s\n", (long)t.tv_sec, t.tv_nsec / 1000000, start);
                fflush(s_log_file);
            }
            __android_log_write(ANDROID_LOG_INFO, NFSMW_TAG, start);
            start = nl + 1;
        }
        used = strlen(start);
        if (used == sizeof(buf) - 1) {
            __android_log_write(ANDROID_LOG_INFO, NFSMW_TAG, start);
            used = 0;
        } else {
            memmove(buf, start, used);
        }
    }
    return NULL;
}

static void redirect_stdio_to_logcat(void)
{
    pthread_t th;
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    if (pipe(s_log_pipe) != 0)
        return;
    dup2(s_log_pipe[1], STDOUT_FILENO);
    dup2(s_log_pipe[1], STDERR_FILENO);
    if (pthread_create(&th, NULL, log_thread, NULL) == 0)
        pthread_detach(th);
}

/* ------------------------------------------------------------------ */
/* nfsmw_env.txt                                                        */

static void load_env_file(const char *dir, const char *name)
{
    char path[512], line[512];
    FILE *f;
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    f = fopen(path, "r");
    if (!f)
        return;
    while (fgets(line, sizeof(line), f)) {
        char *s = line, *eq, *end;
        while (*s == ' ' || *s == '\t') s++;
        if (*s == '#' || *s == '\n' || *s == '\r' || !*s)
            continue;
        end = s + strcspn(s, "\r\n");
        *end = 0;
        eq = strchr(s, '=');
        if (!eq || eq == s)
            continue;
        *eq = 0;
        setenv(s, eq + 1, 1);
        printf("[env] %s=%s\n", s, eq + 1);
    }
    fclose(f);
}

/* ------------------------------------------------------------------ */

static void *game_thread(void *arg)
{
    *(int *)arg = game_main();
    return NULL;
}

int SDL_main(int argc, char **argv)
{
    pthread_attr_t attr;
    pthread_t th;
    const char *ext;
    char game_dir[512];
    int rc = 1;

    (void)argc;
    (void)argv;
    redirect_stdio_to_logcat();
    /* The renderer's window is resizable; without this SDLActivity asks
     * for FULL_USER and the game turns to portrait with the phone. */
    SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");

    ext = SDL_AndroidGetExternalStoragePath();
    if (!ext) {
        __android_log_print(ANDROID_LOG_ERROR, NFSMW_TAG,
                            "no external files directory: %s", SDL_GetError());
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "NFS Most Wanted",
                                 "The app's storage is not available.", NULL);
        return 1;
    }
    printf("[android] files: %s\n", ext);
    if (chdir(ext) != 0)
        printf("[android] chdir %s failed\n", ext);
    /* Launcher settings first, then the developer's nfsmw_env.txt over them. */
    load_env_file(ext, "nfsmw_launcher.txt");
    load_env_file(ext, "nfsmw_env.txt");
    {
        /* The whole log in a file too: logcat's ring is gone in minutes. */
        const char *e = getenv("NFSMW_LOG_FILE");
        if (e && *e == '1') {
            char path[600];
            snprintf(path, sizeof path, "%s/nfsmw_log.txt", ext);
            s_log_file = fopen(path, "w");
            if (s_log_file)
                printf("[android] logging to %s\n", path);
        }
    }

    snprintf(game_dir, sizeof(game_dir), "%s/game", ext);
    setenv("NFSU2_GAME_DIR", game_dir, 0);
    /* Android maps no RAM mirrors (as on the Switch): fewer large shared
     * mappings for the low-memory killer to weigh. */
    setenv("RECOMP_NO_MIRRORS", "1", 0);
    /* The Xbox's own 4:3 (EEPROM video flags without widescreen): the game
     * renders 4:3 and the present pillarboxes it at that aspect on the
     * phone's landscape screen, never stretched. */
    setenv("RECOMP_WIDESCREEN", "0", 0);

    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 16u * 1024 * 1024);
    if (pthread_create(&th, &attr, game_thread, &rc) == 0)
        pthread_join(th, NULL);
    else
        fprintf(stderr, "[FATAL] cannot start the game thread\n");
    pthread_attr_destroy(&attr);
    printf("[android] game exited with %d\n", rc);
    return rc;
}

#endif /* __ANDROID__ */
