// Achievements (Mareshine), the platform side: the unlocks and the progress
// in <data folder>/achievements/unlocked.json, the run timers of the save files
// in runs.json next to it, and the app's banner (SDL_AndroidSendMessage,
// SunshineActivity.COMMAND_ACHIEVEMENTS).
//
// unlocked.json is the shared format (docs/ACHIEVEMENTS-FORMAT.md): other
// programs may write it too, so every write first reads it again and merges
// (union, the earliest unlock wins), and entries this port does not know are
// kept as they are.
#include "achievements.h"
#include "port_host.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <map>
#include <string>
#include <vector>

extern "C" {
void port_log(const char* fmt, ...);
const char* port_card_dir(void);
__attribute__((weak)) int SDL_AndroidSendMessage(unsigned int command, int param);
extern int port_cheat_god_mode, port_cheat_movement_speed, port_cheat_jump_height, port_cheat_infinite_lives,
    port_cheat_infinite_fludd, port_cheat_coin_multiplier;
}

namespace {

struct Def {
	const char* id;
	int target;
};
const Def kDefs[PORT_ACH_COUNT] = {
#define ACH(e, id, target) { id, target },
#include "achievements_list.h"
#undef ACH
};

// SunshineActivity.COMMAND_ACHIEVEMENTS; param = how many were unlocked
const unsigned kCommandAchievements = 0x8004;
const char* const kPort = "mareshine";

// --- a small JSON value (objects keep their order) ---------------------------

struct Json {
	enum Type { NUL, BOOL, NUM, STR, ARR, OBJ } type = NUL;
	bool b = false;
	double num = 0;
	std::string str;
	std::vector<Json> arr;
	std::vector<std::pair<std::string, Json> > obj;

	Json* find(const char* key)
	{
		for (auto& kv : obj)
			if (kv.first == key)
				return &kv.second;
		return nullptr;
	}
	Json& at(const char* key)
	{
		if (Json* j = find(key))
			return *j;
		type = OBJ;
		obj.push_back({ key, Json() });
		return obj.back().second;
	}
	static Json number(double v) { Json j; j.type = NUM; j.num = v; return j; }
	static Json boolean(bool v) { Json j; j.type = BOOL; j.b = v; return j; }
	static Json string(const std::string& v) { Json j; j.type = STR; j.str = v; return j; }
	static Json object() { Json j; j.type = OBJ; return j; }
};

struct Parser {
	const char* p;
	const char* end;
	bool ok;
	Parser(const char* b, const char* e) : p(b), end(e), ok(true) {}

	void ws() { while (p < end && (*p == ' ' || *p == '\n' || *p == '\r' || *p == '\t')) p++; }
	bool eat(char c) { ws(); if (p < end && *p == c) { p++; return true; } return false; }

	std::string text()
	{
		std::string s;
		if (!eat('"')) { ok = false; return s; }
		while (p < end && *p != '"') {
			if (*p == '\\' && p + 1 < end) {
				p++;
				switch (*p) {
				case 'n': s += '\n'; break;
				case 't': s += '\t'; break;
				case 'r': s += '\r'; break;
				case 'b': s += '\b'; break;
				case 'f': s += '\f'; break;
				case 'u':
					if (p + 4 < end) {
						unsigned c = (unsigned)strtoul(std::string(p + 1, 4).c_str(), nullptr, 16);
						if (c < 0x80) s += (char)c;
						else if (c < 0x800) { s += (char)(0xC0 | c >> 6); s += (char)(0x80 | (c & 0x3F)); }
						else { s += (char)(0xE0 | c >> 12); s += (char)(0x80 | (c >> 6 & 0x3F)); s += (char)(0x80 | (c & 0x3F)); }
						p += 4;
					}
					break;
				default: s += *p;
				}
				p++;
			} else {
				s += *p++;
			}
		}
		if (p < end) p++;
		else ok = false;
		return s;
	}

	Json value(int depth = 0)
	{
		Json j;
		ws();
		if (p >= end || depth > 32) { ok = false; return j; }
		if (*p == '{') {
			p++;
			j.type = Json::OBJ;
			if (eat('}')) return j;
			do {
				std::string k = text();
				if (!eat(':')) { ok = false; return j; }
				j.obj.push_back({ k, value(depth + 1) });
			} while (ok && eat(','));
			if (!eat('}')) ok = false;
		} else if (*p == '[') {
			p++;
			j.type = Json::ARR;
			if (eat(']')) return j;
			do j.arr.push_back(value(depth + 1)); while (ok && eat(','));
			if (!eat(']')) ok = false;
		} else if (*p == '"') {
			j.type = Json::STR;
			j.str = text();
		} else if (!strncmp(p, "true", 4)) { j.type = Json::BOOL; j.b = true; p += 4; }
		else if (!strncmp(p, "false", 5)) { j.type = Json::BOOL; p += 5; }
		else if (!strncmp(p, "null", 4)) { p += 4; }
		else {
			char* e = nullptr;
			j.type = Json::NUM;
			j.num = strtod(p, &e);
			if (e == p) ok = false;
			p = e;
		}
		return j;
	}
};

void quote(std::string& out, const std::string& s)
{
	out += '"';
	for (unsigned char c : s) {
		if (c == '"' || c == '\\') { out += '\\'; out += (char)c; }
		else if (c == '\n') out += "\\n";
		else if (c < 0x20) { char u[8]; snprintf(u, sizeof u, "\\u%04x", c); out += u; }
		else out += (char)c;
	}
	out += '"';
}

void write(std::string& out, const Json& j, int indent)
{
	std::string pad(indent + 2, ' ');
	switch (j.type) {
	case Json::NUL: out += "null"; break;
	case Json::BOOL: out += j.b ? "true" : "false"; break;
	case Json::NUM: {
		char n[32];
		if (j.num == (double)(long long)j.num) snprintf(n, sizeof n, "%lld", (long long)j.num);
		else snprintf(n, sizeof n, "%.6g", j.num);
		out += n;
		break;
	}
	case Json::STR: quote(out, j.str); break;
	case Json::ARR:
		out += '[';
		for (size_t i = 0; i < j.arr.size(); i++) {
			out += i ? ", " : "";
			write(out, j.arr[i], indent + 2);
		}
		out += ']';
		break;
	case Json::OBJ:
		if (j.obj.empty()) { out += "{}"; break; }
		out += "{\n";
		for (size_t i = 0; i < j.obj.size(); i++) {
			out += pad;
			quote(out, j.obj[i].first);
			out += ": ";
			write(out, j.obj[i].second, indent + 2);
			out += i + 1 < j.obj.size() ? ",\n" : "\n";
		}
		out += std::string(indent, ' ') + "}";
		break;
	}
}

bool read_file(const std::string& path, Json& out)
{
	FILE* f = fopen(path.c_str(), "rb");
	if (!f)
		return false;
	std::string s;
	char buf[4096];
	size_t n;
	while ((n = fread(buf, 1, sizeof buf, f)) > 0)
		s.append(buf, n);
	fclose(f);
	Parser ps(s.data(), s.data() + s.size());
	Json j = ps.value();
	if (!ps.ok || j.type != Json::OBJ)
		return false;
	out = j;
	return true;
}

bool write_file(const std::string& path, const Json& j)
{
	std::string s;
	write(s, j, 0);
	s += '\n';
	std::string tmp = path + ".tmp";
	FILE* f = fopen(tmp.c_str(), "wb");
	if (!f)
		return false;
	bool ok = fwrite(s.data(), 1, s.size(), f) == s.size();
	ok = fclose(f) == 0 && ok;
	return ok && rename(tmp.c_str(), path.c_str()) == 0;
}

// --- state ----------------------------------------------------------------------

struct Unlock {
	bool on = false;
	long long at = 0;
	bool cheats = false, states = false;
};

struct Run {
	unsigned long long ms = 0;
	bool clean = false; // a timed run: started at no shines, no save state, no cheat
	bool known = false; // the timer has seen this file
	int shines = 0;
};

bool s_enabled = false;
bool s_inited = false;
std::string s_dir, s_version = "unknown";
Unlock s_unlock[PORT_ACH_COUNT];
int s_progress[PORT_ACH_COUNT];
int s_new = 0;            // unlocked since the last flush
std::vector<int> s_fresh; // unlocked since the last port_ach_take_new
bool s_dirty = false;     // unlocked.json needs writing
bool s_runs_dirty = false;
bool s_state_loaded = false; // in this session
int s_active = -1;           // the save file being played
time_t s_runs_saved = 0;
Run s_runs[PORT_ACH_SLOTS];
unsigned long long s_total_ms = 0;

std::string unlocked_path() { return s_dir + "/unlocked.json"; }
std::string runs_path() { return s_dir + "/runs.json"; }

int index_of(const std::string& id)
{
	for (int i = 0; i < PORT_ACH_COUNT; i++)
		if (id == kDefs[i].id)
			return i;
	return -1;
}

// unlocked.json's entries into ours: union, the earliest unlock wins
void merge_from(Json& file)
{
	if (Json* u = file.find("unlocked"))
		for (auto& kv : u->obj) {
			int i = index_of(kv.first);
			if (i < 0 || kv.second.type != Json::OBJ)
				continue;
			Json* at = kv.second.find("at");
			long long t = at && at->type == Json::NUM ? (long long)at->num : 0;
			if (!s_unlock[i].on || (t > 0 && t < s_unlock[i].at)) {
				s_unlock[i].on = true;
				s_unlock[i].at = t;
				Json* c = kv.second.find("cheats");
				Json* st = kv.second.find("save_states");
				s_unlock[i].cheats = c && c->type == Json::BOOL && c->b;
				s_unlock[i].states = st && st->type == Json::BOOL && st->b;
			}
			if (kDefs[i].target > 0)
				s_progress[i] = kDefs[i].target;
		}
	if (Json* pr = file.find("progress"))
		for (auto& kv : pr->obj) {
			int i = index_of(kv.first);
			if (i >= 0 && kv.second.type == Json::NUM && (int)kv.second.num > s_progress[i])
				s_progress[i] = (int)kv.second.num;
		}
}

void save_unlocked()
{
	Json file;
	if (!read_file(unlocked_path(), file))
		file = Json::object();
	merge_from(file);
	file.at("format") = Json::string("achievements");
	file.at("schema") = Json::number(1);
	file.at("game") = Json::string("sms");
	// (at() may add to file.obj: take each reference only while it is used)
	if (file.at("unlocked").type != Json::OBJ) file.at("unlocked") = Json::object();
	if (file.at("progress").type != Json::OBJ) file.at("progress") = Json::object();
	for (int i = 0; i < PORT_ACH_COUNT; i++) {
		Json& u = file.at("unlocked");
		if (s_unlock[i].on) {
			Json* e = u.find(kDefs[i].id);
			Json* at = e ? e->find("at") : nullptr;
			if (!e || !at || at->type != Json::NUM || (long long)at->num != s_unlock[i].at) {
				Json entry = Json::object();
				entry.at("at") = Json::number((double)s_unlock[i].at);
				entry.at("port") = Json::string(kPort);
				entry.at("version") = Json::string(s_version);
				entry.at("cheats") = Json::boolean(s_unlock[i].cheats);
				entry.at("save_states") = Json::boolean(s_unlock[i].states);
				u.at(kDefs[i].id) = entry;
			}
		}
		if (kDefs[i].target > 0 && s_progress[i] > 0)
			file.at("progress").at(kDefs[i].id) = Json::number(s_progress[i] < kDefs[i].target ? s_progress[i] : kDefs[i].target);
	}
	if (!write_file(unlocked_path(), file))
		port_log("[achievements] cannot write %s\n", unlocked_path().c_str());
}

void load_runs()
{
	Json f;
	if (!read_file(runs_path(), f))
		return;
	Json* t = f.find("total_ms");
	if (t && t->type == Json::NUM)
		s_total_ms = (unsigned long long)t->num;
	Json* a = f.find("files");
	if (a && a->type == Json::ARR)
		for (size_t i = 0; i < a->arr.size() && i < PORT_ACH_SLOTS; i++) {
			Json& r = a->arr[i];
			if (r.type != Json::OBJ)
				continue;
			Json* ms = r.find("ms");
			Json* clean = r.find("clean");
			Json* sh = r.find("shines");
			s_runs[i].known = true;
			s_runs[i].ms = ms && ms->type == Json::NUM ? (unsigned long long)ms->num : 0;
			s_runs[i].clean = clean && clean->type == Json::BOOL && clean->b;
			s_runs[i].shines = sh && sh->type == Json::NUM ? (int)sh->num : 0;
		}
}

void save_runs()
{
	Json f = Json::object();
	f.at("schema") = Json::number(1);
	f.at("total_ms") = Json::number((double)s_total_ms);
	Json& a = f.at("files");
	a.type = Json::ARR;
	for (int i = 0; i < PORT_ACH_SLOTS; i++) {
		Json r = Json::object();
		if (s_runs[i].known) {
			r.at("ms") = Json::number((double)s_runs[i].ms);
			r.at("clean") = Json::boolean(s_runs[i].clean);
			r.at("shines") = Json::number(s_runs[i].shines);
		}
		a.arr.push_back(r);
	}
	write_file(runs_path(), f);
}

} // namespace

extern "C" void port_ach_init(void)
{
	if (s_inited)
		return;
	s_inited = true;
	const char* e = getenv("SMS_ACHIEVEMENTS");
	s_enabled = !e || strcmp(e, "0") != 0;
	if (!s_enabled) {
		port_log("[achievements] off\n");
		return;
	}
	if (const char* info = getenv("SMS_DEVICE_INFO")) { // "app 1.4.0, ..."
		if (!strncmp(info, "app ", 4)) {
			const char* v = info + 4;
			s_version = std::string(v, strcspn(v, ", "));
		}
	}
	std::string card = port_card_dir() ? port_card_dir() : ".";
	while (!card.empty() && (card.back() == '/' || card.back() == '\\'))
		card.pop_back();
	size_t slash = card.find_last_of("/\\");
	s_dir = (slash == std::string::npos ? std::string(".") : card.substr(0, slash)) + "/achievements";
	for (size_t i = 1; i <= s_dir.size(); i++)
		if (i == s_dir.size() || s_dir[i] == '/')
			port_mkdir(s_dir.substr(0, i).c_str(), 0755);
	Json file;
	if (read_file(unlocked_path(), file))
		merge_from(file);
	load_runs();
	int n = 0;
	for (int i = 0; i < PORT_ACH_COUNT; i++)
		n += s_unlock[i].on;
	port_log("[achievements] %d of %d unlocked (%s)\n", n, (int)PORT_ACH_COUNT, s_dir.c_str());
}

extern "C" int port_ach_enabled(void) { return s_enabled; }
extern "C" const char* port_ach_id(int a) { return a >= 0 && a < PORT_ACH_COUNT ? kDefs[a].id : ""; }
extern "C" int port_ach_target(int a) { return a >= 0 && a < PORT_ACH_COUNT ? kDefs[a].target : 0; }
extern "C" int port_ach_is_unlocked(int a) { return a >= 0 && a < PORT_ACH_COUNT && s_unlock[a].on; }

extern "C" int port_ach_cheats_active(void)
{
	return port_cheat_god_mode || port_cheat_infinite_lives || port_cheat_infinite_fludd
	    || port_cheat_movement_speed != 1 || port_cheat_jump_height != 1 || port_cheat_coin_multiplier != 1;
}

extern "C" void port_ach_unlock(int a)
{
	if (!s_enabled || a < 0 || a >= PORT_ACH_COUNT || s_unlock[a].on)
		return;
	s_unlock[a].on = true;
	s_unlock[a].at = (long long)time(nullptr);
	s_unlock[a].cheats = port_ach_cheats_active();
	s_unlock[a].states = s_state_loaded;
	if (kDefs[a].target > 0)
		s_progress[a] = kDefs[a].target;
	s_new++;
	s_fresh.push_back(a);
	s_dirty = true;
	port_log("[achievements] unlocked %s\n", kDefs[a].id);
}

extern "C" void port_ach_unlock_id(const char* id)
{
	if (id)
		port_ach_unlock(index_of(id));
}

extern "C" void port_ach_progress(int a, int value)
{
	if (!s_enabled || a < 0 || a >= PORT_ACH_COUNT)
		return;
	if (kDefs[a].target > 0 && value > s_progress[a]) {
		s_progress[a] = value < kDefs[a].target ? value : kDefs[a].target;
		s_dirty = true;
	}
	if (kDefs[a].target > 0 && value >= kDefs[a].target)
		port_ach_unlock(a);
}

extern "C" void port_ach_flush(void)
{
	if (!s_enabled)
		return;
	if (s_dirty) {
		save_unlocked();
		s_dirty = false;
	}
	// the timers every 10 seconds (and with an unlock), not every call
	if (s_runs_dirty && (s_new || time(nullptr) - s_runs_saved >= 10)) {
		save_runs();
		s_runs_dirty = false;
		s_runs_saved = time(nullptr);
	}
	if (s_new) {
		if (SDL_AndroidSendMessage)
			SDL_AndroidSendMessage(kCommandAchievements, s_new);
		s_new = 0;
	}
}

extern "C" int port_ach_take_new(int* list, int max)
{
	int n = 0;
	for (size_t i = 0; i < s_fresh.size() && n < max; i++)
		list[n++] = s_fresh[i];
	s_fresh.clear();
	return n;
}

extern "C" void port_ach_note_state_loaded(void)
{
	s_state_loaded = true;
	if (s_active >= 0)
		s_runs[s_active].clean = false;
	s_runs_dirty = true;
}

extern "C" void port_ach_run_update(int slot, int shines, unsigned ms)
{
	if (!s_enabled || slot < 0 || slot >= PORT_ACH_SLOTS)
		return;
	Run& r = s_runs[slot];
	if (!r.known || (shines == 0 && r.shines > 0)) {
		// a file the timer has not seen, or a new game in it
		r = Run();
		r.known = true;
		r.clean = shines == 0;
	}
	s_active = slot;
	r.shines = shines;
	r.ms += ms;
	if (port_ach_cheats_active())
		r.clean = false;
	s_total_ms += ms;
	s_runs_dirty = true;
	port_ach_progress(PORT_ACH_FUN_PLAYTIME, (int)(s_total_ms / 60000));
}

extern "C" unsigned long long port_ach_run_ms(int slot)
{
	return slot >= 0 && slot < PORT_ACH_SLOTS ? s_runs[slot].ms : 0;
}

extern "C" int port_ach_run_clean(int slot)
{
	return slot >= 0 && slot < PORT_ACH_SLOTS && s_runs[slot].clean;
}
