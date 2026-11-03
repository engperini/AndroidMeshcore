// PC test of the Meshtastic firmware as the app builds it (same sources and glue, SimRadio instead of USB).
// Boots the node and drives it the way the app does (mt_api.h): asks for its config, renames it, sends a
// text, sets the region. Every event the app would get is printed as "EV <json>" for check_events.py, and
// "STEP <name> <0|1>" lines report the requests.
#include "mt_api.h"
#include "mt_glue.h"

#include <chrono>
#include <cstdio>
#include <functional>
#include <string>
#include <thread>

bool mt_use_simradio()
{
    return true;
}

namespace {

// Runs the firmware loop for [ms], printing the events; stops early when [until] matches an event.
bool pump(int ms, const std::function<bool(const std::string&)>& until = nullptr)
{
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
    while (std::chrono::steady_clock::now() < deadline) {
        loop();
        uint8_t out[1024];
        size_t n;
        while ((n = mt_api_from_radio(out, sizeof(out))) > 0) {
            std::string ev = mt_event_json(out, n);
            size_t start = 0;
            while (start < ev.size()) {
                size_t end = ev.find('\n', start);
                if (end == std::string::npos) end = ev.size();
                const std::string one = ev.substr(start, end - start);
                printf("EV %s\n", one.c_str());
                if (until && until(one)) return true;
                start = end + 1;
            }
        }
    }
    return false;
}

void step(const char* name, bool ok)
{
    printf("STEP %s %d\n", name, ok ? 1 : 0);
    fflush(stdout);
}

bool has(const std::string& ev, const char* s)
{
    return ev.find(s) != std::string::npos;
}

} // namespace

int main(int argc, char** argv)
{
    std::string root = argc > 1 ? argv[1] : "/tmp/mt-hosttest";
    mt_set_data_root(root);
    mt_fs_mount();
    portduinoSetup();
    setup();
    mt_sync_clock();

    step("config_request", mt_request_config(4242));
    step("config_complete", pump(20000, [](const std::string& e) { return has(e, "\"t\":\"ready\""); }));

    step("set_owner", mt_set_owner("Host Test", "HT"));
    pump(500);
    step("config_request2", mt_request_config(4243));
    bool renamed = false;
    pump(20000, [&](const std::string& e) {
        if (has(e, "\"long\":\"Host Test\"")) renamed = true;
        return has(e, "\"nonce\":4243");
    });
    step("owner_renamed", renamed);

    step("send_text", mt_send_text(0x12345678, 0xFFFFFFFF, 0, "hello from the phone"));
    pump(1000);
    step("traceroute", mt_traceroute(0x12345679, 0x0BADF00D, 0));
    pump(500);

    step("set_lora", mt_set_lora(6 /* ANZ */, 0 /* LONG_FAST */, 3, true, 0));
    pump(3000);
    printf("REBOOT %d\n", mt_take_reboot_request() ? 1 : 0);
    step("config_request3", mt_request_config(4244));
    bool anz = false;
    pump(20000, [&](const std::string& e) {
        if (has(e, "\"t\":\"lora\"") && has(e, "\"region\":6")) anz = true;
        return has(e, "\"nonce\":4244");
    });
    step("region_set", anz);

    // The node announces itself at most once a minute; the region change above just made it do so.
    printf("INFO nodeinfo_sent %d\n", mt_send_nodeinfo() ? 1 : 0);
    step("set_position", mt_set_position(-229068000, -470629000, 650));
    pump(1000);
    step("config_request_pos", mt_request_config(4245));
    bool located = false;
    pump(20000, [&](const std::string& e) {
        if (has(e, "\"long\":\"Host Test\"") && has(e, "\"lat\":-22.9")) located = true;
        return has(e, "\"nonce\":4245");
    });
    step("own_position", located);
    return 0;
}
