// The overlay's icon for a menu prompt, by its string's language hash (FEString::LabelHash, src/game/fe_mouse.cpp):
// the hash is the same in every language, where the label's text is the game's language's (and cut, upcased), so the
// icon follows the action whatever the language. The pad button decides nothing: Pause_Controller's A is Back,
// Pause_Performance_Tuning's B is Accept. The keys are ButtonArt.Icon's names; a hash not here gives "" and the
// overlay tries the English text, then draws the pad's letter. Hashes from the disc's string table (ZZDATA7/9.BIN,
// the ids in the comments), .work/ui-settings-evidence/prompt_labels.md
#include <algorithm>
#include <cstdint>
#include <iterator>

#include "host.h"

namespace touch {
namespace {

struct PromptIconEntry {
    uint32_t hash;
    const char* key;
};

// sorted by hash
constexpr PromptIconEntry kPromptIcons[] = {
    {0x001335F0, "BACK"},      // BACK
    {0x0018ECFF, "SAVE"},      // LOAD
    {0x001C8ACE, "SAVE"},      // SAVE
    {0x00CA369E, "RESTART"},   // H_DEFAULTS
    {0x00DC0C9F, "RESTART"},   // PR_RESTART_RACE
    {0x01BD185C, "PLAY"},      // PAUSE_RESUME_FREE_ROAM
    {0x028FEADD, "CART"},      // CHECK "Add"
    {0x039DF715, "RESTART"},   // RETRY
    {0x04D88D82, "OPTIONS"},   // OL_HOST_OPTIONS
    {0x053D8734, "STATS"},     // OL_PLAYER_STATS
    {0x07F10319, "RESTART"},   // LAN_OL_LOBBY_REFRESH
    {0x0F8F50AC, "EDIT"},      // KEY_VIEW_EDIT_PRESETS
    {0x12107FFE, "SEND"},      // OL_MESS_SEND_MESSAGE
    {0x130D90D1, "RESTART"},   // PC_OL_LOBBY_REFRESH
    {0x1459065C, "OPTIONS"},   // H_ADVANCED
    {0x159D2F37, "ACCEPT"},    // COMMON_ACCEPT
    {0x17E18F87, "PLAY"},      // START_NEW_CAREER
    {0x19A8C0AF, "OPTIONS"},   // MAIN_OPTIONS
    {0x1A294DAD, "CANCEL"},    // COMMON_CANCEL
    {0x1C557D01, "CANCEL"},    // ON_CANCEL
    {0x1CEDC132, "RESTART"},   // BUTTON_RESET_CAR
    {0x213587BF, "ZOOM_OUT"},  // WM_ZOOM_ALL
    {0x224936F4, "EXIT"},      // OLX_QUIT
    {0x245448DB, "OPTIONS"},   // OLX_RACE_MODE_OPTIONS
    {0x2557DE51, "CART"},      // CSHOP_CHECKOUT
    {0x281DEE8A, "CASH"},      // CS_RELEASE_CAR
    {0x29AE0714, "CART"},      // MYCARS_ADD_NEW_CAR
    {0x2B5A03A8, "OPTIONS"},   // PAUSE_OPTIONS
    {0x2B65A216, "ACCEPT"},    // H_ACCEPT
    {0x2C119933, "CANCEL"},    // DECLINE
    {0x2C35EC64, "EYE_OFF"},   // WM_HIDE_ICON
    {0x2C420D64, "RESTART"},   // DEFAULT
    {0x31AB6B65, "SEND"},      // OL_REPLY
    {0x325E1F8F, "PLAY"},      // JUKE_PREVIEW
    {0x363D108F, "RESTART"},   // H_RANDOMIZE
    {0x39751BDF, "RESTART"},   // OL_LOBBY_REFRESH
    {0x3BF2CF8C, "PLAY"},      // BL_CHALLENGE_RIVAL
    {0x3FC32947, "ACCEPT"},    // COMMON_SELECT
    {0x417B25E4, "CANCEL"},    // COMMON_NO
    {0x417B2601, "ACCEPT"},    // COMMON_OK
    {0x43512519, "MAP"},       // ACTIVATE_GPS
    {0x459EA230, "MAP"},       // OL_GAME_ROOM_RACE_LINE "View Track"
    {0x45EA70EB, "EYE"},       // WM_TOGGLE_VISIBILITY
    {0x4981E3C8, "RESTART"},   // OL_REFRESH
    {0x4B6140BF, "FILTER"},    // OL_FILTER
    {0x4EB9591F, "CASH"},      // CS_USE_CASH
    {0x4F876B6F, "ACCEPT"},    // ACCEPT
    {0x4F920879, "CASH"},      // CS_SELL_CAR
    {0x4FB803C4, "RESTART"},   // QRB_RANDOM
    {0x52E0675A, "CAMERA"},    // CAM_ACTION_PULLBACK
    {0x52EF2787, "RESTART"},   // H_RANDOM
    {0x53EFBCB5, "ROTATE"},    // H_ROTATE
    {0x53F9FD5C, "CHAT"},      // UI_CHAT
    {0x552094E0, "EYE"},       // OL_VIEW_PLAYER
    {0x56B00632, "DELETE"},    // DELETE
    {0x58B828ED, "ACCEPT"},    // KEY_DONE
    {0x5B9D89D0, "PLAY"},      // CARLOT_DRIVE_NOW
    {0x5DABCBC0, "DELETE"},    // UNCHECK "Remove"
    {0x5DE79F0B, "EYE"},       // SHOWCASE_VIEW_CAR
    {0x5E112210, "FILTER"},    // ON_SEARCH_AGAIN
    {0x6005281E, "PLAY"},      // START_CAREER
    {0x61D596D7, "BACK"},      // H_BACK
    {0x61D8F34F, "HELP"},      // H_HELP
    {0x61D90340, "EYE_OFF"},   // H_HIDE
    {0x61DE6DA2, "EYE"},       // H_READ
    {0x61DF26CE, "SORT"},      // H_SORT
    {0x61DF9966, "INFO"},      // H_TIPS
    {0x61E34056, "ZOOM_IN"},   // H_Z_IN
    {0x649F4A65, "CART"},      // CP_ADD_TO_CART
    {0x6729A51E, "PERSON"},    // OL_INVITE_STRING
    {0x68155458, "DELETE"},    // PC_CLEAR
    {0x6965DDEF, "FILTER"},    // OL_SEARCH
    {0x7010BBF2, "CART"},      // CL_BUY_CAR
    {0x70E01038, "ACCEPT"},    // COMMON_YES
    {0x7177DC17, "DELETE"},    // CP_REMOVE_DECAL
    {0x74413352, "LIST"},      // PRP_BUTTON_TEXT_MILESTONES
    {0x7448870B, "LIST"},      // PRP_BUTTON_TEXT_PURSUIT
    {0x7A255162, "MAP"},       // OL_HOST_VIEW_TRACK_MAP
    {0x7F7340AF, "STATS"},     // COMMON_GAME_STATS
    {0x8098A54C, "ACCEPT"},    // COMMON_CONTINUE
    {0x8159A0B2, "LIST"},      // PRR_BUTTON_TEXT "Standings"
    {0x889D822E, "PLAY"},      // CARLOT_DELIVER
    {0x8CD567B8, "BACK"},      // COMMON_BACK
    {0x8CD6BD6D, "ACCEPT"},    // COMMON_DONE
    {0x8CDC1006, "PLAY"},      // COMMON_NEXT
    {0x8D1D137F, "EDIT"},      // OL_EDIT
    {0x8D200029, "PERSON"},    // OL_JOIN
    {0x8DF17226, "PLAY"},      // BL_VIEW_MOVIE
    {0x8F049C18, "RESTART"},   // GAME_ACTION_RESET
    {0x9145A5F2, "PLAY"},      // PRP_BUTTON_TEXT_CHALLENGE
    {0x933E9FFF, "FILTER"},    // OL_CHANGE_COLUMNS
    {0x9633E6C2, "STATS"},     // OL_VIEW_STATS
    {0x9A9F1336, "PLAY"},      // OL_GAME_ROOM_START_RACE
    {0x9C88B5C8, "OPTIONS"},   // H_BASIC
    {0x9E4B65D7, "ZOOM_OUT"},  // H_Z_OUT
    {0x9F014666, "DELETE"},    // DELETE_PS2
    {0x9F016663, "DELETE"},    // DELETE_XBX
    {0xA1BFBD99, "CART"},      // CSHOP_CHECK "Add"
    {0xA1C1EEE2, "DELETE"},    // CSHOP_CLEAR
    {0xA1CD823E, "BACK"},      // BACKWARD
    {0xA270B0E2, "STATS"},     // REP_RC_TOGGLE_BIO_STATS
    {0xA3E07AB0, "PLAY"},      // RAP_LOGIN_SKIP
    {0xA9950B93, "EXIT"},      // CL_LEAVE_SHOP
    {0xAEAEB62F, "LIST"},      // PRP_BUTTON_TEXT_INFRACTIONS
    {0xB18A5DE4, "FILTER"},    // OL_APPLY_FILTER
    {0xB4D985B2, "CAMERA"},    // CAM_ACTION_CHANGE
    {0xBA0A6A2B, "EYE"},       // WM_SHOW_ICON
    {0xBA425139, "SORT"},      // H_REORDER
    {0xBD81116C, "OPTIONS"},   // UI_ONLINE_OPTIONS
    {0xBE434A38, "DELETE"},    // CN_REMOVE_NUMBERS
    {0xBF2E2A8B, "DELETE"},    // OL_DELETE_PERSONA_HELP
    {0xBF61E081, "RESTART"},   // BUTTON_DPAD_DOWN_RESET_CAR
    {0xC2598BD8, "CART"},      // MYCAR_ADD_CAR
    {0xC48A4103, "EYE"},       // OL_READ_INVITE
    {0xC51FBF74, "EXIT"},      // PC_QUIT
    {0xC5249616, "ZOOM_IN"},   // PC_ZOOM
    {0xC6119204, "EYE"},       // WM_HIDE_SHOW_ICON
    {0xC9847935, "EDIT"},      // MYCAR_EDIT_CAR "Customize Car"
    {0xCE39D54D, "EYE"},       // VIEW_TUTORIAL
    {0xCFDF1D5A, "CART"},      // H_ADD_TO_CART
    {0xD0B8AA33, "STATS"},     // PRS_BUTTON_TEXT "Statistics"
    {0xD1F2C488, "STATS"},     // RACE_STATS
    {0xD38DF194, "CAMERA"},    // BUTTON_CHANGE_POV
    {0xD5A11802, "DELETE"},    // MYCAR_DELETE_CAR
    {0xD616E103, "EYE"},       // H_SHOWCASE
    {0xDAE908B2, "CAMERA"},    // CAM_ACTION_LOOKBACK
    {0xDAF7F119, "EYE"},       // BL_VIEW_RIVAL_CAR
    {0xDE6EFF34, "FORWARD"},   // FORWARD
    {0xDED357E7, "PLAY"},      // PAUSE_RESUME_RACE
    {0xDFC27A6F, "LIST"},      // HUDACTION_PAD_RIGHT "View SMS Messages"
    {0xDFF69109, "LIST"},      // HUDACTION_PAD_DOWN "View Blacklist"
    {0xDFFAC75C, "MAP"},       // HUDACTION_PAD_LEFT "View World Map"
    {0xE072DB21, "PLAY"},      // RESUME_CAREER
    {0xE2D5AD1E, "EYE"},       // OL_GAME_ROOM_VIEW_CAR
    {0xE2DF460A, "CART"},      // H_VIEW_CART
    {0xE5F757CB, "STATS"},     // OL_PERSONAL_STATS
    {0xEB94705C, "LIST"},      // CS_LOAD_CARS
    {0xECB904DE, "CAMERA"},    // CON_CHANGE_VIEW
    {0xF1D0D8A5, "MAP"},       // WM_DEACTIVATE_GPS
    {0xF4B00E99, "ROTATE"},    // OPT_GAMEPLAY_MINIMAP_ROTATE
    {0xF893AFA1, "RESTART"},   // PAUSE_RESTART
    {0xF95320B8, "EXIT"},      // ON_Quit_Race
    {0xF9C73CC2, "ACCEPT"},    // CS_USE_MARKER
    {0xFB94489B, "EXIT"},      // OL_GAME_ROOM_LEAVE
    {0xFC00D963, "ACCEPT"},    // OL_GAME_ROOM_READY
    {0xFD21A00B, "CAMERA"},    // SWITCH_VIEW
    {0xFECE4E04, "OPTIONS"},   // OL_EDIT_RACE_OPTIONS
};

constexpr bool Sorted() {
    for (size_t i = 1; i < std::size(kPromptIcons); i++)
        if (kPromptIcons[i - 1].hash >= kPromptIcons[i].hash) return false;
    return true;
}
static_assert(Sorted(), "kPromptIcons must be sorted by hash, no repeats");

}  // namespace

const char* PromptIcon(uint32_t hash) {
    auto at = std::lower_bound(std::begin(kPromptIcons), std::end(kPromptIcons), hash,
                               [](const PromptIconEntry& e, uint32_t h) { return e.hash < h; });
    return at != std::end(kPromptIcons) && at->hash == hash ? at->key : "";
}

}  // namespace touch
