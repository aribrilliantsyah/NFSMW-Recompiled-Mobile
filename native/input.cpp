// controllers through SDL on Android: as the SDK's runtime/core/input.cpp reads them, but the plug events come from
// the main thread's event loop (Android's input reaches SDL there). Each Xbox input takes the SDL gamepad button or
// trigger the launcher's controller mapping names ([padmap] in the settings file, GamepadMapActivity). Port 0 also
// takes the launcher shell's touch overlay (TouchControllerView, through native/activity.cpp) and a controller some
// phones pass on without its device (the loose one below).
#include "core/input.h"

#include <algorithm>
#include <array>
#include <bitset>
#include <cmath>
#include <cstdlib>
#include <iterator>
#include <mutex>
#include <string>

#include <SDL3/SDL.h>
#include <toml++/toml.hpp>

#include "app/app.h"
#include "common/mouse_keyboard.h"
#include "core/log.h"
#include "host.h"

namespace input {

namespace {

// a controller takes the lowest free port when it is plugged in and keeps it until it is unplugged
std::mutex g_lock;
SDL_Gamepad* g_pads[4] = {};

// ---- the launcher's mapping: the Xbox inputs in Settings.XBOX_INPUTS' order, each an SDL gamepad button or trigger
// by SDL's own name. The pad's digital bits (dpad, start, back, thumbs) and its analog buttons (A B X Y black white
// LT RT), -1 for neither
struct XboxInput {
    const char* key;          // its key in [padmap]
    const char* sdl_default;  // Settings.SDL_DEFAULTS
    int bit;
    int analog;
};
constexpr XboxInput kInputs[] = {
    {"a", "a", -1, 0},
    {"b", "b", -1, 1},
    {"x", "x", -1, 2},
    {"y", "y", -1, 3},
    {"black", "leftshoulder", -1, 4},
    {"white", "rightshoulder", -1, 5},
    {"lt", "lefttrigger", -1, 6},
    {"rt", "righttrigger", -1, 7},
    {"up", "dpup", 0, -1},
    {"down", "dpdown", 1, -1},
    {"left", "dpleft", 2, -1},
    {"right", "dpright", 3, -1},
    {"start", "start", 4, -1},
    {"back", "back", 5, -1},
    {"lthumb", "leftstick", 6, -1},
    {"rthumb", "rightstick", 7, -1},
};
constexpr size_t kInputCount = std::size(kInputs);

// what an Xbox input reads: a button, or a trigger's axis; neither for "none"
struct Source {
    SDL_GamepadButton button = SDL_GAMEPAD_BUTTON_INVALID;
    SDL_GamepadAxis axis = SDL_GAMEPAD_AXIS_INVALID;
};
struct PadMap {
    std::array<Source, kInputCount> sources;
    float deadzone = 0.08f;  // share of a stick's travel ignored around its centre
    bool invert_ly = false, invert_ry = false;
};
PadMap g_map;  // set before boot, read only after
bool g_map_loaded = false;

Source ParseSource(const std::string& name) {
    Source s;
    s.button = SDL_GetGamepadButtonFromString(name.c_str());
    if (s.button == SDL_GAMEPAD_BUTTON_INVALID) s.axis = SDL_GetGamepadAxisFromString(name.c_str());
    return s;
}

void AddPad(SDL_JoystickID id) {
    for (SDL_Gamepad* pad : g_pads)
        if (pad && SDL_GetGamepadID(pad) == id) return;
    SDL_Gamepad** slot = std::find(std::begin(g_pads), std::end(g_pads), nullptr);
    if (slot == std::end(g_pads)) return;
    *slot = SDL_OpenGamepad(id);
    const char* name = *slot ? SDL_GetGamepadName(*slot) : SDL_GetError();
    LOGI("input: controller %d %s: %s", int(slot - g_pads) + 1, *slot ? "plugged in" : "failed to open", name ? name : "");
}

void RemovePad(SDL_JoystickID id) {
    for (int i = 0; i < 4; i++) {
        if (!g_pads[i] || SDL_GetGamepadID(g_pads[i]) != id) continue;
        SDL_CloseGamepad(g_pads[i]);
        g_pads[i] = nullptr;
        LOGI("input: controller %d unplugged", i + 1);
    }
}

// a stick's axis past the dead zone, the rest of its travel stretched over the whole range
int16_t Stick(int v, bool invert) {
    float f = std::clamp(float(v) / 32767.0f, -1.0f, 1.0f);
    if (invert) f = -f;
    float dz = g_map.deadzone, mag = std::fabs(f);
    if (mag <= dz) return 0;
    f = std::copysign((mag - dz) / (1.0f - dz), f);
    return int16_t(std::lround(f * 32767.0f));
}

void Read(SDL_Gamepad* pad, Pad& p) {
    p.connected = true;
    for (size_t i = 0; i < kInputCount; i++) {
        const Source& s = g_map.sources[i];
        // 0 to 255: a button fully or not at all, a trigger as far as it is pulled
        int v = 0;
        if (s.button != SDL_GAMEPAD_BUTTON_INVALID) v = SDL_GetGamepadButton(pad, s.button) ? 255 : 0;
        else if (s.axis != SDL_GAMEPAD_AXIS_INVALID) v = std::clamp(int(SDL_GetGamepadAxis(pad, s.axis)) >> 7, 0, 255);
        const XboxInput& in = kInputs[i];
        if (in.analog >= 0) p.analog[in.analog] = uint8_t(std::max<int>(p.analog[in.analog], v));
        else if (v >= 128) p.buttons |= uint16_t(1u << in.bit);
    }
    auto axis = [&](SDL_GamepadAxis a) { return int(SDL_GetGamepadAxis(pad, a)); };
    // SDL's sticks have y down; not plain negation: -32768 has no positive
    p.lx = Stick(axis(SDL_GAMEPAD_AXIS_LEFTX), false);
    p.ly = Stick(-1 - axis(SDL_GAMEPAD_AXIS_LEFTY), g_map.invert_ly);
    p.rx = Stick(axis(SDL_GAMEPAD_AXIS_RIGHTX), false);
    p.ry = Stick(-1 - axis(SDL_GAMEPAD_AXIS_RIGHTY), g_map.invert_ry);
}

// the touch overlay's pad (TouchControllerView): the buttons as the pad's bits, A B X Y black white as bits 0-5 of
// face, the triggers 0 to 255, the sticks with y up
struct TouchPad {
    uint16_t buttons = 0;
    uint8_t face = 0, lt = 0, rt = 0;
    int16_t lx = 0, ly = 0, rx = 0, ry = 0;
};
std::mutex g_touch_lock;
TouchPad g_touch;

// A controller Android hands over without its device: some phones' game services (MIUI's on a Redmi Note 8 Pro)
// take a DualShock 4 and pass its buttons and sticks on as a virtual device, which SDL takes for no controller.
// GameActivity sends those here as Android's key codes and axes; they play port 0.
constexpr int kLooseCodes = 512;  // Android's key codes held, the controller's all under 320
struct Loose {
    std::bitset<kLooseCodes> keys;
    float lx = 0, ly = 0, rx = 0, ry = 0, lt = 0, rt = 0, hx = 0, hy = 0;
    bool seen = false;
};
std::mutex g_loose_lock;
Loose g_loose;

// Android's key code as the pad's, laid out as the launcher's default mapping: a button bit (dpad, start, back,
// thumbs) or an analog button (the shoulders black and white, L2 / R2 the triggers), -1 for neither
void LooseKey(int code, int& bit, int& analog) {
    bit = analog = -1;
    switch (code) {
        case 19: bit = 0; break;    // DPAD_UP
        case 20: bit = 1; break;    // DPAD_DOWN
        case 21: bit = 2; break;    // DPAD_LEFT
        case 22: bit = 3; break;    // DPAD_RIGHT
        case 108: bit = 4; break;   // BUTTON_START (Options)
        case 109: bit = 5; break;   // BUTTON_SELECT (Share)
        case 106: bit = 6; break;   // BUTTON_THUMBL
        case 107: bit = 7; break;   // BUTTON_THUMBR
        case 96: analog = 0; break;   // BUTTON_A (cross)
        case 97: analog = 1; break;   // BUTTON_B (circle)
        case 99: analog = 2; break;   // BUTTON_X (square)
        case 100: analog = 3; break;  // BUTTON_Y (triangle)
        case 102: analog = 4; break;  // BUTTON_L1: black
        case 103: analog = 5; break;  // BUTTON_R1: white
        case 104: analog = 6; break;  // BUTTON_L2: LT
        case 105: analog = 7; break;  // BUTTON_R2: RT
        default: break;
    }
}

int16_t Axis(float v) { return int16_t(std::clamp(v, -1.0f, 1.0f) * 32767.0f); }
uint8_t Trigger(float v) { return uint8_t(std::clamp(v, 0.0f, 1.0f) * 255.0f); }

// a stick pushed further than the one already read takes its place
void Further(int16_t& to, int16_t v) {
    if (std::abs(int(v)) > std::abs(int(to))) to = v;
}

void MergeTouch(Pad& p) {
    std::lock_guard lock(g_touch_lock);
    const TouchPad& t = g_touch;
    p.buttons |= t.buttons;
    for (int i = 0; i < 6; i++)
        if (t.face & (1 << i)) p.analog[i] = 0xFF;
    p.analog[6] = std::max(p.analog[6], t.lt);
    p.analog[7] = std::max(p.analog[7], t.rt);
    Further(p.lx, t.lx);
    Further(p.ly, t.ly);
    Further(p.rx, t.rx);
    Further(p.ry, t.ry);
}

// the loose controller's state on top of what SDL read: buttons added, the stick pushed further taken
void MergeLoose(Pad& p) {
    std::lock_guard lock(g_loose_lock);
    const Loose& l = g_loose;
    if (!l.seen) return;
    p.connected = true;
    for (int code = 0; code < kLooseCodes; code++) {
        if (!l.keys[code]) continue;
        int bit, analog;
        LooseKey(code, bit, analog);
        if (bit >= 0) p.buttons |= uint16_t(1u << bit);
        if (analog >= 0) p.analog[analog] = 0xFF;
    }
    // the hat as the dpad: x left -1 right 1, y up -1 down 1
    if (l.hy < -0.5f) p.buttons |= 1 << 0;
    if (l.hy > 0.5f) p.buttons |= 1 << 1;
    if (l.hx < -0.5f) p.buttons |= 1 << 2;
    if (l.hx > 0.5f) p.buttons |= 1 << 3;
    p.analog[6] = std::max(p.analog[6], Trigger(l.lt));
    p.analog[7] = std::max(p.analog[7], Trigger(l.rt));
    // Android's y is down, the pad's up
    Further(p.lx, Axis(l.lx));
    Further(p.ly, Axis(-l.ly));
    Further(p.rx, Axis(l.rx));
    Further(p.ry, Axis(-l.ry));
}

}  // namespace

void SetTouchPad(int buttons, int face, int lt, int rt, int lx, int ly) {
    std::lock_guard lock(g_touch_lock);
    g_touch.buttons = uint16_t(buttons & 0xFF);
    g_touch.face = uint8_t(face & 0x3F);
    g_touch.lt = uint8_t(std::clamp(lt, 0, 255));
    g_touch.rt = uint8_t(std::clamp(rt, 0, 255));
    g_touch.lx = int16_t(std::clamp(lx, -32768, 32767));
    g_touch.ly = int16_t(std::clamp(ly, -32768, 32767));
}

void SetTouchPadRight(int rx, int ry) {
    std::lock_guard lock(g_touch_lock);
    g_touch.rx = int16_t(std::clamp(rx, -32768, 32767));
    g_touch.ry = int16_t(std::clamp(ry, -32768, 32767));
}

void LooseKey(int code, bool down) {
    // every button kept, a generic controller's BUTTON_1 to 16 too
    if (code < 0 || code >= kLooseCodes) return;
    std::lock_guard lock(g_loose_lock);
    g_loose.seen = true;
    g_loose.keys[size_t(code)] = down;
}

void LooseAxes(float lx, float ly, float rx, float ry, float lt, float rt, float hx, float hy) {
    std::lock_guard lock(g_loose_lock);
    g_loose.seen = true;
    g_loose.lx = lx, g_loose.ly = ly, g_loose.rx = rx, g_loose.ry = ry;
    g_loose.lt = lt, g_loose.rt = rt, g_loose.hx = hx, g_loose.hy = hy;
}

void LooseRelease() {
    std::lock_guard lock(g_loose_lock);
    bool seen = g_loose.seen;
    g_loose = {};
    g_loose.seen = seen;
}

void LoadPadMap(const void* table) {
    const toml::table* t = static_cast<const toml::table*>(table);
    const toml::table* map = t ? (*t)["padmap"].as_table() : nullptr;
    PadMap m;
    for (size_t i = 0; i < kInputCount; i++) {
        std::string name = kInputs[i].sdl_default;
        if (map) name = (*map)[kInputs[i].key].value_or(name);
        m.sources[i] = ParseSource(name);
    }
    if (map) {
        m.deadzone = std::clamp(float((*map)["deadzone"].value_or(int64_t(8))) / 100.0f, 0.0f, 0.9f);
        m.invert_ly = (*map)["invert_ly"].value_or(false);
        m.invert_ry = (*map)["invert_ry"].value_or(false);
    }
    g_map = m;
    g_map_loaded = true;
}

void Init() {
    int v = SDL_GetVersion();
    LOGI("input: SDL %d.%d.%d", SDL_VERSIONNUM_MAJOR(v), SDL_VERSIONNUM_MINOR(v), SDL_VERSIONNUM_MICRO(v));
    if (!g_map_loaded) LoadPadMap(nullptr);  // no settings file yet: SDL's names as the launcher's defaults
    std::lock_guard lock(g_lock);
    int count = 0;
    if (SDL_JoystickID* ids = SDL_GetGamepads(&count)) {
        for (int i = 0; i < count; i++) AddPad(ids[i]);
        SDL_free(ids);
    }
}

void OnPadEvent(const SDL_Event& e) {
    std::lock_guard lock(g_lock);
    if (e.type == SDL_EVENT_GAMEPAD_ADDED) AddPad(e.gdevice.which);
    else if (e.type == SDL_EVENT_GAMEPAD_REMOVED) RemovePad(e.gdevice.which);
}

Pad Poll(int port) {
    Pad p;
    {
        std::lock_guard lock(g_lock);
        if (SDL_Gamepad* pad = port >= 0 && port < 4 ? g_pads[port] : nullptr) Read(pad, p);
    }
    if (port == 0) {
        MergeLoose(p);
        MergeTouch(p);
        touch::Apply(p);
    }
    if (app::kGame.pad) app::kGame.pad(port, p);
    return p;
}

// the motors run until set again; with no controller on the port, port 0's rumble is the phone's (GameActivity)
void Rumble(int port, uint16_t left, uint16_t right) {
    if (app::kGame.rumble) app::kGame.rumble(port, left, right);
    bool pad;
    {
        std::lock_guard lock(g_lock);
        pad = port >= 0 && port < 4 && g_pads[port];
        if (pad) SDL_RumbleGamepad(g_pads[port], left, right, 0);
    }
    if (!pad && port == 0) host::PhoneRumble(left, right);
}

}  // namespace input
