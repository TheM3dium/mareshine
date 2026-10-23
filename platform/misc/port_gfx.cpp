// Mareshine's graphics settings (src/port_include/port_gfx.h), each read once.
#include "port_gfx.h"
#include <stdlib.h>
#include <string.h>

extern "C" void port_log(const char* fmt, ...);

namespace {

float env_float(const char* name, float def, float lo, float hi)
{
	const char* e = getenv(name);
	if (!e || !*e)
		return def;
	const float v = (float)atof(e);
	return v < lo || v > hi ? def : v;
}

bool env_on(const char* name)
{
	const char* e = getenv(name);
	return e && *e && strcmp(e, "0") != 0;
}

struct Gfx {
	float drawDistance, farPlane;
	int shadowsOff, reflectionsOff, waterAnim;
	Gfx()
	{
		drawDistance   = env_float("SMS_DRAW_DISTANCE", 1.0f, 0.05f, 5.0f);
		farPlane       = env_float("SMS_FAR_PLANE", 0.0f, 0.0f, 1e9f);
		shadowsOff     = env_on("SMS_DISABLE_SHADOWS");
		reflectionsOff = env_on("SMS_DISABLE_REFLECTIONS");
		const char* w  = getenv("SMS_DISABLE_WATER_ANIM");
		waterAnim      = w && *w ? atoi(w) : 0;
		waterAnim      = waterAnim < 0 ? 0 : waterAnim > 2 ? 2 : waterAnim;
		if (drawDistance != 1.0f || farPlane > 0 || shadowsOff || reflectionsOff || waterAnim)
			port_log("[gfx] draw distance %.2f, far plane %.0f, shadows %s, reflections %s, water %s\n", drawDistance,
			         farPlane, shadowsOff ? "off" : "on", reflectionsOff ? "off" : "on",
			         waterAnim == 2 ? "off" : waterAnim ? "still" : "moving");
	}
};

const Gfx& gfx()
{
	static Gfx g;
	return g;
}

} // namespace

extern "C" float port_gfx_draw_distance(void) { return gfx().drawDistance; }
extern "C" float port_gfx_far_plane(void) { return gfx().farPlane; }
extern "C" int port_gfx_shadows_off(void) { return gfx().shadowsOff; }
extern "C" int port_gfx_reflections_off(void) { return gfx().reflectionsOff; }
extern "C" int port_gfx_water_anim(void) { return gfx().waterAnim; }
