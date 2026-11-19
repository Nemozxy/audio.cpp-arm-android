# Android ARM64 ASR: Vulkan compatibility and CPU thread scaling

The Snapdragon 8 Gen 3 SM8650Q tablet completed MOSS-Transcribe-Diarize Q4_K inference on Adreno 750 Vulkan. The CPU was substantially faster on the same workload. Keep CPU as the default; these results establish compatibility on the tested driver, not a GPU speedup or support on every Adreno 750 device.

The ARM64 builds used NDK r28.1 and Android API 28, with CPU Repack enabled, I8MM/DOTPROD/FP16 compile flags and KleidiAI disabled. The Vulkan build used scalar shaders. The tables report measurements from the compatibility builds, not emulator results. Audio, transcripts, checksums of inputs and outputs, device identifiers, local paths and raw logs are not included.

## Same-APK backend comparison

One foreground, non-profiled run per backend on the same 23.92-second mono input. The APK resampled it to 16 kHz. Both runs used the same model and options, and generated 121 output tokens. The CPU used four threads. Times are seconds; inference excludes model loading and session creation.

| Stage | CPU | Vulkan |
|---|---:|---:|
| Whisper encoder | 10.01 | 19.90 |
| Decoder prefill | 3.45 | 18.49 |
| Token decode | 3.05 | 105.83 |
| Total inference | 16.57 | 144.34 |

Vulkan inference took 8.7 times as long as CPU. Token decode accounted for approximately 80% of the additional latency. Both runs returned the same text and speaker labels; two segment start times differed by 30 ms and 50 ms. That comparison is not a general accuracy evaluation. The GPU ran the inference graph without per-operation CPU fallback; preprocessing and tokenization remained on the CPU.

Earlier CLI checks on the same tablet also completed without Vulkan profiling overrides. Inference took 12.28 s (CPU) versus 50.94 s (Vulkan) on a 3-second input, and 17.48 s versus 146.83 s on a 23.92-second input. CLI results matched across backends on those inputs. These checks preceded the Android driver-specific submission limit used in the APK comparison above.

## CPU thread scaling

Same tablet, APK, model and 23.92-second input. The app stayed awake and in the foreground, with profiling off and no manual core affinity. It was restarted for each of nine runs, in the interleaved order `2,4,6 / 4,6,2 / 6,2,4`. Times are seconds; each stage and total is independently summarized by its median. JNI total includes audio handling and session creation but excludes model load.

| CPU threads | Individual inference times | Median inference | Median JNI total | Encoder | Prefill | Decode |
|---:|---|---:|---:|---:|---:|---:|
| 2 | 25.20 / 25.94 / 26.05 | 25.94 | 27.27 | 17.15 | 5.35 | 3.35 |
| 4 | 15.98 / 16.12 / 16.18 | 16.12 | 17.49 | 9.83 | 3.39 | 2.83 |
| 6 | 23.17 / 26.92 / 21.62 | 23.17 | 24.56 | 14.13 | 3.45 | 5.86 |

All nine runs generated 121 tokens and returned identical results. Four threads were fastest in this test: 38% less inference time than two threads and 30% less than six. The cause of the six-thread slowdown was not established. This is a recommendation for the tested device and workload, not a universal thread default.

## Compatibility checks and limits

- The Android proprietary Adreno 750 compatibility path uses mapped coherent UMA memory, avoids FP16 compute while retaining FP16 storage, routes small-column Q4_K multiplication to general matrix multiplication, and reduces the graph submission threshold from 100 to 16 nodes.
- Whisper groups attention heads to fit the device storage-buffer range. Vulkan also splits large contiguous `GET_ROWS` source tables by aligned rows. The CPU comparison for a BF16 table crossing the 128 MiB descriptor limit passed, as did the grouped Whisper encoder unit test.
- Focused Vulkan comparisons passed for Q4_K (7/7 single-column cases and 1/1 nine-column case) and Q8_0 (8/8 single-column cases). The full `GET_ROWS` suite passed 88/108 cases versus 87/107 in the earlier build. Both had the same 20 failures on IQ formats not used by this workload.
- A separate SM8650 phone returned `ErrorDeviceLost` on an earlier APK even with the app awake and foregrounded. Its background CPU run was also observed to be frozen by the OS and resumed when foregrounded. The final compatibility APK was not verified on that phone. Do not count background-frozen time in performance comparisons.
