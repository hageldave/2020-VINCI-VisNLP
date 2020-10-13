#!/bin/sh
# building motion planner
cd motionplanner
echo "installing required libs (apt-get, sudo required)"
make -j1 installUbuntuAll
echo "building RAI (robot AI libs)"
make CXXFLAGS='-DRAI_NOCHECK -O3' -j4
echo "building problem 0,1,2"
cd problem0
make
cd ..
cd problem1
make
cd ..
cd problem2
make
cd ..
cd ..
# building VA system
cd vasystem
echo "building VA system"
./build_vasys.sh

