#include <algorithm>

#include <toml++/toml.hpp>

#include "app/app.h"
#include "audio/output.h"
#include "common/menu.h"
#include "common/hud_layout.h"
#include "common/mouse_keyboard.h"
#include "common/options.h"

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
}

// layout.toml's [hud], the launcher's HUD layout editor's: each item's offset from where the game puts it, FEng units,
// kept inside FEng's widest screen
void LoadHudLayout() {
    toml::table t;
    try {
        t = toml::parse_file((app::ExeDir() / "layout.toml").string());
    } catch (...) {
        return;
    }
    constexpr const char* kIds[kHudItems] = {"speedometer", "minimap", "race_info", "ea_trax"};
    for (size_t i = 0; i < kHudItems; i++) {
        const toml::table* p = t["hud"][kIds[i]].as_table();
        if (!p) continue;
        g_hud_x[i] = std::clamp(float((*p)["x"].value_or(0.0)), -600.0f, 600.0f);
        g_hud_y[i] = std::clamp(float((*p)["y"].value_or(0.0)), -240.0f, 240.0f);
    }
}

// after the settings load, before boot
void Init() {
    BootLanguage();  // latched before the options can change the setting
    input::LoadBindings();
    LoadHudLayout();
    audio::SetOutputMode(Opt().audio_output);
}

void Save(void* table) {
    toml::table& t = *static_cast<toml::table*>(table);
    const Options& o = Opt();
    t.insert("language", o.language);
    toml::table keys;
    for (const auto& [id, value] : o.keys) keys.insert(id, value);
    t.insert("keys", keys);
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
