/* Minimal on-device check for the Android C ABI build. No model is required. */
#include "audiocpp.h"

#include <stdio.h>

int main(void) {
    const uint32_t abi = audiocpp_abi_version();
    if ((abi >> 16) != AUDIOCPP_ABI_VERSION_MAJOR) {
        fprintf(stderr, "ABI major mismatch: header=%u library=%u\n",
                AUDIOCPP_ABI_VERSION_MAJOR, abi >> 16);
        return 1;
    }

    audiocpp_cpu_log_diagnostics();
    audiocpp_cpu_profile_set_enabled(0);
    audiocpp_cpu_profile_reset();
    if (audiocpp_cpu_profile_is_enabled() != 0) {
        fprintf(stderr, "CPU profiling did not remain disabled\n");
        return 4;
    }

    audiocpp_registry * registry = NULL;
    const audiocpp_status status = audiocpp_registry_create(NULL, &registry);
    if (status != AUDIOCPP_OK || registry == NULL) {
        fprintf(stderr, "registry creation failed (%d): %s\n",
                (int) status, audiocpp_last_error());
        return 2;
    }

    const size_t families = audiocpp_registry_family_count(registry);
    const size_t tasks = audiocpp_task_count();
    printf("audio.cpp Android smoke test passed\n");
    printf("version=%s abi=%u.%u.%u families=%zu tasks=%zu\n",
           audiocpp_build_version(), abi >> 16, (abi >> 8) & 0xffu, abi & 0xffu,
           families, tasks);

    audiocpp_registry_free(registry);
    return families > 0 && tasks > 0 ? 0 : 3;
}
