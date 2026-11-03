// PC test of the Meshtastic firmware as the app builds it (same sources and glue, SimRadio instead of USB):
// boots the node, asks for its config the way a client does (ToRadio.want_config_id) and checks the
// answers (MyNodeInfo, our own NodeInfo, channels, config, then config_complete_id).
#include "mt_glue.h"

#include "mesh/generated/meshtastic/mesh.pb.h"
#include <pb_decode.h>
#include <pb_encode.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>

bool mt_use_simradio()
{
    return true;
}

int main(int argc, char** argv)
{
    std::string root = argc > 1 ? argv[1] : "/tmp/mt-hosttest";
    mt_set_data_root(root);
    mt_fs_mount();
    portduinoSetup();
    setup();

    // ToRadio { want_config_id = 4242 }
    meshtastic_ToRadio to = meshtastic_ToRadio_init_zero;
    to.which_payload_variant = meshtastic_ToRadio_want_config_id_tag;
    to.want_config_id = 4242;
    uint8_t buf[meshtastic_ToRadio_size];
    pb_ostream_t os = pb_ostream_from_buffer(buf, sizeof(buf));
    if (!pb_encode(&os, meshtastic_ToRadio_fields, &to)) return 2;

    int my_info = 0, node_info = 0, channels = 0, configs = 0, complete = 0;
    bool sent = false;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(20);
    while (std::chrono::steady_clock::now() < deadline && !complete) {
        loop();
        if (!sent) sent = mt_api_to_radio(buf, os.bytes_written);
        uint8_t out[1024];
        size_t n;
        while ((n = mt_api_from_radio(out, sizeof(out))) > 0) {
            meshtastic_FromRadio fr = meshtastic_FromRadio_init_zero;
            pb_istream_t is = pb_istream_from_buffer(out, n);
            if (!pb_decode(&is, meshtastic_FromRadio_fields, &fr)) continue;
            switch (fr.which_payload_variant) {
                case meshtastic_FromRadio_my_info_tag:
                    my_info++;
                    printf("my_info: node 0x%08x\n", fr.my_info.my_node_num);
                    break;
                case meshtastic_FromRadio_node_info_tag: node_info++; break;
                case meshtastic_FromRadio_channel_tag: channels++; break;
                case meshtastic_FromRadio_config_tag: configs++; break;
                case meshtastic_FromRadio_config_complete_id_tag:
                    complete = fr.config_complete_id == 4242;
                    break;
                default: break;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    printf("my_info %d, node_info %d, channels %d, config %d, complete %d\n", my_info, node_info, channels, configs, complete);
    return (my_info && node_info && channels && configs && complete) ? 0 : 1;
}
