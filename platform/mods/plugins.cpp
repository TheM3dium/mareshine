// Mareshine's plugins: native code a mod brings along (src/port_include/
// mareshine_plugin.h). SMS_PLUGIN lists their files (';' between them); the
// launcher sets it for the mod that is played, from its folder (on Android
// after copying the file into the app's own storage: shared storage is
// mounted without execute rights). The plugins' module on BetterSunshineEngine
// (platform/mods/plugins/src/plugin_bridge.cpp) starts them when the code mods
// load, and fills in what the engine provides.

#include "../../src/port_include/mareshine_plugin.h"

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#if defined(_WIN32)
#include <windows.h>
#else
#include <dlfcn.h>
#endif

#ifndef MARESHINE_PLUGIN_GAME
#define MARESHINE_PLUGIN_GAME "unknown"
#endif

extern "C" int sms_mod_register(int kind, uint32_t addr, uintptr_t value, int enabled, const char* file,
                                int line);
extern "C" void sms_mod_code_write(uint32_t addr, uint32_t value, int size);

// The game build plugins are made for (cmake/plugins.cmake), for the tools
// that build them.
extern "C" const char mareshine_game_build[] = MARESHINE_PLUGIN_GAME;

namespace {

std::vector<std::string> plugin_files()
{
	std::vector<std::string> files;
	const char* e = getenv("SMS_PLUGIN");
	if (!e)
		return files;
	std::string all = e;
	size_t start = 0;
	while (start <= all.size()) {
		size_t end = all.find(';', start);
		if (end == std::string::npos)
			end = all.size();
		if (end > start)
			files.push_back(all.substr(start, end - start));
		start = end + 1;
	}
	return files;
}

void host_log(const char* fmt, ...)
{
	char line[1024];
	va_list ap;
	va_start(ap, fmt);
	vsnprintf(line, sizeof line, fmt, ap);
	va_end(ap);
	size_t n = strlen(line);
	fprintf(stderr, "[plugin] %s%s", line, n && line[n - 1] == '\n' ? "" : "\n");
}

int host_patch(int kind, uint32_t addr, uintptr_t value)
{
	return sms_mod_register(kind, addr, value, 1, "plugin", 0);
}

void* open_library(const char* path, std::string* error)
{
#if defined(_WIN32)
	HMODULE h = LoadLibraryA(path);
	if (!h) {
		char buf[64];
		snprintf(buf, sizeof buf, "error %lu", (unsigned long)GetLastError());
		*error = buf;
	}
	return (void*)h;
#else
	void* h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
	if (!h)
		*error = dlerror();
	return h;
#endif
}

void* find_symbol(void* lib, const char* name)
{
#if defined(_WIN32)
	return (void*)GetProcAddress((HMODULE)lib, name);
#else
	return dlsym(lib, name);
#endif
}

} // namespace

extern "C" int sms_plugin_count(void) { return (int)plugin_files().size(); }

// Loads plugin `index` of SMS_PLUGIN and starts it with `host` (the engine's
// part filled in by the caller): 0 when it started.
extern "C" int sms_plugin_start(int index, MareshineHost* host)
{
	std::vector<std::string> files = plugin_files();
	if (index < 0 || index >= (int)files.size() || !host)
		return -1;
	const char* path = files[index].c_str();
	std::string error;
	void* lib = open_library(path, &error);
	if (!lib) {
		fprintf(stderr, "[plugin] %s does not load: %s\n", path, error.c_str());
		return -1;
	}
	const char* built = (const char*)find_symbol(lib, "mareshine_plugin_game");
	if (!built || strcmp(built, MARESHINE_PLUGIN_GAME) != 0) {
		fprintf(stderr, "[plugin] %s is made for game build %s, this is %s: not started\n", path,
		        built ? built : "(none)", MARESHINE_PLUGIN_GAME);
		return -1;
	}
	mareshine_plugin_start_t start = (mareshine_plugin_start_t)find_symbol(lib, "mareshine_plugin_start");
	if (!start) {
		fprintf(stderr, "[plugin] %s has no mareshine_plugin_start\n", path);
		return -1;
	}
	static std::string version, dir;
	const char* v = getenv("SMS_APP_VERSION");
	const char* d = getenv("SMS_GAME_MOD");
	version = v ? v : "";
	dir     = d ? d : "";
	host->abi         = MARESHINE_PLUGIN_ABI;
	host->size        = sizeof(MareshineHost);
	host->app_version = version.c_str();
	host->mod_dir     = dir.c_str();
	host->log         = host_log;
	host->patch       = host_patch;
	host->code_write  = sms_mod_code_write;
	int rc = start(host);
	fprintf(stderr, "[plugin] %s started (%d)\n", path, rc);
	return rc;
}
