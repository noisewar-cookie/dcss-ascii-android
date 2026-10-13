/**
 * @file
 * @brief Game recording: per-turn player-knowledge snapshots (Knowledge
 *        track) and message capture (Message track). See
 *        RecordingImplementation.md.
 *
 * Written against DCSS 0.34.1 (submodule 1eebc1a2). Re-audit on every
 * submodule bump (see RecordingImplementation.md).
 *
 * VISIBILITY RULE (do not break): everything captured here must come from the
 * player-knowledge layer only:
 *   OK:     monster_info, env.map_knowledge / map_cell, get_cell_glyph,
 *           item_def::name() / get_item_description() (player-facing),
 *           describe.h / chardump section text with full_id == false,
 *           you.* fields the player can see on their own screens.
 *   BANNED: env.grid / env.mgrid / env.mons (live monster structs), true item
 *           identity flags or props, unvisited level data, RNG state, anything
 *           read via full_id == true, anything after identify_inventory() at
 *           game end.
 */
#pragma once

#include <string>

// ---- Engine hooks (called from patched upstream files) --------------------

// main.cc _input(): the player is about to be asked for a command.
void android_rec_on_command_prompt();
// main.cc world_reacts(): end of a world turn (also fires during rest/travel/
// explore delays, so every elapsed player turn gets a snapshot).
void android_rec_on_world_reacted();
// message.cc message_store::store_msg(): one finalized message line, with
// DCSS colour tags. temporary == true for prompt lines that may be rolled back.
void android_rec_on_message(const std::string &tagged_text, int channel,
                            int turn, bool temporary);
// message.cc message_store::roll_back(): drop the last `count` temporary lines.
void android_rec_on_message_rollback(int count);
// end.cc end_game(): called BEFORE identify_inventory(), so the final snapshot
// still shows only what the player knew.
void android_rec_on_game_end(int death_type, const std::string &reason,
                             const std::string &morgue_file);

// ---- Control (called from libandroid.cc JNI entry points) -----------------

void android_rec_set_enabled(bool on);
bool android_rec_is_enabled();
// Forget everything sent so far (blob hashes, map caches). Next snapshot is a
// full keyframe. Call when Java opens/reopens a recording file.
void android_rec_reset_session();
// JSON: name, birth time, seed, versions, species, job, game type.
std::string android_rec_game_identity_json();

// ---- Bridges to Java (implemented in libandroid.cc) -----------------------
// All are no-ops when not on the game thread.

void android_rec_emit_snapshot(const std::string &payload);
void android_rec_emit_message(const std::string &tagged_text, int channel,
                              int turn, bool temporary);
void android_rec_emit_message_rollback(int count);
void android_rec_emit_game_end(int death_type, const std::string &reason,
                               const std::string &morgue_file);
