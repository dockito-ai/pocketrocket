#!/data/data/app.pocket/files/usr/bin/bash
# wait-idle.sh [max-seconds]: waits until no Java process (Gradle, Kotlin daemons) has used more than 10% of a core
# over a 15 s sample, so two heavy builds don't run on the phone at once. Exits 1 after max-seconds (default 1800).
max=${1:-1800} start=$SECONDS hz=100   # USER_HZ on Android kernels
ticks() { local p; for p in $(pgrep -f 'bin/java'); do awk '{print $1, $14+$15}' "/proc/$p/stat" 2>/dev/null; done; }
while :; do
  declare -A a=(); while read -r p t; do a[$p]=$t; done < <(ticks)
  sleep 15
  busy=""
  while read -r p t; do
    [ -n "${a[$p]:-}" ] || continue
    (( (t - a[$p]) * 100 / (15 * hz) > 10 )) && busy+=" $p"
  done < <(ticks)
  unset a
  [ -z "$busy" ] && { echo "idle after $((SECONDS - start)) s"; exit 0; }
  (( SECONDS - start > max )) && { echo "still busy:$busy"; exit 1; }
done
