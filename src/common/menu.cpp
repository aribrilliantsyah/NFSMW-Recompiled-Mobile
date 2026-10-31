// What draws over the game, the FPS counter (its place, CPU / GPU load and temperatures as the launcher sets them), and
// the frame pacing. The settings themselves are the launcher's (Settings.java), read from the settings file at start
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <string>
#include <thread>

#include <SDL3/SDL.h>
#include <imgui.h>

#include "app/app.h"
#include "common/menu.h"
#include "common/options.h"
#include "common/render_settings.h"
#include "host.h"

namespace app {

namespace {

// the counter's place on the screen, Options::fps_position (Settings.java's FPS position)
enum FpsPosition : uint32_t { kFpsTop, kFpsTopLeft, kFpsTopRight, kFpsBottomLeft, kFpsBottom, kFpsBottomRight };

// the window's display's refresh rate, 0 when unreadable
uint32_t RefreshRate() {
    SDL_Window* w = static_cast<SDL_Window*>(Window());
    const SDL_DisplayMode* m = SDL_GetCurrentDisplayMode(w ? SDL_GetDisplayForWindow(w) : SDL_GetPrimaryDisplay());
    return m && m->refresh_rate > 1 ? uint32_t(std::lround(m->refresh_rate)) : 0;
}

// as app::Pace, at this cap
void PaceAt(uint32_t cap) {
    using clock = std::chrono::steady_clock;
    static clock::time_point next = clock::now();
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

// "CPU 40% 65°C", the parts the device tells
void AppendPerf(std::string& line, const char* name, float load, float temp) {
    if (load < 0 && temp < 0) return;
    char part[48];
    int n = std::snprintf(part, sizeof(part), "%s%s", line.empty() ? "" : "   ", name);
    if (load >= 0) n += std::snprintf(part + n, sizeof(part) - n, " %.0f%%", load);
    if (temp >= 0) std::snprintf(part + n, sizeof(part) - n, " %.0f\xC2\xB0" "C", temp);
    line += part;
}

// a line of text with a drop shadow, its left edge at x
void DrawShadowed(ImDrawList* dl, float size, ImVec2 at, ImU32 color, const char* text) {
    float shadow = std::max(2.0f, size / 12.0f);
    dl->AddText(ImGui::GetFont(), size, ImVec2(at.x + shadow, at.y + shadow), IM_COL32(0, 0, 0, 200), text);
    dl->AddText(ImGui::GetFont(), size, at, color, text);
}

// the frame rate, and under it the CPU's and GPU's load and temperatures and the battery's, at the place
// Options::fps_position picks; drawn on the render side, so from the frame's settings (render_settings.h)
void DrawFpsCounter(double fps) {
    const game::RenderSettings o = game::FrameRenderSettings();
    if (!o.fps_counter) return;
    host::SamplePerf();
    char text[16];
    std::snprintf(text, sizeof(text), "%.0f FPS", fps);
    std::string perf;
    AppendPerf(perf, "CPU", o.fps_usage ? host::CpuLoad() : -1, o.fps_temps ? host::CpuTemp() : -1);
    AppendPerf(perf, "GPU", o.fps_usage ? host::GpuLoad() : -1, o.fps_temps ? host::GpuTemp() : -1);
    if (o.fps_temps) AppendPerf(perf, "BAT", -1, host::BatteryTemp());

    ImDrawList* dl = ImGui::GetForegroundDrawList();
    ImVec2 screen = ImGui::GetIO().DisplaySize;
    float big = ImGui::GetFontSize() * 1.5f, small = ImGui::GetFontSize();
    ImVec2 fs = ImGui::GetFont()->CalcTextSizeA(big, FLT_MAX, 0, text);
    ImVec2 ps = perf.empty() ? ImVec2(0, 0) : ImGui::GetFont()->CalcTextSizeA(small, FLT_MAX, 0, perf.c_str());
    float block = fs.y + ps.y;
    float margin = screen.y * 0.03f;
    uint32_t pos = o.fps_position;
    bool bottom = pos == kFpsBottomLeft || pos == kFpsBottom || pos == kFpsBottomRight;
    float y = bottom ? screen.y - margin - block : margin;
    auto x = [&](float w) {
        if (pos == kFpsTopLeft || pos == kFpsBottomLeft) return margin;
        if (pos == kFpsTopRight || pos == kFpsBottomRight) return screen.x - margin - w;
        return screen.x / 2 - w / 2;
    };
    DrawShadowed(dl, big, ImVec2(x(fs.x), y), IM_COL32(255, 214, 90, 255), text);
    if (!perf.empty()) DrawShadowed(dl, small, ImVec2(x(ps.x), y + fs.y), IM_COL32(235, 235, 235, 255), perf.c_str());
}

}  // namespace

void PaceFrame() {
    if (Get().fps_cap != kFpsCapRefresh) {
        Pace();
        return;
    }
    // a few under, so a variable refresh display stays in its range and vsync never waits a whole refresh
    constexpr uint32_t kRefreshMargin = 3;
    uint32_t rate = RefreshRate();
    PaceAt(rate > kRefreshMargin ? rate - kRefreshMargin : 0);
}

void DrawUi(double fps) {
    ImGuiIO& io = ImGui::GetIO();
    // the font scaled with the output height: 13 px text at 540 rows to the height, the game's font (native/ui.cpp,
    // taller) brought to that size
    float base = io.Fonts->Fonts.empty() ? 13.0f : io.Fonts->Fonts[0]->FontSize;
    io.FontGlobalScale = std::max(1.0f, io.DisplaySize.y / 540.0f) * 13.0f / base;
    DrawFpsCounter(fps);
}

}  // namespace app
