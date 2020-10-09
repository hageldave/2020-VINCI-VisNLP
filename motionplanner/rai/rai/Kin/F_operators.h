/*  ------------------------------------------------------------------
    Copyright (c) 2017 Marc Toussaint
    email: marc.toussaint@informatik.uni-stuttgart.de

    This code is distributed under the MIT License.
    Please see <root-path>/LICENSE for details.
    --------------------------------------------------------------  */

#pragma once
#include "feature.h"

//===========================================================================

struct TM_Max : Feature {
  Feature *map;
  bool neg;
  
  TM_Max(Feature *map, bool neg=false) : map(map), neg(neg) {}
  
  virtual void phi(arr& y, arr& J, const rai::KinematicWorld& G);
  virtual uint dim_phi(const rai::KinematicWorld& G) { return 1; }
  virtual rai::String shortTag(const rai::KinematicWorld& G) { return STRING("Max:"<<map->shortTag((G))); }
};

//===========================================================================

struct TM_Norm : Feature {
  Feature *map;

  TM_Norm(Feature *map) : map(map) {}

  virtual void phi(arr& y, arr& J, const rai::KinematicWorld& G);
  virtual uint dim_phi(const rai::KinematicWorld& G);
  virtual rai::String shortTag(const rai::KinematicWorld& G) { return STRING("Norm:"<<map->shortTag((G))); }
};
