// The recomp's settings in the game's own options menu, six categories (SCREEN, SPEED, VISUALS, CONTROLS, SYSTEM,
// CHEATS: src/common/settings_rows.cpp). UIOptionsMain (ctor 0x104C70, the title's OPTIONS and the pause menu's) is an
// icon scroller its Setup (0xED5A0) fills with IconOptions (0x4C bytes, ctor 0x138BC0); an icon's React (vtable + 4)
// sets the category, FEDatabase's options [+0x24], on Accept, and Accept's fade (0xE1FDE1D1) opens Options.fng or
// Pause_Options.fng, where UIOptionsScreen::Setup (0x1055B0) adds the category's widgets and finishes with 0x153080;
// a category past 9 gets none. So a category of ours is one more icon (category 0x20 + Category) and its rows are
// toggle widgets (0x5C bytes, ctor 0x13A800, added by 0x139D40) whose Act and Draw are host functions. The six sit in
// a menu of their own, SETTINGS, an icon of the main and pause menus beside their OPTIONS: it opens the options'
// package as OPTIONS does and that UIOptionsMain gets our icons in place of the game's; a category's Back comes back
// to it. The icons' textures are made here: GetTextureInfo (0xC5900) answers their hashes with a copy of the Audio
// icon's TextureInfo whose D3D texture is ours (category_icons.inc, tools/category_icons.py). The game's addresses
// are as Rangga's options_menu.cpp found them; the categories, icons and rows are ours.
#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_map>
#include <vector>

#include "common/aspect.h"
#include "common/fe_mouse.h"
#include "common/options.h"
#include "common/settings_menu.h"
#include "common/settings_rows.h"
#include "common/translations.h"
#include "core/log.h"
#include "cpu/x86_ops.h"
#include "kernel/kernel.h"
#include "kernel/memory.h"

namespace dispatch {
uint32_t HostThunk(X86Func* fn);  // sdk/runtime/cpu/dispatch.cpp
}

namespace game {

void SetOwnString(uint32_t hash, const std::string& text);  // prompts.cpp


namespace {

using kernel::CallGuest;
using kernel::R32;
using kernel::W32;

#include "game/category_icons.inc"  // kIconCount, kIcons: 64x64 ARGB rows top down

constexpr uint32_t kFEng = 0x48E94C;
constexpr uint32_t kFEDatabase = 0x48EDD4;
constexpr uint32_t kCategory = 0x24;
constexpr uint32_t kFirstCategory = 0x20;  // past the game's 0 to 9

// game functions
constexpr uint32_t kNew = 0x40110, kPrintf = 0xE11F0, kFindPackage = 0xE1B10, kFindObject = 0x15CA70;
constexpr uint32_t kSwitchPackage = 0xEB160;
constexpr uint32_t kIconOptionSize = 0x4C, kIconCtor = 0x138BC0, kIconDtor = 0x1031A0, kAddIcon = 0x12A120;
constexpr uint32_t kWidgetSize = 0x5C, kWidgetCtor = 0x13A800, kToggleVtable = 0x3D4CF0, kAddWidget = 0x139D40;
constexpr uint32_t kVtableSlots = 15;
constexpr uint32_t kWidgetEnabled = 0x28, kWidgetChanged = 0x2A, kWidgetTitle = 0x2C, kWidgetValue = 0x30;
constexpr uint32_t kPositionSlot = 0x10, kNormalSlot = 0x1C, kGreySlot = 0x20, kBlinkSlot = 0x38;
constexpr uint32_t kHighlightSlot = 0x24, kUnhighlightSlot = 0x28;
constexpr uint32_t kMainLast = 0x134, kMainInGame = 0x144, kMainCurrent = 0x38;
constexpr uint32_t kMainLabels[] = {0x128, 0x12C};
constexpr uint32_t kScreenPackage = 0x10, kScreenCurrent = 0x34, kScreenTitlePrefix = 0xAC, kScreenCount = 0x100;
constexpr uint32_t kScreenVisible = 0x108, kSetCurrent = 0x149E20;
constexpr uint32_t kHeaderIcon = 0x8007B4C, kHeaderTitle = 0x42ADB44C;
constexpr uint32_t kObjectType = 0x18, kObjectFlags = 0x1C, kImageTexture = 0x24;
constexpr uint32_t kTypeImage = 1, kTypeString = 2, kTypeLanguage = 5, kImageDirty = 0x2400000;
constexpr uint32_t kSetLanguageString = 0xEAB30;
// FEng messages
constexpr uint32_t kAccept = 0x0C407210, kLeft = 0x9120409E, kRight = 0xB5971BF1;
constexpr uint32_t kUp = 0x72619778, kDown = 0x911C0A4B;
constexpr uint32_t kFadeDone = 0xE1FDE1D1;
// return addresses: hooks after icons, after rows
constexpr uint32_t kAfterIcons = 0xED82C, kAfterRows = 0x105758;
constexpr uint32_t kPauseOptionsFng = 0x3D0604, kOptionsFng = 0x3D6A34;
constexpr uint32_t kScreenBack = 0x105479;
// menu setups
constexpr uint32_t kSetupFirst = 0xED5A0, kSetupLast = 0xED82C;
struct OptionsMenu {
    uint32_t after_icons, options_vtable, options_react;
};
constexpr OptionsMenu kOptionsMenus[] = {{0x104C0F, 0x3D534C, 0x0E3F30}, {0x105A5B, 0x3D537C, 0x0EEC30}};
constexpr uint32_t kTemplateTexture = 0xF37AF144;  // the Audio icon
constexpr uint32_t kIconHashes = 0x53430000;  // ours: "SC" + category, distinct from Rangga's "RC"
constexpr uint32_t kTitleHashes = 0x53430100;
constexpr uint32_t kStringHash = 0x157E40;
// text functions
constexpr uint32_t kFontOf = 0x137A40, kMeasure = 0x138060, kSetPosition = 0xEA970, kToggleWidgetPosition = 0x13A870;
constexpr uint32_t kWidgetX = 0x0C, kWidgetY = 0x10, kWidgetTitleWidth = 0x34, kWidgetHeight = 0x38;
constexpr uint32_t kStringFont = 0x24, kStringText = 0x64, kObjectData = 0x2C, kDataScaleX = 0x38;
constexpr float kTitleEnd = 53.0f;
constexpr uint32_t kVideoFlags = 0x48099C;
// mouse/touch
constexpr uint32_t kTestForIntersection = 0x10CF20;
constexpr uint32_t kWidgetLeft = 0x4C, kWidgetRight = 0x50, kObjectScript = 0x54, kScriptId = 0x30;
constexpr uint32_t kArrowPressed = 0xE647B375;
constexpr uint32_t kMouseStates = 0xAC, kMouseStateSize = 0x10, kGetPosition = 0xEA7E0;
constexpr uint32_t kPackageMouseCount = 0xB0;
constexpr uint32_t kStringBuffer = 0x64, kStringRoom = 0x68, kStringReserve = 0x15D680;
constexpr uint32_t kAcceptLeave = 0x406415E3;
constexpr uint32_t kFindImage = 0xEAAB0;
constexpr uint32_t kMainScroller = 0x2C, kScrollerPackage = 0x18, kScrollerPrefix = 0x1C, kScrollerCount = 0x24;

// state
uint32_t g_scratch = 0;
uint32_t g_icon_vtable = 0, g_widget_vtable = 0;
std::unordered_map<uint32_t, int> g_icons;  // IconOption → category index
uint32_t g_textures[kIconCount] = {};
bool g_settings_next = false;  // next UIOptionsMain is SETTINGS
uint32_t g_settings_react = 0;

// ---- text helpers ----

std::string TitleCase(std::string text) {
    static const char* const kKeep[] = {"FPS", "CPU", "GPU", "FXAA", "SSAA", "HDR", "CRT", "EA", "UI", "TV", "HUD", "V-Sync"};
    size_t start = 0;
    while (start < text.size()) {
        size_t end = text.find_first_of(" /()-", start);
        if (end == std::string::npos) end = text.size();
        std::string word = text.substr(start, end - start);
        bool keep = std::any_of(word.begin(), word.end(), [](char c) { return std::isdigit(uint8_t(c)); });
        for (const char* k : kKeep) keep |= word == k;
        for (size_t i = start + 1; !keep && i < end; i++)
            if (uint8_t(text[i]) < 0x80) text[i] = char(std::tolower(uint8_t(text[i])));
        start = end + 1;
    }
    return text;
}

std::string Latin1(const std::string& text) {
    std::string out;
    for (size_t i = 0; i < text.size(); i++) {
        uint8_t c = uint8_t(text[i]);
        if (c < 0x80) { out += char(c); }
        else if ((c & 0xE0) == 0xC0 && i + 1 < text.size()) {
            uint32_t cp = (c & 0x1Fu) << 6 | (uint8_t(text[++i]) & 0x3Fu);
            out += cp < 0x100 ? char(cp) : '?';
        } else {
            while (i + 1 < text.size() && (uint8_t(text[i + 1]) & 0xC0) == 0x80) i++;
            out += '?';
        }
    }
    return out;
}

uint32_t Scratch() {
    if (!g_scratch) g_scratch = mem::AllocSystem(512);
    return g_scratch;
}

void Print(X86Context& ctx, uint32_t object, const std::string& text) {
    if (!object) return;
    std::string latin = Latin1(text).substr(0, 499);
    if (latin.size() + 1 > R32(object + kStringRoom)) {
        ctx.ecx = object + kStringBuffer;
        CallGuest(ctx, kStringReserve, {uint32_t(latin.size() + 1)});
    }
    uint32_t s = Scratch();
    std::memcpy(kernel::HostPtr(s), "%s", 3);
    std::snprintf(static_cast<char*>(kernel::HostPtr(s + 4)), 500, "%s", latin.c_str());
    CallGuest(ctx, kPrintf, {object, s, s + 4});
    W32(object + kObjectFlags, R32(object + kObjectFlags) & ~0x2u);
}

uint32_t FindObject(X86Context& ctx, uint32_t package_name, uint32_t hash) {
    if (!package_name) return 0;
    ctx.ecx = R32(kFEng);
    uint32_t package = CallGuest(ctx, kFindPackage, {package_name});
    if (!package) return 0;
    ctx.ecx = package;
    return CallGuest(ctx, kFindObject, {hash});
}

uint32_t CallThis(X86Context& ctx, uint32_t self, uint32_t fn, std::initializer_list<uint32_t> args) {
    ctx.ecx = self;
    return CallGuest(ctx, fn, args);
}

float F32(uint32_t va) { float f; std::memcpy(&f, kernel::HostPtr(va), 4); return f; }
uint32_t Bits(float f) { uint32_t b; std::memcpy(&b, &f, 4); return b; }
uint32_t Peek(uint32_t va) { return va && mem::IsCommitted(va) && mem::IsCommitted(va + 3) ? R32(va) : 0; }

// ---- textures ----

uint32_t Swizzle(uint32_t x, uint32_t y) {
    uint32_t out = 0;
    for (uint32_t bit = 0; bit < 16; bit++) {
        out |= ((x >> bit) & 1) << (2 * bit);
        out |= ((y >> bit) & 1) << (2 * bit + 1);
    }
    return out;
}

uint32_t Log2(uint32_t v) { uint32_t n = 0; while ((1u << n) < v) n++; return n; }

uint32_t MakeTexture(X86Context& ctx, int cat) {
    constexpr uint32_t kInfoBytes = 0x80, kPlatBytes = 0x40, kD3D = 0x10, kInfoHash = 0x24;
    uint32_t tmpl = CallGuest(ctx, 0xC5900, {kTemplateTexture, 0, 0});
    if (!tmpl || !R32(tmpl)) return 0;
    uint32_t info = mem::AllocSystem(kInfoBytes);
    uint32_t plat = mem::AllocSystem(kPlatBytes);
    std::memcpy(kernel::HostPtr(info), kernel::HostPtr(tmpl), kInfoBytes);
    std::memcpy(kernel::HostPtr(plat), kernel::HostPtr(R32(tmpl)), kPlatBytes);
    W32(info, plat);
    W32(info + kInfoHash, kIconHashes + uint32_t(cat));
    uint32_t size = kIconSize, shift = Log2(size);
    uint32_t data = mem::AllocContiguous(size * size * 4, 0, 0xFFFFFFFF, 0x1000);
    const uint32_t* src = kIcons[cat];
    for (uint32_t y = 0; y < size; y++)
        for (uint32_t x = 0; x < size; x++) W32(data + Swizzle(x, y) * 4, src[y * size + x]);
    uint32_t d3d = plat + kD3D;
    uint32_t format = (R32(d3d + 12) & 0xFF) | 0x06u << 8 | 1u << 16 | shift << 20 | shift << 24;
    W32(d3d + 4, mem::PhysicalFromVirtual(data));
    W32(d3d + 8, 0);
    W32(d3d + 12, format);
    W32(d3d + 16, 0);
    return info;
}

// ---- category helpers ----

uint32_t Options() { return R32(R32(kFEDatabase) + 0x10); }

int CurrentCategory() {
    uint32_t options = R32(kFEDatabase) ? Options() : 0;
    if (!options) return -1;
    int cat = int(R32(options + kCategory)) - int(kFirstCategory);
    return cat >= 0 && cat < settings::kCount ? cat : -1;
}

std::string CategoryTitle(int cat) {
    return TitleCase(app::Ui(settings::Name(settings::Category(cat))));
}

// ---- icons ----

void IconReact(X86Context& ctx, uint8_t*) {
    auto it = g_icons.find(ctx.ecx);
    uint32_t esp = ctx.esp;
    // the SETTINGS menu-level icon: as OPTIONS beside it
    if (it != g_icons.end() && it->second == settings::kCount && g_settings_react) {
        CallThis(ctx, ctx.ecx, g_settings_react,
                 {R32(esp + 4), R32(esp + 8), R32(esp + 12), R32(esp + 16), R32(esp + 20)});
        if (R32(esp + 8) == kAccept) g_settings_next = true;
    } else if (it != g_icons.end() && R32(esp + 8) == kAccept) {
        W32(Options() + kCategory, kFirstCategory + uint32_t(it->second));
    }
    ctx.esp = esp + 4 + 0x14;
}

void PlaceBeside(uint32_t menu, uint32_t options_vtable) {
    uint32_t head = menu + 0x2C + 4, ours = 0, target = 0;
    for (uint32_t n = R32(head), k = 0; n && n != head && k < 64; n = R32(n), k++) {
        if (R32(n - 4) == g_icon_vtable) ours = n;
        else if (R32(n - 4) == options_vtable) target = n;
    }
    if (!ours || !target) return;
    kernel::G<uint8_t>(ours - 4 + 0x45) = kernel::G<uint8_t>(target - 4 + 0x45);
    if (R32(target) == ours) return;
    W32(R32(ours + 4), R32(ours));
    W32(R32(ours) + 4, R32(ours + 4));
    uint32_t next = R32(target);
    W32(ours, next);
    W32(ours + 4, target);
    W32(target, ours);
    W32(next + 4, ours);
}

bool SlotFree(X86Context& ctx, uint32_t menu) {
    uint32_t scroller = menu + kMainScroller;
    uint32_t s = Scratch();
    std::snprintf(static_cast<char*>(kernel::HostPtr(s)), 64, "%s%u",
                  kernel::ReadCString(R32(scroller + kScrollerPrefix), 32).c_str(), R32(scroller + kScrollerCount));
    uint32_t hash = CallGuest(ctx, kStringHash, {s});
    return CallGuest(ctx, kFindImage, {R32(scroller + kScrollerPackage), hash}) != 0;
}

// add category icons to the SETTINGS menu (0 to kCount-1), or the SETTINGS top-level icon (kCount)
void AddIcons(X86Context& ctx, uint32_t menu, int first, int last) {
    if (!g_icon_vtable) {
        g_icon_vtable = mem::AllocSystem(8);
        W32(g_icon_vtable, kIconDtor);
        W32(g_icon_vtable + 4, dispatch::HostThunk(IconReact));
    }
    g_icons.clear();
    for (int cat = first; cat < last && SlotFree(ctx, menu); cat++) {
        uint32_t option = CallGuest(ctx, kNew, {kIconOptionSize});
        if (!option) break;
        uint32_t hash = kIconHashes + uint32_t(cat);  // kCount: SETTINGS' own
        CallThis(ctx, option, kIconCtor, {hash, 0, 0});
        W32(option, g_icon_vtable);
        g_icons[option] = cat;
        CallThis(ctx, menu, kAddIcon, {option});
    }
}

// ---- rows ----

struct Window {
    uint32_t screen = 0;
    int category = -1;
    std::vector<uint32_t> widgets;
    size_t top = 0;
};
Window g_window;

const std::vector<settings::Row>& Rows() {
    static const std::vector<settings::Row> empty;
    int cat = g_window.category;
    return cat >= 0 && cat < settings::kCount ? settings::Rows(settings::Category(cat)) : empty;
}

const settings::Row* RowOf(uint32_t widget) {
    const auto& rows = Rows();
    for (size_t i = 0; i < g_window.widgets.size(); i++)
        if (g_window.widgets[i] == widget && g_window.top + i < rows.size()) return &rows[g_window.top + i];
    return nullptr;
}

void AlignTitle(X86Context& ctx, uint32_t widget) {
    uint32_t title = R32(widget + kWidgetTitle);
    if (!title || !R32(title + kStringText)) return;
    uint32_t font = CallGuest(ctx, kFontOf, {R32(title + kStringFont)});
    if (!font) return;
    CallThis(ctx, font, kMeasure, {R32(title + kStringText), 0});
    float width = float(X87_ST(0)) * F32(R32(title + kObjectData) + kDataScaleX);
    ctx.top = (ctx.top + 1) & 7;
    if (!(kernel::G<uint8_t>(kVideoFlags) & 1)) width *= 640.0f / (480.0f * game::Aspect());
    float x = F32(widget + kWidgetX) + F32(widget + kWidgetTitleWidth) + kTitleEnd - width * 0.5f;
    float y = F32(widget + kWidgetY) + F32(widget + kWidgetHeight) * 0.5f;
    CallGuest(ctx, kSetPosition, {title, Bits(x), Bits(y)});
}

void WidgetPosition(X86Context& ctx, uint8_t*) {
    uint32_t widget = ctx.ecx, esp = ctx.esp;
    CallThis(ctx, widget, kToggleWidgetPosition, {});
    if (RowOf(widget)) AlignTitle(ctx, widget);
    ctx.esp = esp + 4;
}

// an object's colour: its data's (+0x2C) first field, FEColor (b, g, r, a: 0 to 255 each), as hud_layout.cpp has the
// place after it (FEObjData: colour +0, pivot +0x10, place +0x1C)
void Colour(uint32_t object, uint32_t argb) {
    uint32_t data = object ? Peek(object + kObjectData) : 0;
    if (!data || !mem::IsCommitted(data + 15)) return;
    W32(data, argb & 0xFF), W32(data + 4, argb >> 8 & 0xFF), W32(data + 8, argb >> 16 & 0xFF);
    W32(data + 12, argb >> 24);
}

void WidgetDraw(X86Context& ctx, uint8_t*) {
    uint32_t widget = ctx.ecx;
    if (const settings::Row* r = RowOf(widget)) {
        std::string label = app::Ui(r->label, true);
        std::string value = settings::Value(*r);
        Print(ctx, R32(widget + kWidgetTitle), TitleCase(label));
        Print(ctx, R32(widget + kWidgetValue), TitleCase(value));
        uint32_t esp = ctx.esp;
        CallThis(ctx, widget, R32(R32(widget) + kPositionSlot), {});
        ctx.esp = esp;
    }
    ctx.esp += 4;
}

// CHEATS' rows: their titles, the highlighted one's arrows too, in the category's orange; again each update, as the
// widgets' highlight scripts set their own colours
void TintRows() {
    if (g_window.category != settings::kCheats || CurrentCategory() != settings::kCheats) return;
    uint32_t argb = settings::Color(settings::kCheats);
    uint32_t current = Peek(g_window.screen + kScreenCurrent);
    for (uint32_t widget : g_window.widgets) {
        if (Peek(widget) != g_widget_vtable || !RowOf(widget)) continue;
        Colour(Peek(widget + kWidgetTitle), argb);
        if (widget != current) continue;
        Colour(Peek(widget + kWidgetLeft), argb);
        Colour(Peek(widget + kWidgetRight), argb);
    }
}

void Redraw(X86Context& ctx, uint32_t widget) {
    if (!RowOf(widget) || R32(widget) != g_widget_vtable) return;
    uint32_t saved = ctx.esp;
    ctx.esp -= 4;
    ctx.ecx = widget;
    WidgetDraw(ctx, nullptr);
    ctx.esp = saved;
}

void SetCursorState(X86Context& ctx, uint32_t widget) {
    uint32_t screen = g_window.screen;
    if (widget == R32(screen + kScreenCurrent))
        CallThis(ctx, widget, R32(R32(widget) + kHighlightSlot), {R32(screen + kScreenPackage)});
    else
        CallThis(ctx, widget, R32(R32(widget) + kUnhighlightSlot), {});
}

void RedrawAll(X86Context& ctx) {
    const auto& rows = Rows();
    for (size_t i = 0; i < g_window.widgets.size() && g_window.top + i < rows.size(); i++) {
        uint32_t widget = g_window.widgets[i];
        SetCursorState(ctx, widget);
        Redraw(ctx, widget);
    }
}

void Step(X86Context& ctx, uint32_t widget, uint32_t message) {
    const settings::Row* r = RowOf(widget);
    if (!r || r->kind == settings::Kind::Action) return;
    settings::Value(*r, message == kLeft ? -1 : 1);
    CallThis(ctx, widget, R32(R32(widget) + kBlinkSlot), {message});
    kernel::G<uint8_t>(widget + kWidgetChanged) = 1;
    RedrawAll(ctx);
}

// ---- mouse/touch ----

uint32_t g_pending = 0;
int g_pending_updates = 0;
constexpr int kPendingUpdates = 6;

bool LiveWidgets(X86Context& ctx, uint32_t& current, uint32_t* package = nullptr) {
    uint32_t screen = g_window.screen;
    if (!screen || g_window.widgets.empty() || CurrentCategory() != g_window.category) return false;
    uint32_t name = Peek(screen + kScreenPackage);
    if (!name || !Peek(name)) return false;
    std::string text = kernel::ReadCString(name, 32);
    if (text != kernel::ReadCString(kOptionsFng, 32) && text != kernel::ReadCString(kPauseOptionsFng, 32)) return false;
    ctx.ecx = R32(kFEng);
    uint32_t found = CallGuest(ctx, kFindPackage, {name});
    if (!found) return false;
    if (package) *package = found;
    current = Peek(screen + kScreenCurrent);
    return true;
}

bool ArrowPressed(uint32_t widget) {
    for (uint32_t field : {kWidgetLeft, kWidgetRight})
        if (Peek(Peek(Peek(widget + field) + kObjectScript) + kScriptId) == kArrowPressed) return true;
    return false;
}

uint32_t PressedRow(X86Context& ctx, uint32_t& current) {
    current = 0;
    if (!LiveWidgets(ctx, current)) return 0;
    for (uint32_t widget : g_window.widgets)
        if (Peek(widget) == g_widget_vtable && kernel::G<uint8_t>(widget + kWidgetEnabled) && ArrowPressed(widget))
            return widget;
    return 0;
}

void FocusTouchedRow(X86Context& ctx) {
    uint32_t current;
    uint32_t pressed = PressedRow(ctx, current);
    if (pressed && pressed != current)
        CallThis(ctx, g_window.screen, kSetCurrent, {pressed});
    if (!g_pending || (!pressed && ++g_pending_updates < kPendingUpdates)) return;
    uint32_t message = g_pending;
    g_pending = 0;
    if (current) Step(ctx, current, message);
}

bool GroupOffset(X86Context& ctx, uint32_t package, float& ox, float& oy) {
    uint32_t states = Peek(package + kMouseStates), count = Peek(package + kPackageMouseCount);
    for (uint32_t i = 0; states && i < count && i < 256; i++) {
        uint32_t entry = states + i * kMouseStateSize, object = Peek(entry);
        if (!object) continue;
        for (uint32_t widget : g_window.widgets) {
            if (object != Peek(widget + kWidgetLeft) && object != Peek(widget + kWidgetRight)) continue;
            uint32_t s = Scratch();
            CallGuest(ctx, kGetPosition, {object, s, s + 4});
            ox = F32(entry + 4) - F32(s);
            oy = F32(entry + 8) - F32(s + 4);
            return true;
        }
    }
    return false;
}

bool Hits(X86Context& ctx, float x, float y, uint32_t object) {
    return object && (CallGuest(ctx, kTestForIntersection, {Bits(x), Bits(y), object}) & 0xFF);
}

uint32_t RowAt(X86Context& ctx, float x, float y) {
    uint32_t current, package;
    float ox, oy;
    if (!LiveWidgets(ctx, current, &package) || !GroupOffset(ctx, package, ox, oy)) return 0;
    x -= ox; y -= oy;
    for (uint32_t widget : g_window.widgets) {
        if (Peek(widget) != g_widget_vtable || !kernel::G<uint8_t>(widget + kWidgetEnabled)) continue;
        for (uint32_t field : {kWidgetTitle, kWidgetValue, kWidgetLeft, kWidgetRight})
            if (Hits(ctx, x, y, Peek(widget + field))) return widget;
    }
    return 0;
}

bool OpenRow(X86Context& ctx, uint32_t widget) {
    const settings::Row* r = RowOf(widget);
    if (!r || r->kind != settings::Kind::Action) return false;
    settings::Activate(*r);
    Redraw(ctx, widget);
    return true;
}

uint32_t g_tap_row = 0;
bool g_finger_was_down = false;
float g_finger_x = 0, g_finger_y = 0;

void TapRows(X86Context& ctx) {
    float x, y;
    bool down = FeFinger(x, y);
    if (down) g_finger_x = x, g_finger_y = y;
    if (down && !g_finger_was_down) g_tap_row = RowAt(ctx, x, y);
    bool lifted = !down && g_finger_was_down;
    g_finger_was_down = down;
    if (!lifted || !g_tap_row) return;
    uint32_t widget = g_tap_row;
    g_tap_row = 0;
    uint32_t current;
    if (RowAt(ctx, g_finger_x, g_finger_y) != widget || !LiveWidgets(ctx, current)) return;
    if (widget != current) CallThis(ctx, g_window.screen, kSetCurrent, {widget});
    if (OpenRow(ctx, widget)) FeTakeAccept();
}

void WidgetAct(X86Context& ctx, uint8_t*) {
    uint32_t widget = ctx.ecx, message = R32(ctx.esp + 8);
    uint32_t esp = ctx.esp;
    bool step = message == kLeft || message == kRight;
    if (step && FeMouseDown()) {
        g_pending = message;
        g_pending_updates = 0;
    } else if (step) {
        Step(ctx, widget, message);
    } else if (message == kAccept) {
        OpenRow(ctx, widget);
    }
    ctx.esp = esp + 4 + 8;
}

bool RowSlot(X86Context& ctx, uint32_t screen, uint32_t n) {
    uint32_t s = Scratch();
    std::snprintf(static_cast<char*>(kernel::HostPtr(s)), 64, "%s%u",
                  kernel::ReadCString(R32(screen + kScreenTitlePrefix), 32).c_str(), n);
    uint32_t hash = CallGuest(ctx, kStringHash, {s});
    uint32_t package = R32(screen + kScreenPackage);
    return FindObject(ctx, package, hash) != 0;
}

void AddRows(X86Context& ctx, uint32_t screen, int category) {
    if (!g_widget_vtable) {
        g_widget_vtable = mem::AllocSystem(kVtableSlots * 4);
        for (uint32_t i = 0; i < kVtableSlots; i++) W32(g_widget_vtable + i * 4, R32(kToggleVtable + i * 4));
        W32(g_widget_vtable + 4, dispatch::HostThunk(WidgetAct));
        W32(g_widget_vtable + 12, dispatch::HostThunk(WidgetDraw));
        W32(g_widget_vtable + kPositionSlot, dispatch::HostThunk(WidgetPosition));
    }
    g_window = Window{screen, category, {}, 0};
    g_tap_row = 0;
    const auto& rows = Rows();
    size_t visible = std::max<uint32_t>(R32(screen + kScreenVisible), 1);
    while (g_window.widgets.size() < rows.size() && g_window.widgets.size() < visible &&
           RowSlot(ctx, screen, R32(screen + kScreenCount))) {
        uint32_t widget = CallGuest(ctx, kNew, {kWidgetSize});
        if (!widget) break;
        CallThis(ctx, widget, kWidgetCtor, {1u});  // all rows enabled
        W32(widget, g_widget_vtable);
        g_window.widgets.push_back(widget);
        CallThis(ctx, screen, kAddWidget, {widget, 1});
        SetCursorState(ctx, widget);
    }
    // header icon and title
    uint32_t package = R32(screen + kScreenPackage);
    uint32_t icon = FindObject(ctx, package, kHeaderIcon);
    if (icon && R32(icon + kObjectType) == kTypeImage) {
        W32(icon + kImageTexture, kIconHashes + uint32_t(category));
        W32(icon + kObjectFlags, R32(icon + kObjectFlags) | kImageDirty);
    }
    uint32_t title = FindObject(ctx, package, kHeaderTitle);
    // a plain string printed; another kind (5, the pause menu's) takes a language hash (0xEAB30: package, object,
    // hash), one of ours that SearchForString answers with the category's name (prompts.cpp)
    if (title && R32(title + kObjectType) == kTypeString) {
        Print(ctx, title, CategoryTitle(category));
    } else if (title && R32(title + kObjectType) == kTypeLanguage) {
        uint32_t hash = kTitleHashes + uint32_t(category);
        SetOwnString(hash, Latin1(CategoryTitle(category)));
        CallGuest(ctx, kSetLanguageString, {package, kHeaderTitle, hash});
    }
}

bool Scroll(X86Context& ctx, uint32_t screen, int step) {
    const auto& rows = Rows();
    size_t shown = g_window.widgets.size();
    if (screen != g_window.screen || rows.size() <= shown) return false;
    size_t at = 0;
    while (at < shown && g_window.widgets[at] != R32(screen + kScreenCurrent)) at++;
    if (at == shown) return false;
    long target = long(g_window.top + at) + step;
    if (target < 0 || target >= long(rows.size())) return true;
    size_t top = g_window.top;
    if (size_t(target) < top) top = size_t(target);
    else if (size_t(target) >= top + shown) top = size_t(target) - shown + 1;
    if (top == g_window.top) return false;
    g_window.top = top;
    RedrawAll(ctx);
    CallThis(ctx, screen, kSetCurrent, {g_window.widgets[size_t(target) - top]});
    return true;
}

// ---- the ☰'s request (RequestOpenSettings) ----
//
// In a race the game is paused first: while the request waits with no menu up, the touch controls pulse START
// (SettingsWantPause, native/touch.cpp); once the pause menu is up the settings open over it as its OPTIONS opens the
// game's (0x10F135: the FEDatabase flag 0x10, Pause_Main.fng with 1), so Back comes back to it and the race goes on only
// on its Resume. Out of a race they open from the menu up, as the main menu's OPTIONS opens MainMenu_Sub.fng
// (0x1169B0). A category opens its options screen at once, as Accept on its icon does (0x104D40 below)
constexpr int kNoRequest = -2;
constexpr uint64_t kRequestMs = 6000;  // a request the game never got to (a cutscene, a loading screen) is dropped
constexpr uint32_t kPauseMainFng = 0x3D0618, kMainMenuSubFng = 0x3D5DDC;
constexpr uint32_t kFlags = 0x12C, kFlagOptions = 0x10;
std::atomic<int> g_request{kNoRequest};
std::atomic<uint64_t> g_request_at{0};
std::atomic<bool> g_pause_sent{false};  // START once a request: a second would resume the race
bool g_flag_ours = false;  // OpenRequested set the FEDatabase options flag, not a game's OPTIONS

uint64_t NowMs() {
    using namespace std::chrono;
    return uint64_t(duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count());
}

// a package name (guest string) of the options' own: the game's options or SETTINGS, their screens
bool OptionsPackage(uint32_t name) {
    if (!name || !Peek(name)) return false;
    std::string text = kernel::ReadCString(name, 32);
    for (uint32_t own : {kOptionsFng, kPauseOptionsFng, kPauseMainFng, kMainMenuSubFng})
        if (text == kernel::ReadCString(own, 32)) return true;
    return false;
}

// the menu up is one of the options' own packages: the settings are open, or the game's options are
bool InOptions(uint32_t package) { return OptionsPackage(Peek(package + 0xC)); }

// SETTINGS left for another package: the next UIOptionsMain is the game's, and the options flag ours goes
void LeaveSettings() {
    g_settings_next = false;
    if (!g_flag_ours) return;
    g_flag_ours = false;
    if (uint32_t db = R32(kFEDatabase)) W32(db + kFlags, R32(db + kFlags) & ~kFlagOptions);
}

void OpenRequested(X86Context& ctx, int category) {
    uint32_t db = R32(kFEDatabase);
    if (!db || !R32(db + 0x10)) return;
    bool pause = Racing();
    g_flag_ours = !(R32(db + kFlags) & kFlagOptions);
    W32(db + kFlags, R32(db + kFlags) | kFlagOptions);
    uint32_t package;
    if (category >= 0 && category < settings::kCount) {
        W32(Options() + kCategory, kFirstCategory + uint32_t(category));  // its Back comes to the categories (0xEB160)
        package = pause ? kPauseOptionsFng : kOptionsFng;
    } else {
        g_settings_next = true;  // the UIOptionsMain it opens is SETTINGS' (0x128AB0)
        package = pause ? kPauseMainFng : kMainMenuSubFng;
    }
    CallThis(ctx, R32(kFEng), kSwitchPackage, {package, pause ? 1u : 0u, 0, 0});
}

}  // namespace

void RequestOpenSettings(int category) {
    g_request_at = NowMs();
    g_pause_sent = false;
    g_request = category < 0 ? -1 : category;
}

bool SettingsTakePause() {
    return g_request != kNoRequest && Racing() && !FeMenuShown() && !g_pause_sent.exchange(true);
}

// game thread, after FEngine::Update (hud_layout.cpp's wrapper): a request opens once a menu is up
void SettingsTick(X86Context& ctx) {
    int request = g_request;
    if (request == kNoRequest) return;
    if (NowMs() - g_request_at > kRequestMs) {
        g_request = kNoRequest;
        return;
    }
    uint32_t package = FeMenuPackage();
    if (!package) return;  // in a race: SettingsTakePause has START pressed
    g_request = kNoRequest;
    if (InOptions(package)) return;
    uint32_t saved[] = {ctx.eax, ctx.ecx, ctx.edx, ctx.ebx, ctx.esi, ctx.edi, ctx.ebp};
    OpenRequested(ctx, request);
    ctx.eax = saved[0], ctx.ecx = saved[1], ctx.edx = saved[2], ctx.ebx = saved[3], ctx.esi = saved[4];
    ctx.edi = saved[5], ctx.ebp = saved[6];
}

// game thread tick from fe_mouse.cpp's GetMouseInfo: the rows' touches
void SettingsMenuTick(X86Context& ctx) {
    FocusTouchedRow(ctx);
    TapRows(ctx);
    TintRows();
}

}  // namespace game

using namespace game;

// GetTextureInfo(hash, default, unloaded), cdecl: our icons' hashes get our textures (the categories', then SETTINGS')
X86_EXTERN_FUNC(__imp__sub_000C5900);
X86_FUNC(sub_000C5900) {
    uint32_t icon = kernel::R32(ctx.esp + 4) - kIconHashes;
    if (icon >= uint32_t(kIconCount)) return __imp__sub_000C5900(ctx, base);
    if (!g_textures[icon]) g_textures[icon] = MakeTexture(ctx, int(icon));
    ctx.eax = g_textures[icon];
    ctx.esp += 4;
}

// cdecl (package), called by UIOptionsMain::Setup and menus after their icons: ours go after the game's
X86_EXTERN_FUNC(__imp__sub_00128AB0);
X86_FUNC(sub_00128AB0) {
    uint32_t from = kernel::R32(ctx.esp), menu = ctx.esi, ecx = ctx.ecx;
    bool settings = false;
    if (from == kAfterIcons) {
        settings = g_settings_next;
        g_settings_next = false;
        if (settings) AddIcons(ctx, menu, 0, settings::kCount);
        else g_icons.clear();
    } else {
        for (const OptionsMenu& m : kOptionsMenus) {
            if (from != m.after_icons) continue;
            g_settings_next = false;
            g_settings_react = m.options_react;
            // SETTINGS (icon kCount) beside OPTIONS
            AddIcons(ctx, menu, settings::kCount, settings::kCount + 1);
            PlaceBeside(menu, m.options_vtable);
        }
    }
    ctx.ecx = ecx;
    __imp__sub_00128AB0(ctx, base);
    if (settings) {
        int cat = CurrentCategory();
        ctx.eax = (ctx.eax & ~0xFFu) | uint32_t(cat >= 0 && cat < settings::kCount ? cat : 0);
    }
}

// the scroller's add (option), thiscall: SETTINGS' UIOptionsMain takes none of the game's icons
X86_EXTERN_FUNC(__imp__sub_0012A120);
X86_FUNC(sub_0012A120) {
    uint32_t from = kernel::R32(ctx.esp), option = kernel::R32(ctx.esp + 4);
    if (!g_settings_next || from <= kSetupFirst || from > kSetupLast || !option ||
        kernel::R32(option) == g_icon_vtable)
        return __imp__sub_0012A120(ctx, base);
    uint32_t esp = ctx.esp;
    CallThis(ctx, option, kernel::R32(kernel::R32(option)), {1});
    ctx.esp = esp + 4 + 4;
}

// UIOptionsMain header, thiscall: our icon's name
X86_EXTERN_FUNC(__imp__sub_0012A030);
X86_FUNC(sub_0012A030) {
    uint32_t menu = ctx.ecx;
    __imp__sub_0012A030(ctx, base);
    uint32_t current = kernel::R32(menu + kMainCurrent);
    auto it = g_icons.find(current);
    if (it == g_icons.end() || kernel::R32(current) != g_icon_vtable) return;
    uint32_t eax = ctx.eax;
    std::string name;
    if (it->second < settings::kCount)
        name = CategoryTitle(it->second);
    else
        name = TitleCase(app::Ui("SETTINGS"));
    for (uint32_t label : kMainLabels) Print(ctx, kernel::R32(menu + label), name);
    ctx.eax = eax;
}

// UIOptionsMain::Notification(message, ...), thiscall: Accept's fade on our category opens the options screen
X86_EXTERN_FUNC(__imp__sub_00104D40);
X86_FUNC(sub_00104D40) {
    uint32_t menu = ctx.ecx;
    bool fade = kernel::R32(ctx.esp + 4) == kFadeDone && kernel::R32(menu + kMainLast) == kAccept;
    __imp__sub_00104D40(ctx, base);
    if (!fade || CurrentCategory() < 0) return;
    uint32_t flags = kernel::R32(kernel::R32(kFEDatabase) + 0x12C);
    bool pause = kernel::G<uint8_t>(menu + kMainInGame) && !(flags & 0x8) && !(flags & 0x40);
    uint32_t eax = ctx.eax;
    CallThis(ctx, kernel::R32(kFEng), kSwitchPackage,
             {pause ? kPauseOptionsFng : kOptionsFng, pause ? 1u : 0u, 0, 0});
    ctx.eax = eax;
}

// UIOptionsScreen::Setup's finisher: our category's rows before it
X86_EXTERN_FUNC(__imp__sub_00153080);
X86_FUNC(sub_00153080) {
    int cat = kernel::R32(ctx.esp) == kAfterRows ? CurrentCategory() : -1;
    if (cat >= 0) {
        uint32_t screen = ctx.ecx;
        AddRows(ctx, screen, cat);
        ctx.ecx = screen;
    }
    __imp__sub_00153080(ctx, base);
}

// UIWidgetMenu::Notification: scroll and accept on our rows
X86_EXTERN_FUNC(__imp__sub_00155D50);
X86_FUNC(sub_00155D50) {
    uint32_t message = kernel::R32(ctx.esp + 4);
    if ((message == kAccept || message == kAcceptLeave) && ctx.ecx == g_window.screen && CurrentCategory() >= 0) {
        uint32_t ecx = ctx.ecx;
        if (OpenRow(ctx, kernel::R32(ecx + kScreenCurrent))) {
            ctx.esp += 4 + 0x10;
            return;
        }
        ctx.ecx = ecx;
    }
    if ((message == kUp || message == kDown) && ctx.ecx == g_window.screen && CurrentCategory() >= 0) {
        uint32_t ecx = ctx.ecx;
        if (Scroll(ctx, ecx, message == kDown ? 1 : -1)) {
            ctx.esp += 4 + 0x10;
            return;
        }
        ctx.ecx = ecx;
    }
    __imp__sub_00155D50(ctx, base);
}

// UIOptionsScreen's defaults (restore), thiscall (confirmed): on our category, its defaults (settings::Defaults)
X86_EXTERN_FUNC(__imp__sub_000EE570);
X86_FUNC(sub_000EE570) {
    int cat = CurrentCategory();
    if (cat < 0 || ctx.ecx != g_window.screen) return __imp__sub_000EE570(ctx, base);
    if (kernel::R32(ctx.esp + 4) & 0xFF) settings::Defaults(settings::Category(cat));
    uint32_t ecx = ctx.ecx;
    RedrawAll(ctx);
    ctx.ecx = ecx;
    ctx.esp += 4 + 4;
}

// SwitchPackage (name, arg, 0, 0), thiscall: an options screen of ours leaving for the options (0x105479) comes back
// to SETTINGS' icons. Any other package than the options' own ends SETTINGS: the next UIOptionsMain is the game's,
// and the FEDatabase options flag the ☰ set (OpenRequested) goes, else the main menu's next Accept fade still reads
// it (0x1169A6) and opens the options in place of the item picked (Quick Race opened SETTINGS)
X86_EXTERN_FUNC(__imp__sub_000EB160);
X86_FUNC(sub_000EB160) {
    uint32_t name = kernel::R32(ctx.esp + 4);
    int cat = kernel::R32(ctx.esp) == kScreenBack ? CurrentCategory() : -1;
    if (cat >= 0 && cat < settings::kCount) g_settings_next = true;
    else if (!OptionsPackage(name)) LeaveSettings();
    __imp__sub_000EB160(ctx, base);
}
