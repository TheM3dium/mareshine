// Save states on Android (Mareshine): the app's side of platform/savestate.
//
// The app's pause menu (SaveStates.java, GameMenuActivity) lists the five
// slots and saves, loads and deletes through the JNI functions below; the
// requests are carried out by the game at the top of its next frame. The
// game's messages ("State saved") go to SunshineActivity as a code, which it
// shows in the player's language (SDL_AndroidSendMessage, COMMAND_SAVESTATE).
#ifdef __ANDROID__
#include "port_savestate.h"

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern "C" __attribute__((weak)) int SDL_AndroidSendMessage(uint32_t command, int param);
extern "C" void port_log(const char* fmt, ...);

namespace {

// SunshineActivity.COMMAND_SAVESTATE; param = message code << 8 | slot
const uint32_t kCommandSaveState = 0x8002;

struct Message {
	const char* text;
	int code;
};
// the messages platform/savestate and the frame patch give, as the codes
// SunshineActivity knows (SaveStates.MSG_*)
const Message kMessages[] = {
	{ "State saved", 1 },
	{ "Could not save the state", 2 },
	{ "State loaded", 3 },
	{ "State loaded (position only)", 4 },
	{ "Could not load the state", 5 },
	{ "Save states work in a level, once it has loaded", 6 },
	{ "Load states in a level, once it has loaded", 6 },
	{ "Still saving the last state", 7 },
	{ "Could not save the state: the game is busy", 7 },
	{ "This slot is empty", 8 },
	{ "That save state is from another game", 9 },
	{ "That save state could not be read", 10 },
	{ "Hold SAVE to save", 11 },
	{ "Hold LOAD to load", 12 },
	{ "Still loading a state", 13 },
};

} // namespace

extern "C" void port_savestate_android_message(const char* text, int slot)
{
	int code = 0;
	for (const Message& m : kMessages)
		if (!strcmp(text, m.text))
			code = m.code;
	if (code && SDL_AndroidSendMessage)
		SDL_AndroidSendMessage(kCommandSaveState, code << 8 | (slot & 0xFF));
}

#define JNI_FN(name) Java_io_github_them3dium_mareshine_SaveStates_##name

// A slot's state as "stage\tepisode\twhen\tcreated\texact\tsameGame", or null.
extern "C" JNIEXPORT jstring JNICALL JNI_FN(nativeSlotInfo)(JNIEnv* env, jclass, jint slot)
{
	PortSaveStateInfo info;
	if (!port_savestate_slot_info(slot, &info))
		return nullptr;
	char line[PORT_SAVESTATE_NAME_LEN * 2 + 96];
	snprintf(line, sizeof line, "%s\t%s\t%s\t%lld\t%d\t%d", info.stageName, info.episodeName, info.when,
	         (long long)info.created, info.exact, info.sameGame);
	return env->NewStringUTF(line);
}

// A slot's picture, a PNG, or null.
extern "C" JNIEXPORT jbyteArray JNICALL JNI_FN(nativeThumbnail)(JNIEnv* env, jclass, jint slot)
{
	PortSaveStateInfo info;
	if (!port_savestate_slot_info(slot, &info))
		return nullptr;
	size_t size = 0;
	uint8_t* png = port_savestate_thumbnail_png(info.path, &size);
	if (!png)
		return nullptr;
	jbyteArray out = env->NewByteArray((jsize)size);
	if (out)
		env->SetByteArrayRegion(out, 0, (jsize)size, (const jbyte*)png);
	free(png);
	return out;
}

extern "C" JNIEXPORT void JNICALL JNI_FN(nativeSave)(JNIEnv*, jclass, jint slot) { port_savestate_request_save_slot(slot); }
extern "C" JNIEXPORT jint JNICALL JNI_FN(nativeLoad)(JNIEnv*, jclass, jint slot) { return port_savestate_request_load_slot(slot); }
extern "C" JNIEXPORT jint JNICALL JNI_FN(nativeDelete)(JNIEnv*, jclass, jint slot) { return port_savestate_delete_slot(slot); }
extern "C" JNIEXPORT void JNICALL JNI_FN(nativeSetQuickSlot)(JNIEnv*, jclass, jint slot) { port_savestate_set_quick_slot(slot); }
extern "C" JNIEXPORT jint JNICALL JNI_FN(nativeQuickSlot)(JNIEnv*, jclass) { return port_savestate_quick_slot(); }
extern "C" JNIEXPORT jint JNICALL JNI_FN(nativeReady)(JNIEnv*, jclass) { return port_savestate_ready(); }
// the state menu's "Save/Load delay" switch (platform/pad/touch.cpp)
extern "C" void touch_controls_set_state_delay(int on);
extern "C" JNIEXPORT void JNICALL JNI_FN(nativeSetDelay)(JNIEnv*, jclass, jboolean on) { touch_controls_set_state_delay(on ? 1 : 0); }

// At start: the quick slot the player chose (settings.txt SMS_SAVESTATE_SLOT).
extern "C" void port_savestate_android_init(void)
{
	if (const char* s = getenv("SMS_SAVESTATE_SLOT"))
		port_savestate_set_quick_slot(atoi(s));
	port_log("[savestate] quick slot %d\n", port_savestate_quick_slot());
}
#endif
