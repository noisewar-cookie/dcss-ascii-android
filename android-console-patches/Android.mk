LOCAL_PATH := $(call my-dir)

#####################################################################
# build sqlite3
#####################################################################
include $(CLEAR_VARS)
SQLITE_DIR := contrib/sqlite
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(SQLITE_DIR)
LOCAL_MODULE := sqlite3
LOCAL_SRC_FILES := $(SQLITE_DIR)/sqlite3.c
include $(BUILD_STATIC_LIBRARY)

#####################################################################
# build lua 5.4
#####################################################################
include $(CLEAR_VARS)
LUA_DIR := contrib/lua/src
LOCAL_ARM_MODE  := arm
LOCAL_MODULE    := lua
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(LUA_DIR)
LOCAL_CFLAGS := -O3 -U_FORTIFY_SOURCE
LOCAL_SRC_FILES := \
	$(LUA_DIR)/lapi.c \
	$(LUA_DIR)/lauxlib.c \
	$(LUA_DIR)/lbaselib.c \
	$(LUA_DIR)/lcode.c \
	$(LUA_DIR)/lcorolib.c \
	$(LUA_DIR)/lctype.c \
	$(LUA_DIR)/ldblib.c \
	$(LUA_DIR)/ldebug.c \
	$(LUA_DIR)/ldo.c \
	$(LUA_DIR)/ldump.c \
	$(LUA_DIR)/lfunc.c \
	$(LUA_DIR)/lgc.c \
	$(LUA_DIR)/linit.c \
	$(LUA_DIR)/liolib.c \
	$(LUA_DIR)/llex.c \
	$(LUA_DIR)/lmathlib.c \
	$(LUA_DIR)/lmem.c \
	$(LUA_DIR)/loadlib.c \
	$(LUA_DIR)/lobject.c \
	$(LUA_DIR)/lopcodes.c \
	$(LUA_DIR)/loslib.c \
	$(LUA_DIR)/lparser.c \
	$(LUA_DIR)/lstate.c \
	$(LUA_DIR)/lstring.c \
	$(LUA_DIR)/lstrlib.c \
	$(LUA_DIR)/ltable.c \
	$(LUA_DIR)/ltablib.c \
	$(LUA_DIR)/ltm.c \
	$(LUA_DIR)/lundump.c \
	$(LUA_DIR)/lutf8lib.c \
	$(LUA_DIR)/lvm.c \
	$(LUA_DIR)/lzio.c

include $(BUILD_STATIC_LIBRARY)

#####################################################################
# build crawl (console mode)
#####################################################################
include $(CLEAR_VARS)
RLTILES_DIR := rltiles

LOCAL_CPP_EXTENSION := .cc
LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/rltiles \
	$(LOCAL_PATH)/rltiles/tool \
	$(LOCAL_PATH)/prebuilt \
	$(LOCAL_PATH)/$(SQLITE_DIR) \
	$(LOCAL_PATH)/$(LUA_DIR) \
	$(LOCAL_PATH)/contrib/pcre

LOCAL_STATIC_LIBRARIES := libsqlite3 liblua
LOCAL_LDLIBS := -lz -llog -landroid
LOCAL_SHORT_COMMANDS := true

LOCAL_CFLAGS += -DCLUA_BINDINGS -DWIZARD -DASSERTS -DUNIX -DCRAWL_HAVE_USLEEP -DCRAWL_HAVE_FDATASYNC -fsigned-char -frtti -std=c++17 -fexceptions

# Source list based on 0.34.1 Makefile.obj OBJECTS
# Excludes: libunix.cc (replaced by libandroid.cc)
# Excludes all TILES_OBJECTS, GLTILES_OBJECTS (no SDL2/tiles in console mode)
# Includes: libandroid.cc, main.cc, version.cc, rltiles tiledef-*.cc, prebuilt levcomp
CRAWLSRC = \
	ability.cc \
	abyss.cc \
	acquire.cc \
	act-iter.cc \
	actor-los.cc \
	actor.cc \
	adjust.cc \
	areas.cc \
	arena.cc \
	artefact.cc \
	attack.cc \
	attitude-change.cc \
	beam.cc \
	behold.cc \
	bitary.cc \
	branch.cc \
	branch-data-json.cc \
	bloodspatter.cc \
	chardump.cc \
	cio.cc \
	cloud.cc \
	clua.cc \
	cluautil.cc \
	colour.cc \
	command.cc \
	coord.cc \
	coord-circle.cc \
	coordit.cc \
	corpse.cc \
	crash.cc \
	ctest.cc \
	dactions.cc \
	database.cc \
	dbg-asrt.cc \
	dbg-maps.cc \
	dbg-objstat.cc \
	dbg-scan.cc \
	dbg-util.cc \
	death-curse.cc \
	decks.cc \
	delay.cc \
	describe.cc \
	describe-god.cc \
	describe-spells.cc \
	dgl-message.cc \
	dgn-delve.cc \
	dgn-height.cc \
	dgn-irregular-box.cc \
	dgn-layouts.cc \
	dgn-overview.cc \
	dgn-proclayouts.cc \
	dgn-shoals.cc \
	dgn-swamp.cc \
	dgn-event.cc \
	directn.cc \
	dlua.cc \
	domino.cc \
	dungeon.cc \
	end.cc \
	english.cc \
	errors.cc \
	evoke.cc \
	exclude.cc \
	exercise.cc \
	fearmonger.cc \
	feature.cc \
	fight.cc \
	files.cc \
	fineff.cc \
	format.cc \
	fprop.cc \
	game-options.cc \
	geom2d.cc \
	ghost.cc \
	god-abil.cc \
	god-blessing.cc \
	god-companions.cc \
	god-conduct.cc \
	god-item.cc \
	god-menu.cc \
	god-passive.cc \
	god-prayer.cc \
	god-wrath.cc \
	hash.cc \
	hints.cc \
	hiscores.cc \
	initfile.cc \
	invent.cc \
	item-use.cc \
	item-name.cc \
	item-prop.cc \
	items.cc \
	jobs.cc \
	json.cc \
	kills.cc \
	known-items.cc \
	l-colour.cc \
	l-crawl.cc \
	l-debug.cc \
	l-dgn.cc \
	l-dgnbld.cc \
	l-dgnevt.cc \
	l-dgngrd.cc \
	l-dgnit.cc \
	l-dgnlvl.cc \
	l-dgnmon.cc \
	l-dgntil.cc \
	l-feat.cc \
	l-file.cc \
	l-global.cc \
	l-item.cc \
	l-los.cc \
	l-mapgrd.cc \
	l-mapmrk.cc \
	l-moninf.cc \
	l-mons.cc \
	l-option.cc \
	l-spells.cc \
	l-subvault.cc \
	l-travel.cc \
	l-view.cc \
	l-wiz.cc \
	l-you.cc \
	lang-fake.cc \
	lev-pand.cc \
	libutil.cc \
	libandroid.cc \
	loading-screen.cc \
	lookup-help.cc \
	los.cc \
	los-def.cc \
	losglobal.cc \
	losparam.cc \
	luaterp.cc \
	macro.cc \
	main.cc \
	makeitem.cc \
	map-knowledge.cc \
	mapdef.cc \
	mapmark.cc \
	maps.cc \
	maybe-bool.cc \
	melee-attack.cc \
	menu.cc \
	message-stream.cc \
	message.cc \
	misc.cc \
	mon-abil.cc \
	mon-act.cc \
	mon-aura.cc \
	mon-behv.cc \
	mon-cast.cc \
	mon-clone.cc \
	mon-death.cc \
	mon-ench.cc \
	mon-explode.cc \
	mon-gear.cc \
	mon-info.cc \
	mon-movetarget.cc \
	mon-pathfind.cc \
	mon-pick.cc \
	mon-place.cc \
	mon-poly.cc \
	mon-project.cc \
	mon-speak.cc \
	mon-tentacle.cc \
	mon-transit.cc \
	mon-util.cc \
	monster.cc \
	movement.cc \
	mutation.cc \
	nearby-danger.cc \
	newgame.cc \
	ng-init.cc \
	ng-init-branches.cc \
	ng-input.cc \
	ng-restr.cc \
	ng-setup.cc \
	ng-wanderer.cc \
	notes.cc \
	orb.cc \
	ouch.cc \
	outer-menu.cc \
	output.cc \
	package.cc \
	pattern.cc \
	pcg.cc \
	perlin.cc \
	piety-info.cc \
	place-info.cc \
	place.cc \
	playable.cc \
	player-act.cc \
	player-equip.cc \
	player-notices.cc \
	player-reacts.cc \
	player-stats.cc \
	player.cc \
	potion.cc \
	precision-menu.cc \
	prompt.cc \
	quiver.cc \
	randbook.cc \
	random.cc \
	random-var.cc \
	ranged-attack.cc \
	ray.cc \
	religion.cc \
	scroller.cc \
	shopping.cc \
	shout.cc \
	show.cc \
	showsymb.cc \
	skill-menu.cc \
	skills.cc \
	sound.cc \
	species.cc \
	spl-book.cc \
	spl-cast.cc \
	spl-clouds.cc \
	spl-damage.cc \
	spl-goditem.cc \
	spl-miscast.cc \
	spl-monench.cc \
	spl-other.cc \
	spl-selfench.cc \
	spl-summoning.cc \
	spl-transloc.cc \
	spl-util.cc \
	spl-vortex.cc \
	spl-zap.cc \
	sprint.cc \
	sqldbm.cc \
	stairs.cc \
	startup.cc \
	stash.cc \
	state.cc \
	status.cc \
	stepdown.cc \
	store.cc \
	stringutil.cc \
	syscalls.cc \
	tags.cc \
	target.cc \
	target-compass.cc \
	teleport.cc \
	terrain.cc \
	throw.cc \
	timed-effects.cc \
	tilepick.cc \
	tileview.cc \
	transform.cc \
	traps.cc \
	travel.cc \
	tutorial.cc \
	ui.cc \
	uncancel.cc \
	unicode.cc \
	version.cc \
	view.cc \
	viewchar.cc \
	viewgeom.cc \
	viewmap.cc \
	wcwidth.cc \
	wiz-dgn.cc \
	wiz-dump.cc \
	wiz-fsim.cc \
	wiz-item.cc \
	wiz-mon.cc \
	wiz-you.cc \
	wizard.cc \
	worley.cc \
	xom.cc \
	zot.cc \
	$(RLTILES_DIR)/tiledef-dngn.cc \
	$(RLTILES_DIR)/tiledef-feat.cc \
	$(RLTILES_DIR)/tiledef-floor.cc \
	$(RLTILES_DIR)/tiledef-gui.cc \
	$(RLTILES_DIR)/tiledef-icons.cc \
	$(RLTILES_DIR)/tiledef-main.cc \
	$(RLTILES_DIR)/tiledef-player.cc \
	$(RLTILES_DIR)/tiledef-unrand.cc \
	$(RLTILES_DIR)/tiledef-wall.cc \
	prebuilt/levcomp.lex.cc \
	prebuilt/levcomp.tab.cc

LOCAL_MODULE := crawl
LOCAL_SRC_FILES := $(CRAWLSRC)

include $(BUILD_SHARED_LIBRARY)
