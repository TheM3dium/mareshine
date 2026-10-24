// Mareshine's JNI functions (SaveStates, GameAudio, Warp) live in the game library,
// which android/bootstrap.cpp loads itself (android_dlopen_ext), so Java's
// lookup of native methods, which searches only the libraries System.loadLibrary
// loaded, does not find them. They are registered here when the game starts,
// through the activity's class loader (this thread's FindClass sees only the
// system's classes). Each method on its own: one the app lacks skips only itself.
#ifdef __ANDROID__
#include <jni.h>
#include <stddef.h>

extern "C" void port_log(const char* fmt, ...);
extern "C" __attribute__((weak)) void* SDL_AndroidGetJNIEnv(void);
extern "C" __attribute__((weak)) void* SDL_AndroidGetActivity(void);

#define SAVESTATES(name) Java_io_github_them3dium_mareshine_SaveStates_##name
extern "C" {
__attribute__((weak)) jstring SAVESTATES(nativeSlotInfo)(JNIEnv*, jclass, jint);
__attribute__((weak)) jbyteArray SAVESTATES(nativeThumbnail)(JNIEnv*, jclass, jint);
__attribute__((weak)) void SAVESTATES(nativeSave)(JNIEnv*, jclass, jint);
__attribute__((weak)) jint SAVESTATES(nativeLoad)(JNIEnv*, jclass, jint);
__attribute__((weak)) jint SAVESTATES(nativeDelete)(JNIEnv*, jclass, jint);
__attribute__((weak)) void SAVESTATES(nativeSetQuickSlot)(JNIEnv*, jclass, jint);
__attribute__((weak)) void SAVESTATES(nativeSetDelay)(JNIEnv*, jclass, jboolean);
__attribute__((weak)) jint SAVESTATES(nativeQuickSlot)(JNIEnv*, jclass);
__attribute__((weak)) jint SAVESTATES(nativeReady)(JNIEnv*, jclass);
__attribute__((weak)) void Java_io_github_them3dium_mareshine_GameAudio_nativeSetVolume(JNIEnv*, jclass, jint, jint);
__attribute__((weak)) jobjectArray Java_io_github_them3dium_mareshine_Warp_nativeList(JNIEnv*, jclass);
__attribute__((weak)) jint Java_io_github_them3dium_mareshine_Warp_nativeReady(JNIEnv*, jclass);
__attribute__((weak)) void Java_io_github_them3dium_mareshine_Warp_nativeGo(JNIEnv*, jclass, jint, jint);
}

namespace {

struct Native {
	const char* cls;
	JNINativeMethod method;
};

const Native kNatives[] = {
	{ "SaveStates", { "nativeSlotInfo", "(I)Ljava/lang/String;", (void*)SAVESTATES(nativeSlotInfo) } },
	{ "SaveStates", { "nativeThumbnail", "(I)[B", (void*)SAVESTATES(nativeThumbnail) } },
	{ "SaveStates", { "nativeSave", "(I)V", (void*)SAVESTATES(nativeSave) } },
	{ "SaveStates", { "nativeLoad", "(I)I", (void*)SAVESTATES(nativeLoad) } },
	{ "SaveStates", { "nativeDelete", "(I)I", (void*)SAVESTATES(nativeDelete) } },
	{ "SaveStates", { "nativeSetQuickSlot", "(I)V", (void*)SAVESTATES(nativeSetQuickSlot) } },
	{ "SaveStates", { "nativeSetDelay", "(Z)V", (void*)SAVESTATES(nativeSetDelay) } },
	{ "SaveStates", { "nativeQuickSlot", "()I", (void*)SAVESTATES(nativeQuickSlot) } },
	{ "SaveStates", { "nativeReady", "()I", (void*)SAVESTATES(nativeReady) } },
	{ "GameAudio", { "nativeSetVolume", "(II)V", (void*)Java_io_github_them3dium_mareshine_GameAudio_nativeSetVolume } },
	{ "Warp", { "nativeList", "()[Ljava/lang/String;", (void*)Java_io_github_them3dium_mareshine_Warp_nativeList } },
	{ "Warp", { "nativeReady", "()I", (void*)Java_io_github_them3dium_mareshine_Warp_nativeReady } },
	{ "Warp", { "nativeGo", "(II)V", (void*)Java_io_github_them3dium_mareshine_Warp_nativeGo } },
};

// The app's class `name` (in io.github.them3dium.mareshine), or null.
jclass app_class(JNIEnv* env, jobject loader, jmethodID loadClass, const char* name)
{
	char full[96];
	int n = 0;
	for (const char* p = "io.github.them3dium.mareshine."; *p; p++)
		full[n++] = *p;
	for (const char* p = name; *p && n < (int)sizeof full - 1; p++)
		full[n++] = *p;
	full[n] = 0;
	jstring jname = env->NewStringUTF(full);
	jclass cls    = (jclass)env->CallObjectMethod(loader, loadClass, jname);
	env->DeleteLocalRef(jname);
	if (env->ExceptionCheck()) {
		env->ExceptionClear();
		return nullptr;
	}
	return cls;
}

} // namespace

extern "C" void port_android_register_natives(void)
{
	JNIEnv* env      = SDL_AndroidGetJNIEnv ? (JNIEnv*)SDL_AndroidGetJNIEnv() : nullptr;
	jobject activity = SDL_AndroidGetActivity ? (jobject)SDL_AndroidGetActivity() : nullptr;
	if (!env || !activity) {
		port_log("[android] no activity: the app's native methods stay unregistered\n");
		return;
	}
	jclass actClass  = env->GetObjectClass(activity);
	jobject loader   = env->CallObjectMethod(activity, env->GetMethodID(actClass, "getClassLoader", "()Ljava/lang/ClassLoader;"));
	jclass ldrClass  = env->FindClass("java/lang/ClassLoader");
	jmethodID loadCl = env->GetMethodID(ldrClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
	int done = 0, total = 0;
	for (const Native& n : kNatives) {
		if (!n.method.fnPtr)
			continue;
		total++;
		jclass cls = app_class(env, loader, loadCl, n.cls);
		if (!cls)
			continue;
		if (env->RegisterNatives(cls, &n.method, 1) == 0)
			done++;
		else if (env->ExceptionCheck())
			env->ExceptionClear();
		env->DeleteLocalRef(cls);
	}
	env->DeleteLocalRef(ldrClass);
	env->DeleteLocalRef(loader);
	env->DeleteLocalRef(actClass);
	env->DeleteLocalRef(activity);
	port_log("[android] %d of %d app native methods registered\n", done, total);
}
#endif
