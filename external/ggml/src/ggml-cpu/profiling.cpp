#include "profiling.h"

#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "ggml-impl.h"
#include "traits.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <tuple>
#include <vector>

#if defined(__aarch64__) && defined(__linux__)
#    include <sys/auxv.h>
#endif

#if defined(__linux__)
#    include <sched.h>
#    include <unistd.h>
#endif

namespace {

using Clock = std::chrono::steady_clock;

std::atomic<bool> g_enabled{[]() {
    const char * value = std::getenv("GGML_CPU_PROFILE");
    return value != nullptr && std::strcmp(value, "0") != 0 && *value != '\0';
}()};
std::atomic<uint64_t> g_next_graph_id{1};
std::mutex g_mutex;

struct OpKey {
    ggml_op op = GGML_OP_NONE;
    ggml_type src0 = GGML_TYPE_COUNT;
    ggml_type src1 = GGML_TYPE_COUNT;
    int fused = 0;

    bool operator<(const OpKey & other) const {
        return std::tie(op, src0, src1, fused) <
               std::tie(other.op, other.src0, other.src1, other.fused);
    }
};

struct OpStats {
    uint64_t calls = 0;
    uint64_t wall_us = 0;
};

struct KernelKey {
    std::string buffer;
    ggml_op op = GGML_OP_NONE;
    ggml_type src0 = GGML_TYPE_COUNT;
    ggml_type src1 = GGML_TYPE_COUNT;

    bool operator<(const KernelKey & other) const {
        return std::tie(buffer, op, src0, src1) <
               std::tie(other.buffer, other.op, other.src0, other.src1);
    }
};

struct ThreadStats {
    std::string affinity = "unavailable";
    bool affinity_logged = false;
    std::map<int, uint64_t> cores;
};

std::map<OpKey, OpStats> g_ops;
std::map<KernelKey, uint64_t> g_kernels;
std::map<int, ThreadStats> g_threads;
uint64_t g_graphs = 0;
uint64_t g_graph_wall_us = 0;

struct GraphProfile {
    uint64_t id = 0;
    Clock::time_point started;
    Clock::time_point op_started;
    std::map<OpKey, OpStats> ops;
};

thread_local GraphProfile t_graph;

OpKey op_key(const ggml_tensor * tensor, int fused_ops) {
    OpKey key;
    key.op = tensor->op;
    key.src0 = tensor->src[0] != nullptr ? tensor->src[0]->type : GGML_TYPE_COUNT;
    key.src1 = tensor->src[1] != nullptr ? tensor->src[1]->type : GGML_TYPE_COUNT;
    key.fused = fused_ops;
    return key;
}

const char * type_name(ggml_type type) {
    return type == GGML_TYPE_COUNT ? "none" : ggml_type_name(type);
}

int graph_min_ms() {
    static const int value = []() {
        const char * env = std::getenv("GGML_CPU_PROFILE_GRAPH_MIN_MS");
        if (env == nullptr || *env == '\0') {
            return 1000;
        }
        return std::max(0, std::atoi(env));
    }();
    return value;
}

#if defined(__linux__)
std::string cpu_set_string(const cpu_set_t * set) {
    std::ostringstream out;
    bool first = true;
    for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
        if (CPU_ISSET(cpu, set)) {
            if (!first) {
                out << ',';
            }
            out << cpu;
            first = false;
        }
    }
    const std::string result = out.str();
    return result.empty() ? "empty" : result;
}
#endif

void log_op(const char * scope, uint64_t graph_id, const OpKey & key, const OpStats & stats,
            double total_ms) {
    const double wall_ms = stats.wall_us / 1000.0;
    GGML_LOG_INFO(
        "ggml.profile.op scope=%s graph=%" PRIu64 " op=%s src0=%s src1=%s fused=%d calls=%" PRIu64
        " wall_ms=%.3f share_pct=%.3f\n",
        scope, graph_id, ggml_op_name(key.op), type_name(key.src0), type_name(key.src1), key.fused,
        stats.calls, wall_ms, total_ms > 0.0 ? wall_ms * 100.0 / total_ms : 0.0);
}

} // namespace

extern "C" {

void ggml_cpu_profile_set_enabled(int enabled) {
    g_enabled.store(enabled != 0, std::memory_order_relaxed);
}

int ggml_cpu_profile_is_enabled(void) {
    return g_enabled.load(std::memory_order_relaxed) ? 1 : 0;
}

void ggml_cpu_profile_reset(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_ops.clear();
    g_kernels.clear();
    g_threads.clear();
    g_graphs = 0;
    g_graph_wall_us = 0;
}

void ggml_cpu_log_diagnostics(void) {
#if defined(__aarch64__)
    const char * arch = "aarch64";
#else
    const char * arch = "other";
#endif
    GGML_LOG_INFO(
        "ggml.cpu.build arch=%s arm_arch=%s neon=%d arm_fma=%d fp16_va=%d dotprod=%d i8mm=%d "
        "repack=%d kleidiai=%d\n",
        arch,
#ifdef GGML_CPU_ARM_ARCH_STRING
        GGML_CPU_ARM_ARCH_STRING,
#else
        "unspecified",
#endif
        ggml_cpu_has_neon(), ggml_cpu_has_arm_fma(), ggml_cpu_has_fp16_va(), ggml_cpu_has_dotprod(),
        ggml_cpu_has_matmul_int8(),
#ifdef GGML_USE_CPU_REPACK
        1,
#else
        0,
#endif
#ifdef GGML_USE_CPU_KLEIDIAI
        1
#else
        0
#endif
    );

#if defined(__aarch64__) && defined(__linux__)
    const unsigned long hwcap = getauxval(AT_HWCAP);
    const unsigned long hwcap2 = getauxval(AT_HWCAP2);
    GGML_LOG_INFO(
        "ggml.cpu.hwcap AT_HWCAP=0x%lx AT_HWCAP2=0x%lx asimd=%d fphp=%d asimdhp=%d asimddp=%d i8mm=%d sve=%d\n",
        hwcap, hwcap2,
#ifdef HWCAP_ASIMD
        !!(hwcap & HWCAP_ASIMD),
#else
        -1,
#endif
#ifdef HWCAP_FPHP
        !!(hwcap & HWCAP_FPHP),
#else
        -1,
#endif
#ifdef HWCAP_ASIMDHP
        !!(hwcap & HWCAP_ASIMDHP),
#else
        -1,
#endif
#ifdef HWCAP_ASIMDDP
        !!(hwcap & HWCAP_ASIMDDP),
#else
        -1,
#endif
#ifdef HWCAP2_I8MM
        !!(hwcap2 & HWCAP2_I8MM),
#else
        -1,
#endif
#ifdef HWCAP_SVE
        !!(hwcap & HWCAP_SVE)
#else
        -1
#endif
    );
#else
    GGML_LOG_INFO("ggml.cpu.hwcap unavailable=non_linux_aarch64\n");
#endif

    std::string buffers;
    for (ggml_backend_buffer_type_t buffer : ggml_backend_cpu_get_extra_buffer_types()) {
        if (!buffers.empty()) {
            buffers += ',';
        }
        buffers += buffer != nullptr ? ggml_backend_buft_name(buffer) : "null";
    }
    GGML_LOG_INFO("ggml.cpu.extra_buffers=%s\n", buffers.empty() ? "none" : buffers.c_str());
}

void ggml_cpu_profile_graph_begin(void) {
    if (!g_enabled.load(std::memory_order_relaxed)) {
        return;
    }
    t_graph.id = g_next_graph_id.fetch_add(1, std::memory_order_relaxed);
    t_graph.started = Clock::now();
    t_graph.ops.clear();
}

void ggml_cpu_profile_op_begin(int ith) {
    if (ith == 0 && t_graph.id != 0) {
        t_graph.op_started = Clock::now();
    }
}

void ggml_cpu_profile_op_end(int ith, const ggml_tensor * tensor, int fused_ops) {
    if (ith != 0 || t_graph.id == 0 || tensor == nullptr) {
        return;
    }
    const uint64_t elapsed = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - t_graph.op_started).count());
    const OpKey key = op_key(tensor, fused_ops);
    OpStats & local = t_graph.ops[key];
    ++local.calls;
    local.wall_us += elapsed;
    std::lock_guard<std::mutex> lock(g_mutex);
    OpStats & global = g_ops[key];
    ++global.calls;
    global.wall_us += elapsed;
}

void ggml_cpu_profile_graph_end(const struct ggml_cgraph * cgraph, int n_threads) {
    if (t_graph.id == 0) {
        return;
    }
    const uint64_t graph_id = t_graph.id;
    const uint64_t elapsed = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - t_graph.started).count());
    const double total_ms = elapsed / 1000.0;
    t_graph.id = 0;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        ++g_graphs;
        g_graph_wall_us += elapsed;
    }
    if (total_ms < graph_min_ms()) {
        return;
    }
    const int nodes = cgraph != nullptr ? cgraph->n_nodes : 0;
    GGML_LOG_INFO("ggml.profile.graph id=%" PRIu64 " nodes=%d threads=%d wall_ms=%.3f includes_barriers=1\n",
                  graph_id, nodes, n_threads, total_ms);
    for (const auto & entry : t_graph.ops) {
        log_op("graph", graph_id, entry.first, entry.second, total_ms);
    }
}

void ggml_cpu_profile_thread(int ith) {
    if (t_graph.id == 0 && !g_enabled.load(std::memory_order_relaxed)) {
        return;
    }
    if (!g_enabled.load(std::memory_order_relaxed)) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    ThreadStats & stats = g_threads[ith];
#if defined(__linux__)
    const int cpu = sched_getcpu();
    if (cpu >= 0) {
        ++stats.cores[cpu];
    }
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof(set), &set) == 0) {
        stats.affinity = cpu_set_string(&set);
    }
#endif
    if (!stats.affinity_logged) {
        GGML_LOG_INFO("ggml.profile.cpu worker=%d affinity=%s\n", ith, stats.affinity.c_str());
        stats.affinity_logged = true;
    }
}

void ggml_cpu_profile_kernel(int ith, const char * buffer_type, const ggml_tensor * tensor) {
    if (ith != 0 || tensor == nullptr || !g_enabled.load(std::memory_order_relaxed)) {
        return;
    }
    KernelKey key;
    key.buffer = buffer_type != nullptr ? buffer_type : "unknown";
    key.op = tensor->op;
    key.src0 = tensor->src[0] != nullptr ? tensor->src[0]->type : GGML_TYPE_COUNT;
    key.src1 = tensor->src[1] != nullptr ? tensor->src[1]->type : GGML_TYPE_COUNT;
    std::lock_guard<std::mutex> lock(g_mutex);
    ++g_kernels[key];
}

void ggml_cpu_profile_log_summary(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const double total_ms = g_graph_wall_us / 1000.0;
    GGML_LOG_INFO("ggml.profile.summary enabled=%d graphs=%" PRIu64 " graph_wall_ms=%.3f includes_barriers=1\n",
                  g_enabled.load(std::memory_order_relaxed) ? 1 : 0, g_graphs, total_ms);
    for (const auto & entry : g_ops) {
        log_op("all", 0, entry.first, entry.second, total_ms);
    }
    for (const auto & entry : g_kernels) {
        GGML_LOG_INFO("ggml.kernel.hit buffer=%s op=%s src0=%s src1=%s calls=%" PRIu64 "\n",
                      entry.first.buffer.c_str(), ggml_op_name(entry.first.op), type_name(entry.first.src0),
                      type_name(entry.first.src1), entry.second);
    }
    for (const auto & entry : g_threads) {
        std::ostringstream cores;
        for (const auto & core : entry.second.cores) {
            if (!cores.str().empty()) {
                cores << ',';
            }
            cores << core.first << ':' << core.second;
        }
        GGML_LOG_INFO("ggml.profile.cpu_summary worker=%d affinity=%s observed_cores=%s\n",
                      entry.first, entry.second.affinity.c_str(),
                      cores.str().empty() ? "unavailable" : cores.str().c_str());
    }
}

} // extern "C"
