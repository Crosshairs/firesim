########################################################################
# CS152 Lab 2                                                          #
########################################################################

lab_dir := $(chipyard_dir)/lab

# Directed Portion

$(OUTPUT_DIR)/transpose: $(lab_dir)/directed/transpose.riscv
	mkdir -p $(dir $@)
	ln -sf $< $@

.PHONY: run-transpose run-transpose-debug
run-transpose: $(OUTPUT_DIR)/transpose.out
run-transpose-debug: $(OUTPUT_DIR)/transpose.vpd

# Open-Ended Problem 4.1

$(OUTPUT_DIR)/ccbench: $(lab_dir)/open1/ccbench/caches/caches
	mkdir -p $(dir $@)
	ln -sf $< $@

$(OUTPUT_DIR)/ccbench.out: EXEC_INTERP := pk
$(OUTPUT_DIR)/ccbench.out: EXEC_ARGS := 24576 1000 0
$(OUTPUT_DIR)/ccbench.out: disasm := >&2 2>/dev/null | tee

.PHONY: run-ccbench
run-ccbench: $(OUTPUT_DIR)/ccbench.out

# Open-Ended Problem 4.2

$(OUTPUT_DIR)/bfs: $(lab_dir)/open2/bfs
	mkdir -p $(dir $@)
	ln -sf $< $@

$(OUTPUT_DIR)/bfs.out $(OUTPUT_DIR)/bfs.vpd: EXEC_INTERP := pk
$(OUTPUT_DIR)/bfs.out $(OUTPUT_DIR)/bfs.vpd: EXEC_ARGS := -f $(lab_dir)/open2/kron10.sg -n 1

.PHONY: run-bfs run-bfs-debug
run-bfs: $(OUTPUT_DIR)/bfs.out
run-bfs-debug: $(OUTPUT_DIR)/bfs.vpd
