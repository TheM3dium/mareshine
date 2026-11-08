// Achievements, the game side for the mods with a game of their own (Super
// Mario Sunburn, Super Mario Eclipse): what Sunshine's checks (ach_game.cpp)
// do not cover. Those run in a mod too and stand for the mod's achievements of
// the same name (platform/achievements/achievements.cpp). Built into the game
// library with the game's flags, as ach_game.cpp.
//
// Also here, for testing (dev builds only): SMS_ACH_TEST sets flags in the save
// file being played, once, so achievements can be checked without playing
// for them (the game saves them only if it saves), and SMS_SHINE_MAP logs
// every Shine Sprite a stage places (to compare a mod's levels with Sunshine's).
#include <System/Application.hpp>
#include <System/FlagManager.hpp>
#include <System/MarDirector.hpp>
#include <Player/Mario.hpp>

#include <stdlib.h>
#include <string.h>

#include "achievements.h"

#define COUNT(a) (int)(sizeof(a) / sizeof((a)[0]))

extern "C" void port_log(const char* fmt, ...);

namespace {

TFlagManager* flags() { return TFlagManager::getInstance(); }
bool flag(u32 f) { return flags()->getBool(f); }
// Shine ids past 119 (Eclipse's) are BetterSunshineEngine's: its hooks on
// TFlagManager::getShineFlag read them where it keeps them
bool shine(int id) { return id >= 0 && id < 256 && flags()->getShineFlag((u8)id); }
bool game(const char* g) { return !strcmp(port_ach_game(), g); }

// --- SMS_ACH_TEST ------------------------------------------------------------
// A list of what to set, separated by ';' or ',':
//   shine:A-B      Shine Sprites A to B (ids, decimal or 0x..)
//   flag:A-B       boolean flags A to B (0x10000 ...)
//   value:F=N      the flag F to the number N (0x40001=420)
//   all            everything the running game's achievements count
// Example: SMS_ACH_TEST=shine:120-129;shine:207

unsigned long num(const char*& p)
{
	char* end;
	unsigned long v = strtoul(p, &end, 0);
	p = end;
	return v;
}

void setRange(bool isShine, unsigned long a, unsigned long b)
{
	for (unsigned long i = a; i <= b && i - a < 0x1000; i++) {
		if (isShine)
			flags()->setShineFlag((u8)i);
		else
			flags()->setBool(true, (u32)i);
	}
}

void testAll()
{
	bool eclipse = game("eclipse");
	setRange(true, 0, eclipse ? 239 : 119);
	setRange(false, 0x10078, 0x10078 + 9 * 50 - 1); // Sunshine's blue coins
	if (eclipse)
		setRange(false, 0x1023A, 0x1023A + 14 * 20 - 1); // Eclipse's areas' (record.cpp)
	setRange(false, 0x10366, 0x10366 + 2 * 20 - 1);     // nozzle boxes
	setRange(false, 0x10391, 0x10391 + 20);             // movies seen
	flags()->setBool(true, 0x1038F);                    // Yoshi
	flags()->setBool(true, 0x10384);                    // the statue's goop
	flags()->setBool(true, 0x103AE);                    // Corona visited
	if (eclipse) {
		flags()->setBool(true, 0x30018); // Luigi
		flags()->setBool(true, 0x30019); // Piantissimo
	}
	flags()->setFlag(0x40001, eclipse ? 420 : 240);
}

void applyTest(const char* spec)
{
	port_log("[achievements] SMS_ACH_TEST: %s\n", spec);
	const char* p = spec;
	while (*p) {
		while (*p == ';' || *p == ',' || *p == ' ')
			p++;
		if (!strncmp(p, "all", 3)) {
			testAll();
			p += 3;
		} else if (!strncmp(p, "shine:", 6) || !strncmp(p, "flag:", 5)) {
			bool isShine = *p == 's';
			p += isShine ? 6 : 5;
			unsigned long a = num(p), b = a;
			if (*p == '-') {
				p++;
				b = num(p);
			}
			setRange(isShine, a, b);
		} else if (!strncmp(p, "value:", 6)) {
			p += 6;
			unsigned long f = num(p);
			if (*p == '=') {
				p++;
				flags()->setFlag((u32)f, (s32)num(p));
			}
		} else if (*p) {
			port_log("[achievements] SMS_ACH_TEST: cannot read \"%s\"\n", p);
			return;
		}
	}
}

// --- live: what changed since the last look, per save file -------------------

// Bowser's flag and the Shine count when last looked at in this save file
// (-1: not yet, so what a save already has does not count as just now)
struct Seen {
	int slot = -2;
	int bowser = -1;
	int shines = -1;
};
Seen s_seen;

void lookAgain()
{
	int slot = gpApplication.mSaveFile;
	if (slot != s_seen.slot) {
		s_seen = Seen();
		s_seen.slot = slot;
	}
}

// --- Super Mario Sunburn -----------------------------------------------------
// New Game Plus and the win condition come from its module (ach-hooks.patch:
// gameplus.cpp, each frame of a stage); 0 = "70 Shines", 1 = "All Shadow Mario"

void sunburnSaveFile(int shines)
{
	if (shines > 0)
		port_ach_unlock(PORT_ACH_SUNBURN_STORY_FIRST_SHINE); // its first one need not be the airstrip's
	if (flag(0x103AE))
		port_ach_unlock(PORT_ACH_SUNBURN_STORY_CORONA_OPEN);
	if (shines >= 120) // the module's own condition (gameplus.cpp)
		port_ach_unlock(PORT_ACH_SUNBURN_NGPLUS_UNLOCKED);

	lookAgain();
	bool ngplus = port_ach_mod_get("sunburn.ngplus", 0, 0) == 1;
	int bowser = flag(0x10077); // Bowser beaten (its Shine comes with the epilogue)
	if (s_seen.bowser == 0 && bowser) {
		int win = port_ach_mod_get("sunburn.win", -1, 0);
		if (win == 0)
			port_ach_unlock(PORT_ACH_SUNBURN_STORY_WIN_70);
		else if (win == 1)
			port_ach_unlock(PORT_ACH_SUNBURN_STORY_WIN_SHADOW);
		if (ngplus)
			port_ach_unlock(PORT_ACH_SUNBURN_NGPLUS_BOWSER);
	}
	s_seen.bowser = bowser;
	int all = flags()->getFlag(0x40000);
	if (s_seen.shines >= 0 && all > s_seen.shines && ngplus)
		port_ach_progress(PORT_ACH_SUNBURN_NGPLUS_SHINES_30,
		                  port_ach_progress_of(PORT_ACH_SUNBURN_NGPLUS_SHINES_30) + all - s_seen.shines);
	s_seen.shines = all;
}

// --- Super Mario Eclipse -----------------------------------------------------

int countShines(int a, int b)
{
	int n = 0;
	for (int i = a; i <= b; i++)
		n += shine(i);
	return n;
}

// the blue coins of an Eclipse area (record.cpp: 20 a stage from flag 0x1023A)
int areaBlue(int index, int coins)
{
	int n = 0;
	for (int c = 0; c < coins; c++)
		n += flag(0x1023A + index * 20 + c);
	return n;
}

struct Area {
	int first, count;  // its Shine ids
	int coins100;      // its 100-coin Shine
	int blueIndex;     // its blue coins' stage index (record.cpp), -1 none
	int blue;          // how many
	int complete;      // the achievement
	int blueAch;       // its blue coins on their own (-1: they are part of complete)
	int secret;        // its secret level's achievement (-1 none)
	int secretShine, secretShine2;
};
// eclipse/src/stage/tracker.cpp (Shine ids) and stage.cpp (secret levels).
// The blue coins' index is the Shine stage minus 61, minus one more from 73 on.
const Area kAreas[] = {
	{ 120, 10, 207, 0, 20, PORT_ACH_ECLIPSE_AREA_ERTO, PORT_ACH_ECLIPSE_AREA_ERTO_BLUE_COINS, PORT_ACH_ECLIPSE_AREA_ERTO_SECRET, 124, -1 },
	{ 130, 10, 208, 1, 20, PORT_ACH_ECLIPSE_AREA_LIGHTHOUSE, PORT_ACH_ECLIPSE_AREA_LIGHTHOUSE_BLUE_COINS, PORT_ACH_ECLIPSE_AREA_LIGHTHOUSE_SECRET, 133, -1 },
	{ 140, 10, 209, 2, 20, PORT_ACH_ECLIPSE_AREA_WARSHIP, PORT_ACH_ECLIPSE_AREA_WARSHIP_BLUE_COINS, PORT_ACH_ECLIPSE_AREA_WARSHIP_SECRET, 144, -1 },
	{ 150, 10, 210, 3, 20, PORT_ACH_ECLIPSE_AREA_LACRIMA, PORT_ACH_ECLIPSE_AREA_LACRIMA_BLUE_COINS, PORT_ACH_ECLIPSE_AREA_LACRIMA_SECRET, 152, 154 },
	{ 160, 6, 211, 4, 20, PORT_ACH_ECLIPSE_AREA_PIANTA_PIT, PORT_ACH_ECLIPSE_AREA_PIANTA_PIT_BLUE_COINS, -1, -1, -1 },
	{ 166, 5, 212, 5, 10, PORT_ACH_ECLIPSE_AREA_CRUISER, PORT_ACH_ECLIPSE_AREA_CRUISER_BLUE_COINS, PORT_ACH_ECLIPSE_AREA_CRUISER_SECRET, 169, -1 },
	{ 171, 4, 213, 6, 10, PORT_ACH_ECLIPSE_AREA_MARIO_DREAM, -1, -1, -1, -1 },
	{ 175, 4, 214, 7, 10, PORT_ACH_ECLIPSE_AREA_LANCIA, -1, -1, -1, -1 },
	{ 179, 4, 215, 8, 10, PORT_ACH_ECLIPSE_AREA_VAPORWAVE, -1, -1, -1, -1 },
	{ 183, 4, 216, 9, 10, PORT_ACH_ECLIPSE_AREA_YOSHI_VILLAGE, -1, -1, -1, -1 },
	{ 187, 4, 217, 10, 10, PORT_ACH_ECLIPSE_AREA_RED_LILY, -1, -1, -1, -1 },
	{ 191, 4, 218, 11, 10, PORT_ACH_ECLIPSE_AREA_PEACH_BEACH, -1, -1, -1, -1 },
	{ 195, 4, 219, 12, 10, PORT_ACH_ECLIPSE_AREA_SPETTRO, -1, -1, -1, -1 },
};
// the twelve secret levels' Shines (stage.cpp: registerExStage)
const u8 kSecretShines[] = { 124, 133, 144, 152, 154, 169, 178, 182, 186, 190, 194, 198 };

void eclipseSaveFile()
{
	// every Shine Sprite: the game's own count (what Eclipse itself goes by)
	int shines = flags()->getFlag(0x40000);
	static const int kShineAch[] = { PORT_ACH_ECLIPSE_SHINES_10, PORT_ACH_ECLIPSE_SHINES_30, PORT_ACH_ECLIPSE_SHINES_60,
		                             PORT_ACH_ECLIPSE_SHINES_90, PORT_ACH_ECLIPSE_SHINES_120, PORT_ACH_ECLIPSE_SHINES_150,
		                             PORT_ACH_ECLIPSE_SHINES_180, PORT_ACH_ECLIPSE_SHINES_210, PORT_ACH_ECLIPSE_SHINES_240 };
	for (int i = 0; i < COUNT(kShineAch); i++)
		port_ach_progress(kShineAch[i], shines);
	int blue = flags()->getFlag(0x40001);
	static const int kBlueAch[] = { PORT_ACH_ECLIPSE_BLUE_COINS_50, PORT_ACH_ECLIPSE_BLUE_COINS_100,
		                            PORT_ACH_ECLIPSE_BLUE_COINS_200, PORT_ACH_ECLIPSE_BLUE_COINS_300,
		                            PORT_ACH_ECLIPSE_BLUE_COINS_420 };
	for (int i = 0; i < COUNT(kBlueAch); i++)
		port_ach_progress(kBlueAch[i], blue);
	int trades = countShines(0x46, 0x55) + countShines(0x6C, 0x73) + countShines(221, 238);
	port_ach_progress(PORT_ACH_ECLIPSE_BLUE_COINS_TRADE_ALL, trades);

	if (flag(0x30018))
		port_ach_unlock(PORT_ACH_ECLIPSE_CHAR_LUIGI);
	if (flag(0x30019))
		port_ach_unlock(PORT_ACH_ECLIPSE_CHAR_PIANTISSIMO);

	// Sunshine's worlds: their ten Shines (tracker.cpp: Noki's are 50 to 59,
	// Pianta's 60 to 69)
	static const int kWorldShines[] = { PORT_ACH_ECLIPSE_WORLD_BIANCO_SHINES, PORT_ACH_ECLIPSE_WORLD_RICCO_SHINES,
		                                PORT_ACH_ECLIPSE_WORLD_GELATO_SHINES, PORT_ACH_ECLIPSE_WORLD_PINNA_SHINES,
		                                PORT_ACH_ECLIPSE_WORLD_SIRENA_SHINES, PORT_ACH_ECLIPSE_WORLD_NOKI_SHINES,
		                                PORT_ACH_ECLIPSE_WORLD_PIANTA_SHINES };
	for (int w = 0; w < COUNT(kWorldShines); w++)
		port_ach_progress(kWorldShines[w], countShines(w * 10, w * 10 + 9));

	int complete = 0;
	for (int i = 0; i < COUNT(kAreas); i++) {
		const Area& a = kAreas[i];
		bool all = countShines(a.first, a.first + a.count - 1) == a.count && shine(a.coins100);
		bool blueAll = areaBlue(a.blueIndex, a.blue) == a.blue;
		if (a.blueAch >= 0)
			port_ach_progress(a.blueAch, areaBlue(a.blueIndex, a.blue));
		if (all && (a.blueAch >= 0 || blueAll)) {
			port_ach_unlock(a.complete);
			complete++;
		}
		if (a.secret >= 0)
			port_ach_progress(a.secret, shine(a.secretShine) + (a.secretShine2 >= 0 && shine(a.secretShine2)));
		if (a.secret >= 0 && a.secretShine2 < 0 && shine(a.secretShine))
			port_ach_unlock(a.secret);
	}
	if (countShines(199, 206) == 8 && shine(220)) {
		port_ach_unlock(PORT_ACH_ECLIPSE_AREA_ISLE_DELFINO);
		complete++;
	}
	port_ach_progress(PORT_ACH_ECLIPSE_AREA_ALL_COMPLETE, complete);
	int secrets = 0;
	for (int i = 0; i < COUNT(kSecretShines); i++)
		secrets += shine(kSecretShines[i]);
	port_ach_progress(PORT_ACH_ECLIPSE_AREA_ALL_SECRETS, secrets);

	// Delfino Plaza: both 100-coin Shines and its 20 blue coins (Eclipse counts
	// the plaza's from coin 0)
	port_ach_progress(PORT_ACH_ECLIPSE_PLAZA_COINS100, flag(0x1006B) + shine(239));
	int plazaBlue = 0;
	for (u8 i = 0; i < 50; i++)
		plazaBlue += flags()->getBlueCoinFlag(0x1, i);
	port_ach_progress(PORT_ACH_ECLIPSE_PLAZA_BLUE_COINS, plazaBlue);

	// all 240 within 12 hours of play in one file
	int slot = gpApplication.mSaveFile;
	if (shines >= 240 && slot >= 0 && slot < PORT_ACH_SLOTS && port_ach_run_clean(slot)
	    && port_ach_run_ms(slot) / 60000 < 12 * 60)
		port_ach_unlock(PORT_ACH_ECLIPSE_PRO_SPEEDRUN_240);
}

} // namespace

// --- Super Mario Eclipse, live (ach-hooks.patch) -----------------------------

enum { CHAR_MARIO, CHAR_LUIGI, CHAR_PIANTISSIMO, CHAR_SHADOW_MARIO }; // SME::CharacterID

struct Live {
	int stage = -1;
	bool hurt = false;   // Mario lost health in this stage
	s16 health = 0;
	int shines = -1;
	u8 played = 0;       // the characters played in this session
	int in2d = 0;        // frames of the 2D camera just now
	int after2d = 0;     // frames since it ended
};
Live s_live;

int character() { return port_ach_mod_get("eclipse.character", -1, 0); }

void eclipseFrame(TMario* m)
{
	int stage = gpApplication.mCurrArea.unk0;
	bool down = (m->mFlag & 0x400) || m->mHealth <= 0;
	if (stage != s_live.stage) {
		s_live.stage = stage;
		s_live.hurt = false;
		s_live.health = m->mHealth;
		s_live.in2d = s_live.after2d = 0;
	}
	if (m->mHealth < s_live.health || down)
		s_live.hurt = true;
	s_live.health = m->mHealth;

	int ch = character();
	if (ch >= 0 && ch < 4) {
		s_live.played |= 1 << ch;
		int n = 0;
		for (int i = 0; i < 4; i++)
			n += (s_live.played >> i) & 1;
		port_ach_progress(PORT_ACH_ECLIPSE_CHAR_ALL_PLAYED, n);
	}

	// a Shine Sprite just got as Shadow Mario
	int all = flags()->getFlag(0x40000);
	if (s_live.shines >= 0 && all > s_live.shines && ch == CHAR_SHADOW_MARIO)
		port_ach_unlock(PORT_ACH_ECLIPSE_FUN_SHADOW_SHINE);
	s_live.shines = all;

	// a boss of Eclipse's own beaten
	const char* boss = port_ach_mod_take_boss();
	if (boss[0]) {
		static const struct {
			const char* name;
			int defeat, nohit;
		} kBoss[] = {
			{ "dark_zhine", PORT_ACH_ECLIPSE_BOSS_DARK_ZHINE_DEFEAT, PORT_ACH_ECLIPSE_BOSS_DARK_ZHINE_NOHIT },
			{ "fiery_petey", PORT_ACH_ECLIPSE_BOSS_FIERY_PETEY_DEFEAT, PORT_ACH_ECLIPSE_BOSS_FIERY_PETEY_NOHIT },
			{ "bowser_car", PORT_ACH_ECLIPSE_BOSS_BOWSER_CAR_DEFEAT, PORT_ACH_ECLIPSE_BOSS_BOWSER_CAR_NOHIT },
		};
		for (int i = 0; i < COUNT(kBoss); i++)
			if (!strcmp(boss, kBoss[i].name)) {
				port_ach_unlock(kBoss[i].defeat);
				if (!s_live.hurt)
					port_ach_unlock(kBoss[i].nohit);
				if (ch == CHAR_LUIGI)
					port_ach_unlock(PORT_ACH_ECLIPSE_FUN_LUIGI_BOSS);
			}
	}

	// a 2D section played through: the 2D camera ran (2d_camera.cpp, every
	// frame it is on) and then gave way, Mario alive, in the same stage
	if (port_ach_mod_get("eclipse.camera_2d", 0, 1)) {
		s_live.in2d++;
		s_live.after2d = 0;
	} else if (s_live.in2d > 0) {
		if (down) {
			s_live.in2d = 0;
		} else if (++s_live.after2d > 30) {
			if (s_live.in2d > 150) // more than a glance (5 seconds)
				port_ach_unlock(PORT_ACH_ECLIPSE_FUN_FLAT_2D);
			s_live.in2d = s_live.after2d = 0;
		}
	}
}

// --- called by ach_game.cpp --------------------------------------------------

// a boss of Sunshine's just beaten (the Shine of its fight), and whether
// without a hit
extern "C" void port_ach_mods_boss_won(int nohit)
{
	if (game("sunburn") && nohit && port_ach_mod_get("sunburn.ngplus", 0, 0) == 1)
		port_ach_unlock(PORT_ACH_SUNBURN_NGPLUS_BOSS_NOHIT);
	if (game("eclipse") && character() == CHAR_LUIGI)
		port_ach_unlock(PORT_ACH_ECLIPSE_FUN_LUIGI_BOSS);
}

// once a second, after Sunshine's checks (shines: Sunshine's 120 in the file)
extern "C" void port_ach_mods_save_file(int shines)
{
	if (game("sunburn"))
		sunburnSaveFile(shines);
	else if (game("eclipse"))
		eclipseSaveFile();
}

// every frame a level is played (and loaded): the live checks, and
// SMS_ACH_TEST once per save file
extern "C" void port_ach_mods_playing(void)
{
	if (game("eclipse") && gpMarioOriginal)
		eclipseFrame(gpMarioOriginal);
	static int s_done = -1;
	static const char* s_spec = getenv("SMS_ACH_TEST");
	if (!s_spec || !*s_spec || !port_ach_dev_build()) // a dev build's alone: no unlocks for free
		return;
	int slot = gpApplication.mSaveFile;
	if (slot == s_done)
		return;
	s_done = slot;
	applyTest(s_spec);
	s_live.shines = -1; // what the test set is not "just got"
	s_seen = Seen();
}

// --- SMS_SHINE_MAP -------------------------------------------------------------

// a stage's Shine Sprite as it is loaded (decomp-patches zzzzz-achievements-02):
// its id and how it appears (0 at once, 2 quickly, 1 later)
extern "C" void port_shine_loaded(int id, int kind)
{
	static int s_on = -1;
	if (s_on < 0) {
		const char* e = getenv("SMS_SHINE_MAP");
		s_on = e && *e && strcmp(e, "0") != 0;
	}
	if (!s_on)
		return;
	port_log("[shinemap] stage %d episode %d shine %d kind %d\n", (int)gpApplication.mCurrArea.unk0,
	         (int)flags()->getFlag(0x40003), id, kind);
}
