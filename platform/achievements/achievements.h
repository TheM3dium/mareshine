// Achievements (Mareshine): the port's own, offline. The game side
// (ach_game.cpp, built into the game library) watches the game and calls
// these; achievements.cpp keeps them in <data folder>/achievements/unlocked.json
// in the shared format (docs/ACHIEVEMENTS-FORMAT.md) and tells the app, which
// shows the banner and the lists. Off when settings.txt has SMS_ACHIEVEMENTS = 0.
#ifndef PORT_ACHIEVEMENTS_H
#define PORT_ACHIEVEMENTS_H

#ifdef __cplusplus
extern "C" {
#endif

// Every game's achievements in one list: Super Mario Sunshine's, then those of
// the mods with a game of their own (tools/achievements/mod_defs.py). Only the
// running game's count (port_ach_game); one of Sunshine's that the running
// game has too (the same id after the game's name) stands for that one.
enum {
#define ACH(e, id, target) PORT_ACH_##e,
#define ACH_SECRET ACH
#include "achievements_list.h"
	PORT_ACH_SMS_COUNT,
	PORT_ACH_MODS_FIRST = PORT_ACH_SMS_COUNT - 1,
#include "achievements_list_sunburn.h"
#include "achievements_list_eclipse.h"
#undef ACH_SECRET
#undef ACH
	PORT_ACH_COUNT
};

// the save files' run timers (A, B, C)
#define PORT_ACH_SLOTS 3

void port_ach_init(void);
int port_ach_enabled(void);
// the game whose achievements count: "sms", "sunburn" or "eclipse"
// (SMS_ACHIEVEMENTS_GAME, set by the app for the mod being played)
const char* port_ach_game(void);
// a dev build of the app (its version says "dev"): SMS_ACH_TEST works only there
int port_ach_dev_build(void);
// a secret one (the bigger sound)
int port_ach_is_secret(int a);
// how far it is now
int port_ach_progress_of(int a);

// The mods' games (platform/mods/eclipse/ach-hooks.patch): their code reports
// states (port_ach_mod_state: New Game Plus on, the character played, ...),
// adds to an achievement by id (port_ach_add_id) and names a boss of its own
// just beaten (port_ach_mod_boss); ach_mods.cpp reads them.
void port_ach_add_id(const char* id, int amount);
void port_ach_mod_state(const char* key, int value);
int port_ach_mod_get(const char* key, int missing, int clear);
void port_ach_mod_boss(const char* boss);
const char* port_ach_mod_take_boss(void);
const char* port_ach_id(int a);
int port_ach_target(int a);
int port_ach_is_unlocked(int a);
void port_ach_unlock(int a);
// the same by id ("sms.fun.impatient"), for the decomp patches
void port_ach_unlock_id(const char* id);
// how far the player is (a progress bar in the list); unlocks at the target
void port_ach_progress(int a, int value);
// writes unlocked.json when something changed and tells the app about new ones
void port_ach_flush(void);
// the achievements unlocked since the last call (for the game side's sound), at most `max`
int port_ach_take_new(int* list, int max);

// a save state was loaded in this session (platform/savestate)
void port_ach_note_state_loaded(void);
// any of the launcher's cheats is on (platform/misc/port_cheats.cpp)
int port_ach_cheats_active(void);

// The run timer of save file `slot`: called about once a second while a
// file is played, with the real time since the last call and the file's
// shine count. A file with no shines starts a new run; one first seen with
// shines (from before the timer) is not a timed run. A run stops counting
// as one once a save state is loaded or a cheat is on during it.
void port_ach_run_update(int slot, int shines, unsigned ms);
unsigned long long port_ach_run_ms(int slot);
int port_ach_run_clean(int slot);

#ifdef __cplusplus
}
#endif

#endif
