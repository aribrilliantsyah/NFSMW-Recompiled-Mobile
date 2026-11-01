#include <algorithm>
#include <filesystem>
#include <fstream>

#include <toml++/toml.hpp>

#include "app/app.h"
#include "audio/output.h"
#include "common/aspect.h"
#include "common/menu.h"
#include "common/hud_layout.h"
#include "common/mouse_keyboard.h"
#include "common/options.h"
#include "common/render_settings.h"
#include "common/settings_lock.h"

namespace game {

namespace {

// port 0 has the touch overlay, the keyboard and mouse
void Pad(int port, input::Pad& pad) {
    input::SeenPad(pad);
    if (port != 0) return;
    pad.connected = true;
    input::Keyboard(pad);
}

// no vibration while the keyboard and mouse are in use
void Rumble(int port, uint16_t& left, uint16_t& right) {
    if (port != 0 || !input::KeyboardActive()) return;
    left = 0;
    right = 0;
}

// the HUD editor's keys (HudEditorActivity, Settings.java): the game's HUD items' offsets and switches, the FPS
// counter's place and size; kept in range whatever the file says
void LoadHud(const toml::table& t) {
    Options& o = Opt();
    for (size_t i = 0; i < kHudItems; i++) {
        std::string id = std::string("hud_") + kHudIds[i];
        o.hud_x[i] = std::clamp(int32_t(t[id + "_x"].value_or(int64_t(0))), -600, 600);
        o.hud_y[i] = std::clamp(int32_t(t[id + "_y"].value_or(int64_t(0))), -240, 240);
        o.hud_shown[i] = t[id + "_shown"].value_or(true);
    }
    o.fps_x = uint32_t(std::clamp(t["fps_x"].value_or(int64_t(500)), int64_t(0), int64_t(1000)));
    o.fps_y = uint32_t(std::clamp(t["fps_y"].value_or(int64_t(120)), int64_t(0), int64_t(1000)));
    o.fps_scale = uint32_t(std::clamp(t["fps_scale"].value_or(int64_t(100)), int64_t(kFpsScaleMin),
                                      int64_t(kFpsScaleMax)));
    if (o.fps_position >= kFpsPositionCount) o.fps_position = 0;
}

// what the options list cannot hold
void Load(const void* table) {
    const toml::table& t = *static_cast<const toml::table*>(table);
    Options& o = Opt();
    std::string language = t["language"].value_or(o.language);
    // a language no longer offered (French, German, Italian, Spanish) goes back to English
    if (KnownLanguage(language) && LanguageAvailable(language)) o.language = language;
    if (const toml::table* keys = t["keys"].as_table())
        for (auto&& [id, value] : *keys)
            if (auto v = value.value<std::string>()) o.keys[std::string(id.str())] = *v;
    input::LoadPadMap(&t);
    // the launcher's set cash request: cheat_set_cash_pending with its amount cheat_set_cash (an amount alone, as
    // builds before the flag wrote 0 there, is none)
    int64_t cash = t["cheat_set_cash"].value_or(int64_t(-1));
    if (t["cheat_set_cash_pending"].value_or(false) && cash >= 0 && cash <= int64_t(kMaxCash))
        o.cheat_set_cash = o.cheat_set_cash_file = cash;
    LoadHud(t);
}

}  // namespace

bool AckCashRequest(int64_t value) {
    std::filesystem::path path = app::ExeDir() / app::kGame.settings;
    SettingsLock lock(path);
    if (!lock.ok()) return false;
    toml::table t;
    try {
        t = toml::parse_file(path.string());
    } catch (...) {
        return false;  // gone or broken: the request may still be there
    }
    // answered already, or the launcher wrote another since: nothing of ours to clear
    if (!t["cheat_set_cash_pending"].value_or(false) || t["cheat_set_cash"].value_or(int64_t(-1)) != value) return true;
    t.insert_or_assign("cheat_set_cash_pending", false);
    std::string tmp = path.string() + ".tmp";
    {
        std::ofstream f(tmp);
        f << t << "\n";
        if (!f) return false;
    }
    std::error_code ec;
    std::filesystem::rename(tmp, path, ec);
    return !ec;
}

// the HUD editor's DONE (GameActivity.nativeReloadHud): its keys from the file as the launcher process left them, and
// the FPS counter's switches (the HUD category's rows), the rest of the run's settings as they are
void ReloadHudIfAsked() {
    if (!g_hud_reload.exchange(false)) return;
    std::filesystem::path path = app::ExeDir() / app::kGame.settings;
    toml::table t;
    try {
        SettingsLock lock(path);
        t = toml::parse_file(path.string());
    } catch (...) {
        return;
    }
    Options& o = Opt();
    o.fps_counter = t["fps_counter"].value_or(o.fps_counter);
    o.fps_position = uint32_t(t["fps_position"].value_or(int64_t(o.fps_position)));
    o.fps_usage = t["fps_usage"].value_or(o.fps_usage);
    o.fps_temps = t["fps_temps"].value_or(o.fps_temps);
    LoadHud(t);
}

namespace {

// after the settings load, before boot
void Init() {
    BootLanguage();  // latched before the options can change the setting
    input::LoadBindings();
    // layout.toml's [hud], the HUD offsets before they were the settings file's: gone, once
    std::error_code ec;
    std::filesystem::remove(app::ExeDir() / "layout.toml", ec);
    audio::SetOutputMode(Opt().audio_output);
    LatchAspect();            // the frame's shape for this run, before anything asks for it
    PublishRenderSettings();  // the renderer's first set, before its Init
}

void Save(void* table) {
    toml::table& t = *static_cast<toml::table*>(table);
    const Options& o = Opt();
    t.insert("language", o.language);
    toml::table keys;
    for (const auto& [id, value] : o.keys) keys.insert(id, value);
    t.insert("keys", keys);
    for (size_t i = 0; i < kHudItems; i++) {
        std::string id = std::string("hud_") + kHudIds[i];
        t.insert(id + "_x", int64_t(o.hud_x[i]));
        t.insert(id + "_y", int64_t(o.hud_y[i]));
        t.insert(id + "_shown", o.hud_shown[i]);
    }
    t.insert("fps_x", int64_t(o.fps_x));
    t.insert("fps_y", int64_t(o.fps_y));
    t.insert("fps_scale", int64_t(o.fps_scale));
}

}  // namespace

Options& Opt() {
    static Options o;
    return o;
}

const std::string& BootLanguage() {
    static const std::string language = Opt().language;
    return language;
}

}  // namespace game

const app::GameInfo app::kGame = {
    .title = L"Need for Speed: Most Wanted",
    .short_title = L"Most Wanted",
    .name = L"MostWanted",
    .log = "mostwanted",
    .settings = "mostwanted.toml",
    .splash = nullptr,
    .missing = L"Install the game from the launcher (INSTALL FROM ISO), or pick the folder with default.xbe and the "
               L"disc's NFS folder.",
    .images = {{"default.xbe", "MostWanted"}},
    .options = {{"fps_counter", &game::Opt().fps_counter, nullptr},
                {"widescreen", &game::Opt().widescreen, nullptr},
                {"bloom", &game::Opt().bloom, nullptr},
                {"fog", &game::Opt().fog, nullptr},
                {"car_reflections", nullptr, &game::Opt().reflections},
                {"particles", &game::Opt().particles, nullptr},
                {"shadows", &game::Opt().shadows, nullptr},
                {"road_reflections", &game::Opt().road_reflections, nullptr},
                {"rain", &game::Opt().rain, nullptr},
                {"rain_drops", &game::Opt().rain_drops, nullptr},
                {"yellow_tint", &game::Opt().yellow_tint, nullptr},
                {"motion_blur", &game::Opt().motion_blur, nullptr},
                {"shader_preload", &game::Opt().shader_preload, nullptr},
                {"cheat_nos", &game::Opt().cheat_nos, nullptr},
                {"cheat_speedbreaker", &game::Opt().cheat_speedbreaker, nullptr},
                {"cheat_cash", &game::Opt().cheat_cash, nullptr},
                {"cheat_no_police", &game::Opt().cheat_no_police, nullptr},
                {"cheat_unlock_all", &game::Opt().cheat_unlock_all, nullptr},
                {"skip_movies", &game::Opt().skip_movies, nullptr},
                {"fps_usage", &game::Opt().fps_usage, nullptr},
                {"fps_temps", &game::Opt().fps_temps, nullptr},
                {"fps_position", nullptr, &game::Opt().fps_position},
                {"audio_output", nullptr, &game::Opt().audio_output},
                {"aspect_ratio", nullptr, &game::Opt().aspect_ratio},
                {"screen_filter", nullptr, &game::Opt().screen_filter},
                {"anti_aliasing", nullptr, &game::Opt().anti_aliasing}},
    .load = game::Load,
    .save = game::Save,
    .init = game::Init,
    .loaded = nullptr,
    .window = nullptr,
    .message = nullptr,
    .ui = app::DrawUi,
    .pad = game::Pad,
    .rumble = game::Rumble,
};
