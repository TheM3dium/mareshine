#ifndef SMS_PORT_CULL_H
#define SMS_PORT_CULL_H
#ifdef __cplusplus
extern "C" {
#endif
// The base Android port's distance culls (NPCs, live actors, map objects,
// static props), which retail does not have. Returns the distance for `env`:
// its own setting when given (0 or less turns that cull off), else
// `forkDefault` while the draw distance is below the original (1.0), and 0
// (off, as on the GameCube) at the original draw distance or more.
float port_cull_distance(const char* env, float forkDefault);
#ifdef __cplusplus
}
#endif
#endif
