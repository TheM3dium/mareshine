/* Mareshine's plugin interface: native code a mod brings along, loaded by the
 * game at start (platform/mods/plugins.cpp). MIT, like the rest of Mareshine's
 * own code.
 *
 * A plugin is a shared library (lib<name>.so on Android and Linux) built with
 * the game's own headers and flags (cmake/plugins.cmake). It may call the
 * game's functions directly: they are resolved by name against the game when
 * the plugin loads. What it needs from Mareshine beyond that comes through
 * MareshineHost.
 *
 * The host structure stays valid for as long as the game runs: a plugin may
 * keep the pointer.
 *
 * It exports two names:
 *
 *   const char mareshine_plugin_game[]    = MARESHINE_PLUGIN_GAME;
 *   int mareshine_plugin_start(const MareshineHost* host);   // 0: started
 *
 * mareshine_plugin_game is the game build the plugin was compiled against (a
 * hash of the game's headers as the port compiles them, set by
 * cmake/plugins.cmake). The game loads no plugin made for another build: the
 * classes it uses could be laid out differently there. Releases that change
 * only the game's functions keep the build, so a plugin is rebuilt only when a
 * release changes those headers.
 *
 * Patches at retail addresses go through the same registry as the code mods'
 * (sms_modhook.h): the decomp asks it at the addresses a mod patches on the
 * console. */
#ifndef MARESHINE_PLUGIN_H
#define MARESHINE_PLUGIN_H

#include <stdint.h>
#ifndef __cplusplus
#include <stdbool.h>
#endif

#define MARESHINE_PLUGIN_ABI 1

#ifdef __cplusplus
extern "C" {
#endif

enum {
	MARESHINE_PATCH_BRANCH = 0, /* a branch to `value` at `addr` (the function's start) */
	MARESHINE_PATCH_CALL   = 1, /* a call at `addr` goes to `value` */
	MARESHINE_PATCH_WORD   = 2, /* the instruction word at `addr` is `value` (0x60000000: a nop) */
};

typedef struct MareshineHost {
	uint32_t abi;  /* MARESHINE_PLUGIN_ABI */
	uint32_t size; /* sizeof(MareshineHost): fields are only ever added at the end */
	const char* app_version;
	const char* mod_dir; /* the mod's folder, or "" */

	void (*log)(const char* fmt, ...);

	/* The code-mod registry. */
	int (*patch)(int kind, uint32_t addr, uintptr_t value);
	void (*code_write)(uint32_t addr, uint32_t value, int size);

	/* Calls into the plugin while the game runs. The pointers are the game's
	 * own (TMario*, TMarDirector*). */
	void (*on_player_update)(void (*fn)(void* mario, bool isMario));
	void (*on_stage_init)(void (*fn)(void* director));
	void (*on_stage_update)(void (*fn)(void* director));
	/* A scene object a stage names in its scene files: `make` returns a new
	 * JDrama::TNameRef* (allocated on the game's heap, as the game's new does). */
	void (*scene_object)(const char* name, void* (*make)(void));

	/* The plugin in the game's module list (pause menu settings): its name,
	 * version, and credits, a role with one or more names each. */
	void (*module_info)(const char* name, int major, int minor);
	void (*credit)(const char* role, const char* const* names, int count);
} MareshineHost;

typedef int (*mareshine_plugin_start_t)(const MareshineHost* host);

#ifdef __cplusplus
}
#endif

#endif
