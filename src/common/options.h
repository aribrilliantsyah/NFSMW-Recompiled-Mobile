#pragma once
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <iterator>
#include <map>
#include <string>

// the game's own settings, in the settings file through app::kGame.options
namespace game {

// the languages, saved by these codes: English, French, German, Italian, Spanish, Indonesian. Indonesian is the
// recomp's own text only: the game files have none, so the game itself stays in English
inline constexpr const char* kLanguages[] = {"int", "fra", "deu", "ita", "esp", "ind"};
inline bool KnownLanguage(const std::string& code) {
    return std::find(std::begin(kLanguages), std::end(kLanguages), code) != std::end(kLanguages);
}

// SSAA renders at twice the height, the presenter averages it down
inline constexpr uint32_t kAaOff = 0, kAaFxaa = 1, kAaSsaa = 2;

inline constexpr uint32_t kMaxCash = 9999999;  // the cash cheats' top, the most the career's counter shows
inline constexpr int64_t kNoCash = -1;         // no set cash pending

// the FPS counter's places (Options::fps_position): the six fixed ones, then kFpsCustom, the HUD editor's drag
// (fps_x, fps_y)
enum FpsPosition : uint32_t {
    kFpsTop, kFpsTopLeft, kFpsTopRight, kFpsBottomLeft, kFpsBottom, kFpsBottomRight, kFpsCustom, kFpsPositionCount
};
inline constexpr uint32_t kFpsScaleMin = 50, kFpsScaleMax = 200;

struct Options {
    bool fps_counter = false;
    uint32_t fps_position = 0;  // the counter's place, src/common/menu.cpp's kFpsPositionNames; kFpsCustom: fps_x, fps_y
    bool fps_usage = true;      // the counter's CPU and GPU load
    bool fps_temps = true;      // the counter's CPU, GPU and battery temperatures
    // the HUD editor's (HudEditorActivity): the counter's middle in thousandths of the screen, its size in percent.
    // Integers in the settings file, as the launcher's TOML reader takes them (TomlSettings: bools, integers, strings)
    uint32_t fps_x = 500, fps_y = 120;
    uint32_t fps_scale = 100;
    // the game's HUD items (common/hud_layout.h's HudItem order): each moved by whole FEng units (480 to the frame's
    // height) from where the game puts it, and shown or hidden; src/game/hud_layout.cpp applies them
    int32_t hud_x[4] = {}, hud_y[4] = {};
    bool hud_shown[4] = {true, true, true, true};
    uint32_t screen_filter = 0;  // the present's look: 0 none, 1 sharpen, 2 vivid, 3 scanlines, 4 CRT, 5 HDR
    uint32_t anti_aliasing = 0;  // kAaOff, kAaFxaa or kAaSsaa
    uint32_t aspect_ratio = 0;  // the frame's shape in hundredths (133 is 4:3), 0 the whole screen; common/aspect.h
    // the launcher's WIDESCREEN switch: off, the console's 4:3 frame pillarboxed whatever aspect_ratio says; on,
    // aspect_ratio's (the whole screen by default) with the console's widescreen flag from 3:2 on
    bool widescreen = true;
    bool bloom = true;  // the glow added over the frame, native/vulkan.cpp skips it when off
    bool fog = true;    // the distance fog, native/vulkan.cpp draws without it when off
    // the cars' reflection, six more views: drawn every frame (0), every 2nd (1), every 4th (2) or off (3, a grey
    // cube read instead); src/game/game.cpp's ApplyGraphicsSwitches
    uint32_t reflections = 0;
    bool particles = true;    // tyre smoke, sparks and crash debris, the same
    bool shadows = true;      // the shadows under the cars, the same; the trees' and buildings', native/vulkan.cpp
    bool road_reflections = true;  // the wet road's reflection, the same
    bool rain = true;         // the falling rain, src/game/game.cpp
    bool rain_drops = true;   // the drops on the screen in the rain, the same
    bool yellow_tint = false;  // the game's yellow colour grading, src/game/game.cpp greys it when off
    bool shader_preload = false;  // native/vulkan.cpp makes earlier runs' shaders and pipelines at the start
    bool motion_blur = true;   // the blur by speed and the nitrous' radial blur, src/game/game.cpp skips them when off
    // cheats, src/game/game.cpp's ApplyCheats
    bool cheat_nos = false;           // the nitrous never runs out
    bool cheat_speedbreaker = false;  // the speedbreaker never runs out
    bool cheat_cash = false;          // the career's cash kept at kCheatCash
    bool cheat_no_police = false;     // no new cops or pursuits
    bool cheat_unlock_all = false;    // every car, part and race unlocked
    // the career's cash to set once, 0..kMaxCash (0 is an amount), kNoCash none: the settings file's request
    // (LoadCashRequest) or the CHEATS row's APPLY CASH (this run only), set by ApplyCheats at the first present with a
    // profile. Not in kGame.options: app::Save never writes it, the launcher owns the file's request
    int64_t cheat_set_cash = kNoCash;
    // the file's request as Load read it, kNoCash none: answered (AckCashRequest) once a set cash is applied this run
    int64_t cheat_set_cash_file = kNoCash;
    bool skip_movies = false;         // the movies end as they start, src/game/game.cpp
    uint32_t audio_output = 0;  // audio::kOutputAuto, kOutputStereo, kOutputSurround or kOutputMono
    std::string language = "int";  // one of kLanguages
    std::map<std::string, std::string> keys;  // action id to key names, absent is the default
    std::map<std::string, std::string> pad;   // action id to a controller's input, absent is SDL's mapping
};
Options& Opt();
// the language setting as this run started, the language the game boots in
const std::string& BootLanguage();
bool LanguageAvailable(const std::string& code);  // src/game/game.cpp: the LANGUAGE row offers it
inline std::atomic<uint32_t> g_road_reflection{0};  // the road reflection target's data, virtual; game.cpp, each present
inline std::atomic<uint32_t> g_env_cube{0};         // the cars' reflection cube's data, the same
inline std::atomic<bool> g_focus_paused{false};  // the game thread waits while the app is in the background
inline std::atomic<uint32_t> g_focus_pauses{0};  // focus pauses ended so far
void WaitWhileUnfocused();  // native/game_host.cpp: game thread, before the present
void WidenAspect();         // src/game/game.cpp: game thread, at each present
void ApplyGraphicsSwitches();  // src/game/game.cpp: game thread, at each present
void ApplyCheats();            // src/game/game.cpp: game thread, at each present
// src/game/game.cpp, game thread: the career profile's cash; false while no profile is named (made or loaded)
bool CareerCash(uint32_t& cash);
// src/common/game_info.cpp, game thread: the settings file's set cash request answered: under the settings lock, the
// file's cheat_set_cash made kNoCash if it still is `value` (a newer one the launcher wrote stays). False when the file
// could not be read or written, to be tried again
bool AckCashRequest(int64_t value);
bool Racing();    // src/game/game.cpp, any thread: the game flow is in a race (free roam too), not loading or the front end

}  // namespace game
