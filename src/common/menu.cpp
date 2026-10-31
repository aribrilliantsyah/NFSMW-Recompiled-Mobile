// What draws over the game, the FPS counter (its place, CPU / GPU load and temperatures as the launcher sets them), and
// the frame pacing. The settings themselves are the launcher's (Settings.java), read from the settings file at start
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

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

// Formats one perf segment ("CPU 40% · 65°", "BAT 31°"); empty when data is unavailable
void AppendSegment(std::vector<std::string>& segments, const char* name, float load, float temp) {
    if (load < 0 && temp < 0) return;
    char part[48];
    if (load >= 0 && temp >= 0)
        std::snprintf(part, sizeof(part), "%s %.0f%% \xC2\xB7 %.0f\xC2\xB0", name, load, temp);
    else if (load >= 0)
        std::snprintf(part, sizeof(part), "%s %.0f%%", name, load);
    else
        std::snprintf(part, sizeof(part), "%s %.0f\xC2\xB0", name, temp);
    segments.emplace_back(part);
}

// A horizontal pill with status dot, big fps number, baseline-aligned "fps", and optional perf segments; drawn on the
// render side, so from the frame's settings (render_settings.h)
void DrawFpsCounter(double fps) {
    const game::RenderSettings o = game::FrameRenderSettings();
    if (!o.fps_counter) return;
    host::SamplePerf();

    char num_text[16];
    std::snprintf(num_text, sizeof(num_text), "%.0f", fps);

    std::vector<std::string> segments;
    AppendSegment(segments, "CPU", o.fps_usage ? host::CpuLoad() : -1, o.fps_temps ? host::CpuTemp() : -1);
    AppendSegment(segments, "GPU", o.fps_usage ? host::GpuLoad() : -1, o.fps_temps ? host::GpuTemp() : -1);
    if (o.fps_temps) AppendSegment(segments, "BAT", -1, host::BatteryTemp());

    ImDrawList* dl = ImGui::GetForegroundDrawList();
    ImFont* font = ImGui::GetFont();
    float font_size = ImGui::GetFontSize();
    float big = font_size * 1.35f, small = font_size * 0.95f;

    ImVec2 num_sz = font->CalcTextSizeA(big, FLT_MAX, 0, num_text);
    ImVec2 fps_sz = font->CalcTextSizeA(small, FLT_MAX, 0, "fps");

    float dot_r = std::max(2.5f, font_size * 0.26f);
    float dot_gap = font_size * 0.50f;
    float fps_gap = font_size * 0.30f;
    float pad_v = font_size * 0.38f;
    float pill_h = std::round(big + pad_v * 2.0f);
    float radius = pill_h * 0.5f;
    float pad_h = std::round(radius * 0.85f);
    float sep_margin = font_size * 0.65f;

    std::vector<float> seg_widths;
    seg_widths.reserve(segments.size());
    float pill_w = pad_h + dot_r * 2.0f + dot_gap + num_sz.x + fps_gap + fps_sz.x;
    for (const auto& s : segments) {
        float w = font->CalcTextSizeA(small, FLT_MAX, 0, s.c_str()).x;
        seg_widths.push_back(w);
        pill_w += (sep_margin * 2.0f + 1.0f) + w;
    }
    pill_w += pad_h;

    ImVec2 screen = ImGui::GetIO().DisplaySize;
    float margin = screen.y * 0.03f;
    uint32_t pos = o.fps_position;
    bool bottom = pos == kFpsBottomLeft || pos == kFpsBottom || pos == kFpsBottomRight;
    float y = bottom ? screen.y - margin - pill_h : margin;
    // Top sits under the touch overlay's top row (TouchControllerView: BACK/☰/START centred at 0.07 of the height,
    // 0.7 of its unit min(w/16, h/9) high), the one place clear of its controls in both layouts: the corners are
    // XBOX's triggers and MOBILE's camera
    if (pos == kFpsTop) y = screen.y * 0.07f + 0.35f * std::min(screen.x / 16.0f, screen.y / 9.0f) + margin * 0.5f;
    float x = (pos == kFpsTopLeft || pos == kFpsBottomLeft) ? margin :
              ((pos == kFpsTopRight || pos == kFpsBottomRight) ? screen.x - margin - pill_w : (screen.x - pill_w) * 0.5f);

    // Pill background and border
    ImVec2 p_min(x, y), p_max(x + pill_w, y + pill_h);
    dl->AddRectFilled(p_min, p_max, IM_COL32(12, 13, 16, 158), radius);
    dl->AddRect(p_min, p_max, IM_COL32(154, 160, 166, 179), radius, 0, 1.0f);

    float cy = y + pill_h * 0.5f;

    // Status dot: green >= 55, yellow >= 30, red < 30
    ImU32 dot_col = fps >= 55.0 ? IM_COL32(0x3F, 0xB9, 0x50, 255) :
                    (fps >= 30.0 ? IM_COL32(0xE3, 0xB3, 0x41, 255) : IM_COL32(0xE5, 0x53, 0x4B, 255));
    float dot_cx = x + pad_h + dot_r;
    dl->AddCircleFilled(ImVec2(dot_cx, cy), dot_r, dot_col);

    // Baseline alignment
    float ascent_ratio = (font && font->FontSize > 0) ? (font->Ascent / font->FontSize) : 0.75f;
    float ascent_big = big * ascent_ratio;
    float ascent_small = small * ascent_ratio;

    float cur_x = dot_cx + dot_r + dot_gap;
    float y_big = cy - big * 0.5f;
    float baseline_y = y_big + ascent_big;
    float y_fps = baseline_y - ascent_small;

    dl->AddText(font, big, ImVec2(cur_x, y_big), IM_COL32(255, 255, 255, 255), num_text);
    cur_x += num_sz.x + fps_gap;

    dl->AddText(font, small, ImVec2(cur_x, y_fps), IM_COL32(154, 160, 166, 255), "fps");
    cur_x += fps_sz.x;

    // Perf segments separated by thin vertical lines
    for (size_t i = 0; i < segments.size(); i++) {
        float sep_x = cur_x + sep_margin;
        float sep_h = pill_h * 0.48f;
        dl->AddLine(ImVec2(sep_x, cy - sep_h * 0.5f), ImVec2(sep_x, cy + sep_h * 0.5f),
                    IM_COL32(154, 160, 166, 100), 1.0f);
        cur_x = sep_x + 1.0f + sep_margin;

        float seg_h = font->CalcTextSizeA(small, FLT_MAX, 0, segments[i].c_str()).y;
        dl->AddText(font, small, ImVec2(cur_x, cy - seg_h * 0.5f),
                    IM_COL32(255, 255, 255, 217), segments[i].c_str());
        cur_x += seg_widths[i];
    }
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
