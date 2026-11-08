// Force-included into every BetterSunshineEngine and Eclipse source the port
// compiles: headers their PowerPC build reaches through its own include order.
#pragma once
#include <stddef.h>
#include <Dolphin/types.h>
#ifdef __cplusplus
#include <JSystem/memory.hxx>
#endif

// The game's plain char, which the mods (built -funsigned-char, as for the
// PowerPC) declare as the game does: signed on x86, unsigned on ARM.
#if defined(__aarch64__) || defined(__arm__)
typedef unsigned char sms_game_char;
#else
typedef signed char sms_game_char;
#endif

// The port's function for a retail address a mod calls through a cast
// (fixup_sources.py rewrites the address into a call of this).
#ifdef __cplusplus
extern "C"
#endif
void* sms_mod_rawaddr(unsigned int addr);

// The port's object for a retail data address a mod reads or writes
// (platform/mods/eclipse/rawdata.cpp; fixup_sources.py rewrites the casts).
#ifdef __cplusplus
extern "C"
#endif
void* sms_mod_rawdata(unsigned int addr);

// The port's MEM1 for an OS global's retail address, 0x80000008 and the like
// (platform/mods/eclipse/rawdata.cpp; fixup_sources.py rewrites the casts).
#ifdef __cplusplus
extern "C"
#endif
void* sms_mod_lowmem(unsigned int addr);

// SMS_OFFSET(Class, retail offset): where the port keeps a member the mods
// address by retail offset (tools/mods/shi_layout/offsets.py).
#ifdef __cplusplus
#include <sms_offsets.h>
#endif

// Mareshine's achievements (platform/achievements): what happened in a mod's
// game, for ach_mods.cpp. Weak: the achievements are Android's alone.
#ifdef __cplusplus
extern "C" {
#endif
__attribute__((weak)) void port_ach_unlock_id(const char* id);
__attribute__((weak)) void port_ach_add_id(const char* id, int amount);
__attribute__((weak)) void port_ach_mod_state(const char* key, int value);
__attribute__((weak)) void port_ach_mod_boss(const char* boss);
#ifdef __cplusplus
}
#endif
// an achievement that is unlocked now
static inline void sms_mod_ach(const char* id) { if (port_ach_unlock_id) port_ach_unlock_id(id); }
// one with a progress bar: `amount` more
static inline void sms_mod_ach_add(const char* id, int amount) { if (port_ach_add_id) port_ach_add_id(id, amount); }
// a state of the mod's game (New Game Plus on, the character played, ...)
static inline void sms_mod_ach_state(const char* key, int value) { if (port_ach_mod_state) port_ach_mod_state(key, value); }
// one of the mod's own bosses beaten
static inline void sms_mod_ach_boss(const char* boss) { if (port_ach_mod_boss) port_ach_mod_boss(boss); }
