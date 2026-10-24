// Test builds (Mareshine): warping from the app's quick menu to any episode,
// through the port's warp between the game and its menus (port_warp.h, patch
// zzz-warp-01). The game builds the list of episodes once it is asked to
// (port_warp_android_init, at start) and takes a warp at the top of a frame in
// a stage; the app reads the list and asks for one (Warp.java).
#ifdef __ANDROID__
#include <port_warp.h>

#include <jni.h>
#include <stdio.h>

extern "C" void port_warp_android_init(void) { port_warp_want_list(); }

// The episodes as "area\tepisode\tworld\tname", or null while the game has
// not built the list yet.
extern "C" JNIEXPORT jobjectArray JNICALL Java_io_github_them3dium_mareshine_Warp_nativeList(JNIEnv* env, jclass)
{
	const int n = port_warp_count();
	if (n <= 0)
		return nullptr;
	jobjectArray out = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
	for (int i = 0; i < n; i++) {
		PortWarpEntry e;
		if (!port_warp_entry(i, &e))
			continue;
		char row[256];
		snprintf(row, sizeof row, "%d\t%d\t%s\t%s", e.area, e.episode, e.world, e.name);
		for (char* c = row; *c; c++) // the game's text is not UTF-8 everywhere
			if ((unsigned char)*c >= 0x80)
				*c = '?';
		jstring s = env->NewStringUTF(row);
		env->SetObjectArrayElement(out, i, s);
		env->DeleteLocalRef(s);
	}
	return out;
}

extern "C" JNIEXPORT jint JNICALL Java_io_github_them3dium_mareshine_Warp_nativeReady(JNIEnv*, jclass) { return port_warp_ready(); }

extern "C" JNIEXPORT void JNICALL Java_io_github_them3dium_mareshine_Warp_nativeGo(JNIEnv*, jclass, jint area, jint episode)
{
	port_warp_request(area, episode);
}
#endif
