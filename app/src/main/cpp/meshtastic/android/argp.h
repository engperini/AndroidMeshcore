// Minimal argp declarations for Android, whose libc (bionic) has no argp. The portduino core declares its
// command-line hook with these types and PortduinoGlue.cpp builds its option table with them, but on
// Android nothing parses a command line (mt_glue.cpp's portduinoAddArguments() ignores it). Elsewhere
// this is the system header.
#pragma once
#if defined(__ANDROID__)
#include <errno.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef int error_t;

struct argp_state {
    const struct argp* root_argp;
    int argc;
    char** argv;
    int next;
    unsigned flags;
    unsigned arg_num;
    int quoted;
    void* input;
    void** child_inputs;
    void* hook;
    char* name;
    void* err_stream;
    void* out_stream;
    void* pstate;
};

struct argp_option {
    const char* name;
    int key;
    const char* arg;
    int flags;
    const char* doc;
    int group;
};

typedef error_t (*argp_parser_t)(int key, char* arg, struct argp_state* state);

struct argp_child;

struct argp {
    const struct argp_option* options;
    argp_parser_t parser;
    const char* args_doc;
    const char* doc;
    const struct argp_child* children;
    char* (*help_filter)(int key, const char* text, void* input);
    const char* argp_domain;
};

struct argp_child {
    const struct argp* argp;
    int flags;
    const char* header;
    int group;
};

#define OPTION_ARG_OPTIONAL 0x1
#define OPTION_HIDDEN 0x2
#define OPTION_ALIAS 0x4
#define ARGP_ERR_UNKNOWN E2BIG
#define ARGP_KEY_ARG 0
#define ARGP_KEY_ARGS 0x1000006
#define ARGP_KEY_END 0x1000001
#define ARGP_KEY_NO_ARGS 0x1000002
#define ARGP_KEY_INIT 0x1000003
#define ARGP_KEY_FINI 0x1000007
#define ARGP_KEY_SUCCESS 0x1000004
#define ARGP_KEY_ERROR 0x1000005

static inline void argp_usage(const struct argp_state* state) { (void)state; }
static inline void argp_error(const struct argp_state* state, const char* fmt, ...) { (void)state; (void)fmt; }

#ifdef __cplusplus
}
#endif
#else
#include_next <argp.h>
#endif
