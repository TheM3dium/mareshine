#!/usr/bin/env python3
"""Exercise actual old/new recovery and collision functions with synthetic floors.

Run from any directory after initializing the decomp submodule. Requires patch
and a C++20 compiler. No disc data or recorded scene geometry is used.
"""
from pathlib import Path
import os, subprocess, tempfile
root = Path(__file__).resolve().parents[2]


def function(s, signature):
 start=s.index(signature); op=s.index('{',start); depth=1;i=op+1
 while depth:
  depth+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[start:i]
orig=(root/'decomp/src/NPC/NpcAnm.cpp').read_text()
with tempfile.TemporaryDirectory() as patch_dir:
 target=Path(patch_dir)/'src/NPC/NpcAnm.cpp'
 target.parent.mkdir(parents=True)
 target.write_text(orig)
 subprocess.run(['patch','-s','-p1','-i',str(root/'decomp-patches/port-03-npc-recovery-raised-ground.patch')],cwd=patch_dir,check=True)
 fixed=target.read_text()
query=(root/'decomp/src/Map/MapCheck.cpp').read_text()
sig='bool TBaseNPC::npcRecoverFromSinking()'
f_orig=function(orig,sig).replace('npcRecoverFromSinking()','npcRecoverFromSinkingOriginal()')
f_fixed=function(fixed,sig)
f_query=function(query,'f32 TMapCollisionData::checkGroundList(')
header=r'''
#include <cmath>
#include <cstdio>
#include <cstdint>
using f32=float; using u8=uint8_t;
struct Vec { float x=0,y=0,z=0; void set(float a,float b,float c){x=a;y=b;z=c;} };
struct TBGCheckData {
 unsigned type=0;float mMinY=0;Vec points[3],normal{0,1,0};float distance=0;bool legal=true;
 const Vec& getPoint1()const{return points[0];} const Vec& getPoint2()const{return points[1];}const Vec& getPoint3()const{return points[2];}
 const Vec& getNormal()const{return normal;}float getPlaneDistance()const{return distance;}
 bool isWaterThrough()const{return false;}bool isWaterSurface()const{return type==258;}bool isLegal()const{return legal;}
};
struct TBGCheckList {const TBGCheckData* unk8;const TBGCheckList* next;const TBGCheckList* getNext()const{return next;}};
struct TMapCollisionData {
 enum{IGNORE_WATER_THROUGH=1,IGNORE_WATER_SURFACE=2};static TBGCheckData mIllegalCheckData;
 float checkGroundList(float,float,float,u8,const TBGCheckList*,const TBGCheckData**);
};
TBGCheckData TMapCollisionData::mIllegalCheckData{.legal=false};
struct Map :TMapCollisionData {const TBGCheckList* head=nullptr; float checkGroundIgnoreWaterSurface(float x,float y,float z,const TBGCheckData**p){return checkGroundList(x,y,z,IGNORE_WATER_SURFACE,head,p);}};
Map map;Map* gpMap=&map;
struct PollutionPos {int depth=7;float vertical=-80;bool isSame(int,int,float y)const {int d=int((y-vertical)*.025f);return depth-2<=d && d<=depth+2;}};
struct TPollutionLayer {PollutionPos mPos;int getPlaneType()const{return 0;}bool isInArea(float,float,float)const{return true;}int getTexPosS(float)const{return 0;}int getTexPosT(float)const{return 0;}};
struct PollutionManager {TPollutionLayer layers[2];int count=1;int getJointModelNum()const{return count;}TPollutionLayer* getLayer(int i){return &layers[i];}};
PollutionManager pollution;PollutionManager* gpPollution=&pollution;
struct Param {float value=220;float get()const{return value;}};
struct Params{Param mSinkHeight;};
struct Frame {bool checkPass(float){return false;}};
struct Actor {Frame frame;Frame* getFrameCtrl(int){return &frame;}bool isCurAnmAlreadyEnd(int){return true;}};
float CalcJumpVelocityY(float,float){return 0;}
constexpr int ANM_TYPE_BCK=0;
struct TBaseNPC {
 enum{LIVE_FLAG_AIRBORNE=0x80,LIVE_FLAG_UNK10=0x10,LIVE_FLAG_UNK400000=0x400000,LIVE_FLAG_UNK8000000=0x8000000};
 Params params;Params* mIndividualParams=&params;Actor actor;Vec mPosition{0,-200,0},mVelocity;
 float unk1C4=20.0f,mGroundHeight=20.0f,mVelocityMinY=-30;
 unsigned flags=LIVE_FLAG_AIRBORNE|LIVE_FLAG_UNK10|LIVE_FLAG_UNK400000|LIVE_FLAG_UNK8000000;
 const TBGCheckData* mGroundPlane=nullptr;
 bool checkLiveFlag(unsigned f){return flags&f;}void onLiveFlag(unsigned f){flags|=f;}void offLiveFlag(unsigned f){flags&=~f;}
 Actor* getMActor(){return &actor;}const Vec& getPosition(){return mPosition;}float getGravityY(){return .3f;}
 bool npcRecoverFromSinking();bool npcRecoverFromSinkingOriginal();
};
int fails=0;
void check(const char* name,bool ok){printf("%s %s\n",ok?"PASS":"FAIL",name);fails+=!ok;}
'''
main='\nint main(){\n'
# Two independent, horizontal synthetic triangles, ordered high to low.
records=[dict(type=0,minY=y,points=[[-10,y,-10],[-10,y,10],[10,y,0]],normal=[0,1,0]) for y in (200,19)]
for i,r in enumerate(records):
 pts=','.join('{'+','.join(str(float(v))+'f' for v in p)+'}' for p in r['points']);normal='{'+','.join(str(float(v))+'f' for v in r['normal'])+'}'
 # plane distance inferred from one vertex, retaining the captured normal.
 dist=-sum(a*b for a,b in zip(r['points'][0],r['normal']))
 main+=f'TBGCheckData p{i}{{{r["type"]},{float(r["minY"])}f,{{{pts}}},{normal},{float(dist)}f,true}};\n'
for i in reversed(range(len(records))):main+=f'TBGCheckList n{i}{{&p{i},'+('nullptr' if i==len(records)-1 else f'&n{i+1}')+'};\n'
main+='''
 map.head=&n0;
 TBaseNPC before;before.npcRecoverFromSinkingOriginal();
 check("old recovery preserves stale lower anchor",fabs(before.mPosition.y-20.0f)<.01f);
 TBaseNPC after;after.npcRecoverFromSinking();
 check("raised-floor recovery reaches new collision floor",fabs(after.mPosition.y-200)<.01f);
 check("recovery updates ground pointer and anchor together",after.mGroundPlane==&p0 && fabs(after.unk1C4-200)<.01f && fabs(after.mGroundHeight-200)<.01f);
 check("normal end-of-recovery flags still cleared",!(after.flags&(TBaseNPC::LIVE_FLAG_AIRBORNE|TBaseNPC::LIVE_FLAG_UNK10|TBaseNPC::LIVE_FLAG_UNK400000|TBaseNPC::LIVE_FLAG_UNK8000000)));
 p0.mMinY=400;p0.distance=-400*p0.normal.y;
 TBaseNPC bridge;bridge.npcRecoverFromSinking();
 check("overhead floor outside sink span not selected",fabs(bridge.mPosition.y-20.0f)<.01f);
 map.head=nullptr;TBaseNPC missing;missing.npcRecoverFromSinking();
 check("missing floor preserves original recovery target",fabs(missing.mPosition.y-20.0f)<.01f);
 p0.mMinY=200;p0.distance=-200*p0.normal.y;p0.legal=false;map.head=&n0;
 TBaseNPC invalid;invalid.npcRecoverFromSinking();
 check("illegal collision does not alter target",fabs(invalid.mPosition.y-20.0f)<.01f);
 p0.legal=true;pollution.count=2;pollution.layers[0].mPos.depth=2;
 TBaseNPC stacked;stacked.npcRecoverFromSinking();
 check("valid old surface under a nearby upper floor is preserved",fabs(stacked.mPosition.y-20.0f)<.01f);
 pollution.count=0;TBaseNPC unsupported;unsupported.npcRecoverFromSinking();
 check("no pollution height evidence preserves target",fabs(unsupported.mPosition.y-20.0f)<.01f);
 return fails?1:0;
}
'''
with tempfile.TemporaryDirectory() as test_dir:
 source=Path(test_dir)/'npc_recovery_test.cpp'
 executable=Path(test_dir)/'npc_recovery_test'
 source.write_text(header+'\n'+f_query+'\n'+f_orig+'\n'+f_fixed+'\n'+main)
 subprocess.run([os.environ.get('CXX','c++'),'-std=c++20','-DTARGET_PC','-fsanitize=address,undefined','-fno-omit-frame-pointer',str(source),'-o',str(executable)],check=True)
 subprocess.run([str(executable)],env={**os.environ,'ASAN_OPTIONS':'detect_leaks=0'},check=True)
