// See mt_api.h.

#include "mt_api.h"
#include "mt_glue.h"

#include "MeshService.h"
#include "NodeDB.h"
#include "RadioLibInterface.h"
#include "Router.h"
#include "TypeConversions.h"
#include "modules/NodeInfoModule.h"
#include "mesh/generated/meshtastic/admin.pb.h"
#include "mesh/generated/meshtastic/mesh.pb.h"
#include "mesh/generated/meshtastic/portnums.pb.h"
#include "mesh/generated/meshtastic/telemetry.pb.h"
#include <pb_decode.h>
#include <pb_encode.h>

#include <cstdio>
#include <cstring>

namespace {

// ---- a minimal JSON object writer
class Json
{
  public:
    Json(const char* type)
    {
        s_ = "{";
        str("t", type);
    }
    Json& num(const char* k, double v)
    {
        char b[40];
        snprintf(b, sizeof(b), "%.10g", v);
        key(k);
        s_ += b;
        return *this;
    }
    Json& u32(const char* k, uint32_t v)
    {
        key(k);
        s_ += std::to_string(v);
        return *this;
    }
    Json& i64(const char* k, long long v)
    {
        key(k);
        s_ += std::to_string(v);
        return *this;
    }
    Json& boolean(const char* k, bool v)
    {
        key(k);
        s_ += v ? "true" : "false";
        return *this;
    }
    Json& str(const char* k, const char* v, size_t max = SIZE_MAX)
    {
        key(k);
        quote(v, strnlen(v, max));
        return *this;
    }
    Json& str(const char* k, const std::string& v)
    {
        key(k);
        quote(v.data(), v.size());
        return *this;
    }
    Json& hex(const char* k, const uint8_t* b, size_t n)
    {
        static const char* d = "0123456789abcdef";
        std::string h;
        for (size_t i = 0; i < n; i++) {
            h += d[b[i] >> 4];
            h += d[b[i] & 15];
        }
        return str(k, h);
    }
    Json& raw(const char* k, const std::string& json)
    {
        key(k);
        s_ += json;
        return *this;
    }
    std::string done()
    {
        return s_ + "}";
    }

  private:
    std::string s_;
    void key(const char* k)
    {
        if (s_.size() > 1) s_ += ',';
        s_ += '"';
        s_ += k;
        s_ += "\":";
    }
    void quote(const char* v, size_t n)
    {
        s_ += '"';
        for (size_t i = 0; i < n; i++) {
            const unsigned char c = static_cast<unsigned char>(v[i]);
            if (c == '"' || c == '\\') {
                s_ += '\\';
                s_ += static_cast<char>(c);
            } else if (c < 0x20) {
                char b[8];
                snprintf(b, sizeof(b), "\\u%04x", c);
                s_ += b;
            } else {
                s_ += static_cast<char>(c);
            }
        }
        s_ += '"';
    }
};

template <size_t N> std::string jsonArray(const uint32_t (&a)[N], size_t count)
{
    std::string s = "[";
    for (size_t i = 0; i < count && i < N; i++) {
        if (i) s += ',';
        s += std::to_string(a[i]);
    }
    return s + "]";
}

template <size_t N> std::string jsonArray(const int8_t (&a)[N], size_t count)
{
    std::string s = "[";
    for (size_t i = 0; i < count && i < N; i++) {
        if (i) s += ',';
        s += std::to_string(static_cast<int>(a[i]));
    }
    return s + "]";
}

std::string userJson(const meshtastic_User& u)
{
    return Json("user")
        .str("id", u.id, sizeof(u.id))
        .str("long", u.long_name, sizeof(u.long_name))
        .str("short", u.short_name, sizeof(u.short_name))
        .u32("hw", u.hw_model)
        .u32("role", u.role)
        .boolean("licensed", u.is_licensed)
        .boolean("pk", u.public_key.size == 32)
        .done();
}

std::string nodeInfoJson(const meshtastic_NodeInfo& n)
{
    Json j("node");
    j.u32("num", n.num).num("snr", n.snr).u32("last_heard", n.last_heard).u32("ch", n.channel);
    j.boolean("mqtt", n.via_mqtt).boolean("fav", n.is_favorite);
    if (n.has_hops_away) j.u32("hops", n.hops_away);
    if (n.has_user) j.raw("user", userJson(n.user));
    if (n.has_position && n.position.has_latitude_i && n.position.has_longitude_i &&
        (n.position.latitude_i != 0 || n.position.longitude_i != 0)) {
        j.num("lat", n.position.latitude_i * 1e-7).num("lon", n.position.longitude_i * 1e-7);
        if (n.position.has_altitude) j.i64("alt", n.position.altitude);
        j.u32("pos_time", n.position.time);
    }
    if (n.has_device_metrics) {
        const auto& m = n.device_metrics;
        if (m.has_battery_level) j.u32("batt", m.battery_level);
        if (m.has_voltage) j.num("volt", m.voltage);
        if (m.has_channel_utilization) j.num("ch_util", m.channel_utilization);
        if (m.has_air_util_tx) j.num("air_util", m.air_util_tx);
        if (m.has_uptime_seconds) j.u32("uptime", m.uptime_seconds);
    }
    return j.done();
}

std::string channelJson(const meshtastic_Channel& c)
{
    Json j("chan");
    j.i64("index", c.index).u32("role", c.role);
    if (c.has_settings) {
        j.str("name", c.settings.name, sizeof(c.settings.name));
        j.hex("psk", c.settings.psk.bytes, c.settings.psk.size);
    }
    return j.done();
}

std::string configJson(const meshtastic_Config& c)
{
    switch (c.which_payload_variant) {
    case meshtastic_Config_lora_tag: {
        const auto& l = c.payload_variant.lora;
        return Json("lora")
            .u32("region", l.region)
            .boolean("use_preset", l.use_preset)
            .u32("preset", l.modem_preset)
            .u32("bw", l.bandwidth)
            .u32("sf", l.spread_factor)
            .u32("cr", l.coding_rate)
            .u32("hop_limit", l.hop_limit)
            .boolean("tx", l.tx_enabled)
            .i64("tx_power", l.tx_power)
            .u32("slot", l.channel_num)
            .num("override_freq", l.override_frequency)
            .num("freq", RadioLibInterface::instance ? RadioLibInterface::instance->getFreq() : 0.0)
            .done();
    }
    case meshtastic_Config_device_tag:
        return Json("device").u32("role", c.payload_variant.device.role).done();
    default:
        return {};
    }
}

template <typename T> bool decodePayload(const meshtastic_Data& d, const pb_msgdesc_t* fields, T& out)
{
    pb_istream_t is = pb_istream_from_buffer(d.payload.bytes, d.payload.size);
    return pb_decode(&is, fields, &out);
}

std::string packetJson(const meshtastic_MeshPacket& p)
{
    if (p.which_payload_variant != meshtastic_MeshPacket_decoded_tag) return {};
    const meshtastic_Data& d = p.decoded;
    auto base = [&](const char* type) {
        Json j(type);
        j.u32("id", p.id).u32("from", p.from).u32("to", p.to).u32("ch", p.channel);
        if (p.has_rx_time) j.u32("rx_time", p.rx_time);
        if (p.rx_snr != 0) j.num("snr", p.rx_snr);
        if (p.has_rx_rssi) j.i64("rssi", p.rx_rssi);
        if (p.hop_start > 0) j.i64("hops", static_cast<int>(p.hop_start) - static_cast<int>(p.hop_limit));
        j.boolean("mqtt", p.via_mqtt).boolean("pki", p.pki_encrypted);
        return j;
    };
    switch (d.portnum) {
    case meshtastic_PortNum_TEXT_MESSAGE_APP: {
        Json j = base("text");
        j.str("text", std::string(reinterpret_cast<const char*>(d.payload.bytes), d.payload.size));
        if (d.reply_id) j.u32("reply_id", d.reply_id);
        if (d.emoji) j.u32("emoji", d.emoji);
        return j.done();
    }
    case meshtastic_PortNum_ROUTING_APP: {
        meshtastic_Routing r = meshtastic_Routing_init_zero;
        if (!decodePayload(d, meshtastic_Routing_fields, r)) return {};
        if (r.which_variant != meshtastic_Routing_error_reason_tag || d.request_id == 0) return {};
        return base("ack").u32("req", d.request_id).u32("err", r.error_reason).done();
    }
    case meshtastic_PortNum_TRACEROUTE_APP: {
        meshtastic_RouteDiscovery r = meshtastic_RouteDiscovery_init_zero;
        if (!decodePayload(d, meshtastic_RouteDiscovery_fields, r) || d.request_id == 0) return {};
        return base("trace")
            .u32("req", d.request_id)
            .raw("route", jsonArray(r.route, r.route_count))
            .raw("snr_towards", jsonArray(r.snr_towards, r.snr_towards_count))
            .raw("route_back", jsonArray(r.route_back, r.route_back_count))
            .raw("snr_back", jsonArray(r.snr_back, r.snr_back_count))
            .done();
    }
    default:
        return {};
    }
}

bool PhoneAPI_ready()
{
    return service != nullptr;
}

uint32_t myNum()
{
    return nodeDB ? nodeDB->getNodeNum() : 0;
}

bool sendToRadio(const meshtastic_ToRadio& to)
{
    static uint8_t buf[meshtastic_ToRadio_size];
    pb_ostream_t os = pb_ostream_from_buffer(buf, sizeof(buf));
    if (!pb_encode(&os, meshtastic_ToRadio_fields, &to)) return false;
    return mt_api_to_radio(buf, os.bytes_written);
}

template <typename T>
bool sendData(uint32_t id, uint32_t to, int channel, meshtastic_PortNum port, const pb_msgdesc_t* fields, const T* msg,
              bool want_ack, bool want_response)
{
    meshtastic_ToRadio tr = meshtastic_ToRadio_init_zero;
    tr.which_payload_variant = meshtastic_ToRadio_packet_tag;
    meshtastic_MeshPacket& p = tr.packet;
    p.id = id;
    p.to = to;
    p.channel = static_cast<uint8_t>(channel);
    p.want_ack = want_ack;
    p.which_payload_variant = meshtastic_MeshPacket_decoded_tag;
    p.decoded.portnum = port;
    p.decoded.want_response = want_response;
    if (msg) {
        pb_ostream_t os = pb_ostream_from_buffer(p.decoded.payload.bytes, sizeof(p.decoded.payload.bytes));
        if (!pb_encode(&os, fields, msg)) return false;
        p.decoded.payload.size = static_cast<pb_size_t>(os.bytes_written);
    }
    return sendToRadio(tr);
}

// Admin message to the node itself, as the official apps send them over BLE/serial.
bool sendAdmin(const meshtastic_AdminMessage& a)
{
    const uint32_t me = myNum();
    if (!me) return false;
    return sendData(generatePacketId(), me, 0, meshtastic_PortNum_ADMIN_APP, meshtastic_AdminMessage_fields, &a, false,
                    false);
}

void copyStr(char* dst, size_t cap, const std::string& s)
{
    std::strncpy(dst, s.c_str(), cap - 1);
    dst[cap - 1] = 0;
}

} // namespace

std::string mt_node_json(uint32_t num)
{
    if (!nodeDB || !num) return {};
    const meshtastic_NodeInfoLite* lite = nodeDB->getMeshNode(num);
    if (!lite) return {};
    return nodeInfoJson(TypeConversions::ConvertToNodeInfo(lite));
}

std::string mt_event_json(const uint8_t* buf, size_t len)
{
    static meshtastic_FromRadio fr;   // large; one at a time on the radio thread
    fr = meshtastic_FromRadio_init_zero;
    pb_istream_t is = pb_istream_from_buffer(buf, len);
    if (!pb_decode(&is, meshtastic_FromRadio_fields, &fr)) return {};
    switch (fr.which_payload_variant) {
    case meshtastic_FromRadio_my_info_tag:
        return Json("my").u32("num", fr.my_info.my_node_num).u32("reboots", fr.my_info.reboot_count).done();
    case meshtastic_FromRadio_node_info_tag:
        return nodeInfoJson(fr.node_info);
    case meshtastic_FromRadio_channel_tag:
        return channelJson(fr.channel);
    case meshtastic_FromRadio_config_tag:
        return configJson(fr.config);
    case meshtastic_FromRadio_config_complete_id_tag:
        return Json("ready").u32("nonce", fr.config_complete_id).done();
    case meshtastic_FromRadio_metadata_tag:
        return Json("meta").str("fw", fr.metadata.firmware_version, sizeof(fr.metadata.firmware_version)).done();
    case meshtastic_FromRadio_rebooted_tag:
        return Json("rebooted").done();
    case meshtastic_FromRadio_queueStatus_tag:
        if (fr.queueStatus.res == 0) return {};
        return Json("queue").u32("id", fr.queueStatus.mesh_packet_id).i64("res", fr.queueStatus.res).done();
    case meshtastic_FromRadio_packet_tag: {
        std::string out = packetJson(fr.packet);
        // The node updated what it knows about the sender (name, position, battery, last heard...).
        const uint32_t from = fr.packet.from;
        if (from && from != myNum()) {
            const std::string node = mt_node_json(from);
            if (!node.empty()) out = out.empty() ? node : out + "\n" + node;
        }
        return out;
    }
    default:
        return {};
    }
}

bool mt_request_config(uint32_t nonce)
{
    meshtastic_ToRadio tr = meshtastic_ToRadio_init_zero;
    tr.which_payload_variant = meshtastic_ToRadio_want_config_id_tag;
    tr.want_config_id = nonce;
    sendToRadio(tr);   // PhoneAPI answers it with the config stream but reports false for non-packets
    return PhoneAPI_ready();
}

bool mt_send_text(uint32_t id, uint32_t to, int channel, const std::string& text)
{
    if (text.empty() || text.size() > 200) return false;
    meshtastic_ToRadio tr = meshtastic_ToRadio_init_zero;
    tr.which_payload_variant = meshtastic_ToRadio_packet_tag;
    meshtastic_MeshPacket& p = tr.packet;
    p.id = id;
    p.to = to;
    p.channel = static_cast<uint8_t>(channel);
    p.want_ack = true;   // DMs: an ACK from the destination; broadcasts: an implicit ACK when a node rebroadcasts it
    p.which_payload_variant = meshtastic_MeshPacket_decoded_tag;
    p.decoded.portnum = meshtastic_PortNum_TEXT_MESSAGE_APP;
    std::memcpy(p.decoded.payload.bytes, text.data(), text.size());
    p.decoded.payload.size = static_cast<pb_size_t>(text.size());
    return sendToRadio(tr);
}

bool mt_traceroute(uint32_t id, uint32_t dest, int channel)
{
    meshtastic_RouteDiscovery r = meshtastic_RouteDiscovery_init_zero;
    return sendData(id, dest, channel, meshtastic_PortNum_TRACEROUTE_APP, meshtastic_RouteDiscovery_fields, &r, false, true);
}

bool mt_set_owner(const std::string& long_name, const std::string& short_name)
{
    meshtastic_AdminMessage a = meshtastic_AdminMessage_init_zero;
    a.which_payload_variant = meshtastic_AdminMessage_set_owner_tag;
    copyStr(a.set_owner.long_name, sizeof(a.set_owner.long_name), long_name);
    copyStr(a.set_owner.short_name, sizeof(a.set_owner.short_name), short_name);
    a.set_owner.is_licensed = owner.is_licensed;
    return sendAdmin(a);
}

bool mt_set_lora(int region, int preset, int hop_limit, bool tx_enabled, int tx_power)
{
    meshtastic_AdminMessage a = meshtastic_AdminMessage_init_zero;
    a.which_payload_variant = meshtastic_AdminMessage_set_config_tag;
    a.set_config.which_payload_variant = meshtastic_Config_lora_tag;
    meshtastic_Config_LoRaConfig l = config.lora;   // keep everything the app does not edit
    l.region = static_cast<meshtastic_Config_LoRaConfig_RegionCode>(region);
    l.use_preset = true;
    l.modem_preset = static_cast<meshtastic_Config_LoRaConfig_ModemPreset>(preset);
    if (hop_limit >= 0 && hop_limit <= 7) l.hop_limit = static_cast<uint32_t>(hop_limit);
    l.tx_enabled = tx_enabled;
    l.tx_power = static_cast<int8_t>(tx_power);
    a.set_config.payload_variant.lora = l;
    return sendAdmin(a);
}

bool mt_set_channel(int index, int role, const std::string& name, const std::vector<uint8_t>& psk)
{
    if (index < 0 || index > 7) return false;
    meshtastic_AdminMessage a = meshtastic_AdminMessage_init_zero;
    a.which_payload_variant = meshtastic_AdminMessage_set_channel_tag;
    meshtastic_Channel& c = a.set_channel;
    c.index = static_cast<int8_t>(index);
    c.role = static_cast<meshtastic_Channel_Role>(role);
    c.has_settings = role != meshtastic_Channel_Role_DISABLED;
    if (c.has_settings) {
        copyStr(c.settings.name, sizeof(c.settings.name), name);
        const size_t n = psk.size() > sizeof(c.settings.psk.bytes) ? sizeof(c.settings.psk.bytes) : psk.size();
        std::memcpy(c.settings.psk.bytes, psk.data(), n);
        c.settings.psk.size = static_cast<pb_size_t>(n);
    }
    return sendAdmin(a);
}

bool mt_remove_node(uint32_t num)
{
    meshtastic_AdminMessage a = meshtastic_AdminMessage_init_zero;
    a.which_payload_variant = meshtastic_AdminMessage_remove_by_nodenum_tag;
    a.remove_by_nodenum = num;
    return sendAdmin(a);
}

bool mt_send_nodeinfo()
{
    return nodeInfoModule && nodeInfoModule->sendOurNodeInfo(NODENUM_BROADCAST, true, 0, true);
}

// The phone's location as the node's own position, the way the official apps share it.
bool mt_set_position(int32_t lat_i, int32_t lon_i, int32_t alt)
{
    const uint32_t me = myNum();
    if (!me) return false;
    meshtastic_Position pos = meshtastic_Position_init_zero;
    pos.has_latitude_i = pos.has_longitude_i = pos.has_altitude = true;
    pos.latitude_i = lat_i;
    pos.longitude_i = lon_i;
    pos.altitude = alt;
    pos.time = static_cast<uint32_t>(time(nullptr));
    pos.location_source = meshtastic_Position_LocSource_LOC_EXTERNAL;
    return sendData(generatePacketId(), me, 0, meshtastic_PortNum_POSITION_APP, meshtastic_Position_fields, &pos, false,
                    false);
}
