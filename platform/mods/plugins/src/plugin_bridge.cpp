// Mareshine's plugins on BetterSunshineEngine: a module like the others, which
// starts the plugins a mod brings along (platform/mods/plugins.cpp loads them)
// and gives them the engine's callbacks, scene objects and module list through
// the plugin interface (src/port_include/mareshine_plugin.h). Mareshine's own
// code, MIT; built with the engine's headers, as the modules are.

#include <Dolphin/OS.h>
#include <Dolphin/string.h>

#include <JSystem/JDrama/JDRNameRef.hxx>
#include <SMS/System/MarDirector.hxx>
#include <SMS/Player/Mario.hxx>

#include <BetterSMS/module.hxx>
#include <BetterSMS/object.hxx>
#include <BetterSMS/player.hxx>
#include <BetterSMS/settings.hxx>
#include <BetterSMS/stage.hxx>

#include "../../../../src/port_include/mareshine_plugin.h"

// platform/mods/plugins.cpp
extern "C" int sms_plugin_count(void);
extern "C" int sms_plugin_start(int index, MareshineHost *host);

namespace {

// A credit: a role, and the names it lists for it (the value picks the name).
class CreditSetting : public Settings::IntSetting {
public:
    CreditSetting(const char *role, const char *const *names, int count)
        : IntSetting(role, &mValue), mValue(0), mNames(names) {
        mValueRange = {0, count - 1, 1};
    }

    void getValueName(char *dst) const override { strcpy(dst, mNames[getInt()]); }
    // nothing kept
    void load(JSUMemoryInputStream &in) override {}
    void save(JSUMemoryOutputStream &out) override {}

private:
    int mValue;
    const char *const *mNames;
};

// The plugin starting now: its entry in the module list.
struct PluginModule {
    PluginModule() : settings(1, 0, Settings::Priority::MODE), info(nullptr) {}
    Settings::SettingsGroup settings;
    BetterSMS::ModuleInfo *info;
};
PluginModule *sCurrent;

void hostOnPlayerUpdate(void (*fn)(void *, bool)) {
    BetterSMS::Player::addUpdateCallback(reinterpret_cast<BetterSMS::Player::UpdateCallback>(fn));
}
void hostOnStageInit(void (*fn)(void *)) {
    BetterSMS::Stage::addInitCallback(reinterpret_cast<BetterSMS::Stage::InitCallback>(fn));
}
void hostOnStageUpdate(void (*fn)(void *)) {
    BetterSMS::Stage::addUpdateCallback(reinterpret_cast<BetterSMS::Stage::UpdateCallback>(fn));
}
void hostSceneObject(const char *name, void *(*make)(void)) {
    BetterSMS::Objects::registerObjectAsMisc(
        name, reinterpret_cast<BetterSMS::Objects::NameRefInitializer>(make));
}
void hostModuleInfo(const char *name, int major, int minor) {
    if (sCurrent && !sCurrent->info)
        sCurrent->info = new BetterSMS::ModuleInfo(name, major, minor, &sCurrent->settings);
}
void hostCredit(const char *role, const char *const *names, int count) {
    if (sCurrent && names && count > 0)
        sCurrent->settings.addSetting(new CreditSetting(role, names, count));
}

void startPlugins() {
    int n = sms_plugin_count();
    for (int i = 0; i < n; i++) {
        // The plugin keeps it for as long as the game runs.
        MareshineHost *host     = new MareshineHost();
        host->on_player_update  = hostOnPlayerUpdate;
        host->on_stage_init     = hostOnStageInit;
        host->on_stage_update   = hostOnStageUpdate;
        host->scene_object      = hostSceneObject;
        host->module_info       = hostModuleInfo;
        host->credit            = hostCredit;
        sCurrent                = new PluginModule;
        if (sms_plugin_start(i, host) == 0 && sCurrent->info) {
            // A plugin's credits keep no value: no save of its own.
            sCurrent->settings.setIOValid(false);
            BetterSMS::registerModule(*sCurrent->info);
        }
        sCurrent = nullptr;
    }
}

} // namespace

KURIBO_MODULE_BEGIN("Mareshine plugins", "Mareshine", "v1.0") {
    KURIBO_EXECUTE_ON_LOAD { startPlugins(); }
}
KURIBO_MODULE_END()
