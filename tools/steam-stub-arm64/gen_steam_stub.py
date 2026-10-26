#!/usr/bin/env python3
"""Generate app/src/main/cpp/steam_api_stub.c: an ARM64 libsteam_api.so for native ARM64 Mono.

Valheim quits unless SteamAPI_Init() succeeds and Steamworks.NET's CSteamAPIContext.Init() gets a
pipe, a user and non-NULL interface pointers. Under emulated Mono the Goldberg emulator provides that;
under native ARM64 Mono the game's P/Invokes land in an ARM64 library next to the runtime instead, and
this stub plays Steam in offline single-player mode: every call "works", every query answers a fixed,
plausible value, everything else returns zero.

Input: steamworks_net_imports.tsv (one row per DllImport of Valheim's Steamworks.NET, with the ABI of
its return value). Every entry point gets a typed export so float and struct returns use the right
registers. Answers that differ from zero live in OVERRIDES below; the few entry points that need real
arguments are written by hand in HANDWRITTEN.

Crossplay (the native engine's Crossplay setting): the launcher signs in to Steam itself (JavaSteam)
and hands the game a real session ticket, SteamID and name through the environment:
VALDROID_STEAM_TICKET (a file holding the ticket; read once, then deleted), VALDROID_STEAM_ID and
VALDROID_STEAM_PERSONA. GetAuthSessionTicket then answers with that ticket and queues the
GetAuthSessionTicketResponse_t callback Valheim waits for before it calls PlayFab's LoginWithSteam.
Without these variables everything stays offline as before.

Usage: python gen_steam_stub.py [imports.tsv] [output.c]
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))

STEAM_ID = "76561197960287930ULL"   # a valid individual SteamID (universe 1, type 1)
APP_ID = "892970U"                  # Valheim

# entry point -> C expression returned instead of zero
OVERRIDES = {
    "SteamAPI_Init": "1",
    "SteamAPI_IsSteamRunning": "1",
    "SteamAPI_RestartAppIfNecessary": "0",
    "SteamAPI_GetHSteamPipe": "1",
    "SteamAPI_GetHSteamUser": "1",
    "SteamAPI_ISteamUser_GetHSteamUser": "1",
    "SteamInternal_ContextInit": "(uint64_t)(uintptr_t)vd_interface",
    "SteamInternal_CreateInterface": "(uint64_t)(uintptr_t)vd_interface",
    "SteamInternal_FindOrCreateUserInterface": "(uint64_t)(uintptr_t)vd_interface",
    "SteamAPI_ISteamUser_GetSteamID": "vd_steam_id()",
    "SteamAPI_ISteamUser_BLoggedOn": "0",          # Steam in offline mode, like the Goldberg setup
    "SteamAPI_ISteamFriends_GetPersonaName": "vd_persona_name()",
    "SteamAPI_ISteamFriends_GetFriendPersonaName": "(uint64_t)(uintptr_t)vd_persona",
    "SteamAPI_ISteamApps_GetCurrentGameLanguage": "(uint64_t)(uintptr_t)vd_language",
    "SteamAPI_ISteamApps_GetAvailableGameLanguages": "(uint64_t)(uintptr_t)vd_language",
    "SteamAPI_ISteamApps_BIsSubscribed": "1",
    "SteamAPI_ISteamApps_BIsSubscribedApp": "1",
    "SteamAPI_ISteamApps_GetAppOwner": "vd_steam_id()",
    "SteamAPI_ISteamUtils_GetAppID": APP_ID,
    "SteamAPI_ISteamUtils_GetIPCountry": "(uint64_t)(uintptr_t)vd_country",
    "SteamAPI_ISteamUserStats_RequestCurrentStats": "1",
    "SteamAPI_ISteamNetworkingSockets_InitAuthentication": "100",   # k_ESteamNetworkingAvailability_Current
    # Valheim picks its Steam Deck platform config (lighter defaults, controller UI) from this, not
    # from the SteamDeck environment variable. On by default; SteamDeck=0 in the environment turns it off.
    "SteamAPI_ISteamUtils_IsSteamRunningOnSteamDeck": "vd_steam_deck()",
    # The save system opens a write batch around every character/world save. A false return reads as
    # "a batch is already started": 1.0.15 only warns, but older builds (1.0.7) fail the mount and the
    # character-creation "Done" button does nothing. Cloud stays disabled, so saves still go local.
    "SteamAPI_ISteamRemoteStorage_BeginFileWriteBatch": "1",
    "SteamAPI_ISteamRemoteStorage_EndFileWriteBatch": "1",
}

# Crossplay helpers: the launcher's SteamID and name when it signed in, the fixed offline ones otherwise.
HELPERS = """\
static uint64_t vd_steam_id(void)
{
    const char* v = getenv("VALDROID_STEAM_ID");
    uint64_t id = (v && v[0]) ? strtoull(v, NULL, 10) : 0;
    return id ? id : %s;
}

static uint64_t vd_persona_name(void)
{
    const char* v = getenv("VALDROID_STEAM_PERSONA");
    return (uint64_t)(uintptr_t)((v && v[0]) ? v : vd_persona);
}
""" % STEAM_ID

# Entry points with real arguments, written out in full (their tsv rows only check they exist).
HANDWRITTEN = {
    "SteamAPI_ISteamUser_GetAuthSessionTicket": """\
/* The launcher's ticket (VALDROID_STEAM_TICKET names the file; deleted once read, kept in memory for
 * the game's later logins). Each call also queues GetAuthSessionTicketResponse_t, which Valheim waits
 * for before it sends the ticket to PlayFab. 0 (k_HAuthTicketInvalid) without a ticket, as offline. */
VD_EXPORT uint32_t SteamAPI_ISteamUser_GetAuthSessionTicket(void* self, uint8_t* ticket, int32_t max,
                                                            uint32_t* size, const void* identity)
{
    (void)self; (void)identity;
    if (size) *size = 0;
    if (!vd_ticket_len) {
        const char* path = getenv("VALDROID_STEAM_TICKET");
        FILE* f = (path && path[0]) ? fopen(path, "rb") : NULL;
        if (f) {
            vd_ticket_len = (uint32_t)fread(vd_ticket, 1, sizeof(vd_ticket), f);
            fclose(f);
            unlink(path);
        }
    }
    if (!vd_ticket_len || !ticket || max < (int32_t)vd_ticket_len) return 0;
    memcpy(ticket, vd_ticket, vd_ticket_len);
    if (size) *size = vd_ticket_len;
    vd_ticket_response.handle = ++vd_ticket_handle;
    vd_ticket_response.result = 1;   /* k_EResultOK */
    vd_callback_queued = 1;
    return vd_ticket_handle;
}""",
    "SteamAPI_ManualDispatch_GetNextCallback": """\
/* Steamworks.NET's CallbackDispatcher.RunFrame: Get, dispatch, FreeLastCallback, Get again. */
VD_EXPORT uint8_t SteamAPI_ManualDispatch_GetNextCallback(int32_t pipe, vd_CallbackMsg_t* msg)
{
    (void)pipe;
    if (!vd_callback_queued || !msg) return 0;
    msg->user = 1;
    msg->callback = 163;   /* GetAuthSessionTicketResponse_t::k_iCallback */
    msg->param = &vd_ticket_response;
    msg->size = (int32_t)sizeof(vd_ticket_response);
    return 1;
}""",
    "SteamAPI_ManualDispatch_FreeLastCallback": """\
VD_EXPORT void SteamAPI_ManualDispatch_FreeLastCallback(int32_t pipe)
{
    (void)pipe;
    vd_callback_queued = 0;
}""",
}

# State behind HANDWRITTEN. Layouts as Steamworks.NET marshals them (Pack = 4 off Windows).
HANDWRITTEN_STATE = """\
typedef struct __attribute__((packed, aligned(4))) { int32_t user; int32_t callback; void* param; int32_t size; } vd_CallbackMsg_t;
typedef struct { uint32_t handle; int32_t result; } vd_TicketResponse_t;

static uint8_t vd_ticket[1024];
static uint32_t vd_ticket_len;
static uint32_t vd_ticket_handle;
static vd_TicketResponse_t vd_ticket_response;
static int vd_callback_queued;
"""

# every ISteamClient_GetISteamXxx accessor returns the dummy interface
INTERFACE_PREFIX = "SteamAPI_ISteamClient_GetISteam"

STRUCTS = {
    "SteamIPAddress_t": "struct __attribute__((packed)) { uint8_t ipv6[16]; int32_t type; }",
    "InputDigitalActionData_t": "struct __attribute__((packed)) { uint8_t state; uint8_t active; }",
    "InputAnalogActionData_t": "struct __attribute__((packed)) { int32_t mode; float x; float y; uint8_t active; }",
    "InputMotionData_t": "struct { float rot_quat[4]; float pos_accel[3]; float rot_vel[3]; }",
}


def main():
    tsv = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "steamworks_net_imports.tsv")
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "app", "src", "main", "cpp", "steam_api_stub.c")
    rows = []
    with open(tsv, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            lib, name, managed, abi = line.rstrip("\n").split("\t")
            if lib == "steam_api":
                rows.append((name, managed, abi))

    lines = [
        "/* Generated by tools/steam-stub-arm64/gen_steam_stub.py from steamworks_net_imports.tsv. Do not edit. */",
        "/* ARM64 libsteam_api.so for native ARM64 Mono: Steam in offline single-player mode, plus the launcher's ticket for crossplay (see the generator). */",
        "#include <stdint.h>",
        "#include <stdio.h>",
        "#include <stdlib.h>",
        "#include <string.h>",
        "#include <unistd.h>",
        "",
        "#define VD_EXPORT __attribute__((visibility(\"default\")))",
        "",
        "/* Steam Deck mode unless the launcher environment says SteamDeck=0. */",
        "static uint64_t vd_steam_deck(void)",
        "{",
        "    const char* v = getenv(\"SteamDeck\");",
        "    return (v && v[0] == '0') ? 0 : 1;",
        "}",
        "",
        "/* Interface objects are only ever passed back to this library, so one zeroed block serves them all. */",
        "static uint64_t vd_interface_storage[64];",
        "static void* const vd_interface = vd_interface_storage;",
        "static const char vd_persona[] = \"Viking\";",
        "static const char vd_language[] = \"english\";",
        "static const char vd_country[] = \"US\";",
        "",
    ]
    for tag, decl in STRUCTS.items():
        lines.append(f"typedef {decl} vd_{tag};")
    lines.append("")
    lines.append(HELPERS)
    lines.append(HANDWRITTEN_STATE)

    unused = set(OVERRIDES) | set(HANDWRITTEN)
    for name, managed, abi in rows:
        if name in HANDWRITTEN:
            unused.discard(name)
            lines.append(HANDWRITTEN[name])
            continue
        value = OVERRIDES.get(name)
        if value is None and name.startswith(INTERFACE_PREFIX):
            value = "(uint64_t)(uintptr_t)vd_interface"
        unused.discard(name)
        if abi == "void":
            lines.append(f"VD_EXPORT void {name}(void) {{}}")
        elif abi == "f32":
            lines.append(f"VD_EXPORT float {name}(void) {{ return 0.0f; }}")
        elif abi == "f64":
            lines.append(f"VD_EXPORT double {name}(void) {{ return 0.0; }}")
        elif abi.startswith("struct:"):
            tag = abi.split(":", 1)[1]
            lines.append(f"VD_EXPORT vd_{tag} {name}(void) {{ vd_{tag} r = {{0}}; return r; }}")
        else:
            lines.append(f"VD_EXPORT uint64_t {name}(void) {{ return {value or '0'}; }}")
    if unused:
        sys.exit(f"overrides for unknown entry points: {sorted(unused)}")

    with open(out, "w", newline="\n", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"STEAM_STUB entry_points={len(rows)} overrides={len(OVERRIDES)} output={out}")


if __name__ == "__main__":
    main()
