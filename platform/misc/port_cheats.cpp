// Cheats (the F9 cheat menu, platform/gx), read by the decomp
// (decomp-patches/port-03 to port-08). On Android they are set in the
// launcher (Settings -> CHEATS) and come in as settings.txt keys:
//   SMS_CHEAT_GOD_MODE, SMS_CHEAT_INFINITE_LIVES, SMS_CHEAT_INFINITE_FLUDD,
//   SMS_CHEAT_MOON_JUMP        0 or 1
//   SMS_CHEAT_MOVEMENT_SPEED   1, 2 or 4
//   SMS_CHEAT_JUMP_HEIGHT      1, 2, 4 or 6
//   SMS_CHEAT_COIN_MULTIPLIER  1, 2, 5 or 10
// Movement speed, jump height and coin multiplier are factors: 1 is off.
#include <stdlib.h>
#include <string.h>

extern "C" {
int port_cheat_god_mode = 0;
int port_cheat_movement_speed = 1;
int port_cheat_jump_height = 1;
int port_cheat_infinite_lives = 0;
int port_cheat_infinite_fludd = 0;
int port_cheat_coin_multiplier = 1;
int port_cheat_moon_jump = 0;

void port_log(const char* fmt, ...);
}

// the setting's value if it is one of `allowed` (count values), else `off`
static int read_cheat(const char* name, const int* allowed, int count, int off)
{
	const char* e = getenv(name);
	if (!e || !*e)
		return off;
	const int v = atoi(e);
	for (int i = 0; i < count; i++)
		if (allowed[i] == v)
			return v;
	port_log("[port] %s: unsupported value '%s'; off\n", name, e);
	return off;
}

extern "C" void port_cheats_init(void)
{
	static const int kOnOff[] = { 0, 1 }, kSpeed[] = { 1, 2, 4 }, kJump[] = { 1, 2, 4, 6 },
	                 kCoins[] = { 1, 2, 5, 10 };
	port_cheat_god_mode = read_cheat("SMS_CHEAT_GOD_MODE", kOnOff, 2, 0);
	port_cheat_infinite_lives = read_cheat("SMS_CHEAT_INFINITE_LIVES", kOnOff, 2, 0);
	port_cheat_infinite_fludd = read_cheat("SMS_CHEAT_INFINITE_FLUDD", kOnOff, 2, 0);
	port_cheat_movement_speed = read_cheat("SMS_CHEAT_MOVEMENT_SPEED", kSpeed, 3, 1);
	port_cheat_jump_height = read_cheat("SMS_CHEAT_JUMP_HEIGHT", kJump, 4, 1);
	port_cheat_coin_multiplier = read_cheat("SMS_CHEAT_COIN_MULTIPLIER", kCoins, 4, 1);
	port_cheat_moon_jump = read_cheat("SMS_CHEAT_MOON_JUMP", kOnOff, 2, 0);
	if (port_cheat_god_mode || port_cheat_infinite_lives || port_cheat_infinite_fludd || port_cheat_movement_speed != 1
	    || port_cheat_jump_height != 1 || port_cheat_coin_multiplier != 1 || port_cheat_moon_jump)
		port_log("[port] cheats: god mode %d, infinite lives %d, infinite FLUDD %d, movement x%d, jump x%d, coins x%d, moon jump %d\n",
		         port_cheat_god_mode, port_cheat_infinite_lives, port_cheat_infinite_fludd, port_cheat_movement_speed,
		         port_cheat_jump_height, port_cheat_coin_multiplier, port_cheat_moon_jump);
}
