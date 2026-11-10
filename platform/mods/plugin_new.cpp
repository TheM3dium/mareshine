// A plugin's own operator new and delete (cmake/plugins.cmake): on the game's
// heaps, as the game's new does (CMakeLists.txt renames the game's to these
// names), so objects the plugin makes can be deleted by the game and the
// other way round.

#include <stddef.h>

extern "C" void* __sms_game_new(size_t size);
extern "C" void* __sms_game_new_array(size_t size);
extern "C" void __sms_game_delete(void* p);
extern "C" void __sms_game_delete_array(void* p);

void* operator new(size_t size) { return __sms_game_new(size); }
void* operator new[](size_t size) { return __sms_game_new_array(size); }
void operator delete(void* p) { __sms_game_delete(p); }
void operator delete[](void* p) { __sms_game_delete_array(p); }
