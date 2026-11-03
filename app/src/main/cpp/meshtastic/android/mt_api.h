// The app's view of the Meshtastic node. The node speaks the Meshtastic client protocol (ToRadio /
// FromRadio protobufs, what the official apps send over BLE/serial); this turns its FromRadio stream into
// small JSON events for the app and the app's requests into ToRadio messages. Everything runs on the
// radio thread, between firmware loop() calls.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// JSON event for one FromRadio message; empty when the app has no use for it.
std::string mt_event_json(const uint8_t* buf, size_t len);

// The node's current view of one node (NodeDB), as a "node" event; empty if unknown.
std::string mt_node_json(uint32_t num);

// Requests. Each returns false if the node did not accept it.
bool mt_request_config(uint32_t nonce);
bool mt_send_text(uint32_t id, uint32_t to, int channel, const std::string& text);
bool mt_traceroute(uint32_t id, uint32_t dest, int channel);
bool mt_set_owner(const std::string& long_name, const std::string& short_name);
bool mt_set_lora(int region, int preset, int hop_limit, bool tx_enabled, int tx_power);
bool mt_set_channel(int index, int role, const std::string& name, const std::vector<uint8_t>& psk);
bool mt_remove_node(uint32_t num);
// Announces this node (NodeInfo) to the mesh, asking the nodes that hear it for theirs.
bool mt_send_nodeinfo();
bool mt_set_position(int32_t lat_i, int32_t lon_i, int32_t alt);
