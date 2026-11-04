/* SPDX-License-Identifier: GPL-2.0 */
#ifndef SUSFS_KSU_ROOT_REF_H
#define SUSFS_KSU_ROOT_REF_H

#include "../include/milksu_root.h"

const struct milksu_root_api *susfs_root_api_acquire(void);
void susfs_root_api_release(const struct milksu_root_api **api);

#endif
