// Host test node: runs the same MyMesh companion firmware as the Android app,
// with the companion link on stdin/stdout as hex lines (one frame per line):
//   stdin  "<hex>"        frame from app to node
//   stdout "< <hex>"      frame from node to app
// Usage: host_node <data_dir> <node_index> <num_nodes> [base_udp_port]

#include "AndroidSerialInterface.h"
#include "target.h"

#include <helpers/SimpleMeshTables.h>

#include "DataStore.h"
#include "MyMesh.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstdio>
#include <iostream>
#include <random>
#include <string>

LinuxBoard board;
UdpRadio radio_driver;
LinuxRTCClock rtc_clock;
SensorManager sensors;

StdRNG fast_rng;
SimpleMeshTables tables;
DataStore store(LittleFS, rtc_clock);
MyMesh the_mesh(radio_driver, fast_rng, rtc_clock, tables, store, nullptr);

void UdpRadio::setup(int base, int index, int nodes) {
  base_port = base;
  my_port = base + index;
  num_nodes = nodes;
  fd = socket(AF_INET, SOCK_DGRAM, 0);
  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  addr.sin_port = htons(my_port);
  bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
  fcntl(fd, F_SETFL, O_NONBLOCK);
}

int UdpRadio::recvRaw(uint8_t* bytes, int sz) {
  const int n = static_cast<int>(recv(fd, bytes, sz, 0));
  if (n > 0) recv_count++;
  return n > 0 ? n : 0;
}

bool UdpRadio::startSendRaw(const uint8_t* bytes, int len) {
  for (int i = 0; i < num_nodes; ++i) {
    const int port = base_port + i;
    if (port == my_port) continue;
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons(port);
    sendto(fd, bytes, len, 0, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
  }
  sent_count++;
  return true;
}

void radio_set_params(float, float, uint8_t, uint8_t) {}
void radio_set_tx_power(int8_t) {}

mesh::LocalIdentity radio_new_identity() {
  StdRNG rng;
  rng.begin(static_cast<long>(std::random_device{}()));
  return mesh::LocalIdentity(&rng);
}

static std::string hex(const uint8_t* d, size_t n) {
  static const char* digits = "0123456789abcdef";
  std::string s;
  for (size_t i = 0; i < n; ++i) { s += digits[d[i] >> 4]; s += digits[d[i] & 15]; }
  return s;
}

int main(int argc, char** argv) {
  if (argc < 4) {
    std::cerr << "usage: host_node <data_dir> <node_index> <num_nodes> [base_udp_port]\n";
    return 2;
  }
  std::setvbuf(stdout, nullptr, _IOLBF, 0);
  LittleFS.setRoot(argv[1]);
  LittleFS.begin();
  radio_driver.setup(argc > 4 ? std::stoi(argv[4]) : 47000, std::stoi(argv[2]), std::stoi(argv[3]));

  AndroidSerialInterface serial;
  serial.setSink([](const uint8_t* d, size_t n) { std::printf("< %s\n", hex(d, n).c_str()); });

  fast_rng.begin(static_cast<long>(std::random_device{}()));
  the_mesh.begin(false);
  the_mesh.startInterface(serial);

  fcntl(0, F_SETFL, O_NONBLOCK);
  std::string line;
  char buf[4096];
  for (;;) {
    const ssize_t n = read(0, buf, sizeof(buf));
    if (n == 0) break;
    if (n > 0) {
      for (ssize_t i = 0; i < n; ++i) {
        if (buf[i] == '\n') {
          uint8_t frame[MAX_FRAME_SIZE];
          size_t len = 0;
          for (size_t j = 0; j + 1 < line.size() && len < sizeof(frame); j += 2) {
            frame[len++] = static_cast<uint8_t>(std::stoi(line.substr(j, 2), nullptr, 16));
          }
          if (len > 0) serial.pushInbound(frame, len);
          line.clear();
        } else {
          line += buf[i];
        }
      }
    }
    the_mesh.loop();
    usleep(2000);
  }
  return 0;
}
