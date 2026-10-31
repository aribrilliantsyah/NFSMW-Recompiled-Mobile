// the six categories' rows, their values, stepping and apply: what the in-game settings menu shows
// (src/game/settings_menu.cpp). Each row reads and writes Options or app::Settings, and a step saves at once through
// app::Save; the overlay's rows are the launcher shell's (host::ShellRow). Game thread.
#include "common/settings_rows.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <functional>
#include <iterator>
#include <optional>
#include <string>
#include <vector>

#include <toml++/toml.hpp>

#include "app/app.h"
#include "audio/output.h"
#include "build_info.h"
#include "common/aspect.h"
#include "common/menu.h"
#include "common/options.h"
#include "common/settings_file.h"
#include "core/log.h"
#include "host.h"

namespace app {
Settings DefaultSettings();  // the host's (native/main.cpp, linux/main.cpp): a first run's, the restore's
}

namespace settings {

namespace {

// ---- helpers ----

int StepIndex(int index, int step, int count) {
    if (count <= 0) return 0;
    index = (index + step) % count;
    return index < 0 ? index + count : index;
}

int NearestIndex(const uint32_t* values, int count, uint32_t value) {
    int best = 0;
    uint32_t best_d = UINT32_MAX;
    for (int i = 0; i < count; i++) {
        uint32_t d = values[i] > value ? values[i] - value : value - values[i];
        if (d < best_d) { best_d = d; best = i; }
    }
    return best;
}

template <size_t N>
int StepTable(const uint32_t (&values)[N], uint32_t& value, int step) {
    int i = NearestIndex(values, int(N), value);
    if (step) { i = StepIndex(i, step, int(N)); value = values[i]; }
    return i;
}

std::string OnOff(bool v) { return v ? "ON" : "OFF"; }

// ---- row ids, unique per category ----

// SCREEN
enum : int { kWidescreen, kAspectRatio, kRenderRes, kScreenFilter, kYellowFilter };
// SPEED
enum : int { kPreset = 100, kFpsCap, kVSync, kFpsCounter, kCounterPos, kCpuGpu, kTemps, kShaderPreload };
// VISUALS
enum : int {
    kBloom = 200, kFog, kMotionBlur, kCarReflections, kRoadReflections, kShadows,
    kParticles, kFallingRain, kScreenRaindrops, kAntiAliasing, kAnisotropic
};
// CONTROLS
enum : int { kControlMode = 300, kSteering, kTiltSens, kOpacity, kSize, kEditButtons, kEditHud };
// SYSTEM
enum : int { kLanguage = 400, kAudioOutput, kSkipMovies, kActiveSave, kAbout };
// CHEATS
enum : int { kCheatNos = 500, kCheatSpeedbreaker, kCheatNoPolice, kCheatUnlockAll, kCheatMaxCash, kSetCash, kApplyCash };

// ---- tables ----

constexpr uint32_t kFpsCaps[] = {30, 60, 75, 90, 120, 144, 165, 240, 360, app::kFpsCapRefresh, 0};
const char* FpsCapName(uint32_t v) {
    if (v == 0) return "UNLIMITED";
    if (v == app::kFpsCapRefresh) return "REFRESH RATE";
    static char buf[32];
    std::snprintf(buf, sizeof(buf), "%u", v);
    return buf;
}

constexpr uint32_t kRenderPercents[] = {25, 33, 50, 67, 75, 100, 125, 150, 200};
const char* RenderPercentName(uint32_t v) {
    static char buf[16];
    std::snprintf(buf, sizeof(buf), "%u%%", v);
    return buf;
}

constexpr uint32_t kAspectCodes[] = {0, 100, 125, 133, 150, 160, 178, 200, 217, 222, 233};
const char* AspectName(uint32_t code) {
    for (const game::AspectChoice& a : game::kAspects)
        if (a.code == code) return a.name;
    return "FULL SCREEN";
}

constexpr uint32_t kScreenFilters[] = {0, 1, 2, 3, 4, 5};
const char* const kScreenFilterNames[] = {"OFF", "SHARPEN", "VIVID", "SCANLINES", "CRT", "HDR"};

constexpr uint32_t kAntiAliasings[] = {game::kAaOff, game::kAaFxaa, game::kAaSsaa};
const char* const kAntiAliasingNames[] = {"OFF", "FXAA", "SSAA 2X"};

constexpr uint32_t kAnisoValues[] = {0, 2, 4, 8, 16};
const char* const kAnisoNames[] = {"GAME DEFAULT", "2x", "4x", "8x", "16x"};

constexpr uint32_t kReflectionRates[] = {0, 1, 2, 3};
const char* const kReflectionNames[] = {"FULL", "HALF", "QUARTER", "OFF"};

constexpr uint32_t kAudioOutputs[] = {audio::kOutputAuto, audio::kOutputMono, audio::kOutputStereo, audio::kOutputSurround};
const char* const kAudioOutputNames[] = {"AUTOMATIC", "MONO", "2.0 STEREO", "5.1 SURROUND"};

const char* const kLanguageNames[] = {"ENGLISH", "FRENCH", "GERMAN", "ITALIAN", "SPANISH", "INDONESIAN"};
constexpr int kLanguageCount = int(std::size(game::kLanguages));

// CONTROLS: modes read from mostwanted.toml directly
const char* const kModeNames[] = {"XBOX", "MOBILE"};
const char* const kSteerNames[] = {"STICK", "ARROWS", "TILT", "WHEEL"};

enum FpsPosition : uint32_t { kFpsTop, kFpsTopLeft, kFpsTopRight, kFpsBottomLeft, kFpsBottom, kFpsBottomRight };
constexpr uint32_t kFpsPositions[] = {kFpsTop, kFpsTopLeft, kFpsTopRight, kFpsBottomLeft, kFpsBottom, kFpsBottomRight};
const char* const kFpsPositionNames[] = {"TOP CENTER", "TOP LEFT", "TOP RIGHT", "BOTTOM LEFT", "BOTTOM CENTER", "BOTTOM RIGHT"};

// ---- presets ----

// A preset is the whole state of the rows it covers: render resolution, every SPEED row and every VISUALS row. The rows
// as they are match one exactly, or the preset shows Custom (a row changed by hand, here or in the launcher)
enum Preset { kSaver, kBalanced, kMax, kCustom };
constexpr int kPresetCount = 3;

struct PresetState {
    uint32_t render_percent, fps_cap;
    bool vsync, fps_counter;
    uint32_t fps_position;
    bool fps_usage, fps_temps, shader_preload;
    bool bloom, fog, motion_blur;
    uint32_t reflections;  // the cars': 0 every frame, 1 every 2nd, 3 off
    bool road_reflections, shadows, particles, rain, rain_drops;
    uint32_t anti_aliasing, aniso;
};
constexpr PresetState kPresets[kPresetCount] = {
    // each: no FPS counter (its rows as they come), shaders preloaded (no stutter on a new one)
    // Saver: half the resolution, 30 fps, no V-Sync, no reflections, motion blur or particles
    {50, 30, false, false, 0, true, true, true, true, true, false, 3, false, true, false, true, true, game::kAaOff, 0},
    // Balanced: two thirds, 60 fps, no V-Sync, the cars' reflections every 2nd frame
    {67, 60, false, false, 0, true, true, true, true, true, true, 1, true, true, true, true, true, game::kAaOff, 0},
    // Max: the full resolution, no cap, V-Sync, everything on
    {100, 0, true, false, 0, true, true, true, true, true, true, 0, true, true, true, true, true, game::kAaSsaa, 16},
};
const char* const kPresetNames[] = {"SAVER", "BALANCED", "MAX", "CUSTOM"};

PresetState Current() {
    const game::Options& o = game::Opt();
    const app::Settings& s = app::Get();
    return {s.render_percent, s.fps_cap, s.vsync, o.fps_counter, o.fps_position, o.fps_usage, o.fps_temps,
            o.shader_preload, o.bloom, o.fog, o.motion_blur, o.reflections, o.road_reflections, o.shadows,
            o.particles, o.rain, o.rain_drops, o.anti_aliasing, s.aniso};
}

bool Same(const PresetState& a, const PresetState& b) {
    return a.render_percent == b.render_percent && a.fps_cap == b.fps_cap && a.vsync == b.vsync &&
           a.fps_counter == b.fps_counter && a.fps_position == b.fps_position && a.fps_usage == b.fps_usage &&
           a.fps_temps == b.fps_temps && a.shader_preload == b.shader_preload && a.bloom == b.bloom && a.fog == b.fog &&
           a.motion_blur == b.motion_blur && a.reflections == b.reflections &&
           a.road_reflections == b.road_reflections && a.shadows == b.shadows && a.particles == b.particles &&
           a.rain == b.rain && a.rain_drops == b.rain_drops && a.anti_aliasing == b.anti_aliasing && a.aniso == b.aniso;
}

Preset DetectPreset() {
    PresetState now = Current();
    for (int i = 0; i < kPresetCount; i++)
        if (Same(now, kPresets[i])) return Preset(i);
    return kCustom;
}

void ApplyPreset(Preset p) {
    if (p >= kCustom) return;
    const PresetState& d = kPresets[p];
    game::Options& o = game::Opt();
    app::Settings& s = app::Get();
    s.render_percent = d.render_percent, s.fps_cap = d.fps_cap, s.vsync = d.vsync, s.aniso = d.aniso;
    o.fps_counter = d.fps_counter, o.fps_position = d.fps_position, o.fps_usage = d.fps_usage;
    o.fps_temps = d.fps_temps, o.shader_preload = d.shader_preload;
    o.bloom = d.bloom, o.fog = d.fog, o.motion_blur = d.motion_blur, o.reflections = d.reflections;
    o.road_reflections = d.road_reflections, o.shadows = d.shadows, o.particles = d.particles;
    o.rain = d.rain, o.rain_drops = d.rain_drops, o.anti_aliasing = d.anti_aliasing;
}

// ---- per-category row lists (built once) ----

const std::vector<Row> kScreenRows = {
    {Kind::Toggle, kWidescreen, "Widescreen"},
    {Kind::Choice, kAspectRatio, "Aspect ratio"},
    {Kind::Choice, kRenderRes, "Render resolution"},
    {Kind::Choice, kScreenFilter, "Screen filter"},
    {Kind::Toggle, kYellowFilter, "Yellow filter"},
};

const std::vector<Row> kSpeedRows = {
    {Kind::Choice, kPreset, "Preset"},
    {Kind::Choice, kFpsCap, "FPS cap"},
    {Kind::Toggle, kVSync, "V-Sync"},
    {Kind::Toggle, kFpsCounter, "FPS counter"},
    {Kind::Choice, kCounterPos, "Counter position"},
    {Kind::Toggle, kCpuGpu, "CPU/GPU usage"},
    {Kind::Toggle, kTemps, "Temperatures"},
    {Kind::Toggle, kShaderPreload, "Shader preloading (next start)"},
};

const std::vector<Row> kVisualsRows = {
    {Kind::Toggle, kBloom, "Bloom"},
    {Kind::Toggle, kFog, "Fog"},
    {Kind::Toggle, kMotionBlur, "Motion blur"},
    {Kind::Choice, kCarReflections, "Car reflections"},
    {Kind::Toggle, kRoadReflections, "Road reflections"},
    {Kind::Toggle, kShadows, "Shadows"},
    {Kind::Toggle, kParticles, "Particles"},
    {Kind::Toggle, kFallingRain, "Falling rain"},
    {Kind::Toggle, kScreenRaindrops, "Screen raindrops"},
    {Kind::Choice, kAntiAliasing, "Anti-aliasing"},
    {Kind::Choice, kAnisotropic, "Anisotropic filtering"},
};

const std::vector<Row> kControlsRows = {
    {Kind::Choice, kControlMode, "Control mode"},
    {Kind::Choice, kSteering, "Steering"},
    {Kind::Number, kTiltSens, "Tilt sensitivity"},
    {Kind::Number, kOpacity, "Opacity"},
    {Kind::Number, kSize, "Size"},
    {Kind::Action, kEditButtons, "Edit buttons"},
    {Kind::Action, kEditHud, "Edit HUD"},
};

const std::vector<Row> kSystemRows = {
    {Kind::Choice, kLanguage, "Language (next start)"},
    {Kind::Choice, kAudioOutput, "Audio output (next start)"},
    {Kind::Toggle, kSkipMovies, "Skip movies"},
    {Kind::Choice, kActiveSave, "Active save slot"},
    {Kind::Action, kAbout, "About"},
};

const std::vector<Row> kCheatsRows = {
    {Kind::Toggle, kCheatNos, "Infinite nitrous"},
    {Kind::Toggle, kCheatSpeedbreaker, "Infinite speedbreaker"},
    {Kind::Toggle, kCheatNoPolice, "No police"},
    {Kind::Toggle, kCheatUnlockAll, "Unlock all"},
    {Kind::Toggle, kCheatMaxCash, "Lock cash at max"},
    {Kind::Number, kSetCash, "Set cash"},
    {Kind::Action, kApplyCash, "Apply cash"},
};

// Set cash: the value the row shows until APPLY hands it to ApplyCheats (Options::cheat_set_cash); the pending one,
// else the career's cash, until the player steps it
constexpr uint32_t kCashStep = 10000;
uint32_t g_cash_draft = 0;
bool g_cash_draft_set = false;  // stepped by the player: no longer the pending or career's value
void EnsureCashDraft() {
    if (g_cash_draft_set) return;
    uint32_t cash = 0;
    const game::Options& o = game::Opt();
    g_cash_draft = uint32_t(o.cheat_set_cash != game::kNoCash ? o.cheat_set_cash : game::CareerCash(cash) ? cash : 0);
    g_cash_draft = std::min(g_cash_draft, game::kMaxCash);
}

// a step of the draft: 10,000, ten times that in a run (the menu reports no hold, only steps: a held left or right
// that FEng repeats, or quick presses, kHoldSteps steps each within kRepeatMs of the last), kept to 0..kMaxCash
constexpr int kHoldSteps = 4;
constexpr uint64_t kRepeatMs = 400;
uint64_t NowMs() {
    using namespace std::chrono;
    return uint64_t(duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count());
}
void StepCash(int step) {
    static uint64_t last = 0;
    static int last_step = 0, run = 0;
    uint64_t now = NowMs();
    run = step == last_step && now - last < kRepeatMs ? run + 1 : 0;
    last = now, last_step = step;
    int64_t by = int64_t(kCashStep) * (run >= kHoldSteps ? 10 : 1);
    g_cash_draft = uint32_t(std::clamp<int64_t>(int64_t(g_cash_draft) + step * by, 0, game::kMaxCash));
    g_cash_draft_set = true;
}

std::string Grouped(uint32_t v) {
    std::string digits = std::to_string(v), out;
    for (size_t i = 0; i < digits.size(); i++) {
        if (i && (digits.size() - i) % 3 == 0) out += ',';
        out += digits[i];
    }
    return out;
}

// off Android (no launcher shell): pad_mode, steer and tilt_sens straight in the settings file, the rest kept
struct ControlState {
    int pad_mode = 1;   // 0 XBOX, 1 MOBILE
    int steer = 0;      // 0 stick, 1 arrows, 2 tilt, 3 wheel
    int tilt_sens = 5;  // 1..10
    bool loaded = false;
};
ControlState g_controls;

std::filesystem::path SettingsFile() { return app::ExeDir() / app::kGame.settings; }

void LoadControlState() {
    if (g_controls.loaded) return;
    g_controls.loaded = true;
    try {
        auto t = toml::parse_file(SettingsFile().string());
        g_controls.pad_mode = t["pad_mode"].value_or(g_controls.pad_mode);
        g_controls.steer = t["steer"].value_or(g_controls.steer);
        g_controls.tilt_sens = t["tilt_sens"].value_or(g_controls.tilt_sens);
    } catch (...) {}
}

// one key stepped from its value in the file as it is now (fallback the shown one), under its lock
// (game::UpdateSettingsFile): another editor's newer value is what the step starts from. A broken file is kept as .bad
// and left as it is, not replaced by this one key. The value as it now is
int StepControlKey(const char* key, int shown, const std::function<int(int)>& step) {
    int value = shown;
    bool ok = game::UpdateSettingsFile(SettingsFile(), [&](toml::table& t, bool broken) {
        if (broken) return false;
        value = step(int(t[key].value_or(int64_t(shown))));
        t.insert_or_assign(key, int64_t(value));
        return true;
    });
    if (!ok) LOGW("settings: %s not saved", key);
    return ok ? value : shown;
}

// the slot the launcher's PLAY wrote (save_dir, a slot folder), else the default
std::string CurrentSlot() {
    try {
        auto t = toml::parse_file(SettingsFile().string());
        std::string dir = t["save_dir"].value_or(std::string());
        while (!dir.empty() && dir.back() == '/') dir.pop_back();
        if (!dir.empty()) return std::filesystem::path(dir).filename().string();
    } catch (...) {}
    return "Main";
}

}  // namespace

const std::vector<Row>& Rows(Category cat) {
    static const std::vector<Row> empty;
    switch (cat) {
        case kScreen: return kScreenRows;
        case kSpeed: return kSpeedRows;
        case kVisuals: return kVisualsRows;
        case kControls: return kControlsRows;
        case kSystem: return kSystemRows;
        case kCheats: return kCheatsRows;
        default: return empty;
    }
}

std::string Value(const Row& r, int step) {
    game::Options& o = game::Opt();
    app::Settings& s = app::Get();

    auto save = [&] { app::Save(); };

    auto toggle = [&](bool& flag) -> std::string {
        if (step) { flag = !flag; save(); }
        return OnOff(flag);
    };

    switch (r.id) {
        // SCREEN
        case kWidescreen: return toggle(o.widescreen);
        case kAspectRatio: {
            int i = NearestIndex(kAspectCodes, int(std::size(kAspectCodes)), o.aspect_ratio);
            if (step) { i = StepIndex(i, step, int(std::size(kAspectCodes))); o.aspect_ratio = kAspectCodes[i]; save(); }
            return AspectName(o.aspect_ratio);
        }
        case kRenderRes: {
            int i = NearestIndex(kRenderPercents, int(std::size(kRenderPercents)), s.render_percent);
            if (step) { i = StepIndex(i, step, int(std::size(kRenderPercents))); s.render_percent = kRenderPercents[i]; save(); }
            return RenderPercentName(s.render_percent);
        }
        case kScreenFilter: {
            int i = StepTable(kScreenFilters, o.screen_filter, step);
            if (step) save();
            return kScreenFilterNames[i];
        }
        case kYellowFilter: return toggle(o.yellow_tint);

        // SPEED
        case kPreset: {
            Preset p = DetectPreset();
            if (step) {
                // Custom is no choice of its own: from it, right gives Saver and left Max
                int i = p == kCustom ? (step > 0 ? kPresetCount - 1 : 0) : int(p);
                ApplyPreset(Preset(StepIndex(i, step, kPresetCount)));
                save();
                p = DetectPreset();
            }
            return kPresetNames[int(p)];
        }
        case kFpsCap: {
            int i = NearestIndex(kFpsCaps, int(std::size(kFpsCaps)), s.fps_cap);
            if (step) { i = StepIndex(i, step, int(std::size(kFpsCaps))); s.fps_cap = kFpsCaps[i]; save(); }
            return FpsCapName(s.fps_cap);
        }
        case kVSync: return toggle(s.vsync);
        case kFpsCounter: return toggle(o.fps_counter);
        case kCounterPos: {
            int i = StepTable(kFpsPositions, o.fps_position, step);
            if (step) save();
            return kFpsPositionNames[i];
        }
        case kCpuGpu: return toggle(o.fps_usage);
        case kTemps: return toggle(o.fps_temps);
        case kShaderPreload: return toggle(o.shader_preload);

        // VISUALS
        case kBloom: return toggle(o.bloom);
        case kFog: return toggle(o.fog);
        case kMotionBlur: return toggle(o.motion_blur);
        case kCarReflections: {
            int i = StepTable(kReflectionRates, o.reflections, step);
            if (step) save();
            return kReflectionNames[i];
        }
        case kRoadReflections: return toggle(o.road_reflections);
        case kShadows: return toggle(o.shadows);
        case kParticles: return toggle(o.particles);
        case kFallingRain: return toggle(o.rain);
        case kScreenRaindrops: return toggle(o.rain_drops);
        case kAntiAliasing: {
            int i = StepTable(kAntiAliasings, o.anti_aliasing, step);
            if (step) save();
            return kAntiAliasingNames[i];
        }
        case kAnisotropic: {
            int i = StepTable(kAnisoValues, s.aniso, step);
            if (step) save();
            return kAnisoNames[i];
        }

        // CONTROLS: the overlay's own, the launcher shell's (GameActivity.settingsRow); off Android, the file's three
        case kControlMode:
        case kSteering:
        case kTiltSens:
        case kOpacity:
        case kSize: {
            // on Android the overlay's alone: a change it did not answer for in time is not made again here (it may
            // still be made), the row shows its last value until it answers
            static std::string shown[5];
            int row = r.id - kControlMode;
            if (std::optional<std::string> shell = host::ShellRow(row, step)) {
                if (!shell->empty()) shown[row] = *shell;
                return shown[row].empty() ? "..." : shown[row];
            }
            if (r.id == kOpacity || r.id == kSize) return "-";
            LoadControlState();
            if (r.id == kControlMode) {
                if (step)
                    g_controls.pad_mode = StepControlKey("pad_mode", g_controls.pad_mode,
                                                         [&](int v) { return StepIndex(v, step, 2); });
                return kModeNames[std::clamp(g_controls.pad_mode, 0, 1)];
            }
            if (r.id == kSteering) {
                if (step)
                    g_controls.steer = StepControlKey("steer", g_controls.steer,
                                                      [&](int v) { return StepIndex(v, step, 4); });
                return kSteerNames[std::clamp(g_controls.steer, 0, 3)];
            }
            if (step)
                g_controls.tilt_sens = StepControlKey("tilt_sens", g_controls.tilt_sens,
                                                      [&](int v) { return std::clamp(v + step, 1, 10); });
            return std::to_string(g_controls.tilt_sens);
        }
        case kEditButtons:
        case kEditHud:
            return "";

        // SYSTEM
        case kLanguage: {
            int idx = 0;
            for (int i = 0; i < kLanguageCount; i++)
                if (o.language == game::kLanguages[i]) { idx = i; break; }
            if (step) {
                // only the languages the disc has (and Indonesian, the recomp's own)
                int tries = 0;
                do idx = StepIndex(idx, step, kLanguageCount);
                while (!game::LanguageAvailable(game::kLanguages[idx]) && ++tries < kLanguageCount);
                o.language = game::kLanguages[idx];
                save();
            }
            return kLanguageNames[idx];
        }
        case kAudioOutput: {
            int i = StepTable(kAudioOutputs, o.audio_output, step);
            if (step) { audio::SetOutputMode(o.audio_output); save(); }
            return kAudioOutputNames[i];
        }
        case kSkipMovies: return toggle(o.skip_movies);
        // read-only in the game: the slot is the hard disk the game booted with; the launcher's SAVES changes it
        case kActiveSave: return CurrentSlot();
        case kAbout: return std::string("BUILD ") + BUILD_COMMIT;

        // CHEATS: ApplyCheats (src/game/game.cpp) holds them at each present
        case kCheatNos: return toggle(o.cheat_nos);
        case kCheatSpeedbreaker: return toggle(o.cheat_speedbreaker);
        case kCheatNoPolice: return toggle(o.cheat_no_police);
        case kCheatUnlockAll: return toggle(o.cheat_unlock_all);
        case kCheatMaxCash: return toggle(o.cheat_cash);
        case kSetCash:
            EnsureCashDraft();
            if (step) StepCash(step);  // nothing saved: APPLY hands it over
            return "$" + Grouped(g_cash_draft);
        // a set cash not yet applied (no profile yet) waits
        case kApplyCash: return o.cheat_set_cash != game::kNoCash ? "PENDING" : "";
        default: return "";
    }
}

void Activate(const Row& r) {
    // the overlay's editor and the HUD editor are the launcher shell's
    if (r.id == kEditButtons || r.id == kEditHud) host::ShellRow(r.id - kControlMode, 1);
    // the next present with a profile sets it (ApplyCheats), 0 too. This run's only: the settings file's request is the
    // launcher's (one pending from it is replaced, and answered when this one is set)
    if (r.id == kApplyCash) {
        EnsureCashDraft();
        game::Opt().cheat_set_cash = g_cash_draft;
        g_cash_draft_set = false;  // the row shows the pending value, then the career's
    }
}

void Defaults(Category cat) {
    const game::Options d;
    const app::Settings ds = app::DefaultSettings();
    game::Options& o = game::Opt();
    app::Settings& s = app::Get();
    switch (cat) {
        case kScreen:
            o.widescreen = d.widescreen, o.aspect_ratio = d.aspect_ratio, o.screen_filter = d.screen_filter;
            o.yellow_tint = d.yellow_tint, s.render_percent = ds.render_percent;
            break;
        case kSpeed:
            s.fps_cap = ds.fps_cap, s.vsync = ds.vsync, o.fps_counter = d.fps_counter, o.fps_position = d.fps_position;
            o.fps_usage = d.fps_usage, o.fps_temps = d.fps_temps, o.shader_preload = d.shader_preload;
            break;
        case kVisuals:
            o.bloom = d.bloom, o.fog = d.fog, o.motion_blur = d.motion_blur, o.reflections = d.reflections;
            o.road_reflections = d.road_reflections, o.shadows = d.shadows, o.particles = d.particles;
            o.rain = d.rain, o.rain_drops = d.rain_drops, o.anti_aliasing = d.anti_aliasing, s.aniso = ds.aniso;
            break;
        case kSystem:
            o.skip_movies = d.skip_movies;
            break;
        default:
            return;  // CONTROLS are the overlay's (its editor's RESET), CHEATS their own
    }
    app::Save();
}

const char* Name(Category cat) {
    static const char* names[] = {"SCREEN", "SPEED", "VISUALS", "CONTROLS", "SYSTEM", "CHEATS"};
    return cat >= 0 && cat < kCount ? names[cat] : "";
}

uint32_t Color(Category cat) {
    return cat == kCheats ? 0xFFF28C1C : 0xFF9AA0A6;
}

}  // namespace settings
