#!/bin/sh
# First on-device check of the matrix-leds driver and of our LED layout, before the service
# is involved. Needs root: `adb root` on a userdebug/eng build, or `su` (Magisk/KernelSU)
# on a user build. Set ADB=/path/to/adb if adb is not on PATH.
#
#   tools/matrix-test.sh all     every LED at low brightness
#   tools/matrix-test.sh rows    lights rows one by one, top to bottom
#   tools/matrix-test.sh walk    walks one LED through the first rows (checks row-major order)
#   tools/matrix-test.sh cross   centre row + centre column (checks the centring)
#   tools/matrix-test.sh off
set -e
adb=${ADB:-adb}
dev=/sys/devices/platform/soc/8c0000.qcom,qupv3_2_geni_se/888000.spi/spi_master/spi10/spi10.0/leds/matrix-leds
rows="7 11 15 17 19 21 21 23 23 25 25 25 25 25 25 25 23 23 21 21 19 17 15 11 7"
level=${LEVEL:-300}

# Prints the 489 driver values for a test pattern; $1 picks which LEDs are on (runs on the host).
frame() {
    awk -v rows="$rows" -v level="$level" -v mode="$1" -v arg="$2" 'BEGIN {
        n = split(rows, len, " "); i = 0; out = ""
        for (y = 1; y <= n; y++) {
            start = int((25 - len[y]) / 2)
            for (k = 0; k < len[y]; k++) {
                x = start + k; on = 0
                if (mode == "row") on = (y - 1 == arg)
                if (mode == "led") on = (i == arg)
                if (mode == "cross") on = (x == 12 || y - 1 == 12)
                out = out (i ? " " : "") (on ? level : 0); i++
            }
        }
        print out
    }'
}

# Runs a command as root on the phone: directly if adbd is root, otherwise through su.
root() {
    if [ "$("$adb" shell id -u)" = 0 ]; then
        "$adb" shell "$1"
    else
        "$adb" shell "su -c \"$1\""
    fi
}

push() {
    root "echo 1 > $dev/led_power_on; echo '$1' > $dev/frame_brightness"
}

case "$1" in
    all)   root "echo 1 > $dev/led_power_on; echo $level > $dev/frame_brightness" ;;
    rows)  for r in $(seq 0 24); do push "$(frame row $r)"; sleep 0.3; done ;;
    walk)  for i in $(seq 0 40); do push "$(frame led $i)"; sleep 0.15; done ;;
    cross) push "$(frame cross)" ;;
    off)   root "echo 0 > $dev/all_brightness" ;;
    *)     sed -n 2,12p "$0"; exit 1 ;;
esac
