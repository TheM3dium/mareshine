#ifndef SMS_PORT_FLUDD_AIM_H
#define SMS_PORT_FLUDD_AIM_H
#ifdef __cplusplus
extern "C" {
#endif
// axis 0 = horizontal, 1 = vertical; called only by aim consumers.
float port_fludd_aim_axis(float value, int axis);
#ifdef __cplusplus
}
#endif
#endif
