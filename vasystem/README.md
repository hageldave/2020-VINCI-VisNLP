# Visual Analytics System

## Description
This directory contains the code of the visual analytics system for analyzing optimization runs of the motion planner.

## Build & Run
This program is written in java and uses Maven as a build and library management tool.

On Ubuntu 18.04:
```
sudo apt install maven          # install maven
./build_vasys.sh                # builds the va system (calls mvn install and copies jar to directory)
java -jar visnlp.jar            # starts program without connection to motion planner
java -jar visnlp.jar problem0   # starts program with connection to problem0 instance of motion planner
```
