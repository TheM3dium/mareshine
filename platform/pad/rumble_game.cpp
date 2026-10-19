// The game's own rumble option (Options → Rumble) and the app's (Settings →
// VIBRATION: SMS_VIBRATION_CONTROLLER and SMS_VIBRATION_PHONE, in percent)
// show the same: the game's is on while one of the app's is above 0. The app's are
// put into the game when it starts, after a memory card load and when they
// change; the game's option turns both to 0, and back again as they were
// (SunshineActivity saves them). Built into the game library, as it uses the
// decomp's classes; called once a frame from port_ach_game_frame.
#include <System/FlagManager.hpp>
#include <MarioUtil/RumbleMgr.hpp>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#ifdef __ANDROID__
extern "C" __attribute__((weak)) int SDL_AndroidSendMessage(uint32_t command, int param);
#endif

namespace {
const u32 kRumbleFlag = 0x90000; // live copy of the option, 1 = on (FlagManager.cpp)

extern "C" int port_vibration_percent(const char* name); // platform/pad/pad.cpp

// the controller's percent, and the phone's << 8
int appSettings()
{
	return port_vibration_percent("SMS_VIBRATION_CONTROLLER") | port_vibration_percent("SMS_VIBRATION_PHONE") << 8;
}

void setAppSettings(int v)
{
	char b[8];
	snprintf(b, sizeof b, "%d", v & 0xFF);
	setenv("SMS_VIBRATION_CONTROLLER", b, 1);
	snprintf(b, sizeof b, "%d", (v >> 8) & 0xFF);
	setenv("SMS_VIBRATION_PHONE", b, 1);
#ifdef __ANDROID__
	if (SDL_AndroidSendMessage)
		SDL_AndroidSendMessage(0x8006, v); // SunshineActivity.COMMAND_RUMBLE_OPTION
#endif
}
} // namespace

extern "C" void port_rumble_game_frame(void)
{
	TFlagManager* flags = TFlagManager::getInstance();
	if (!flags || !SMSRumbleMgr)
		return;
	static int s_want = -1, s_active = -1, s_before = 80 | 80 << 8;
	const int active = SMSRumbleMgr->mActive ? 1 : 0;
	if (s_active >= 0 && active != s_active && active != s_want) {
		// changed in the game's options: the app's settings follow
		if (!active)
			s_before = appSettings();
		setAppSettings(active ? (s_before ? s_before : 80 | 80 << 8) : 0);
	}
	const int want = appSettings() ? 1 : 0;
	if (want != s_want || (flags->getFlag(kRumbleFlag) != 0) != (want != 0) || active != want) {
		flags->setFlag(kRumbleFlag, want);
		SMSRumbleMgr->setActive(want != 0);
	}
	s_want   = want;
	s_active = SMSRumbleMgr->mActive ? 1 : 0;
}
