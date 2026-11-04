/* SPDX-License-Identifier: GPL-2.0-only */
/* AVC feature ported from KernelSU-Next extras.c. */
#include <linux/init.h>
#include <linux/kprobes.h>
#include <linux/kernel.h>
#include <linux/mutex.h>
#include <linux/security.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <linux/version.h>
#include <asm/insn.h>

#include "feature/avc_spoof.h"
#include "infra/symbol_resolver.h"
#include "infra/btf_parser.h"
#include "policy/feature.h"
#include "selinux/selinux.h"
#include "klog.h"

static DEFINE_MUTEX(avc_lock);
static bool avc_requested = true;
static bool avc_ready;
static bool avc_running;
static bool avc_registered;
static u32 root_sid, app_sid;

#ifdef CONFIG_KPROBES
static unsigned int avc_source_reg = 0, avc_target_reg = 1;

static int avc_pre_handler(struct kprobe *kp, struct pt_regs *regs)
{
    if (!READ_ONCE(avc_running))
        return 0;
    if ((u32)regs->regs[avc_source_reg] == root_sid)
        regs->regs[avc_source_reg] = app_sid;
    if ((u32)regs->regs[avc_target_reg] == root_sid)
        regs->regs[avc_target_reg] = app_sid;
    return 0;
}

static struct kprobe avc_probe = {
    .symbol_name = "slow_avc_audit",
    .pre_handler = avc_pre_handler,
};

static int avc_enable_locked(void)
{
    static const char replacement[] = "u:r:priv_app:s0:c512,c768";
    unsigned long address;
    u32 instructions[4];
    int i, ret;

    if (avc_running)
        return 0;
#if defined(MODULE) && defined(CONFIG_DEBUG_INFO_BTF)
    ret = ksu_btf_avc_sid_regs(&avc_source_reg, &avc_target_reg);
    if (ret)
        return ret;
#else
    return -EOPNOTSUPP;
#endif
    address = find_kernel_symbol_exact("slow_avc_audit");
    if (!address || copy_from_kernel_nofault(instructions, (void *)address, sizeof(instructions)))
        return -EOPNOTSUPP;
    /* KPatch installs a branch trampoline here before the root LKM loads. */
    for (i = 0; i < ARRAY_SIZE(instructions); i++) {
        u32 instruction = le32_to_cpu(instructions[i]);

        if (aarch64_insn_is_b(instruction) || aarch64_insn_is_br(instruction))
            return -EBUSY;
    }
    ret = security_secctx_to_secid(KERNEL_SU_CONTEXT, strlen(KERNEL_SU_CONTEXT), &root_sid);
    if (ret || !root_sid)
        return ret ?: -EOPNOTSUPP;
    ret = security_secctx_to_secid(replacement, strlen(replacement), &app_sid);
    if (ret || !app_sid)
        return ret ?: -EOPNOTSUPP;

    ret = register_kprobe(&avc_probe);
    if (ret) {
        avc_probe.addr = NULL;
        avc_probe.flags &= ~KPROBE_FLAG_DISABLED;
        return ret;
    }
    WRITE_ONCE(avc_running, true);
    return 0;
}

static void avc_disable_locked(void)
{
    if (!avc_running)
        return;
    WRITE_ONCE(avc_running, false);
    unregister_kprobe(&avc_probe);
    avc_probe.addr = NULL;
    avc_probe.flags &= ~KPROBE_FLAG_DISABLED;
}
#else
static int avc_enable_locked(void)
{
    return -EOPNOTSUPP;
}

static void avc_disable_locked(void) {}
#endif

static int avc_feature_get(u64 *value)
{
    mutex_lock(&avc_lock);
    *value = avc_ready ? avc_running : avc_requested;
    mutex_unlock(&avc_lock);
    return 0;
}

static int avc_feature_set(u64 value)
{
    bool enabled = value != 0;
    int ret = 0;

    if (value > 1)
        return -EINVAL;
    mutex_lock(&avc_lock);
    if (avc_ready && enabled)
        ret = avc_enable_locked();
    else if (!enabled)
        avc_disable_locked();
    if (!ret)
        avc_requested = enabled;
    mutex_unlock(&avc_lock);
    return ret;
}

static const struct ksu_feature_handler avc_handler = {
    .feature_id = KSU_FEATURE_AVC_SPOOF,
    .name = "avc_spoof",
    .get_handler = avc_feature_get,
    .set_handler = avc_feature_set,
};

int ksu_avc_spoof_get(bool *enabled)
{
    u64 value = 0;
    int ret;

    if (!avc_registered)
        return -EOPNOTSUPP;
    ret = avc_feature_get(&value);
    *enabled = value != 0;
    return ret;
}

int ksu_avc_spoof_set(bool enabled)
{
    return ksu_set_feature(KSU_FEATURE_AVC_SPOOF, enabled);
}

int __init ksu_avc_spoof_init(void)
{
    int ret;

#if defined(CONFIG_KPROBES) && defined(MODULE) && defined(CONFIG_DEBUG_INFO_BTF)
    ret = ksu_btf_avc_sid_regs(&avc_source_reg, &avc_target_reg);
    if (ret) {
        pr_warn("avc_spoof: unsupported audit prototype: %d\n", ret);
        return 0;
    }
#else
    return 0;
#endif
    ret = ksu_register_feature_handler(&avc_handler);
    if (!ret)
        avc_registered = true;
    return ret;
}

void ksu_avc_spoof_late_init(void)
{
    int ret = 0;

    if (!avc_registered)
        return;
    mutex_lock(&avc_lock);
    avc_ready = true;
    if (avc_requested)
        ret = avc_enable_locked();
    mutex_unlock(&avc_lock);
    if (ret)
        pr_warn("avc_spoof: activation failed: %d\n", ret);
}

void ksu_avc_spoof_exit(void)
{
    mutex_lock(&avc_lock);
    avc_disable_locked();
    avc_ready = false;
    mutex_unlock(&avc_lock);
    if (avc_registered) {
        ksu_unregister_feature_handler(KSU_FEATURE_AVC_SPOOF);
        avc_registered = false;
    }
}
