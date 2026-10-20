/**
 * @file
 * @brief Game recording — Knowledge-track snapshot builder and Message-track
 *        capture, written against DCSS 0.34.1. See android-recorder.h for the
 *        visibility rule and RecordingImplementation.md for the design.
 *
 * Snapshot payload sent to Java (NativeWrapper.onRecSnapshot(byte[])):
 *
 *   "DCS1"
 *   u32 docLen, docLen bytes UTF-8 JSON (the snapshot doc, schema below)
 *   u32 blobCount
 *   blobCount x { 16-byte hash, u8 kind, u32 len, len bytes UTF-8 }
 *
 * All integers big-endian. Only blobs not yet sent this session are included;
 * the doc references blobs by 32-char hex hash.
 *
 * Snapshot doc (schema v1). Readers ignore unknown keys, so adding keys is
 * free; changing an existing key's meaning needs a RecFormat version bump.
 * {
 *   "v":1, "seq":N, "kind":"CP"|"TE"|"END",
 *   "turn":N, "aut":N, "level":"D:3", "branch":"D", "depth":3,
 *   "player":{hp,hpmax,mp,mpmax,xl,xp,str,int,dex,ac,ev,sh,gold,god,piety},
 *   "pos":{x,y},                           // player grid position
 *   "view":{cx,cy,hx,hy,vx,vy,tx,ty,sx,sy} // crawl_view geometry for examine:
 *       screen(1-based) = grid - (cx,cy) + (hx,hy) + (1,1) + (vx,vy) - (tx,ty)
 *   "panels":{name:hash,...},            // omitted when unchanged
 *   "inv":[{s,n,q,d}],                     // slot, name, qty, desc hash
 *   "spells":[{n,d}], "abil":[{n,d}],
 *   "mons":[{x,y,n,att,dam,thr,vis,d}],
 *   "map":{level,key,w,h,cells:[...],los:{x,y,w,b}}  // delta or keyframe
 *       cell = [x,y,g,c,f,t,d,m] + optional [[itemName,hash],...], or [x,y]
 *       when knowledge is lost. g/c are the in-sight glyph/colour; d is 0
 *       until the cell is seen in LOS. los: w x w bitmap of visible cells
 *       from (x,y), row-major, MSB first, as hex.
 * }
 */

#include "AppHdr.h"

#include "android-recorder.h"

#include <chrono>
#include <cstring>
#include <sstream>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include <android/log.h>

#include "ability.h"
#include "branch.h"
#include "colour.h"
#include "coord.h"
#include "coordit.h"
#include "describe.h"
#include "dgn-overview.h"
#include "directn.h"
#include "env.h"
#include "format.h"
#include "invent.h"
#include "item-name.h"
#include "items.h"
#include "jobs.h"
#include "level-id.h"
#include "libutil.h"
#include "map-cell.h"
#include "message.h"
#include "mon-info.h"
#include "mon-util.h"
#include "mutation.h"
#include "ouch.h"
#include "output.h"
#include "player.h"
#include "prompt.h"
#include "random.h"
#include "religion.h"
#include "showsymb.h"
#include "species.h"
#include "spl-cast.h"
#include "spl-util.h"
#include "state.h"
#include "stringutil.h"
#include "unicode.h"
#include "version.h"
#include "viewgeom.h"

// Added by chardump.cc.patch: one dump section, full_id == false.
string android_chardump_section(const string &section);
// Added by output.cc.patch: the live % screen with colour tags.
string android_overview_screen();

namespace
{

// --------------------------------------------------------------------------
// Hashing. 128-bit content hash for blob dedupe (two independent 64-bit
// FNV/splitmix lanes). Good enough for dedupe within one game; swap
// for a real 128-bit hash (e.g. xxh128) if collisions ever matter.
// --------------------------------------------------------------------------

struct H128
{
    uint64_t a = 0, b = 0;
    bool operator==(const H128 &o) const { return a == o.a && b == o.b; }
};

struct H128Hasher
{
    size_t operator()(const H128 &h) const
    {
        return (size_t)(h.a ^ (h.b * 0x9E3779B97F4A7C15ULL));
    }
};

H128 hash128(const string &s, uint8_t kind)
{
    uint64_t a = 1469598103934665603ULL ^ kind;
    uint64_t b = 0x9E3779B97F4A7C15ULL ^ ((uint64_t)kind << 32);
    for (unsigned char c : s)
    {
        a ^= c;
        a *= 1099511628211ULL;
        b += c + 0x9E3779B97F4A7C15ULL;
        b ^= b >> 30;
        b *= 0xBF58476D1CE4E5B9ULL;
        b ^= b >> 27;
    }
    a ^= s.size();
    b ^= a;
    b *= 0x94D049BB133111EBULL;
    b ^= b >> 31;
    return { a, b };
}

string hex(const H128 &h)
{
    char buf[33];
    snprintf(buf, sizeof(buf), "%016llx%016llx",
             (unsigned long long)h.a, (unsigned long long)h.b);
    return buf;
}

// Cheap 64-bit fingerprint builder for cache keys.
class Fp
{
public:
    Fp &add(uint64_t v)
    {
        for (int i = 0; i < 8; ++i)
        {
            h ^= (v >> (i * 8)) & 0xff;
            h *= 1099511628211ULL;
        }
        return *this;
    }
    Fp &add(const string &s)
    {
        for (unsigned char c : s)
        {
            h ^= c;
            h *= 1099511628211ULL;
        }
        return add((uint64_t)s.size());
    }
    uint64_t get() const { return h; }
private:
    uint64_t h = 1469598103934665603ULL;
};

// --------------------------------------------------------------------------
// Blob kinds (must match Java RecFormat.BLOB_*)
// --------------------------------------------------------------------------

enum blob_kind : uint8_t
{
    BLOB_PANEL      = 1,
    BLOB_ITEM_DESC  = 2,
    BLOB_MONS_DESC  = 3,
    BLOB_FEAT_DESC  = 4,
    BLOB_FEAT_NAME  = 5,
    BLOB_SPELL_DESC = 6,
    BLOB_ABIL_DESC  = 7,
};

// Panels captured on refresh. Values are chardump section names, except the
// four "builtin" ones handled in _build_panels().
const char *const k_dump_panels[] =
{
    "skills", "spells", "religion", "notes", "inventory", "gold", "misc",
    "visits", "kills", "action_counts", "xp_by_level", "turns_by_place",
    "piety_info", "monlist", "transform", "location",
};

const int k_panel_refresh_turns = 20; // tunable
const int k_map_keyframe_every  = 100; // snapshots per level between keyframes
const size_t k_cache_cap        = 20000;

// --------------------------------------------------------------------------
// Session state
// --------------------------------------------------------------------------

struct PendingBlob
{
    H128 h;
    uint8_t kind;
    string data;
};

struct LevelCache
{
    vector<uint64_t> cell_fp;   // GXM * GYM, 0 = unknown
    unordered_map<int, pair<int, H128>> desc; // idx -> (feat, LOS description)
    int snapshots_since_key = 0;
};

struct Session
{
    bool enabled = false;
    unsigned seq = 0;
    unordered_set<H128, H128Hasher> sent;
    vector<PendingBlob> pending;

    // (object fp ^ player fp) -> blob hash
    unordered_map<uint64_t, H128> desc_cache;
    unordered_map<string, LevelCache> levels;

    uint64_t last_player_fp = 0;
    int last_panel_turn = -1000000;
    string last_panel_level;
    unordered_map<string, string> panel_hashes; // name -> hex
    bool panels_dirty = true;
    string game_key; // you.your_name + birth time; change => reset caches
};

Session g;

// --------------------------------------------------------------------------
// Blob helpers
// --------------------------------------------------------------------------

H128 put_blob(uint8_t kind, const string &text)
{
    const H128 h = hash128(text, kind);
    if (!g.sent.count(h))
    {
        g.sent.insert(h);
        g.pending.push_back({ h, kind, text });
    }
    return h;
}

// Cached description: compute `render()` only when (key) is new.
template<typename F>
H128 cached_desc(uint64_t key, uint8_t kind, F render)
{
    auto it = g.desc_cache.find(key);
    if (it != g.desc_cache.end())
        return it->second;
    if (g.desc_cache.size() > k_cache_cap)
        g.desc_cache.clear(); // blobs stay deduped via g.sent
    const H128 h = put_blob(kind, render());
    g.desc_cache[key] = h;
    return h;
}

// --------------------------------------------------------------------------
// JSON helpers (minimal, UTF-8 passthrough)
// --------------------------------------------------------------------------

void jstr(ostringstream &o, const string &s)
{
    o << '"';
    for (unsigned char c : s)
    {
        switch (c)
        {
        case '"':  o << "\\\""; break;
        case '\\': o << "\\\\"; break;
        case '\n': o << "\\n"; break;
        case '\r': o << "\\r"; break;
        case '\t': o << "\\t"; break;
        default:
            if (c < 0x20)
            {
                char buf[8];
                snprintf(buf, sizeof(buf), "\\u%04x", c);
                o << buf;
            }
            else
                o << (char)c;
        }
    }
    o << '"';
}

void jkv(ostringstream &o, const char *k, const string &v, bool comma = true)
{
    if (comma)
        o << ',';
    jstr(o, k);
    o << ':';
    jstr(o, v);
}

void jkv(ostringstream &o, const char *k, long long v, bool comma = true)
{
    if (comma)
        o << ',';
    jstr(o, k);
    o << ':' << v;
}

// --------------------------------------------------------------------------
// Fingerprints
// --------------------------------------------------------------------------

// Inputs that change player-dependent text (item damage, monster to-hit,
// spell fail rates, ...). Over-inclusive is fine; under-inclusive gives
// stale descriptions.
uint64_t player_fp()
{
    Fp f;
    f.add(you.experience_level).add(you.experience);
    for (int i = 0; i < NUM_SKILLS; ++i)
        f.add(you.skill_points[i]).add(you.skills[i]);
    for (int i = 0; i < NUM_STATS; ++i)
        f.add((uint64_t)(int64_t)you.base_stats[i]);
    f.add(you.strength(false)).add(you.intel(false)).add(you.dex(false));
    f.add(you.hp_max).add(you.max_magic_points);
    f.add(you.armour_class()).add(you.evasion());
    f.add(player_displayed_shield_class());
    f.add((uint64_t)you.religion).add(you.piety());
    f.add((uint64_t)you.form);
    for (int i = 0; i < NUM_MUTATIONS; ++i)
        if (you.mutation[i])
            f.add(i).add(you.mutation[i]);
    for (int i = 0; i < ENDOFPACK; ++i)
        if (you.inv[i].defined())
            f.add(you.inv[i].name(DESC_INVENTORY));
    for (spell_type s : you.spells)
        f.add((uint64_t)s);
    return f.get();
}

uint64_t item_fp(const item_def &item)
{
    // Player-facing name already encodes identification state, enchantment
    // as known, inscription, and equipped status (DESC_INVENTORY).
    return Fp().add(item.name(DESC_INVENTORY)).add(item.quantity)
               .add((uint64_t)item.base_type).add(item.sub_type).get();
}

uint64_t mons_fp(const monster_info &mi)
{
    Fp f;
    f.add((uint64_t)mi.type).add((uint64_t)mi.base_type).add(mi.number)
     .add((uint64_t)mi.attitude).add((uint64_t)mi.threat)
     .add((uint64_t)mi.dam).add(mi.hd).add(mi.ac).add(mi.ev).add(mi.sh)
     .add(mi.wl).add((uint64_t)mi.mresists).add(mi.mname)
     .add(mi.full_name(DESC_A)).add(mi.constrictor_name);
    for (int i = 0; i < NUM_MB_FLAGS; ++i)
        if (mi.is(static_cast<monster_info_flags>(i)))
            f.add(i);
    // Visible equipment (wielded weapon, shield, ...) changes the text.
    for (int i = 0; i <= MSLOT_LAST_VISIBLE_SLOT; ++i)
        if (mi.inv[i])
            f.add(i).add(mi.inv[i]->name(DESC_PLAIN));
    return f.get();
}

// --------------------------------------------------------------------------
// Sections
// --------------------------------------------------------------------------

void build_player(ostringstream &o)
{
    o << ",\"player\":{";
    jkv(o, "hp", you.hp, false);
    jkv(o, "hpmax", you.hp_max);
    jkv(o, "mp", you.magic_points);
    jkv(o, "mpmax", you.max_magic_points);
    jkv(o, "xl", you.experience_level);
    jkv(o, "xp", get_exp_progress());
    jkv(o, "str", you.strength(false));
    jkv(o, "int", you.intel(false));
    jkv(o, "dex", you.dex(false));
    jkv(o, "ac", you.armour_class());
    jkv(o, "ev", you.evasion());
    jkv(o, "sh", player_displayed_shield_class());
    jkv(o, "gold", you.gold);
    jkv(o, "god", god_name(you.religion));
    jkv(o, "piety", you.religion == GOD_NO_GOD ? 0 : you.piety());
    o << '}';
}

// Player position + console view geometry, so replay examine mode can map a
// grid cursor onto the recorded screen (crawl_view.grid2screen).
void build_view(ostringstream &o)
{
    const crawl_view_geometry &v = crawl_view;
    o << ",\"pos\":{";
    jkv(o, "x", you.pos().x, false);
    jkv(o, "y", you.pos().y);
    o << "},\"view\":{";
    jkv(o, "cx", v.vgrdc.x, false);
    jkv(o, "cy", v.vgrdc.y);
    jkv(o, "hx", v.viewhalfsz.x);
    jkv(o, "hy", v.viewhalfsz.y);
    jkv(o, "vx", v.viewp.x);
    jkv(o, "vy", v.viewp.y);
    jkv(o, "tx", v.termp.x);
    jkv(o, "ty", v.termp.y);
    jkv(o, "sx", v.viewsz.x);
    jkv(o, "sy", v.viewsz.y);
    o << '}';
}

void build_panels(ostringstream &o, int kind_end, const string &level,
                  uint64_t pfp)
{
    const bool refresh = g.panels_dirty || kind_end
        || pfp != g.last_player_fp
        || level != g.last_panel_level
        || you.num_turns - g.last_panel_turn >= k_panel_refresh_turns;
    if (!refresh)
        return; // Java carries forward the previous panel set

    auto panel = [](const string &name, const string &text)
    {
        g.panel_hashes[name] = hex(put_blob(BLOB_PANEL, text));
    };

    panel("overview", android_overview_screen());
    panel("dungeon", overview_description_string(true));
    panel("mutations", describe_mutations(false));
    panel("abilities_list", print_abilities());
    for (const char *sec : k_dump_panels)
        panel(sec, android_chardump_section(sec));
    // TODO: "discoveries" (\ known items). known-items.cc only exposes the
    // interactive menu; needs a small patch that returns the list as text.

    g.last_panel_turn = you.num_turns;
    g.last_panel_level = level;
    g.panels_dirty = false;

    o << ",\"panels\":{";
    bool first = true;
    for (const auto &kv : g.panel_hashes)
    {
        jkv(o, kv.first.c_str(), kv.second, !first);
        first = false;
    }
    o << '}';
}

void build_inventory(ostringstream &o, uint64_t pfp)
{
    o << ",\"inv\":[";
    bool first = true;
    // Live inventory menu order: by class (inv_order), then slot.
    for (int obj = 0; obj < NUM_OBJECT_CLASSES; ++obj)
    for (int i = 0; i < ENDOFPACK; ++i)
    {
        const item_def &item = you.inv[i];
        if (!item.defined() || item.base_type != inv_order[obj])
            continue;
        const H128 d = cached_desc(item_fp(item) ^ (pfp * 31),
                                   BLOB_ITEM_DESC, [&item]()
        {
            return item.name(DESC_INVENTORY) + "\n\n"
                   + get_item_description(item);
        });
        o << (first ? "" : ",") << '{';
        jkv(o, "s", string(1, index_to_letter(i)), false);
        jkv(o, "n", item.name(DESC_INVENTORY_EQUIP));
        jkv(o, "q", item.quantity);
        // Menu colour as InvMenu draws it (menu_colour options, else stock).
        const int col = InvEntry(item).highlight_colour();
        jkv(o, "c", colour_to_str(col == -1 ? LIGHTGREY : col));
        jkv(o, "k", string(item_class_name(item.base_type)));
        jkv(o, "d", hex(d));
        o << '}';
        first = false;
    }
    o << ']';
}

// list_spells()'s row (spl-cast.cc _spell_base_description, static there).
string spell_menu_line(spell_type spell)
{
    int hl = spell_highlight_by_utility(spell, COL_UNKNOWN, false);
    if (you.duration[DUR_ENKINDLED] && hl == COL_UNKNOWN
        && spell_can_be_enkindled(spell))
    {
        hl = LIGHTCYAN;
    }
    const string col = colour_to_str(hl);
    string row = chop_string(spell_title(spell), 32)
                 + spell_schools_string(spell);
    if (strwidth(row) < 58)
        row += string(58 - strwidth(row), ' ');
    const string fail = spell_failure_rate_string(spell, false);
    const int width =
        strwidth(formatted_string::parse_string(fail).tostring());
    const int pad = you.has_mutation(MUT_MNEMOPHAGE) ? 13 : 9;
    return "<" + col + ">" + row + fail + string(max(0, pad - width), ' ')
           + to_string(spell_difficulty(spell)) + "</" + col + ">";
}

void build_spells(ostringstream &o, uint64_t pfp)
{
    o << ",\"spells\":[";
    bool first = true;
    for (int li = 0; li < 52; ++li)
    {
        const char letter = index_to_letter(li);
        const spell_type s = get_spell_by_letter(letter);
        if (!is_valid_spell(s))
            continue;
        const H128 d = cached_desc(Fp().add((uint64_t)s).get() ^ (pfp * 37),
                                   BLOB_SPELL_DESC, [s]()
        {
            describe_info inf;
            get_spell_desc(s, inf);
            return process_description(inf);
        });
        o << (first ? "" : ",") << '{';
        jkv(o, "n", string(spell_title(s)), false);
        jkv(o, "s", string(1, letter));
        jkv(o, "l", spell_menu_line(s));
        jkv(o, "d", hex(d));
        o << '}';
        first = false;
    }
    o << ']';
}

void build_abilities(ostringstream &o, uint64_t pfp)
{
    o << ",\"abil\":[";
    bool first = true;
    for (const talent &t : your_talents(true))
    {
        const ability_type a = t.which;
        const H128 d = cached_desc(Fp().add((uint64_t)a).get() ^ (pfp * 41),
                                   BLOB_ABIL_DESC, [a]()
        {
            return get_ability_desc(a, true);
        });
        o << (first ? "" : ",") << '{';
        jkv(o, "n", ability_name(a), false);
        jkv(o, "s", string(1, (char)t.hotkey));
        jkv(o, "l", describe_talent(t));
        jkv(o, "c", colour_to_str(check_ability_possible(a, true)
                                  ? LIGHTGREY : COL_INAPPLICABLE));
        jkv(o, "i", t.is_invocation ? 1 : 0);
        jkv(o, "d", hex(d));
        o << '}';
        first = false;
    }
    o << ']';
}

void build_monsters(ostringstream &o, uint64_t pfp)
{
    o << ",\"mons\":[";
    bool first = true;
    for (rectangle_iterator ri(0); ri; ++ri)
    {
        const coord_def p = *ri;
        const map_cell &cell = env.map_knowledge(p);
        const monster_info *mi = cell.monsterinfo();
        if (!mi)
            continue;
        const H128 d = cached_desc(mons_fp(*mi) ^ (pfp * 43),
                                   BLOB_MONS_DESC, [mi]()
        {
            describe_info inf;
            bool has_stat_desc = false;
            get_monster_db_desc(*mi, inf, has_stat_desc);
            return process_description(inf);
        });
        o << (first ? "" : ",") << '{';
        jkv(o, "x", p.x, false);
        jkv(o, "y", p.y);
        jkv(o, "n", mi->full_name(DESC_A));
        jkv(o, "att", (int)mi->attitude);
        jkv(o, "dam", (int)mi->dam);
        jkv(o, "thr", (int)mi->threat);
        jkv(o, "vis", cell.visible() ? 1 : 0);
        jkv(o, "det", cell.detected_monster() ? 1 : 0);
        if (mons_is_unique(mi->type))
            jkv(o, "u", 1);
        jkv(o, "d", hex(d));
        o << '}';
        first = false;
    }
    o << ']';
}

// Items the player knows about on one cell: the full visible stack when in
// LOS, otherwise the remembered top item (+ "more" flag).
void cell_items(const coord_def &p, const map_cell &cell, uint64_t pfp,
                vector<pair<string, H128>> &out, bool &more)
{
    more = false;
    auto add = [&](const item_def &item)
    {
        // Sensed-but-unseen pseudo-items: get_item_description() dies on them.
        if (item.base_type == OBJ_DETECTED
            || item.base_type >= NUM_OBJECT_CLASSES)
        {
            const string n = "a detected item";
            out.emplace_back(n, put_blob(BLOB_ITEM_DESC, n));
            return;
        }
        const H128 d = cached_desc(item_fp(item) ^ (pfp * 47),
                                   BLOB_ITEM_DESC, [&item]()
        {
            return item.name(DESC_A) + "\n\n" + get_item_description(item);
        });
        out.emplace_back(item.name(DESC_A), d);
    };

    if (cell.visible())
    {
        const int idx = you.visible_igrd(p);
        if (idx != NON_ITEM)
            for (const item_def *item : const_item_list_on_square(idx))
                add(*item);
        return;
    }
    if (const item_def *item = cell.item())
    {
        add(*item);
        more = (cell.flags & MAP_MORE_ITEMS) != 0;
    }
}

void build_map(ostringstream &o, const string &level, uint64_t pfp)
{
    LevelCache &lc = g.levels[level];
    const bool key = lc.cell_fp.empty()
                     || lc.snapshots_since_key >= k_map_keyframe_every;
    if (lc.cell_fp.empty())
        lc.cell_fp.assign(GXM * GYM, 0);
    lc.snapshots_since_key = key ? 0 : lc.snapshots_since_key + 1;

    o << ",\"map\":{";
    jkv(o, "level", level, false);
    jkv(o, "key", key ? 1 : 0);
    jkv(o, "w", GXM);
    jkv(o, "h", GYM);
    o << ",\"cells\":[";
    bool first = true;

    for (rectangle_iterator ri(0); ri; ++ri)
    {
        const coord_def p = *ri;
        const map_cell &cell = env.map_knowledge(p);
        const int idx = p.y * GXM + p.x;

        if (!cell.known() && !cell.seen())
        {
            if (lc.cell_fp[idx] != 0)
            {
                // Knowledge lost (e.g. amnesia): emit a clear.
                o << (first ? "" : ",") << '[' << p.x << ',' << p.y << ']';
                first = false;
                lc.cell_fp[idx] = 0;
                lc.desc.erase(idx);
            }
            continue;
        }

        // Always the in-sight colours: visibility goes in "los" instead, so
        // walking doesn't re-send every cell entering or leaving view.
        const cglyph_t gl = get_cell_glyph(p, false, 1);
        const dungeon_feature_type feat = cell.feat();
        vector<pair<string, H128>> items;
        bool more = false;
        cell_items(p, cell, pfp, items, more);

        const uint32_t fl = cell.flags & (MAP_SEEN_FLAG
                                          | MAP_MAGIC_MAPPED_FLAG
                                          | MAP_DETECTED_ITEM
                                          | MAP_MORE_ITEMS);
        Fp f;
        f.add((uint64_t)gl.ch).add(gl.col).add(fl).add((uint64_t)feat);
        for (const auto &it : items)
            f.add(it.first);
        const uint64_t cfp = f.get() | 1; // never 0

        // Full x-look description, captured only in LOS (an unseen terrain
        // change can't leak) and kept while the remembered feat is the same.
        auto dit = lc.desc.find(idx);
        if (dit != lc.desc.end() && dit->second.first != (int)feat)
        {
            lc.desc.erase(dit);
            dit = lc.desc.end();
        }
        bool new_desc = false;
        if (dit == lc.desc.end() && cell.visible())
        {
            describe_info inf;
            get_feature_desc(p, inf, false);
            const H128 dh = put_blob(BLOB_FEAT_DESC, process_description(inf));
            dit = lc.desc.emplace(idx, make_pair((int)feat, dh)).first;
            new_desc = true;
        }
        if (!key && lc.cell_fp[idx] == cfp && !new_desc)
            continue;
        lc.cell_fp[idx] = cfp;

        // Short feature name for any known cell.
        const H128 tname = put_blob(BLOB_FEAT_NAME,
                                    feature_description(feat));

        o << (first ? "" : ",") << '[' << p.x << ',' << p.y << ','
          << (long long)gl.ch << ',' << (int)gl.col << ',' << fl << ',';
        jstr(o, hex(tname));
        o << ',';
        if (dit != lc.desc.end())
            jstr(o, hex(dit->second.second));
        else
            o << '0';
        o << ',' << (more ? 1 : 0);
        if (!items.empty())
        {
            o << ",[";
            for (size_t k = 0; k < items.size(); ++k)
            {
                o << (k ? "," : "") << '[';
                jstr(o, items[k].first);
                o << ',';
                jstr(o, hex(items[k].second));
                o << ']';
            }
            o << ']';
        }
        o << ']';
        first = false;
    }
    o << ']';

    const int w = 2 * LOS_RADIUS + 1;
    const coord_def c0 = you.pos() - coord_def(LOS_RADIUS, LOS_RADIUS);
    string bits;
    int acc = 0, n = 0;
    for (int dy = 0; dy < w; ++dy)
        for (int dx = 0; dx < w; ++dx)
        {
            const coord_def q = c0 + coord_def(dx, dy);
            acc = acc << 1 | (in_bounds(q) && env.map_knowledge(q).visible());
            if (++n % 4 == 0)
            {
                bits += "0123456789abcdef"[acc];
                acc = 0;
            }
        }
    if (n % 4)
        bits += "0123456789abcdef"[acc << (4 - n % 4)];
    o << ",\"los\":{";
    jkv(o, "x", c0.x, false);
    jkv(o, "y", c0.y);
    jkv(o, "w", w);
    jkv(o, "b", bits);
    o << "}}";
}

// --------------------------------------------------------------------------
// Payload
// --------------------------------------------------------------------------

void put_u32(string &s, uint32_t v)
{
    s.push_back((char)(v >> 24));
    s.push_back((char)(v >> 16));
    s.push_back((char)(v >> 8));
    s.push_back((char)v);
}

void put_u64(string &s, uint64_t v)
{
    put_u32(s, (uint32_t)(v >> 32));
    put_u32(s, (uint32_t)v);
}

string make_payload(const string &doc)
{
    string out = "DCS1";
    put_u32(out, doc.size());
    out += doc;
    put_u32(out, g.pending.size());
    for (const PendingBlob &b : g.pending)
    {
        put_u64(out, b.h.a);
        put_u64(out, b.h.b);
        out.push_back((char)b.kind);
        put_u32(out, b.data.size());
        out += b.data;
    }
    g.pending.clear();
    return out;
}

bool in_game()
{
    return crawl_state.need_save && !crawl_state.updating_scores
           && crawl_state.game_started && !crawl_state.game_is_arena();
}

// Debug perf: capture cost, logged every PERF_LOG_EVERY snapshots (tag
// "dcrec") and stored per snapshot as "pcost" (previous snapshot, us).
constexpr int PERF_LOG_EVERY = 200;
long long perf_last_us = 0;
long long perf_sum_us = 0;
long long perf_max_us = 0;
long long perf_sum_bytes = 0;
int perf_n = 0;

void note_perf(long long us, size_t bytes)
{
    perf_last_us = us;
    perf_sum_us += us;
    perf_max_us = max(perf_max_us, us);
    perf_sum_bytes += bytes;
    if (++perf_n < PERF_LOG_EVERY)
        return;
    __android_log_print(ANDROID_LOG_INFO, "dcrec",
                        "snapshot capture: n=%d avg=%lldus max=%lldus "
                        "avg_payload=%lldB",
                        perf_n, perf_sum_us / perf_n, perf_max_us,
                        perf_sum_bytes / perf_n);
    perf_n = 0;
    perf_sum_us = perf_max_us = perf_sum_bytes = 0;
}

void take_snapshot(const char *kind)
{
    const bool is_end = !strcmp(kind, "END");
    // end_game clears need_save before the END hook, so END only needs a
    // started game.
    if (!g.enabled
        || (is_end ? !crawl_state.game_started : !in_game()))
    {
        return;
    }
    // Describe/name helpers may roll dice; keep them off the gameplay RNG.
    rng::generator ui_rng(rng::UI);
    const auto t0 = chrono::steady_clock::now();

    // A different game in the same process (save & quit, load another
    // character) must never be diffed against the previous game's caches.
    const string key = you.your_name + "|" + to_string((long long)you.birth_time);
    if (key != g.game_key)
    {
        const bool enabled = g.enabled;
        g = Session();
        g.enabled = enabled;
        g.game_key = key;
    }

    const string level = level_id::current().describe(false, true);
    const uint64_t pfp = player_fp();

    ostringstream o;
    o << '{';
    jkv(o, "v", 1, false);
    jkv(o, "seq", g.seq++);
    jkv(o, "kind", string(kind));
    jkv(o, "turn", you.num_turns);
    jkv(o, "aut", you.elapsed_time);
    jkv(o, "level", level);
    jkv(o, "branch", string(branches[you.where_are_you].abbrevname));
    jkv(o, "depth", you.depth);
    jkv(o, "pcost", perf_last_us);
    build_player(o);
    build_view(o);
    build_panels(o, is_end, level, pfp);
    build_inventory(o, pfp);
    build_spells(o, pfp);
    build_abilities(o, pfp);
    build_monsters(o, pfp);
    build_map(o, level, pfp);
    o << '}';

    g.last_player_fp = pfp;
    const string payload = make_payload(o.str());
    note_perf(chrono::duration_cast<chrono::microseconds>(
                  chrono::steady_clock::now() - t0).count(),
              payload.size());
    android_rec_emit_snapshot(payload);
}

} // namespace

// ==========================================================================
// Public API
// ==========================================================================

void android_rec_on_command_prompt()
{
    take_snapshot("CP");
}

void android_rec_on_world_reacted()
{
    take_snapshot("TE");
}

void android_rec_on_message(const string &tagged_text, int channel, int turn,
                            bool temporary)
{
    if (!g.enabled)
        return;
    android_rec_emit_message(tagged_text, channel, turn, temporary);
}

void android_rec_on_message_rollback(int count)
{
    if (!g.enabled || count <= 0)
        return;
    android_rec_emit_message_rollback(count);
}

void android_rec_on_game_end(int death_type, const string &reason,
                             const string &morgue_file)
{
    if (!g.enabled)
        return;
    // Death/escape messages are drawn but no key wait follows before
    // end_game, so push them as the recording's last frame.
    flush_prev_message();
    android_rec_emit_final_frame();
    take_snapshot("END");
    android_rec_emit_game_end(death_type, reason, morgue_file);
}

void android_rec_set_enabled(bool on)
{
    g.enabled = on;
}

bool android_rec_is_enabled()
{
    return g.enabled;
}

void android_rec_reset_session()
{
    const bool enabled = g.enabled;
    g = Session();
    g.enabled = enabled;
}

string android_rec_game_identity_json()
{
    ostringstream o;
    o << '{';
    jkv(o, "name", you.your_name, false);
    jkv(o, "birth", (long long)you.birth_time);
    // 64-bit seed as a string: JSON numbers lose precision past 2^53.
    jkv(o, "seed", make_stringf("%llu", (unsigned long long)you.game_seed));
    jkv(o, "dcss_version", string(Version::Long));
    jkv(o, "species", species::name(you.species));
    jkv(o, "job", string(get_job_name(you.char_class)));
    jkv(o, "game_type", crawl_state.game_type_name());
    jkv(o, "in_game", crawl_state.need_save ? 1 : 0);
    o << '}';
    return o.str();
}
