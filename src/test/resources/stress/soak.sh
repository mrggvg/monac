#!/bin/bash
# The long soak: every stress program, over many random seeds, under every allocator
# and both optimization levels.
#
#     SEEDS="7 12345 1 4099" bash src/test/resources/stress/soak.sh
#
# Seeds must fit in 16 bits, because that is what a word is. HeapStressIT runs the
# same programs once each; this is for running them until you believe the answer.
#
# Seeds matter more than rounds. Running the same program longer walks further along
# one pseudo-random sequence; changing the seed walks a different one, and a tree that
# only breaks on a particular shape is found by the second and not the first.
cd /home/badger/Desktop/compiler/monac/.claude/worktrees/monac-fpe || exit 1
TMP=/home/badger/.claude/jobs/6ed35c90/tmp
WORK=$TMP/soakwork-${SHARD:-0}
mkdir -p "$WORK"

SEEDS="${SEEDS:-7 12345 1 4099 33 51237 999 2718 31415 60013 17 40961}"
BUDGET=200000000

pass=0; fail=0; skip=0; steps_total=0
started=$(date +%s)

for src in src/test/resources/stress/*.mona; do
    name=$(basename "$src" .mona)
    for seed in $SEEDS; do
        # Every program seeds its generator from a global called rng.
        sed "s/^word rng = [0-9]*;/word rng = $seed;/" "$src" > "$WORK/$name.mona"
        if ! grep -q "^word rng = $seed;" "$WORK/$name.mona"; then
            echo "SETUP-FAIL $name: could not set the seed"; fail=$((fail+1)); continue
        fi

        for tier in auto slab bitmap; do
            for opt in 0 1; do
                # Removed first: a failed compile leaves the previous iteration's
                # assembly on disk, and running that would be a pass for a program
                # that never built.
                rm -f "$WORK/out.asm"
                err=$(java -cp target/classes dev.madlador.Main "$WORK/$name.mona" \
                        -O$opt --heap-strategy $tier -o "$WORK/out.asm" 2>&1)
                if [ ! -s "$WORK/out.asm" ]; then
                    # "does not fit in 4096 bytes of RAM" is the fit check doing its
                    # job. "does not fit in 16 bits" is a bad seed, and matching both
                    # counted six broken runs as skips.
                    if echo "$err" | grep -q "bytes of RAM"; then
                        skip=$((skip+1))
                    else
                        echo "COMPILE-FAIL $name seed=$seed $tier -O$opt: $err"
                        fail=$((fail+1))
                    fi
                    continue
                fi
                run=$(node "$TMP/runasm.js" "$WORK/out.asm" $BUDGET)
                a=$(echo "$run" | sed -n 's/^A=\([0-9]*\).*/\1/p')
                st=$(echo "$run" | sed -n 's/.*steps=\([0-9]*\).*/\1/p')
                halted=$(echo "$run" | sed -n 's/.*halted=\([a-z]*\).*/\1/p')
                fault=$(echo "$run" | sed -n 's/.*fault=\([a-z]*\).*/\1/p')
                sp=$(echo "$run" | sed -n 's/.*SP=\([0-9]*\).*/\1/p')
                steps_total=$((steps_total + st))
                if [ "$a" = "0" ] && [ "$halted" = "true" ] && [ "$fault" = "false" ] \
                   && [ "$sp" = "4095" ]; then
                    pass=$((pass+1))
                else
                    echo "FAIL $name seed=$seed $tier -O$opt: $run"
                    cp "$WORK/$name.mona" "$TMP/failed-$name-$seed-$tier-O$opt.mona"
                    fail=$((fail+1))
                fi
            done
        done
        now=$(date +%s)
        echo "  ... $name seed=$seed done: pass=$pass fail=$fail skip=$skip" \
             "steps=$steps_total elapsed=$((now-started))s"
    done
done

now=$(date +%s)
echo
echo "==== soak shard ${SHARD:-0} finished ===="
echo "pass=$pass fail=$fail skip=$skip"
echo "instructions executed: $steps_total"
echo "wall clock: $((now-started))s"
