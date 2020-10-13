# Visual Analytics System

## Description
This directory contains the code of the visual analytics system for analyzing optimization runs of the motion planner.

## Build & Run
This program is written in Java and uses Maven as a build and library management tool.

On Ubuntu 18.04:
```
sudo apt install maven          # install maven
./build_vasys.sh                # builds the va system (calls mvn install and copies jar to directory)
java -jar visnlp.jar            # starts program without connection to motion planner (standalone demo)
java -jar visnlp.jar problem0   # starts program with connection to problem0 instance of motion planner
```

## Mouse Controls
General controls (robot path evolution, optimization landscape, inequalities, equalities)
- Zooming
  - `SCROLL WHEEL` zooms in and out 
  - `SHIFT`+`LMB drag` zooms into selected area
- Panning
  - `CTRL`+`LMB drag` pans the view to the desired location

Inequalities/Equalities
- Selecting Optimization step
  - `LMB click`
  - `LMB drag` range selection for optimization steps
- Expanding constraint group
  - `RMB click`

Robot path evolution
- Selecting configuration (robot time step)
  - `LMB click`
