/*  ------------------------------------------------------------------
    Copyright (c) 2017 Marc Toussaint
    email: marc.toussaint@informatik.uni-stuttgart.de

    This code is distributed under the MIT License.
    Please see <root-path>/LICENSE for details.
    --------------------------------------------------------------  */

#pragma once

#include "feature.h"

//===========================================================================

struct F_qItself : Feature {
  enum PickMode { byJointNames, byFrameNames, byJointGroups, byExcludeJointNames };

  uintA selectedFrames; ///< optionally, select only a subset of joints, indicated by the BODIES! indices (reason: frame indices are stable across kinematic switches)
  bool moduloTwoPi; ///< if false, consider multiple turns of a joint as different q values (Default: true)
  bool relative_q0; ///< if true, absolute values are given relative to Joint::q0
  
  F_qItself(bool relative_q0=false);
  F_qItself(PickMode pickMode, const StringA& picks, const rai::KinematicWorld& G, bool relative_q0=false);
  F_qItself(const uintA& _selectedFrames, bool relative_q0=false);
  
  virtual void phi(arr& y, arr& J, const rai::KinematicWorld& G);
  virtual void phi(arr& y, arr& J, const WorldL& Ktuple);
  virtual uint dim_phi(const rai::KinematicWorld& G);
  virtual uint dim_phi(const WorldL& Ktuple);
  virtual rai::String shortTag(const rai::KinematicWorld& G);
private:
  std::map<rai::KinematicWorld*, uint> dimPhi;
};

//===========================================================================

struct F_qZeroVel : Feature {
  int i;               ///< which shapes does it refer to?

  F_qZeroVel(int iShape=-1) : i(iShape) { order=1; }
  F_qZeroVel(const rai::KinematicWorld& K, const char* iShapeName=NULL) : F_qZeroVel(initIdArg(K,iShapeName)){}

  virtual void phi(arr& y, arr& J, const rai::KinematicWorld& G){ NIY; }
  virtual void phi(arr& y, arr& J, const WorldL& Ktuple);
  virtual uint dim_phi(const rai::KinematicWorld& G);
  virtual rai::String shortTag(const rai::KinematicWorld& G){ return STRING("qZeroVel-" <<G.frames(i)->name); }
};

//===========================================================================

struct F_qLimits : Feature {
  //TODO (danny) allow margin specification
  arr limits;

  F_qLimits(const arr& _limits=NoArr) { if(!!_limits) limits=_limits; } ///< if no limits are provided, they are taken from G's joints' attributes on the first call of phi
  virtual void phi(arr& y, arr& J, const rai::KinematicWorld& G);
  virtual uint dim_phi(const rai::KinematicWorld& G) { return 1; }
  virtual rai::String shortTag(const rai::KinematicWorld& G) { return STRING("qLimits"); }
};

//===========================================================================

struct F_qQuaternionNorms : Feature {
  virtual void phi(arr& y, arr& J, const rai::KinematicWorld& G);
  virtual uint dim_phi(const rai::KinematicWorld& G);
  virtual rai::String shortTag(const rai::KinematicWorld& G) { return STRING("QuaternionNorms"); }
};

//===========================================================================

rai::Array<rai::Joint*> getMatchingJoints(const WorldL& Ktuple, bool zeroVelJointsOnly);
rai::Array<rai::Joint*> getSwitchedJoints(const rai::KinematicWorld& G0, const rai::KinematicWorld& G1, int verbose=0);
uintA getSwitchedBodies(const rai::KinematicWorld& G0, const rai::KinematicWorld& G1, int verbose=0);
uintA getNonSwitchedBodies(const WorldL& Ktuple);

