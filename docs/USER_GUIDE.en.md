# FlowCam user guide

[Українською](USER_GUIDE.uk.md) · [Project overview](../README.md)

## First experiment

1. Install the supplied FlowCam APK on a compatible Android device (Android 11+, arm64) and grant camera access. An APK is not currently published in GitHub Releases; see the [development notes](DEVELOPMENT.md) for build instructions. This is an experimental version.
2. Secure the phone in landscape orientation. Place a stationary, detailed background behind the intended plume, such as a printed high-contrast pattern. Heated air should pass between camera and background. Keep hot air away from the phone and background. A monitor can introduce flicker and moiré; a printed background is preferable for the first test.
3. Use steady, even lighting. Open “Камера та фокус” (Camera and focus) and obtain a sharp background image. Manual controls depend on the selected camera.
4. With the hair dryer off, enable “Вибрати область пальцем” (Select region by touch) and drag over the observation area. Leave stationary details outside it, unaffected by the plume, for motion compensation.
5. Select “Зміни відносно еталона” (Reference changes), gain ×8 and noise threshold 2.0 σ. Keep motion compensation and moderate smoothing enabled. Tap “Калібрувати” (Calibrate) and wait for the reference to be ready. Keep the scene and phone still.
6. Observe the unheated baseline, then turn on hot air without moving the camera or scene objects. Compare the plume region with its surroundings and repeat the check after switching off.
7. If the baseline is quiet, increase gain gradually. Gain improves visibility, not the camera’s physical sensitivity. Lowering the threshold admits both weaker changes and more noise.

## Interface

The current app controls are in Ukrainian; the English names below are explanations, not an implemented English interface.

![Interface diagram, not a screenshot](images/interface.svg)

The left side contains the video, overlay and analysis region, with status above and processing information below. The right side is a scrollable control panel. The diagram is abbreviated; device rendering may differ.

| Ukrainian control | Meaning |
|---|---|
| Зміни відносно еталона | Reference changes: compare with the stationary scene captured during calibration. |
| Зміни за інтервал часу | Temporal changes: compare separated frames; the actual interval depends on available frames. |
| Деформація · експеримент | Experimental deformation: local background-image displacement relative to the reference. |
| Калібрувати / Зупинити | Calibrate / stop analysis or replay. |
| Підсилення / Поріг шуму | Display gain / threshold relative to estimated noise. |
| Часове порівняння | Requested minimum interval for temporal comparison. |
| Непрозорість накладання / Без накладання | Overlay opacity / video without overlay. |
| Компенсувати рух камери | Compensate small translations using control features; a tripod is still needed. |
| Помірне згладжування | Moderate noise smoothing; changing the filter requires a new reference. |
| Вибрати область пальцем | Select the analysis region by dragging. |
| Камера та фокус | Camera and focus settings. |
| Діагностика та інструкція | Diagnostics and help. |

## What the output can look like

![Schematic examples, not measurements](images/results.svg)

Coloured patches represent image changes relative to the comparison frame. They are not temperature or speed. In deformation mode, arrows represent local background-image displacement in pixels, drawn with lengths magnified ×10 for visibility. Their direction is not the direction of air motion.

Actual output may be faint, fragmented or empty. An empty map does not establish that no airflow exists. Moving objects, lighting changes and flicker can also produce signals. Cold flow without sufficient refractive-index variation may remain invisible.

## Record and replay

- “Запис досліду · до 10 с” records up to 10 seconds, at most 8 frames/s, within a 40 MiB budget; recording may end earlier.
- “Повторити останній” reprocesses the last recorded experiment. The recording does not contain every live-analysis frame, so temporal results can differ.
- “Зберегти дані .air” saves a separate data file for reanalysis; “Відкрити дослід .air” loads one.
- “Експорт відео MP4” exports a viewing video. Keep the `.air` file as well for reprocessing.
- “Зупинити” during replay returns to the live camera. Recalibrate after returning from another app or the system file picker.

## Troubleshooting

**Changes everywhere:** check lighting, vibrations, focus changes, monitor flicker and unaffected control areas; recalibrate.

**Almost no signal:** check background sharpness and texture, whether the hot plume crosses the viewing path, and the unheated baseline before increasing gain.

**No arrows:** reliable image displacements may be insufficient. Do not relax thresholds simply to produce arrows.

Real-flow measurement accuracy and current-version performance on the S25 Ultra have not yet been established by controlled physical testing.
