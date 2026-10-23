// Achievements, the game side: built into the game library with the game's
// flags (it uses the decomp's classes) and called at the top of every frame
// of TApplication::gameLoop (decomp-patches/zzzz-achievements-01-frame.patch).
// About once a second it looks at the save file's flags (shines, blue coins,
// movies seen, ...), which also unlocks retroactively whatever an older save
// already has; a few things that are over in a moment (Mario's state, the
// glitch checks) are looked at every frame.
#include <System/Application.hpp>
#include <System/FlagManager.hpp>
#include <System/MarDirector.hpp>
#include <GC2D/ShineTable.hpp>
#include <Map/Map.hpp>
#include <Map/PollutionLayer.hpp>
#include <Map/MapCollisionData.hpp>
#include <Player/Mario.hpp>
#include <Player/MarioCap.hpp>
#include <Player/Yoshi.hpp>
#include <MSound/MSoundSE.hpp>

#include <stdlib.h>

#include "achievements.h"

// the game library is C++03
#define COUNT(a) (int)(sizeof(a) / sizeof((a)[0]))

extern "C" unsigned int SDL_GetTicks(void);

namespace {

const u32 kShineFlag = 0x10000; // + shine id, 120 of them
const u32 kBlueCoinFlag = 0x10078; // + (shine stage - 1) * 50 + coin
const u32 kNozzleFlag = 0x10366; // + (shine stage - 1) * 2 + nozzle
const u32 kMovieSeenFlag = 0x10391; // + movie
const u32 kLivesFlag = 0x20001;
const u32 kCoinsFlag = 0x40002; // in this visit of a stage

// the seven worlds: their index in ShineTable.hpp's tables (scShineConvTable),
// their area for the blue coins (scNormalStageTable) and the achievements
struct World {
	u8 table, area;
	int episodes, secrets, coins100, blue;
};
const World kWorlds[] = {
	{ 2, 0x2, PORT_ACH_BIANCO_EPISODES, PORT_ACH_BIANCO_SECRETS, PORT_ACH_BIANCO_COINS, PORT_ACH_BIANCO_BLUE },
	{ 3, 0x3, PORT_ACH_RICCO_EPISODES, PORT_ACH_RICCO_SECRETS, PORT_ACH_RICCO_COINS, PORT_ACH_RICCO_BLUE },
	{ 4, 0x4, PORT_ACH_GELATO_EPISODES, PORT_ACH_GELATO_SECRETS, PORT_ACH_GELATO_COINS, PORT_ACH_GELATO_BLUE },
	{ 5, 0xD, PORT_ACH_PINNA_EPISODES, PORT_ACH_PINNA_SECRETS, PORT_ACH_PINNA_COINS, PORT_ACH_PINNA_BLUE },
	{ 6, 0x6, PORT_ACH_SIRENA_EPISODES, PORT_ACH_SIRENA_SECRETS, PORT_ACH_SIRENA_COINS, PORT_ACH_SIRENA_BLUE },
	{ 8, 0x9, PORT_ACH_NOKI_EPISODES, PORT_ACH_NOKI_SECRETS, PORT_ACH_NOKI_COINS, PORT_ACH_NOKI_BLUE },
	{ 7, 0x8, PORT_ACH_PIANTA_EPISODES, PORT_ACH_PIANTA_SECRETS, PORT_ACH_PIANTA_COINS, PORT_ACH_PIANTA_BLUE },
};

// the bosses: the shine of their fight (two for a boss fought twice; -1 none)
// and where the fight is (stage, and the episode, -1 = any), for "no hit"
const int kBowserShine = 0x77; // set as the epilogue after the fight ends
struct Boss {
	int shine, shine2;
	int defeat, nohit;
	u8 stage;
	s8 episode;
	u8 stage2;
	s8 episode2;
};
const Boss kBosses[] = {
	{ 0x01, 0x04, PORT_ACH_BOSS_PETEY, PORT_ACH_BOSS_PETEY_NOHIT, 0x37, -1, 0x02, 4 },
	{ 0x0A, 0x0E, PORT_ACH_BOSS_GOOPER, PORT_ACH_BOSS_GOOPER_NOHIT, 0x3B, -1, 0x03, 4 },
	{ 0x16, -1, PORT_ACH_BOSS_WIGGLER, PORT_ACH_BOSS_WIGGLER_NOHIT, 0x04, 2, 0xFF, -1 },
	{ 0x1E, 0x25, PORT_ACH_BOSS_MECHA, PORT_ACH_BOSS_MECHA_NOHIT, 0x3A, -1, 0xFF, -1 },
	{ 0x28, -1, PORT_ACH_BOSS_MANTA, PORT_ACH_BOSS_MANTA_NOHIT, 0x06, 0, 0xFF, -1 },
	{ 0x2C, -1, PORT_ACH_BOSS_KINGBOO, PORT_ACH_BOSS_KINGBOO_NOHIT, 0x38, -1, 0xFF, -1 },
	{ 0x35, -1, PORT_ACH_BOSS_EELY, PORT_ACH_BOSS_EELY_NOHIT, 0x39, -1, 0xFF, -1 },
	{ kBowserShine, -1, PORT_ACH_BOSS_BOWSER, PORT_ACH_BOSS_BOWSER_NOHIT, 0x3C, -1, 0xFF, -1 },
};

// Delfino Plaza's ten shines (bells, lighthouse, golden bird, ...)
const u8 kPlazaShines[] = { 0x5D, 0x5E, 0x5F, 0x60, 0x61, 0x62, 0x63, 0x74, 0x75, 0x76 };

// the movies the game marks as seen (TMovieDirector::decideNextMode: all but
// the ending's 14 to 17; 9 is the title's and 12 the attract demo)
const u8 kMovies[] = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 13, 18, 19 };

TFlagManager* flags() { return TFlagManager::getInstance(); }
bool flag(u32 f) { return flags()->getBool(f); }
bool shine(int id) { return id >= 0 && flags()->getShineFlag((u8)id); }

int blueCoins(u8 area)
{
	int n = 0;
	for (u8 i = 0; i < 50; i++)
		n += flags()->getBlueCoinFlag(area, i);
	return n;
}

// a level being played: not the title and file select, which are a level too
// (stage 15, with Mario in the background)
bool inGameplay()
{
	return gpApplication.mAppState == TApplication::APP_STATE_GAMEPLAY && gpMarDirector
	    && gpApplication.mDirector == (JDrama::TDirector*)gpMarDirector && gpApplication.mCurrArea.getStage() != 15;
}

// ...and loaded and running (as the save states have it): before that,
// gpMarioOriginal and gpMap still point into the last stage, which is freed
bool playing()
{
	return inGameplay() && gpMarioOriginal && gpMap && gpMap->mCollisionData
	    && gpMarDirector->mState == TMarDirector::STATE_UNK4
	    && !gpMarDirector->checkUnk4CFlag(TMarDirector::DIRECTOR_FLAG_STAGE_TRANSITION_PENDING);
}

// --- once a second: the save file ---------------------------------------------

void checkSaveFile()
{
	int shines = 0;
	for (int i = 0; i < 120; i++)
		shines += shine(i);
	if (shines == 0)
		return; // no file loaded yet, or a new one
	static const int kShineAch[] = { PORT_ACH_SHINES_3, PORT_ACH_SHINES_10, PORT_ACH_SHINES_30, PORT_ACH_SHINES_50,
		                             PORT_ACH_SHINES_70, PORT_ACH_SHINES_100, PORT_ACH_SHINES_120 };
	for (int i = 0; i < COUNT(kShineAch); i++)
		port_ach_progress(kShineAch[i], shines);

	int blue = 0;
	for (int i = 0; i < 9 * 50; i++)
		blue += flag(kBlueCoinFlag + i);
	port_ach_progress(PORT_ACH_BLUE_50, blue);
	port_ach_progress(PORT_ACH_BLUE_100, blue);
	port_ach_progress(PORT_ACH_BLUE_240, blue);

	int shadow = 0;
	for (int wi = 0; wi < COUNT(kWorlds); wi++) {
		const World& w = kWorlds[wi];
		int ep = 0;
		for (u32 e = 0; e < 8; e++)
			ep += SMS_isGetShine(w.table, e, false);
		port_ach_progress(w.episodes, ep);
		port_ach_progress(w.secrets, SMS_isGetShine(w.table, 1, true) + SMS_isGetShine(w.table, 2, true));
		if (SMS_isGetShine(w.table, 0, true))
			port_ach_unlock(w.coins100);
		port_ach_progress(w.blue, blueCoins(w.area));
		shadow += SMS_isGetShine(w.table, 6, false);
	}
	port_ach_progress(PORT_ACH_STORY_SHADOW_ALL, shadow);

	if (shine(0x56))
		port_ach_unlock(PORT_ACH_STORY_FIRST_SHINE);
	if (flag(0x10384)) // the goop at the statue cleaned: the gate to Bianco Hills
		port_ach_unlock(PORT_ACH_STORY_GATEKEEPER);
	if (flag(0x1038F))
		port_ach_unlock(PORT_ACH_STORY_YOSHI);
	if (flag(kNozzleFlag + 0)) // Delfino Plaza's own nozzle boxes
		port_ach_unlock(PORT_ACH_STORY_ROCKET);
	if (flag(kNozzleFlag + 1))
		port_ach_unlock(PORT_ACH_STORY_TURBO);

	// the bosses (the shine of their episode; a boss fought twice counts with either)
	for (int i = 0; i < COUNT(kBosses); i++)
		if (shine(kBosses[i].shine) || shine(kBosses[i].shine2))
			port_ach_unlock(kBosses[i].defeat);

	// Delfino Plaza: its ten shines, the 24 bought with blue coins, the bonus stages
	int plaza = 0;
	for (int i = 0; i < COUNT(kPlazaShines); i++)
		plaza += shine(kPlazaShines[i]);
	port_ach_progress(PORT_ACH_PLAZA_SHINES, plaza);
	int trades = 0;
	for (int id = 0x46; id <= 0x55; id++)
		trades += shine(id);
	for (int id = 0x6C; id <= 0x73; id++)
		trades += shine(id);
	port_ach_progress(PORT_ACH_BLUE_TRADE_ALL, trades);
	if (shine(0x5A)) port_ach_unlock(PORT_ACH_BONUS_SLIDE);
	if (shine(0x59)) port_ach_unlock(PORT_ACH_BONUS_PACHINKO);
	if (shine(0x5B)) port_ach_unlock(PORT_ACH_BONUS_LILYPAD);
	if (shine(0x57)) port_ach_unlock(PORT_ACH_BONUS_TURBO);
	if (shine(0x5C)) port_ach_unlock(PORT_ACH_BONUS_REDCOIN);
	// the plaza's blue coins are 1 to 49 of its stage; 0 is the airstrip's after Corona
	int plazaBlue = 0;
	for (u8 i = 1; i < 50; i++)
		plazaBlue += flags()->getBlueCoinFlag(0x1, i);
	port_ach_progress(PORT_ACH_PLAZA_BLUE, plazaBlue);
	port_ach_progress(PORT_ACH_CORONA_BLUE, blueCoins(0xA));
	port_ach_progress(PORT_ACH_AIRSTRIP_AFTER, shine(0x58) + flags()->getBlueCoinFlag(0x1, 0));

	// the nozzle boxes: both in Bianco, Ricco, Gelato and Noki, one in Pianta
	int boxes = 0;
	static const u8 kBoxStages[] = { 2, 3, 4, 9 };
	for (int i = 0; i < COUNT(kBoxStages); i++) {
		u8 st = kBoxStages[i];
		boxes += flag(kNozzleFlag + (SMS_getShineStage(st) - 1) * 2) + flag(kNozzleFlag + (SMS_getShineStage(st) - 1) * 2 + 1);
	}
	bool pianta = flag(kNozzleFlag + (SMS_getShineStage(8) - 1) * 2) || flag(kNozzleFlag + (SMS_getShineStage(8) - 1) * 2 + 1);
	if (boxes == 8 && pianta)
		port_ach_unlock(PORT_ACH_NOZZLE_BOXES);

	int movies = 0;
	for (int i = 0; i < COUNT(kMovies); i++)
		movies += flag(kMovieSeenFlag + kMovies[i]);
	if (movies == (int)sizeof kMovies && port_ach_is_unlocked(PORT_ACH_STORY_TRUE_ENDING))
		port_ach_unlock(PORT_ACH_FUN_MOVIES_ALL);

	if (flags()->getFlag(kLivesFlag) >= 99)
		port_ach_unlock(PORT_ACH_FUN_LIVES99);

	// the timed runs (the save file's play time, gameplay only)
	int slot = gpApplication.mSaveFile;
	if (slot >= 0 && slot < PORT_ACH_SLOTS && port_ach_run_clean(slot)) {
		unsigned long long min = port_ach_run_ms(slot) / 60000;
		unsigned long long sec = port_ach_run_ms(slot) / 1000;
		if (shine(0x56) && sec < 90)
			port_ach_unlock(PORT_ACH_PRO_VETERAN);
		if (shine(kBowserShine) && min < 150)
			port_ach_unlock(PORT_ACH_PRO_SPEEDRUN);
		if (shine(kBowserShine) && min < 85)
			port_ach_unlock(PORT_ACH_PRO_WR);
		if (shines >= 25 && min < 90)
			port_ach_unlock(PORT_ACH_PRO_SLIP25);
		if (shines >= 120 && min < 360)
			port_ach_unlock(PORT_ACH_PRO_SPEEDRUN120);
		if (shines >= 120 && min < 210)
			port_ach_unlock(PORT_ACH_PRO_WR120);
	}

	int glitches = port_ach_is_unlocked(PORT_ACH_GLITCH_OOB) + port_ach_is_unlocked(PORT_ACH_GLITCH_UNDERFLOOR)
	    + port_ach_is_unlocked(PORT_ACH_GLITCH_SPEED) + port_ach_is_unlocked(PORT_ACH_GLITCH_SKY);
	port_ach_progress(PORT_ACH_GLITCH_HUNTER, glitches);
}

// --- every frame -------------------------------------------------------------

bool marioDown(const TMario* m)
{
	return (m->mFlag & 0x400) || m->mHealth <= 0; // MARIO_FLAG_GAME_OVER: losing a life
}

void checkMario(TMario* m)
{
	u32 st = m->mStatus;
	if (st == 0x20449 || st == 0x208B4 || st == 0x208B5 || st == 0x208B7 || st == 0x8000239)
		port_ach_unlock(PORT_ACH_FUN_FIRE);
	if (st == 0x20338 || st == 0x21313)
		port_ach_unlock(PORT_ACH_FUN_SHOCK);
	// covered in goop that holds Mario back (the Airstrip's slows him without
	// sinking him): MARIO_FLAG_DIRTY, not in burning or electric goop
	if ((m->mFlag & 0x40) && m->mPollutionTypeStandingOn != POLLUTION_TYPE_FIRE
	    && m->mPollutionTypeStandingOn != POLLUTION_TYPE_ELECTRIC)
		port_ach_unlock(PORT_ACH_FUN_GOOP);
	if (st == 0x208B8) // thrown by a Chuckster
		port_ach_unlock(PORT_ACH_FUN_CHUCKSTER);
	if (m->mCap && (m->mCap->unk4 & 4)) // E_CAP_MODEL_SUNGLASSES
		port_ach_unlock(PORT_ACH_LIVE_SUNGLASSES);
	if (m->mFlag & 0x100000) // MARIO_FLAG_HAS_SHIRT
		port_ach_unlock(PORT_ACH_LIVE_SHIRT);
	// the flagpole at the airstrip: Mario on top of the pole (status 0x342, the handstand)
	if (gpApplication.mCurrArea.unk0 == 0x00 && (st & 0xFFF) == 0x342)
		port_ach_unlock(PORT_ACH_LIVE_FLAGPOLE);

	if (TYoshi* y = m->mYoshi) {
		static u8 s_colours; // seen in this session
		if (y->mState == 8 && y->mType < 4) // mounted
			s_colours |= 1 << y->mType;
		if (s_colours == 0xF)
			port_ach_unlock(PORT_ACH_FUN_YOSHI_COLORS);
		if (y->mState == 3) // dissolved in the water
			port_ach_unlock(PORT_ACH_FUN_JUICE);
	}
}

// the shine of the fight going on (Mecha-Bowser's second is episode 8 on his stage)
int fightShine(const Boss& b, u8 stage, int episode)
{
	if (stage != b.stage)
		return b.shine2;
	if (b.shine == 0x1E && episode == 7)
		return b.shine2;
	return b.shine;
}

// Bowser's fight ended without a hit (his stage was left for the epilogue)
bool s_bowserClean = false;

// "No hit": from entering a boss's stage (and episode) until its shine, Mario's
// sun meter never went down; leaving the stage or losing a life starts over.
void checkBossFight(TMario* m)
{
	static int s_boss = -1;
	static bool s_hit;
	static bool s_had;
	static s16 s_health;
	u8 stage = gpApplication.mCurrArea.unk0;
	int episode = flags()->getFlag(0x40003);
	int boss = -1;
	for (int i = 0; i < COUNT(kBosses); i++) {
		const Boss& b = kBosses[i];
		if ((stage == b.stage && (b.episode < 0 || episode == b.episode))
		    || (b.stage2 != 0xFF && stage == b.stage2 && (b.episode2 < 0 || episode == b.episode2)))
			boss = i;
	}
	if (boss != s_boss) {
		s_boss = boss;
		s_bowserClean = false;
		s_hit = false;
		s_health = m->mHealth;
		s_had = boss >= 0 && shine(fightShine(kBosses[boss], stage, episode));
	}
	if (boss < 0)
		return;
	if (m->mHealth < s_health || marioDown(m))
		s_hit = true;
	s_health = m->mHealth;
	const Boss& b = kBosses[boss];
	bool has = shine(fightShine(b, stage, episode));
	if (has && !s_had && !s_hit)
		port_ach_unlock(b.nohit);
	s_had = has;
	// Bowser's shine only comes with the epilogue movie, after the fight
	s_bowserClean = b.shine == kBowserShine && !s_hit;
}

void checkGlitches(TMario* m)
{
	static int s_outside, s_nofloor, s_fast;
	bool down = marioDown(m);
	const JGeometry::TVec3<f32>& p = m->mPosition;

	// outside the level's collision grid, alive, for a second
	bool outside = !gpMap->isInArea(p.x, p.z);
	s_outside = outside && !down ? s_outside + 1 : 0;
	if (s_outside > 30)
		port_ach_unlock(PORT_ACH_GLITCH_OOB);

	// no floor at all under Mario for 3 seconds without dying (over a pit he
	// is gone within about one)
	bool nofloor = m->mGroundPlane == &TMapCollisionData::mIllegalCheckData || m->mFloorPosition.y <= -32000.0f;
	s_nofloor = nofloor && !down && !outside ? s_nofloor + 1 : 0;
	if (s_nofloor > 90)
		port_ach_unlock(PORT_ACH_GLITCH_UNDERFLOOR);

	// far faster than running or the Turbo Nozzle, for a third of a second
	f32 speed2 = m->mVel.x * m->mVel.x + m->mVel.z * m->mVel.z;
	s_fast = speed2 > 150.0f * 150.0f && !down ? s_fast + 1 : 0;
	if (s_fast > 10)
		port_ach_unlock(PORT_ACH_GLITCH_SPEED);

	// higher than anything in the levels (Delfino's highest shine is at 6800)
	if (p.y > 16000.0f && !down)
		port_ach_unlock(PORT_ACH_GLITCH_SKY);
}

void checkFrame()
{
	if (gpApplication.mAppState == TApplication::APP_STATE_MOVIE && gpApplication.getMovie() == 17)
		port_ach_unlock(PORT_ACH_STORY_TRUE_ENDING); // the other ending, after all 120 shines
	if (gpApplication.mAppState == TApplication::APP_STATE_MOVIE && gpApplication.getMovie() == 14 && s_bowserClean) {
		port_ach_unlock(PORT_ACH_BOSS_BOWSER_NOHIT); // the epilogue: Bowser is beaten
		s_bowserClean = false;
	}
	if (!playing())
		return;
	if (port_ach_cheats_active())
		port_ach_unlock(PORT_ACH_PRO_HACKER);
	port_ach_progress(PORT_ACH_FUN_COINS300, flags()->getFlag(kCoinsFlag));
	if (flags()->getFlag(kLivesFlag) < 0)
		port_ach_unlock(PORT_ACH_FUN_GAME_OVER);

	TMario* m = gpMarioOriginal;
	checkMario(m);
	checkBossFight(m);
	// not while the game shows a scene or a dialogue (Mario may be put anywhere then)
	if (!gpMarDirector->isDemoModeNow() && !gpMarDirector->isTalkModeNow())
		checkGlitches(m);
}

// --- the unlock sound --------------------------------------------------------

// the secret ones get the bigger sound
bool secret(int a)
{
	return a == PORT_ACH_FUN_GAME_OVER || a == PORT_ACH_FUN_IMPATIENT || a == PORT_ACH_FUN_JUICE || a == PORT_ACH_FUN_TUNER
	    || (a >= PORT_ACH_PRO_VETERAN && a <= PORT_ACH_GLITCH_HUNTER);
}

// a sound of the game's own (MSound system sounds): "puzzle solved" for an
// achievement and "a shine appears" for a secret one; settings.txt
// SMS_ACHIEVEMENT_SOUND / SMS_ACHIEVEMENT_SOUND_SECRET take another id (hex),
// 0 for none
u32 soundId(bool isSecret)
{
	const char* e = getenv(isSecret ? "SMS_ACHIEVEMENT_SOUND_SECRET" : "SMS_ACHIEVEMENT_SOUND");
	if (e && *e)
		return (u32)strtoul(e, nullptr, 16);
	return isSecret ? 0x4821 /* MSD_SE_SHINE_APPEAR */ : 0x4847 /* MSD_SE_SY_PROBLEM_SOLVED */;
}

void playUnlockSound()
{
	int fresh[PORT_ACH_COUNT];
	int n = port_ach_take_new(fresh, PORT_ACH_COUNT);
	if (n == 0)
		return;
	bool anySecret = false;
	for (int i = 0; i < n; i++)
		anySecret |= secret(fresh[i]);
	u32 id = soundId(anySecret); // one sound for all at once
	if (id)
		MSoundSESystem::MSoundSE::startSoundSystemSE(id, 0, nullptr, 0);
}

} // namespace

extern "C" void port_rumble_game_frame(void); // platform/pad/rumble_game.cpp

extern "C" void port_ach_game_frame(void)
{
	port_rumble_game_frame();
	if (!port_ach_enabled() || !TFlagManager::getInstance())
		return;
	static unsigned last = SDL_GetTicks();
	unsigned now = SDL_GetTicks();
	checkFrame();
	if (now - last < 1000)
		return;
	unsigned ms = now - last;
	last = now;
	if (ms > 5000)
		ms = 1000; // the app was paused or in the background
	int slot = gpApplication.mSaveFile;
	if (playing() && slot >= 0 && slot < PORT_ACH_SLOTS) {
		int shines = 0;
		for (int i = 0; i < 120; i++)
			shines += shine(i);
		port_ach_run_update(slot, shines, ms);
	}
	checkSaveFile();
	port_ach_flush();
	playUnlockSound();
}
