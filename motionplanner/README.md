# Snapshot of [20-IROS-ForceBased](https://github.com/MarcToussaint/20-IROS-ForceBased) with adaptions and example problems

## Description
This directory contains the RAI bare code which includes the robot motion planning system, planning scenarios, robot models, and three selected problems to be run with our visual analytics tool.
The RAI code has been adapted to expose an http interface for accepting request to sample the optimization space.

## Quick Start

On Ubuntu 18.04:
```
make -j1 installUbuntuAll               # calls sudo apt-get install; you can always interrupt
make CXXFLAGS='-DRAI_NOCHECK -O3' -j4   # builds libs (putting it on steroids for fast sampling)
cd problem0; make; ./x.exe              # build & run the first problem
[CTRL] c                                # terminate program (is idling to handle http requests)
```
