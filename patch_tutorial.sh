#!/bin/sh
patch -p1 < KlingonAssistant/tutorial_patch.txt
cd KlingonAssistant
ant clean
