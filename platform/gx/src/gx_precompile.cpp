// The launcher's background shader compile (Android): ShaderService, in a
// process of its own while the launcher's menu shows, makes an OpenGL ES
// context without a window and runs GXPC_PrecompileShaders (gx_shader.cpp),
// which adds the known programs the shader cache lacks. The game then loads
// them from the cache instead of compiling them on its first visits.
#ifdef __ANDROID__
#include "gl_funcs.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdlib.h>

#include <atomic>

extern "C" int GXPC_PrecompileShaders(int (*progress)(int done, int total, void* user), void* user);

namespace {

std::atomic<bool> s_cancel{false};

void* glProc(const char* name) {
    if (void* p = reinterpret_cast<void*>(eglGetProcAddress(name))) return p;
    static void* lib = dlopen("libGLESv3.so", RTLD_NOW | RTLD_LOCAL);
    return lib ? dlsym(lib, name) : nullptr;
}

struct Reporter {
    JNIEnv* env;
    jobject service;
    jmethodID method;
};

int report(int done, int total, void* user) {
    Reporter* r = static_cast<Reporter*>(user);
    r->env->CallVoidMethod(r->service, r->method, done, total);
    return s_cancel.load() ? 1 : 0;
}

}  // namespace

// Returns the programs made, or -1 (no context, cache off, no key list).
extern "C" JNIEXPORT jint JNICALL Java_io_github_them3dium_mareshine_ShaderService_nativePrecompile(JNIEnv* env, jobject self,
                                                                                         jstring saveDir) {
    const char* dir = env->GetStringUTFChars(saveDir, nullptr);
    setenv("SMS_SAVE_DIR", dir, 1);  // the cache file's folder, as the game has it
    env->ReleaseStringUTFChars(saveDir, dir);

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) return -1;
    const EGLint configAttrs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                                  EGL_NONE};
    EGLConfig config;
    EGLint configs = 0;
    int made = -1;
    if (eglChooseConfig(display, configAttrs, &config, 1, &configs) && configs > 0) {
        const EGLint surfaceAttrs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        const EGLint contextAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        EGLSurface surface = eglCreatePbufferSurface(display, config, surfaceAttrs);
        EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttrs);
        if (surface != EGL_NO_SURFACE && context != EGL_NO_CONTEXT &&
            eglMakeCurrent(display, surface, surface, context)) {
            gx::gl::load(glProc);  // the entry points the compile uses are core ES 3.0
            jclass cls = env->GetObjectClass(self);
            Reporter r{env, self, env->GetMethodID(cls, "onProgress", "(II)V")};
            made = GXPC_PrecompileShaders(report, &r);
            __android_log_print(ANDROID_LOG_INFO, "SMS", "shader precompile: %d programs made", made);
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
    }
    eglTerminate(display);
    return made;
}

extern "C" JNIEXPORT void JNICALL Java_io_github_them3dium_mareshine_ShaderService_nativeCancel(JNIEnv*, jobject) {
    s_cancel = true;
}
#endif
