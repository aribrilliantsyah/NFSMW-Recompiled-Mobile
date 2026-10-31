#pragma once
#include <algorithm>
#include <atomic>
#include <cstdint>

#include "app/app.h"
#include "common/options.h"

namespace game {

// the aspect ratio setting: saved as the ratio in hundredths, 0 the whole screen
struct AspectChoice {
    uint32_t code;
    float ratio;
    const char* name;
};
inline constexpr AspectChoice kAspects[] = {
    {100, 1.0f, "1:1"},          {125, 5.0f / 4.0f, "5:4"},     {133, 4.0f / 3.0f, "4:3"},
    {150, 3.0f / 2.0f, "3:2"},   {160, 16.0f / 10.0f, "16:10"}, {178, 16.0f / 9.0f, "16:9"},
    {200, 18.0f / 9.0f, "18:9"}, {217, 19.5f / 9.0f, "19.5:9"}, {222, 20.0f / 9.0f, "20:9"},
    {233, 21.0f / 9.0f, "21:9"}, {0, 0.0f, "FULL SCREEN"},
};

// the screen's shape, 4:3 at the narrowest
inline float ScreenAspect() {
    // asked per HUD point: the desktop mode lookup only runs when the resolution setting changes
    static std::atomic<uint64_t> key{~0ull};
    static std::atomic<float> aspect{16.0f / 9.0f};
    const app::Settings& s = app::Get();
    uint64_t k = uint64_t(s.width) << 32 | s.height;
    if (key.exchange(k) != k) {
        app::Mode m = app::Output();
        aspect = std::max(float(m.width) / std::max(m.height, 1u), 4.0f / 3.0f);
    }
    return aspect;
}

// the shape the settings ask for: the frame is shown that wide, letterboxed on the screen when it is not the screen's;
// widescreen off is the console's 4:3
inline float WantedAspect() {
    if (!Opt().widescreen) return 4.0f / 3.0f;
    uint32_t code = Opt().aspect_ratio;
    for (const AspectChoice& a : kAspects)
        if (a.code == code && a.ratio > 0) return a.ratio;
    return ScreenAspect();
}

// the boot's shape, latched by LatchAspect (src/common/game_info.cpp's Init, after the settings load): the game takes
// its widescreen flag once (XGetVideoFlags at boot) and lays out its views, HUD and menus for it, so a shape changed
// later (the settings rows) would show that frame squeezed or stretched until a restart. Both editors mark Widescreen
// and Aspect ratio as next start
inline std::atomic<float> g_boot_aspect{0.0f};
inline void LatchAspect() { g_boot_aspect = WantedAspect(); }
inline float Aspect() {
    float a = g_boot_aspect;
    return a > 0 ? a : WantedAspect();
}

}  // namespace game
