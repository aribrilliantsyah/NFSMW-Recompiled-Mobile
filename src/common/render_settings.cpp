#include "common/render_settings.h"

#include <algorithm>
#include <mutex>

#include "app/app.h"
#include "common/aspect.h"
#include "common/options.h"

namespace game {

namespace {

std::mutex g_lock;
RenderSettings g_published;
// the worker's own: written and read on the render worker only
RenderSettings g_handed;
bool g_has_handed = false;

}  // namespace

void PublishRenderSettings() {
    const app::Settings& s = app::Get();
    const Options& o = Opt();
    RenderSettings r;
    r.render_percent = s.render_percent;
    r.aniso = s.aniso;
    r.vsync = s.vsync;
    r.screen_filter = o.screen_filter;
    r.anti_aliasing = o.anti_aliasing;
    r.reflections = o.reflections;
    r.road_reflections = o.road_reflections;
    r.bloom = o.bloom;
    r.shadows = o.shadows;
    r.fog = o.fog;
    r.shader_preload = o.shader_preload;
    r.fps_counter = o.fps_counter;
    r.fps_position = o.fps_position;
    r.fps_usage = o.fps_usage;
    r.fps_temps = o.fps_temps;
    r.aspect = Aspect();
    std::lock_guard lock(g_lock);
    g_published = r;
}

RenderSettings LatestRenderSettings() {
    std::lock_guard lock(g_lock);
    return g_published;
}

void HandOverRenderSettings(const RenderSettings& r) {
    g_handed = r;
    g_has_handed = true;
}

RenderSettings FrameRenderSettings() { return g_has_handed ? g_handed : LatestRenderSettings(); }

float RenderScaleOf(const RenderSettings& r, uint32_t guest_height) {
    float h = app::Output().height * (r.render_percent / 100.0f);
    if (r.anti_aliasing == kAaSsaa) h *= 2.0f;  // the presenter averages it back down
    return guest_height ? std::max(h / guest_height, 0.1f) : 1.0f;
}

}  // namespace game
