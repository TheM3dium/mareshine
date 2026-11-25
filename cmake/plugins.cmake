# Mareshine's plugins (src/port_include/mareshine_plugin.h): native code a mod
# brings along, built with the game's headers and flags and loaded by the game
# at start (platform/mods/plugins.cpp).
#
# -DMARESHINE_PLUGINS_DIR=<folder> builds the plugins in that folder (each in a
# folder of its own with a CMakeLists.txt that calls mareshine_add_plugin);
# they are never part of the game's own build or package.

# The game build a plugin is made for: what lays out the game's classes and
# declares its functions, i.e. the game's headers as the port compiles them
# (the decomp's, those its patches change, and the port's own). A plugin built
# against other ones could see the classes laid out differently, so the game
# loads none made for another build. Changes inside the game's functions
# (patches to its sources) keep the build, and the plugins made for it.
get_filename_component(MARESHINE_ROOT ${CMAKE_CURRENT_LIST_DIR}/.. ABSOLUTE)
set(_layout "")
set(_layout_files "")
foreach(root ${SMS_DECOMP} ${SMS_PATCH_ROOT})
  set(_globs "")
  foreach(d ${SMS_DECOMP_INCLUDE_SUBDIRS})
    list(APPEND _globs ${root}/${d}/*.h ${root}/${d}/*.hpp)
  endforeach()
  file(GLOB_RECURSE _hs RELATIVE ${root} ${_globs})
  list(SORT _hs)
  get_filename_component(_tag ${root} NAME)
  foreach(f ${_hs})
    file(SHA1 ${root}/${f} _h)
    string(APPEND _layout " ${_tag}:${f}=${_h}")
    list(APPEND _layout_files ${root}/${f})
  endforeach()
endforeach()
file(GLOB _port_headers ${SMS_PORT_SRC}/port_include/*.h ${SMS_PORT_SRC}/port_compat.h)
list(SORT _port_headers)
foreach(f ${_port_headers})
  file(SHA1 ${f} _h)
  get_filename_component(_n ${f} NAME)
  string(APPEND _layout " port:${_n}=${_h}")
endforeach()
string(SHA1 _layout_hash "${_layout}")
string(SUBSTRING ${_layout_hash} 0 16 MARESHINE_PLUGIN_GAME)
# patched headers are rewritten when the patches change, which reconfigures
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${_port_headers})
message(STATUS "Mareshine plugin game build: ${MARESHINE_PLUGIN_GAME}")

# The plugins the app fetches (android/app/src/main/assets/plugins.json) must
# be made for this build, or the game would turn every one of them down.
set(_known ${MARESHINE_ROOT}/android/app/src/main/assets/plugins.json)
if(EXISTS ${_known})
  file(READ ${_known} _json)
  string(JSON _n LENGTH "${_json}")
  if(_n GREATER 0)
    math(EXPR _last "${_n} - 1")
    foreach(i RANGE ${_last})
      string(JSON _id MEMBER "${_json}" ${i})
      string(JSON _game GET "${_json}" ${_id} game)
      if(NOT _game STREQUAL MARESHINE_PLUGIN_GAME)
        message(WARNING "plugins.json: ${_id} is made for game build ${_game}, this is "
          "${MARESHINE_PLUGIN_GAME}: rebuild it and publish it with this release")
      endif()
    endforeach()
  endif()
  set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${_known})
endif()

target_compile_definitions(sms PRIVATE MARESHINE_PLUGIN_GAME="${MARESHINE_PLUGIN_GAME}")
target_link_libraries(sms PRIVATE ${CMAKE_DL_LIBS})
if(NOT ANDROID AND NOT WIN32 AND NOT APPLE)
  # The game's functions and globals, for the plugins to find by name.
  set_target_properties(sms PROPERTIES ENABLE_EXPORTS ON)
endif()

# mareshine_add_plugin(<name> <sources>...): lib<name>, built as game code.
function(mareshine_add_plugin name)
  add_library(${name} SHARED ${ARGN} ${MARESHINE_ROOT}/platform/mods/plugin_new.cpp)
  target_include_directories(${name} BEFORE PRIVATE $<TARGET_PROPERTY:sms_game,INCLUDE_DIRECTORIES>
    ${MARESHINE_ROOT}/src/port_include)
  target_compile_definitions(${name} PRIVATE ${SMS_COMMON_DEFS}
    MARESHINE_PLUGIN_GAME="${MARESHINE_PLUGIN_GAME}")
  set(_flags ${SMS_GAME_FLAGS})
  list(REMOVE_ITEM _flags -fno-pie -fno-pic)
  target_compile_options(${name} PRIVATE ${_flags} -fPIC -fvisibility=hidden)
  # Its own new and delete (plugin_new.cpp, on the game's heaps) for its own code.
  if(NOT APPLE AND NOT WIN32)
    target_link_options(${name} PRIVATE -Wl,-Bsymbolic)
  endif()
  target_link_libraries(${name} PRIVATE sms)
  # Out of the game's library folder: the app packages what is there.
  set_target_properties(${name} PROPERTIES PREFIX "lib"
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/plugins/${name}
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/plugins/${name})
  # C++11 for replacing the global new and delete as they are declared
  set_source_files_properties(${MARESHINE_ROOT}/platform/mods/plugin_new.cpp TARGET_DIRECTORY ${name}
    PROPERTIES COMPILE_OPTIONS "-std=gnu++11")
endfunction()

if(MARESHINE_PLUGINS_DIR)
  file(GLOB _plugin_dirs LIST_DIRECTORIES true ${MARESHINE_PLUGINS_DIR}/*)
  foreach(d ${_plugin_dirs})
    if(EXISTS ${d}/CMakeLists.txt)
      get_filename_component(_n ${d} NAME)
      add_subdirectory(${d} ${CMAKE_BINARY_DIR}/plugins/${_n})
    endif()
  endforeach()
endif()
