#pragma once
#include <cstdint>
#include <string>
#include <vector>

// the in-game settings' six categories, their rows and values (settings_rows.cpp); the game's options menu shows them
// (src/game/settings_menu.cpp). Game thread
namespace settings {

enum Category { kScreen, kSpeed, kVisuals, kControls, kSystem, kCheats, kCount };
enum class Kind { Toggle, Choice, Number, Action };

struct Row {
    Kind kind;
    int id;  // unique across the categories
    const char* label;  // English; " (next start)" on a row that applies at the next start only
};

// the category's rows, in the spec's order
const std::vector<Row>& Rows(Category cat);
// the row's value as shown; step (-1 or 1) applies the change and saves first. Empty for an action row
std::string Value(const Row& r, int step = 0);
// an action row: the touch controls' or the HUD's editor
void Activate(const Row& r);
// the category's defaults (the options screen's Y), saved; CONTROLS and CHEATS keep theirs
void Defaults(Category cat);
const char* Name(Category cat);
// 0xAARRGGBB: CHEATS the Most Wanted orange, the rest grey
uint32_t Color(Category cat);

}  // namespace settings
