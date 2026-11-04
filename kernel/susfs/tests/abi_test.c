/* SPDX-License-Identifier: GPL-2.0 */
#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>

#include "../susfs_abi.h"

#define CHECK_LAYOUT(type, bytes, offset) \
	_Static_assert(sizeof(struct type) == bytes, #type " size mismatch"); \
	_Static_assert(offsetof(struct type, err) == offset, #type " err offset mismatch")

CHECK_LAYOUT(st_susfs_sus_path, 260, 256);
CHECK_LAYOUT(st_susfs_sus_map, 260, 256);
CHECK_LAYOUT(st_susfs_sus_kstat, 376, 372);
CHECK_LAYOUT(st_susfs_uname, 136, 132);
CHECK_LAYOUT(st_susfs_log, 8, 4);
CHECK_LAYOUT(st_susfs_avc_log_spoofing, 8, 4);
CHECK_LAYOUT(st_susfs_hide_sus_mnts_for_non_su_procs, 8, 4);
CHECK_LAYOUT(st_susfs_open_redirect, 520, 516);
CHECK_LAYOUT(st_susfs_spoof_cmdline_or_bootconfig, 8196, 8192);
CHECK_LAYOUT(st_susfs_version, 20, 16);
CHECK_LAYOUT(st_susfs_variant, 20, 16);
CHECK_LAYOUT(st_susfs_enabled_features, 8196, 8192);

#define REPORT(type) \
	printf("%s size=%zu err_offset=%zu\n", #type, \
	       sizeof(struct type), offsetof(struct type, err))

int main(void)
{
	REPORT(st_susfs_sus_path);
	REPORT(st_susfs_sus_map);
	REPORT(st_susfs_sus_kstat);
	REPORT(st_susfs_uname);
	REPORT(st_susfs_log);
	REPORT(st_susfs_avc_log_spoofing);
	REPORT(st_susfs_hide_sus_mnts_for_non_su_procs);
	REPORT(st_susfs_open_redirect);
	REPORT(st_susfs_spoof_cmdline_or_bootconfig);
	REPORT(st_susfs_version);
	REPORT(st_susfs_variant);
	REPORT(st_susfs_enabled_features);
	return 0;
}
