#ifndef SMS_PORT_FLUDD_AIM_H
#define SMS_PORT_FLUDD_AIM_H
#ifdef __cplusplus
extern "C" {
#endif
// axis 0 = horizontal, 1 = vertical; called only by aim consumers.
float port_fludd_aim_axis(float value, int axis);
// The Y camera's look (ctrlLButtonCamera_): as above, plus the gyroscope; speed is
// what the camera turns per step for a value of 1 on this axis.
float port_fludd_look_axis(float value, int axis, float speed);
#ifdef __cplusplus
}
#endif
#endif
