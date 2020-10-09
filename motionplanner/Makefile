BASE = rai

DEPEND = Core Algo Geo Kin Gui Optim KOMO

test_paths = $(shell find demo -maxdepth 3 -name 'Makefile' -printf "%h ")

build: $(DEPEND:%=inPath_makeLib/%) $(test_paths:%=inPath_make/%)

installUbuntuAll: $(DEPEND:%=inPath_installUbuntu/%)

printUbuntuAll: $(DEPEND:%=inPath_printUbuntu/%) printUbuntu

clean: $(DEPEND:%=inPath_clean/%)

include $(BASE)/build/generic.mk

.NOTPARALLEL:
