// The launcher shell's GameActivity (com.ryuna.nfsmw2005x, an SDL3 SDLActivity) and this library: its touch overlay's
// pad and the loose controller come in through its natives, the phone's rumble goes out through its static rumble
#include <jni.h>

#include <SDL3/SDL.h>
#include <SDL3/SDL_system.h>

#include <algorithm>
#include <atomic>

#include "common/mouse_keyboard.h"
#include "host.h"

namespace host {

namespace {

// the last level sent: the game sets its motors every frame, the phone is told of a change only
std::atomic<int> g_rumble{-1};

}  // namespace

// any thread: SDL attaches it to the VM
void PhoneRumble(uint16_t left, uint16_t right) {
    int level = std::max(left, right) >> 8;  // 0..255, as GameActivity.rumble takes the larger motor
    if (g_rumble.exchange(level) == level) return;
    auto* env = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
    auto activity = static_cast<jobject>(SDL_GetAndroidActivity());
    if (!env || !activity) return;
    jclass cls = env->GetObjectClass(activity);
    jmethodID m = env->GetStaticMethodID(cls, "rumble", "(II)V");
    if (m) env->CallStaticVoidMethod(cls, m, jint(left), jint(right));
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(activity);
}

}  // namespace host

extern "C" {

// TouchControllerView.send: the overlay's buttons, face buttons, triggers and left stick (y up)
JNIEXPORT void JNICALL Java_com_ryuna_nfsmw2005x_GameActivity_nativeSetPad(JNIEnv*, jclass, jint buttons, jint face,
                                                                             jint lt, jint rt, jint lx, jint ly) {
    input::SetTouchPad(buttons, face, lt, rt, lx, ly);
}

JNIEXPORT void JNICALL Java_com_ryuna_nfsmw2005x_GameActivity_nativeSetPadRight(JNIEnv*, jclass, jint rx, jint ry) {
    input::SetTouchPadRight(rx, ry);
}

// GameActivity: a controller SDL sees no device for, its buttons, sticks and triggers, and its letting go when the app
// loses the focus
JNIEXPORT void JNICALL Java_com_ryuna_nfsmw2005x_GameActivity_nativeLooseKey(JNIEnv*, jclass, jint code, jboolean down) {
    input::LooseKey(code, down);
}

JNIEXPORT void JNICALL Java_com_ryuna_nfsmw2005x_GameActivity_nativeLooseAxes(JNIEnv*, jclass, jfloat lx, jfloat ly,
                                                                                jfloat rx, jfloat ry, jfloat lt,
                                                                                jfloat rt, jfloat hx, jfloat hy) {
    input::LooseAxes(lx, ly, rx, ry, lt, rt, hx, hy);
}

JNIEXPORT void JNICALL Java_com_ryuna_nfsmw2005x_GameActivity_nativeLooseRelease(JNIEnv*, jclass) {
    input::LooseRelease();
}

// TouchControllerView, a few times a second: the FEng menu's button prompts, for the icon buttons it draws over the
// menu. null with no menu up, else { label, pad, label, pad, ... }, pad one of A B X Y LT RT (the button a tap on it
// presses)
JNIEXPORT jobjectArray JNICALL Java_com_ryuna_nfsmw2005x_GameActivity_nativeMenuPrompts(JNIEnv* env, jclass) {
    std::optional<std::vector<touch::MenuPrompt>> prompts = touch::CurrentMenuPrompts();
    if (!prompts) return nullptr;
    static constexpr const char* kPads[] = {"A", "B", "X", "Y", "LT", "RT"};
    jclass string = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(jsize(prompts->size() * 2), string, nullptr);
    jsize i = 0;
    for (const touch::MenuPrompt& p : *prompts) {
        for (const char* s : {p.label.c_str(), kPads[p.pad]}) {
            jstring v = env->NewStringUTF(s);
            env->SetObjectArrayElement(out, i++, v);
            env->DeleteLocalRef(v);
        }
    }
    env->DeleteLocalRef(string);
    return out;
}

}  // extern "C"
