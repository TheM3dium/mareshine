// Achievements, the game side for Super Mario Tropical Freeze (its module is
// a plugin, not in this repository). Sunshine's checks (ach_game.cpp) cover
// its Sunshine-shaped list; this is the ice. Called every frame a level
// is played by ach_mods.cpp. Built into the game library, as ach_game.cpp.
#include <System/Application.hpp>
#include <System/MarDirector.hpp>
#include <Player/Mario.hpp>
#include <Map/MapCollisionData.hpp>

#include "achievements.h"

namespace {

const u16 kIceFloor = 0x901; // the mod's ice floor (its collision type)

int s_onIce;      // frames in a row on the ice, on the ground
int s_sinceIce = 1000; // frames since Mario was last on the ice
bool s_wasDown;

} // namespace

extern "C" void port_ach_tf_frame(void)
{
	TMario* m = gpMarioOriginal;
	if (!m || !m->mGroundPlane)
		return;
	bool airborne = (m->mStatus & 0x800) != 0; // MARIO_STATUS_FLAG_JUMPING (airborne)
	bool ice = !airborne && m->mGroundPlane != &TMapCollisionData::mIllegalCheckData
	           && m->mGroundPlane->mBGType == kIceFloor;
	bool down = (m->mFlag & 0x400) || m->mHealth <= 0;

	if (ice) {
		// sliding: moving over it (standing still does not count for the 10 seconds)
		s_onIce = m->mForwardVel > 5.0f || m->mForwardVel < -5.0f ? s_onIce + 1 : 0;
		s_sinceIce = 0;
		if (s_onIce >= 300) // 10 seconds at 30 a second
			port_ach_unlock(PORT_ACH_TF_ICE_SKATE_10S);
	} else {
		// a jump straight off the ice at full speed (TF keeps 0.9 of it)
		if (airborne && s_sinceIce <= 2 && m->mForwardVel > 30.0f)
			port_ach_unlock(PORT_ACH_TF_ICE_MOMENTUM_JUMP);
		s_onIce = 0;
		if (s_sinceIce < 1000)
			s_sinceIce++;
	}
	// a life lost within two seconds of sliding on the ice
	if (down && !s_wasDown && s_sinceIce < 60)
		port_ach_unlock(PORT_ACH_TF_ICE_NO_BRAKES);
	s_wasDown = down;
}
