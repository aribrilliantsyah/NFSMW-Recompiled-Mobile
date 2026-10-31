// Android's input on port 0 beside the controllers and the launcher shell's touch overlay (TouchControllerView, which
// draws and reads the touch controls in Java): a keyboard and mouse when one is plugged in, as an Xbox controller
// (src/common/mouse_keyboard.h; SDL's keys arrive as the virtual keys of common/keys.h), and the game's menus by touch.
// The overlay takes the touches on its controls; the rest reach SDL here, and in a FEng menu they are its mouse
// (src/game/fe_mouse.cpp): a tap on the highlighted item presses A, a swipe steps the dpad, a drag in a menu that
// prompts the right stick is that stick. Android's back key is Start in a race, B in the menus.
#include "common/mouse_keyboard.h"

#include <SDL3/SDL.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <iterator>
#include <mutex>
#include <string>
#include <vector>

#include "app/app.h"
#include "common/aspect.h"
#include "common/fe_mouse.h"
#include "common/keys.h"
#include "common/options.h"
#include "host.h"

namespace input {

namespace {

std::atomic<bool> g_settle{false};
std::atomic<bool> g_keyboard{false};  // the keyboard or mouse used last
// the keys and mouse buttons held, by virtual key
std::array<std::atomic<bool>, 256> g_down{};

// what an action presses: 0-7 analog A B X Y black white LT RT, 8-15 the buttons bits, 16-19 the move keys,
// 20-23 the look keys
struct Target {
    const char* id;
    const char* defaults;
    int button;
};
constexpr int kFirstButtonBit = 8;
constexpr int kFirstMoveKey = 16;
constexpr int kStickMax = 32767;
// the controller's buttons and sticks, each a key of its own; [keys] in the settings file overrides the defaults
constexpr Target kActions[] = {
    {"move_up", "W", 16},
    {"move_down", "S", 17},
    {"move_left", "A", 18},
    {"move_right", "D", 19},
    {"button_a", "Space,K", 0},
    {"button_b", "L", 1},
    {"button_x", "J", 2},
    {"button_y", "I", 3},
    {"button_black", "O", 4},
    {"button_white", "U", 5},
    {"trigger_left", "Q", 6},
    {"trigger_right", "E", 7},
    {"dpad_up", "Up", 8},
    {"dpad_down", "Down", 9},
    {"dpad_left", "Left", 10},
    {"dpad_right", "Right", 11},
    {"start", "Enter,Escape", 12},
    {"back", "Backspace,Tab", 13},
    {"thumb_left", "C", 14},
    {"thumb_right", "V", 15},
    {"look_up", "Numpad8", 20},
    {"look_down", "Numpad5", 21},
    {"look_left", "Numpad4", 22},
    {"look_right", "Numpad6", 23},
};
constexpr size_t kCount = std::size(kActions);
struct Name {
    int vk;
    const char* name;
};
constexpr Name kNames[] = {
    {VK_LBUTTON, "LMB"}, {VK_RBUTTON, "RMB"}, {VK_MBUTTON, "MMB"}, {VK_XBUTTON1, "Mouse4"}, {VK_XBUTTON2, "Mouse5"},
    {VK_SPACE, "Space"}, {VK_RETURN, "Enter"}, {VK_ESCAPE, "Escape"}, {VK_BACK, "Backspace"}, {VK_TAB, "Tab"},
    {VK_SHIFT, "Shift"}, {VK_CONTROL, "Ctrl"}, {VK_MENU, "Alt"}, {VK_CAPITAL, "CapsLock"},
    {VK_UP, "Up"}, {VK_DOWN, "Down"}, {VK_LEFT, "Left"}, {VK_RIGHT, "Right"},
    {VK_INSERT, "Insert"}, {VK_DELETE, "Delete"}, {VK_HOME, "Home"}, {VK_END, "End"},
    {VK_PRIOR, "PageUp"}, {VK_NEXT, "PageDown"},
    {'A', "A"}, {'B', "B"}, {'C', "C"}, {'D', "D"}, {'E', "E"}, {'F', "F"}, {'G', "G"}, {'H', "H"}, {'I', "I"},
    {'J', "J"}, {'K', "K"}, {'L', "L"}, {'M', "M"}, {'N', "N"}, {'O', "O"}, {'P', "P"}, {'Q', "Q"}, {'R', "R"},
    {'S', "S"}, {'T', "T"}, {'U', "U"}, {'V', "V"}, {'W', "W"}, {'X', "X"}, {'Y', "Y"}, {'Z', "Z"},
    {'0', "0"}, {'1', "1"}, {'2', "2"}, {'3', "3"}, {'4', "4"}, {'5', "5"}, {'6', "6"}, {'7', "7"}, {'8', "8"},
    {'9', "9"},
    {VK_F1, "F1"}, {VK_F2, "F2"}, {VK_F4, "F4"}, {VK_F5, "F5"}, {VK_F6, "F6"}, {VK_F7, "F7"},
    {VK_F8, "F8"}, {VK_F9, "F9"}, {VK_F10, "F10"}, {VK_F11, "F11"}, {VK_F12, "F12"},
    {VK_NUMPAD0, "Numpad0"}, {VK_NUMPAD1, "Numpad1"}, {VK_NUMPAD2, "Numpad2"}, {VK_NUMPAD3, "Numpad3"},
    {VK_NUMPAD4, "Numpad4"}, {VK_NUMPAD5, "Numpad5"}, {VK_NUMPAD6, "Numpad6"}, {VK_NUMPAD7, "Numpad7"},
    {VK_NUMPAD8, "Numpad8"}, {VK_NUMPAD9, "Numpad9"}, {VK_ADD, "NumpadPlus"}, {VK_SUBTRACT, "NumpadMinus"},
    {VK_MULTIPLY, "NumpadStar"}, {VK_DIVIDE, "NumpadSlash"}, {VK_DECIMAL, "NumpadPeriod"},
    {VK_OEM_3, "Backtick"}, {VK_OEM_MINUS, "Minus"}, {VK_OEM_PLUS, "Equals"}, {VK_OEM_4, "LBracket"},
    {VK_OEM_6, "RBracket"}, {VK_OEM_5, "Backslash"}, {VK_OEM_1, "Semicolon"}, {VK_OEM_7, "Quote"},
    {VK_OEM_COMMA, "Comma"}, {VK_OEM_PERIOD, "Period"}, {VK_OEM_2, "Slash"},
};

// held from before the game had the input, these wait for release as the bound keys do
constexpr int kFixed[] = {VK_UP, VK_DOWN, VK_LEFT, VK_RIGHT, VK_RETURN, VK_BACK};

using Binds = std::array<std::vector<int>, kCount>;
Binds g_binds;  // set before boot, read only after

int Vk(const std::string& name) {
    for (const Name& n : kNames)
        if (strcasecmp(n.name, name.c_str()) == 0) return n.vk;
    return 0;
}

std::vector<int> Parse(const std::string& list) {
    std::vector<int> out;
    size_t start = 0;
    while (start <= list.size()) {
        size_t comma = list.find(',', start);
        if (comma == std::string::npos) comma = list.size();
        if (int vk = Vk(list.substr(start, comma - start))) out.push_back(vk);
        start = comma + 1;
    }
    return out;
}

bool Down(int vk) { return vk > 0 && vk < 256 && g_down[size_t(vk)]; }

// keys held from before the game had the input wait for release; true once none is held
bool UpdateSettle() {
    if (!g_settle) return true;
    for (auto& b : g_binds)
        for (int vk : b)
            if (Down(vk)) return false;
    for (int vk : kFixed)
        if (Down(vk)) return false;
    g_settle = false;
    return true;
}

// SDL's scancodes as the virtual keys (common/keys.h) the bindings name, by position as on a US keyboard
int VkOf(SDL_Scancode s) {
    if (s >= SDL_SCANCODE_A && s <= SDL_SCANCODE_Z) return 'A' + (s - SDL_SCANCODE_A);
    if (s >= SDL_SCANCODE_1 && s <= SDL_SCANCODE_9) return '1' + (s - SDL_SCANCODE_1);
    if (s >= SDL_SCANCODE_F1 && s <= SDL_SCANCODE_F12) return VK_F1 + (s - SDL_SCANCODE_F1);
    if (s >= SDL_SCANCODE_KP_1 && s <= SDL_SCANCODE_KP_9) return VK_NUMPAD1 + (s - SDL_SCANCODE_KP_1);
    switch (s) {
        case SDL_SCANCODE_0: return '0';
        case SDL_SCANCODE_RETURN: case SDL_SCANCODE_KP_ENTER: return VK_RETURN;
        case SDL_SCANCODE_ESCAPE: return VK_ESCAPE;
        case SDL_SCANCODE_BACKSPACE: return VK_BACK;
        case SDL_SCANCODE_TAB: return VK_TAB;
        case SDL_SCANCODE_SPACE: return VK_SPACE;
        case SDL_SCANCODE_LSHIFT: case SDL_SCANCODE_RSHIFT: return VK_SHIFT;
        case SDL_SCANCODE_LCTRL: case SDL_SCANCODE_RCTRL: return VK_CONTROL;
        case SDL_SCANCODE_LALT: case SDL_SCANCODE_RALT: return VK_MENU;
        case SDL_SCANCODE_CAPSLOCK: return VK_CAPITAL;
        case SDL_SCANCODE_UP: return VK_UP;
        case SDL_SCANCODE_DOWN: return VK_DOWN;
        case SDL_SCANCODE_LEFT: return VK_LEFT;
        case SDL_SCANCODE_RIGHT: return VK_RIGHT;
        case SDL_SCANCODE_INSERT: return VK_INSERT;
        case SDL_SCANCODE_DELETE: return VK_DELETE;
        case SDL_SCANCODE_HOME: return VK_HOME;
        case SDL_SCANCODE_END: return VK_END;
        case SDL_SCANCODE_PAGEUP: return VK_PRIOR;
        case SDL_SCANCODE_PAGEDOWN: return VK_NEXT;
        case SDL_SCANCODE_KP_0: return VK_NUMPAD0;
        case SDL_SCANCODE_KP_PLUS: return VK_ADD;
        case SDL_SCANCODE_KP_MINUS: return VK_SUBTRACT;
        case SDL_SCANCODE_KP_MULTIPLY: return VK_MULTIPLY;
        case SDL_SCANCODE_KP_DIVIDE: return VK_DIVIDE;
        case SDL_SCANCODE_KP_PERIOD: return VK_DECIMAL;
        case SDL_SCANCODE_GRAVE: return VK_OEM_3;
        case SDL_SCANCODE_MINUS: return VK_OEM_MINUS;
        case SDL_SCANCODE_EQUALS: return VK_OEM_PLUS;
        case SDL_SCANCODE_LEFTBRACKET: return VK_OEM_4;
        case SDL_SCANCODE_RIGHTBRACKET: return VK_OEM_6;
        case SDL_SCANCODE_BACKSLASH: return VK_OEM_5;
        case SDL_SCANCODE_SEMICOLON: return VK_OEM_1;
        case SDL_SCANCODE_APOSTROPHE: return VK_OEM_7;
        case SDL_SCANCODE_COMMA: return VK_OEM_COMMA;
        case SDL_SCANCODE_PERIOD: return VK_OEM_PERIOD;
        case SDL_SCANCODE_SLASH: return VK_OEM_2;
        default: return 0;
    }
}

int VkOfButton(uint8_t b) {
    switch (b) {
        case SDL_BUTTON_LEFT: return VK_LBUTTON;
        case SDL_BUTTON_RIGHT: return VK_RBUTTON;
        case SDL_BUTTON_MIDDLE: return VK_MBUTTON;
        case SDL_BUTTON_X1: return VK_XBUTTON1;
        case SDL_BUTTON_X2: return VK_XBUTTON2;
        default: return 0;
    }
}

}  // namespace

void ReleaseCursor() { g_settle = true; }

void Keyboard(Pad& p) {
    if (!host::Foreground()) {
        g_settle = true;
        return;
    }
    if (!UpdateSettle()) return;
    bool used = std::any_of(std::begin(kFixed), std::end(kFixed), Down);
    int stick[8] = {};  // move forward back left right, look up down left right
    for (size_t i = 0; i < kCount; i++) {
        if (!std::any_of(g_binds[i].begin(), g_binds[i].end(), Down)) continue;
        used = true;
        int b = kActions[i].button;
        if (b >= kFirstMoveKey) stick[b - kFirstMoveKey] = 1;
        else if (b < kFirstButtonBit) p.analog[b] = 0xFF;
        else p.buttons |= uint16_t(1u << (b - kFirstButtonBit));
    }
    if (int x = stick[3] - stick[2]) p.lx = int16_t(x * kStickMax);
    if (int y = stick[0] - stick[1]) p.ly = int16_t(y * kStickMax);
    if (int x = stick[7] - stick[6]) p.rx = int16_t(x * kStickMax);
    if (int y = stick[4] - stick[5]) p.ry = int16_t(y * kStickMax);
    if (used) g_keyboard = true;
}

void SeenPad(const Pad& p) {
    constexpr int16_t kStickPushed = 16000;
    constexpr uint8_t kAnalogPushed = 0x20;
    auto pushed = [](int16_t v) { return v > kStickPushed || v < -kStickPushed; };
    auto pressed = [](uint8_t a) { return a > kAnalogPushed; };
    bool analog = std::any_of(std::begin(p.analog), std::end(p.analog), pressed);
    bool sticks = pushed(p.lx) || pushed(p.ly) || pushed(p.rx) || pushed(p.ry);
    if (p.buttons || analog || sticks) g_keyboard = false;
}

bool KeyboardActive() { return g_keyboard; }

void LoadBindings() {
    const auto& keys = game::Opt().keys;
    for (size_t i = 0; i < kCount; i++) {
        auto it = keys.find(kActions[i].id);
        g_binds[i] = Parse(it != keys.end() ? it->second : kActions[i].defaults);
    }
}

}  // namespace input

namespace touch {

namespace {

using Clock = std::chrono::steady_clock;
uint64_t NowMs() {
    return uint64_t(std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now().time_since_epoch()).count()) + 1;
}

// what a finger on the screen does: FEng's pointer, a tap or the right stick in a menu prompting it (FEng's pointer
// would click whatever the drag began on, the map's GPS), the right stick, nothing (out of the menus)
enum class Role : uint8_t { None, FeMouse, FeTap, Look };
struct Finger {
    SDL_FingerID id = 0;
    Role role = Role::None;
    float x0 = 0, y0 = 0;  // where it went down, pixels
    float x = 0, y = 0;
};

constexpr int kBit = 8;  // kBit + n: buttons bit n (dpad up down left right, start, back, left thumb, right thumb)
constexpr int kAnalogA = 0, kAnalogB = 1;
constexpr float kLookRadius = 0.12f;  // screen heights: a drag that far is the right stick pushed all the way

std::mutex g_lock;
std::vector<Finger> g_fingers;
float g_rstick_x = 0, g_rstick_y = 0;  // -1 to 1, y up
std::atomic<uint64_t> g_back_until{0};  // the back key's tap of Start or B lasts until then
constexpr uint64_t kBackTapMs = 120;

float Width() {
    int w, h;
    host::WindowPixels(w, h);
    return float(std::max(w, 1));
}
float Height() {
    int w, h;
    host::WindowPixels(w, h);
    return float(std::max(h, 1));
}

bool FeMenu() { return game::FeMenuShown(); }

// Pad presses the FEng menus get from touches, one after another: a swipe's dpad steps (the list follows the finger:
// a swipe left or up goes to the next item, right or down; an arrow per kSwipeStep of the swipe), a tap on the
// highlighted item's A (src/game/fe_mouse.cpp)
constexpr float kSwipe = 0.06f, kSwipeStep = 0.12f;  // screen heights
constexpr int kMaxSwipeSteps = 6;
constexpr uint64_t kFePressMs = 60, kFeGapMs = 60;
struct FeQueue {
    int press = -1;  // kBit + dpad bit or kAnalogA
    int left = 0;
    uint64_t until = 0;  // the press is held until then, then let go until a gap later
    bool down = false;
} g_fe_queue;

// a tap's A waits this long, for FEng's mouse to have answered it if it does (src/game/fe_mouse.cpp's FeTakeAccept)
constexpr uint64_t kFeAcceptMs = 150;
uint64_t g_fe_accept_at = 0;

void FeQueuePresses(int press, int count) {
    if (g_fe_queue.left && g_fe_queue.press != press) g_fe_queue.left = 0;
    g_fe_queue.press = press;
    g_fe_queue.left += count;
}

// the press held now, -1 for none
int FeQueuePress() {
    FeQueue& q = g_fe_queue;
    uint64_t now = NowMs();
    if (now < q.until) return q.down ? q.press : -1;
    if (q.down) {
        q.down = false;
        q.until = now + kFeGapMs;
        return -1;
    }
    if (q.left <= 0) return -1;
    q.left--;
    q.down = true;
    q.until = now + kFePressMs;
    return q.press;
}

// a finger lifted from a FEng menu: a swipe steps the dpad, a tap that came down on the highlighted item presses A
void FeSwipe(const Finger& f) {
    float h = Height(), dx = f.x - f.x0, dy = f.y - f.y0;
    float along = std::max(std::fabs(dx), std::fabs(dy));
    if (along < kSwipe * h) {
        g_fe_accept_at = NowMs() + kFeAcceptMs;
        return;
    }
    game::FeTakeAccept();  // a swipe accepts nothing
    int steps = std::clamp(int(along / (kSwipeStep * h) + 0.5f), 1, kMaxSwipeSteps);
    int dpad = std::fabs(dx) > std::fabs(dy) ? (dx < 0 ? 3 : 2) : (dy < 0 ? 1 : 0);  // up down left right
    FeQueuePresses(kBit + dpad, steps);
}

// the FEng mouse at a finger, in shares of the frame as the presenter letterboxes it
void FePointer(const Finger& f, bool down) {
    float w = Width(), h = Height(), aspect = game::Aspect();
    float vw = std::min(w, h * aspect), vh = vw / aspect;
    game::FeTouch(down, (f.x - (w - vw) / 2) / vw, (f.y - (h - vh) / 2) / vh);
}

void Look(const Finger& f) {
    float r = kLookRadius * Height();
    float dx = (f.x - f.x0) / r, dy = (f.y - f.y0) / r;
    float len = std::sqrt(dx * dx + dy * dy);
    if (len > 1) dx /= len, dy /= len;
    g_rstick_x = dx;
    g_rstick_y = -dy;
}

void FingerDown(SDL_FingerID id, float x, float y) {
    // Android gives a new finger the id of one lifted: one still here lost its lift
    std::erase_if(g_fingers, [id](const Finger& o) { return o.id == id; });
    Finger f;
    f.id = id;
    f.x0 = f.x = x;
    f.y0 = f.y = y;
    if (FeMenu()) {
        f.role = game::CurrentFePrompts().right_stick ? Role::FeTap : Role::FeMouse;
        if (f.role == Role::FeMouse) FePointer(f, true);
    }
    g_fingers.push_back(f);
}

void FingerMove(SDL_FingerID id, float x, float y) {
    for (Finger& f : g_fingers) {
        if (f.id != id) continue;
        f.x = x;
        f.y = y;
        // a menu prompting the right stick (the showcase turning the car, the map's cursor): a drag past a tap's
        // reach is the stick from where it came down
        if (f.role == Role::FeTap && std::hypot(f.x - f.x0, f.y - f.y0) >= kSwipe * Height()) f.role = Role::Look;
        if (f.role == Role::FeMouse) FePointer(f, true);
        if (f.role == Role::Look) Look(f);
        break;
    }
}

void FingerUp(SDL_FingerID id) {
    for (const Finger& f : g_fingers) {
        if (f.id != id) continue;
        if (f.role == Role::FeMouse) {
            FePointer(f, false);
            FeSwipe(f);
        } else if (f.role == Role::FeTap) {  // a tap after all: FEng's pointer pressed there and let go
            FePointer(f, true);
            FePointer(f, false);
            FeSwipe(f);
        } else if (f.role == Role::Look) {
            g_rstick_x = g_rstick_y = 0;
        }
    }
    std::erase_if(g_fingers, [id](const Finger& f) { return f.id == id; });
}

// the triggers zoom wherever they are prompted with a label (the showcase, the world map): a label that is no action
// (the map's L is followed by its zoom, "ZOOM 1.5X", its R by nothing) gives way to ZOOM OUT and ZOOM IN (Rangga's
// FeLabel, its triggers' part). With no label after either (drag track select's region switch) they stay unnamed,
// and the overlay draws LT and RT
std::string TriggerLabel(const game::FePrompts& p, int prompt) {
    if (p.label[game::kFePromptLT].empty() && p.label[game::kFePromptRT].empty()) return {};
    auto action = [&](int i) {
        const std::string& l = p.label[i];
        return !l.empty() && std::none_of(l.begin(), l.end(), [](char c) { return c >= '0' && c <= '9'; });
    };
    // ZOOM OUT and ZOOM IN in English, the overlay's own: it draws their icons (PromptIcon's keys), no text
    if (!action(game::kFePromptLT) || !action(game::kFePromptRT))
        return prompt == game::kFePromptLT ? "ZOOM OUT" : "ZOOM IN";
    return p.label[prompt];
}

}  // namespace

std::optional<std::vector<MenuPrompt>> CurrentMenuPrompts() {
    if (!FeMenu()) return std::nullopt;
    game::FePrompts p = game::CurrentFePrompts();
    std::vector<MenuPrompt> out;
    // the prompt's index is its pad button: A B X Y LT RT; one with no label after its art is still prompted (Quick
    // Play's A, track select's L and R): the overlay draws its letter
    for (int i = 0; i < game::kFePromptCount; i++) {
        if (!p.shown[i]) continue;
        bool trigger = i == game::kFePromptLT || i == game::kFePromptRT;
        std::string label = trigger ? TriggerLabel(p, i) : p.label[i];
        // the triggers given way to the zoom have its icon whatever their string was (the map's "ZOOM 1.5X")
        std::string icon = label == "ZOOM OUT" ? "ZOOM_OUT" : label == "ZOOM IN" ? "ZOOM_IN" : PromptIcon(p.hash[i]);
        out.push_back({std::move(icon), std::move(label), i});
    }
    return out;
}

namespace {

void KeyEvent(const SDL_KeyboardEvent& k) {
    // Android's back key: the game's pause menu in a race, back out of the game's menus
    if (k.scancode == SDL_SCANCODE_AC_BACK) {
        if (k.down && !k.repeat) g_back_until = NowMs() + kBackTapMs;
        return;
    }
    int vk = input::VkOf(k.scancode);
    if (!vk) return;
    input::g_down[size_t(vk)] = k.down;
    if (k.down) input::g_keyboard = true;
}

}  // namespace

bool OnEvent(const SDL_Event& e) {
    switch (e.type) {
        case SDL_EVENT_KEY_DOWN:
        case SDL_EVENT_KEY_UP:
            KeyEvent(e.key);
            return true;
        case SDL_EVENT_MOUSE_MOTION:
            if (e.motion.which != SDL_TOUCH_MOUSEID) input::g_keyboard = true;
            return true;
        case SDL_EVENT_MOUSE_BUTTON_DOWN:
        case SDL_EVENT_MOUSE_BUTTON_UP:
            if (e.button.which == SDL_TOUCH_MOUSEID) return true;
            if (int vk = input::VkOfButton(e.button.button)) input::g_down[size_t(vk)] = e.button.down;
            input::g_keyboard = true;
            return true;
        case SDL_EVENT_WILL_ENTER_BACKGROUND: {
            // the fingers' lifts may not come while the app is away: none is held when it is back
            std::lock_guard lock(g_lock);
            g_fingers.clear();
            g_rstick_x = g_rstick_y = 0;
            return false;
        }
        case SDL_EVENT_FINGER_DOWN:
        case SDL_EVENT_FINGER_MOTION:
        case SDL_EVENT_FINGER_UP:
        case SDL_EVENT_FINGER_CANCELED: {
            float x = e.tfinger.x * Width(), y = e.tfinger.y * Height();
            std::lock_guard lock(g_lock);
            if (e.type == SDL_EVENT_FINGER_DOWN) FingerDown(e.tfinger.fingerID, x, y);
            else if (e.type == SDL_EVENT_FINGER_MOTION) FingerMove(e.tfinger.fingerID, x, y);
            else FingerUp(e.tfinger.fingerID);
            return true;
        }
        default:
            return false;
    }
}

void Apply(input::Pad& pad) {
    std::lock_guard lock(g_lock);
    bool used = false;
    if (g_fe_accept_at && NowMs() >= g_fe_accept_at) {
        g_fe_accept_at = 0;
        if (game::FeTakeAccept()) FeQueuePresses(kAnalogA, 1);
    }
    if (int p = FeQueuePress(); p >= 0) {
        used = true;
        if (p < kBit) pad.analog[p] = 0xFF;
        else pad.buttons |= uint16_t(1u << (p - kBit));
    }
    if (NowMs() < g_back_until) {
        used = true;
        if (FeMenu()) pad.analog[kAnalogB] = 0xFF;
        else pad.buttons |= uint16_t(1u << 4);  // Start
    }
    if (g_rstick_x || g_rstick_y) {
        used = true;
        pad.rx = int16_t(std::clamp(g_rstick_x, -1.0f, 1.0f) * 32767.0f);
        pad.ry = int16_t(std::clamp(g_rstick_y, -1.0f, 1.0f) * 32767.0f);
    }
    if (used) pad.connected = true;
}

}  // namespace touch
