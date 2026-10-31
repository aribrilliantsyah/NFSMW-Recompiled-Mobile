// Linux stand in for native/main.cpp, for benchmarking on a PC: an SDL3 window of MW_WIDTH x MW_HEIGHT (640x480),
// settings and saves in the working directory, the log to stderr and logs/, and port 0 from a virtual SDL gamepad
// driven by a live script (MW_PAD_LIVE, the grammar of nfsw-recompiled-mobile's RECOMP_PAD_LIVE):
//   [&]button[+button...]:hold_ms   queued one after another 250 ms apart; '&' starts at once, beside the queue
//   buttons: up down left right start back lthumb rthumb a b x y black white lt rt lsl lsr lsu lsd
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include <imgui.h>
#include <toml++/toml.hpp>

#include "app/app.h"
#include "build_info.h"
#include "common/aspect.h"
#include "common/settings_lock.h"
#include "common/settings_menu.h"
#include "core/input.h"
#include "core/log.h"
#include "host.h"
#include "image_symbols.h"
#include "kernel/kernel.h"

namespace {

SDL_Window* g_window = nullptr;

std::string Utf8(const wchar_t* w) {
    std::string s;
    for (; w && *w; w++) s += *w < 0x80 ? char(*w) : '?';
    return s;
}

int EnvInt(const char* name, int fallback) {
    const char* v = std::getenv(name);
    return v && *v ? std::atoi(v) : fallback;
}

// ---- the scripted pad: a virtual SDL gamepad, read by native/input.cpp as any controller
SDL_Joystick* g_vpad = nullptr;
using Clock = std::chrono::steady_clock;
struct Step {
    std::vector<std::string> buttons;
    Clock::time_point start, end;
};
std::vector<Step> g_steps;
Clock::time_point g_queue_end;
FILE* g_live = nullptr;

void OpenPad() {
    SDL_VirtualJoystickDesc d;
    SDL_INIT_INTERFACE(&d);
    d.type = SDL_JOYSTICK_TYPE_GAMEPAD;
    d.naxes = SDL_GAMEPAD_AXIS_COUNT;
    d.nbuttons = SDL_GAMEPAD_BUTTON_COUNT;
    d.name = "bench pad";
    SDL_JoystickID id = SDL_AttachVirtualJoystick(&d);
    g_vpad = id ? SDL_OpenJoystick(id) : nullptr;
    if (!g_vpad) LOGE("bench: no virtual pad: %s", SDL_GetError());
    if (const char* p = std::getenv("MW_PAD_LIVE")) {
        g_live = std::fopen(p, "r");
        LOGR("bench: live pad script %s%s", p, g_live ? "" : " (cannot open)");
    }
    g_queue_end = Clock::now();
}

void ReadLive() {
    if (!g_live) return;
    char line[160];
    while (std::fgets(line, sizeof line, g_live)) {
        std::string s(line);
        while (!s.empty() && (s.back() == '\n' || s.back() == '\r' || s.back() == ' ')) s.pop_back();
        if (s.empty()) continue;
        auto now = Clock::now();
        if (s.rfind("settings", 0) == 0) {  // the overlay's ☰: "settings" the categories, "settings N" one
            game::RequestOpenSettings(s.size() > 9 ? std::atoi(s.c_str() + 9) : -1);
            continue;
        }
        if (s.rfind("clear", 0) == 0) {
            g_steps.erase(std::remove_if(g_steps.begin(), g_steps.end(), [&](const Step& st) { return st.start > now; }),
                          g_steps.end());
            g_queue_end = now;
            continue;
        }
        bool overlap = s[0] == '&';
        if (overlap) s.erase(0, 1);
        size_t colon = s.find(':');
        if (colon == std::string::npos) continue;
        auto hold = std::chrono::milliseconds(std::atol(s.c_str() + colon + 1));
        Step st;
        std::stringstream names(s.substr(0, colon));
        for (std::string b; std::getline(names, b, '+');) st.buttons.push_back(b);
        if (overlap) {
            st.start = now;
        } else {
            st.start = std::max(now, g_queue_end);
            g_queue_end = st.start + hold + std::chrono::milliseconds(250);
        }
        st.end = st.start + hold;
        g_steps.push_back(st);
    }
    std::clearerr(g_live);
}

void UpdatePad() {
    if (!g_vpad) return;
    ReadLive();
    auto now = Clock::now();
    bool btn[SDL_GAMEPAD_BUTTON_COUNT] = {};
    int16_t ax[SDL_GAMEPAD_AXIS_COUNT] = {};
    for (const Step& st : g_steps) {
        if (now < st.start || now >= st.end) continue;
        for (const std::string& b : st.buttons) {
            if (b == "up") btn[SDL_GAMEPAD_BUTTON_DPAD_UP] = true;
            else if (b == "down") btn[SDL_GAMEPAD_BUTTON_DPAD_DOWN] = true;
            else if (b == "left") btn[SDL_GAMEPAD_BUTTON_DPAD_LEFT] = true;
            else if (b == "right") btn[SDL_GAMEPAD_BUTTON_DPAD_RIGHT] = true;
            else if (b == "start") btn[SDL_GAMEPAD_BUTTON_START] = true;
            else if (b == "back") btn[SDL_GAMEPAD_BUTTON_BACK] = true;
            else if (b == "lthumb") btn[SDL_GAMEPAD_BUTTON_LEFT_STICK] = true;
            else if (b == "rthumb") btn[SDL_GAMEPAD_BUTTON_RIGHT_STICK] = true;
            else if (b == "a") btn[SDL_GAMEPAD_BUTTON_SOUTH] = true;
            else if (b == "b") btn[SDL_GAMEPAD_BUTTON_EAST] = true;
            else if (b == "x") btn[SDL_GAMEPAD_BUTTON_WEST] = true;
            else if (b == "y") btn[SDL_GAMEPAD_BUTTON_NORTH] = true;
            // native/input.cpp's default mapping (the launcher's): the triggers are the game's LT / RT, the shoulders
            // its Black / White
            else if (b == "lt") ax[SDL_GAMEPAD_AXIS_LEFT_TRIGGER] = SDL_JOYSTICK_AXIS_MAX;
            else if (b == "rt") ax[SDL_GAMEPAD_AXIS_RIGHT_TRIGGER] = SDL_JOYSTICK_AXIS_MAX;
            else if (b == "black") btn[SDL_GAMEPAD_BUTTON_LEFT_SHOULDER] = true;
            else if (b == "white") btn[SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER] = true;
            else if (b == "lsl") ax[SDL_GAMEPAD_AXIS_LEFTX] = -SDL_JOYSTICK_AXIS_MAX;
            else if (b == "lsr") ax[SDL_GAMEPAD_AXIS_LEFTX] = SDL_JOYSTICK_AXIS_MAX;
            else if (b == "lsu") ax[SDL_GAMEPAD_AXIS_LEFTY] = -SDL_JOYSTICK_AXIS_MAX;
            else if (b == "lsd") ax[SDL_GAMEPAD_AXIS_LEFTY] = SDL_JOYSTICK_AXIS_MAX;
        }
    }
    g_steps.erase(std::remove_if(g_steps.begin(), g_steps.end(), [&](const Step& st) { return now >= st.end; }),
                  g_steps.end());
    for (int i = 0; i < SDL_GAMEPAD_BUTTON_COUNT; i++) SDL_SetJoystickVirtualButton(g_vpad, i, btn[i]);
    for (int i = 0; i < SDL_GAMEPAD_AXIS_COUNT; i++) SDL_SetJoystickVirtualAxis(g_vpad, i, ax[i]);
}

}  // namespace

namespace host {

SDL_Window* Window() { return g_window; }
bool Foreground() { return true; }
uint32_t SurfaceEpoch() { return 0; }
void WaitForeground() {}

void WindowPixels(int& w, int& h) {
    w = h = 0;
    if (g_window) SDL_GetWindowSizeInPixels(g_window, &w, &h);
}

}  // namespace host

namespace app {

namespace {

const char* kModes[] = {"fullscreen", "borderless", "windowed"};
constexpr int kDefaults = 3;
// the save slot's folder (save_dir in the settings file, as the launcher writes it on the phone); empty: hdd here
std::string g_save_dir;

std::filesystem::path File() { return ExeDir() / kGame.settings; }

toml::table Table() {
    const Settings& s = Get();
    toml::table t{{"display_mode", kModes[uint32_t(s.display_mode)]},
                  {"width", int64_t(s.width)},
                  {"height", int64_t(s.height)},
                  {"render_percent", int64_t(s.render_percent)},
                  {"fps_cap", int64_t(s.fps_cap)},
                  {"vsync", s.vsync},
                  {"anisotropic_filtering", int64_t(s.aniso)},
                  {"join_ip", s.join_ip},
                  {"defaults", int64_t(kDefaults)}};
    for (const Option& o : kGame.options) {
        if (o.flag) t.insert(o.key, *o.flag);
        else t.insert(o.key, int64_t(*o.value));
    }
    if (kGame.save) kGame.save(&t);
    return t;
}

}  // namespace

// a bench run: the console's frame at 1x, no cap, no vsync unless the settings file says otherwise
Settings DefaultSettings() {
    Settings s;
    s.display_mode = DisplayMode::Windowed;
    s.render_percent = 100;
    s.fps_cap = 0;
    s.vsync = false;
    return s;
}

Settings& Get() {
    static Settings s = DefaultSettings();
    return s;
}

void Load() {
    Settings& s = Get();
    toml::table t;
    try {
        t = toml::parse_file(File().string());
    } catch (const toml::parse_error&) {
        // defaults, the broken file kept beside for the player
        std::error_code ec;
        std::filesystem::copy_file(File(), File().string() + ".bad", std::filesystem::copy_options::overwrite_existing,
                                   ec);
        return;
    } catch (...) {
        return;
    }
    s.render_percent = t["render_percent"].value_or(s.render_percent);
    s.fps_cap = t["fps_cap"].value_or(s.fps_cap);
    s.vsync = t["vsync"].value_or(s.vsync);
    s.aniso = t["anisotropic_filtering"].value_or(s.aniso);
    g_save_dir = t["save_dir"].value_or(std::string());
    for (const Option& o : kGame.options) {
        if (o.flag) *o.flag = t[o.key].value_or(*o.flag);
        else *o.value = t[o.key].value_or(*o.value);
    }
    if (kGame.load) kGame.load(&t);
}

// the hard disk kernel::Boot mounts, as native/main.cpp picks it
std::filesystem::path SaveRoot() {
    std::error_code ec;
    if (!g_save_dir.empty() && std::filesystem::is_directory(g_save_dir, ec)) return g_save_dir;
    if (!g_save_dir.empty()) LOGW("app: save_dir %s is not a folder, using hdd", g_save_dir.c_str());
    return ExeDir() / "hdd";
}

// our values into the file's tables, leaf by leaf: a table we write ([keys]) keeps the entries we do not know
static void Merge(toml::table& into, const toml::table& from) {
    for (auto&& [key, value] : from) {
        toml::table* have = into[key].as_table();
        if (have && value.is_table()) Merge(*have, *value.as_table());
        else into.insert_or_assign(key, value);
    }
}

// the file is the launcher's too: our keys over what it holds ([padmap], save_dir, the overlay's, keys we do not know),
// through a temporary file so a crash never leaves half of it. One broken since Load is kept as .bad first (as Load
// does), and left alone if it cannot be
void Save() {
    game::SettingsLock lock(File());
    if (!lock.ok()) return;  // another editor may be writing: no write over it
    toml::table t;
    std::error_code ec;
    // a file we cannot even look at may still be there: not a missing file to write fresh
    bool exists = std::filesystem::exists(File(), ec);
    if (ec) return;
    if (exists) {
        try {
            t = toml::parse_file(File().string());
        } catch (const toml::parse_error&) {
            std::filesystem::copy_file(File(), File().string() + ".bad",
                                       std::filesystem::copy_options::overwrite_existing, ec);
            if (ec) return;
        }
    }
    Merge(t, Table());
    std::filesystem::path tmp = File().string() + ".tmp";
    {
        std::ofstream f(tmp);
        f << t << "\n";
        if (!f) return;
    }
    std::filesystem::rename(tmp, File(), ec);
}

void LogSettings() {
    std::ostringstream text;
    text << Table();
    std::istringstream lines(text.str());
    LOGR("Settings:");
    for (std::string line; std::getline(lines, line);)
        if (!line.empty()) LOGR("  %s", line.c_str());
}

void LogSystem() {
    std::time_t now = std::time(nullptr);
    char date[32] = {};
    std::strftime(date, sizeof(date), "%Y-%m-%d %H:%M:%S", std::localtime(&now));
    LOGR("%ls (%s), %s, build %s (Linux bench host)", kGame.title, IMAGE_XBE, date, BUILD_COMMIT);
    LOGR("Path: %s", ExeDir().c_str());
    LOGR("CPU: %d threads, RAM %.1f GB", SDL_GetNumLogicalCPUCores(), SDL_GetSystemRAM() / 1024.0);
    Mode m = Desktop();
    LOGR("Window: %ux%u", m.width, m.height);
    LogSettings();
}

Mode Desktop() {
    if (g_window) {
        int w = 0, h = 0;
        host::WindowPixels(w, h);
        if (w > 0 && h > 0) return {uint32_t(w), uint32_t(h)};
    }
    return {uint32_t(EnvInt("MW_WIDTH", 640)), uint32_t(EnvInt("MW_HEIGHT", 480))};
}

Mode Output() { return Desktop(); }

bool Widescreen() { return game::Aspect() >= 1.5f; }

float RenderScale(uint32_t guest_height) {
    float h = Output().height * (Get().render_percent / 100.0f);
    if (game::Opt().anti_aliasing == game::kAaSsaa) h *= 2.0f;
    return guest_height ? std::max(h / guest_height, 0.1f) : 1.0f;
}

std::filesystem::path ExeDir() {
    static const std::filesystem::path dir = std::filesystem::current_path();
    return dir;
}

int Instance() { return 0; }
void* Window() { return g_window; }
void SplashFirstFrame() {}
bool HandingOver() { return false; }
void FinishHandover() {}

std::recursive_mutex& UiLock() {
    static std::recursive_mutex m;
    return m;
}

void Pace() {
    using clock = std::chrono::steady_clock;
    static clock::time_point next = clock::now();
    uint32_t cap = Get().fps_cap;
    auto now = clock::now();
    if (!cap) {
        next = now;
        return;
    }
    auto period = std::chrono::nanoseconds(1000000000ull / cap);
    next += period;
    if (next < now - period) next = now;
    while (clock::now() + std::chrono::milliseconds(2) < next) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    while (clock::now() < next) std::this_thread::yield();
}

}  // namespace app

namespace {

void InitUi() {
    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;
    io.BackendPlatformName = "linux_sdl3";
}

void Dispatch(const SDL_Event& e) {
    switch (e.type) {
        case SDL_EVENT_QUIT:
        case SDL_EVENT_TERMINATING:
            logx::Flush();
            kernel::Shutdown(0);
            break;
        case SDL_EVENT_GAMEPAD_ADDED:
        case SDL_EVENT_GAMEPAD_REMOVED:
            input::OnPadEvent(e);
            break;
        default:
            break;
    }
    touch::OnEvent(e);
}

}  // namespace

int main(int, char**) {
    SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
    // MW_MOUSE_TOUCH=1: the mouse is a finger (SDL's synthetic touches), as the phone's screen reaches the menus' taps,
    // swipes and drags (native/touch.cpp)
    const char* mouse_touch = std::getenv("MW_MOUSE_TOUCH");
    SDL_SetHint(SDL_HINT_MOUSE_TOUCH_EVENTS, mouse_touch && *mouse_touch == '1' ? "1" : "0");
    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS | SDL_INIT_GAMEPAD | SDL_INIT_AUDIO)) {
        std::fprintf(stderr, "SDL_Init: %s\n", SDL_GetError());
        return 1;
    }
    logx::Init((app::ExeDir() / "logs").string(), app::kGame.log);
    LOGR("app: log %s", logx::Path().c_str());
    app::Load();
    if (app::kGame.init) app::kGame.init();

    std::string title = Utf8(app::kGame.title);
    // MW_FULLSCREEN=1: the whole screen, its pixels the window's (a scripted pointer then lands where it is aimed)
    SDL_WindowFlags flags = SDL_WINDOW_VULKAN | (EnvInt("MW_FULLSCREEN", 0) ? SDL_WINDOW_FULLSCREEN : 0);
    g_window = SDL_CreateWindow(title.c_str(), EnvInt("MW_WIDTH", 640), EnvInt("MW_HEIGHT", 480), flags);
    if (!g_window) {
        LOGE("app: no window: %s", SDL_GetError());
        return 1;
    }
    app::LogSystem();

    app::GameFiles game;
    if (!app::FindGame(game)) {
        logx::Flush();
        return 1;
    }

    InitUi();
    OpenPad();
    input::Init();
    if (app::kGame.window) app::kGame.window(g_window);
    std::filesystem::path saves = app::SaveRoot();
    LOGR("app: save dir: %s", saves.c_str());
    if (!kernel::Boot(std::move(game.xbe), IMAGE_XBE, game.dir.string(), saves.string())) {
        LOGE("app: boot failed");
        logx::Flush();
        return 1;
    }

    for (;;) {
        SDL_Event e;
        if (SDL_WaitEventTimeout(&e, 4)) {
            Dispatch(e);
            while (SDL_PollEvent(&e)) Dispatch(e);
        }
        UpdatePad();
    }
}
