// ImGui on Android draws the FPS counter alone (src/common/menu.cpp) and takes no input: the touch controls and the
// settings are the launcher shell's, in Java
#include <chrono>
#include <vector>

#include <imgui.h>

#include "common/game_font.h"
#include "host.h"

namespace ui {

void NewFrame(float width, float height) {
    ImGuiIO& io = ImGui::GetIO();
    using clock = std::chrono::steady_clock;
    static clock::time_point last = clock::now();
    auto now = clock::now();
    float dt = std::chrono::duration<float>(now - last).count();
    last = now;
    io.DeltaTime = dt > 0 ? dt : 1.0f / 60;
    io.DisplaySize = ImVec2(width, height);
    io.DisplayFramebufferScale = ImVec2(1, 1);
}

// The game's body font in place of ImGui's (src/game/game_font.cpp): the atlas made again with its glyphs over
// ProggyClean at the game's line height (which stays for what the game's font lacks). True when the atlas changed,
// for the renderer to upload it again
bool ApplyGameFont() {
    game::FontImage font;
    if (!game::TakeFont(font) || font.glyphs.empty() || font.line_height <= 0) return false;
    ImFontAtlas* atlas = ImGui::GetIO().Fonts;
    atlas->Clear();
    ImFontConfig config;
    config.SizePixels = float(font.line_height);
    ImFont* base = atlas->AddFontDefault(&config);
    std::vector<int> rects;
    rects.reserve(font.glyphs.size());
    for (const game::FontGlyph& g : font.glyphs)
        rects.push_back(atlas->AddCustomRectFontGlyph(base, ImWchar(g.code), g.w, g.h, float(g.advance),
                                                      ImVec2(float(g.x_off), float(g.y_off))));
    unsigned char* pixels;
    int width, height;
    atlas->GetTexDataAsRGBA32(&pixels, &width, &height);
    for (size_t i = 0; i < font.glyphs.size(); i++) {
        const game::FontGlyph& g = font.glyphs[i];
        const ImFontAtlasCustomRect* r = atlas->GetCustomRectByIndex(rects[i]);
        if (!r) continue;
        for (int y = 0; y < g.h; y++)
            for (int x = 0; x < g.w; x++) {
                unsigned char* p = pixels + (size_t(r->Y + y) * width + r->X + x) * 4;
                p[0] = p[1] = p[2] = 255;
                p[3] = g.alpha[size_t(y) * g.w + x];
            }
    }
    return true;
}

}  // namespace ui
