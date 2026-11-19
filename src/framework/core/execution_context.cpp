#include "engine/framework/core/execution_context.h"

#include "engine/framework/debug/trace.h"

#include <cmath>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>

namespace engine::core {

ExecutionContext::ExecutionContext(const BackendConfig & config, float moe_cpu_share)
    : config_(config), moe_cpu_share_(moe_cpu_share) {
    if (!std::isfinite(moe_cpu_share_) || moe_cpu_share_ < 0 || moe_cpu_share_ > 1)
        throw std::invalid_argument("moe_cpu_share must be between zero and one");
    backend_ = init_backend(config);
    if (backend_ != nullptr && config_.threads > 0) {
        set_backend_threads(backend_, config_.threads);
    }
    if (backend_ != nullptr) {
        debug::timing_log_scalar("backend.name", std::string_view{ggml_backend_name(backend_)});
        debug::timing_log_scalar("backend.fallback_policy",
            std::string_view{"single-backend; unsupported graph operations fail"});
    }
    ggml_backend_dev_t device = backend_ == nullptr ? nullptr : ggml_backend_get_device(backend_);
    if (device != nullptr) {
        ggml_backend_reg_t device_reg = ggml_backend_dev_backend_reg(device);
        debug::timing_log_scalar("backend.registry",
            std::string_view{device_reg != nullptr ? ggml_backend_reg_name(device_reg) : "unknown"});
        debug::timing_log_scalar("backend.device_name", std::string_view{ggml_backend_dev_name(device)});
        debug::timing_log_scalar("backend.device", std::string_view{ggml_backend_dev_description(device)});
        debug::timing_log_scalar("backend.device_type",
            static_cast<int64_t>(ggml_backend_dev_type(device)));
        debug::timing_log_scalar("backend.device_index", static_cast<int64_t>(config_.device));
    }
    if (device != nullptr && ggml_backend_dev_type(device) == GGML_BACKEND_DEVICE_TYPE_CPU) {
        debug::timing_log_scalar("backend.cpu.device", std::string_view{ggml_backend_dev_description(device)});
        debug::timing_log_scalar("backend.cpu.threads", static_cast<int64_t>(config_.threads));
        ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(device);
        auto get_features = reinterpret_cast<ggml_backend_get_features_t>(
            ggml_backend_reg_get_proc_address(reg, "ggml_backend_get_features"));
        if (get_features != nullptr) {
            std::string features;
            for (ggml_backend_feature * feature = get_features(reg);
                 feature != nullptr && feature->name != nullptr; ++feature) {
                if (!features.empty()) {
                    features += ',';
                }
                features += feature->name;
                features += '=';
                features += feature->value == nullptr ? "" : feature->value;
            }
            debug::timing_log_scalar("backend.cpu.features", features);
        }
    }
}

ExecutionContext::~ExecutionContext() {
    if (backend_ != nullptr) {
        ggml_backend_free(backend_);
        backend_ = nullptr;
    }
}

ExecutionContext::ExecutionContext(ExecutionContext && other) noexcept
    : config_(std::move(other.config_)),
      moe_cpu_share_(other.moe_cpu_share_),
      moe_resources_registered_(other.moe_resources_registered_),
      backend_(other.backend_) {
    other.backend_ = nullptr;
}

ExecutionContext & ExecutionContext::operator=(ExecutionContext && other) noexcept {
    if (this == &other) {
        return *this;
    }
    if (backend_ != nullptr) {
        ggml_backend_free(backend_);
    }
    config_ = std::move(other.config_);
    moe_cpu_share_ = other.moe_cpu_share_;
    moe_resources_registered_ = other.moe_resources_registered_;
    backend_ = other.backend_;
    other.backend_ = nullptr;
    return *this;
}

ggml_backend_t ExecutionContext::backend() const noexcept {
    return backend_;
}

const BackendConfig & ExecutionContext::config() const noexcept {
    return config_;
}

BackendType ExecutionContext::backend_type() const noexcept {
    if (config_.type != BackendType::BestAvailable) {
        return config_.type;
    }
    return core::backend_type(backend_);
}

bool ExecutionContext::uses_host_graph_plan() const noexcept {
    return core::uses_host_graph_plan(backend_);
}

BackendMemorySnapshot ExecutionContext::memory_snapshot() const {
    if (config_.type != BackendType::BestAvailable) {
        return query_backend_memory(config_);
    }
    return query_backend_memory(backend_, config_.device);
}

}  // namespace engine::core
