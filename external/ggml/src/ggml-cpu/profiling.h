#pragma once

#include "ggml.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// These hooks are compiled in for every CPU backend but remain dormant unless
// profiling is explicitly enabled. Only thread 0 records wall-time operator
// samples; the surrounding graph barriers are therefore included in each sample.
void ggml_cpu_profile_graph_begin(void);
void ggml_cpu_profile_graph_end(const struct ggml_cgraph * cgraph, int n_threads);
void ggml_cpu_profile_op_begin(int ith);
void ggml_cpu_profile_op_end(int ith, const struct ggml_tensor * op, int fused_ops);
void ggml_cpu_profile_thread(int ith);
void ggml_cpu_profile_kernel(int ith, const char * buffer_type, const struct ggml_tensor * op);

#ifdef __cplusplus
}
#endif
