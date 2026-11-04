#ifdef __aarch64__

#include "../syscall_hook.h"

#include <linux/kallsyms.h>
#include <linux/mutex.h>
#include <linux/wait.h>
#include <asm/cacheflush.h>
#include "infra/symbol_resolver.h"
#include "../patch_memory.h"
#include "arch.h"
#include "klog.h" // IWYU pragma: keep

syscall_fn_t *ksu_syscall_table = NULL;
int ksu_dispatcher_nr = -1;

// Hook registration table — read with READ_ONCE from tracepoint/dispatcher
// context, written with WRITE_ONCE from init/exit context.
static ksu_syscall_hook_fn syscall_hooks[__NR_syscalls];

// Track all hooked syscall entries for restoration.
// Protected by hooked_entries_lock.
struct syscall_hook_entry {
    int nr;
    syscall_fn_t orig;
    syscall_fn_t replacement;
};

static DEFINE_MUTEX(hooked_entries_lock);
static struct syscall_hook_entry hooked_entries[16];
static int hooked_count = 0;
static atomic_t active_hooks = ATOMIC_INIT(0);
static DECLARE_WAIT_QUEUE_HEAD(hook_wait);
static void (*synchronize_tasks_fn)(void);

long __nocfi ksu_call_hook_guarded(syscall_fn_t fn, const struct pt_regs *regs)
{
    long ret;

    atomic_inc(&active_hooks);
    ret = fn(regs);
    if (atomic_dec_and_test(&active_hooks))
        wake_up_all(&hook_wait);
    return ret;
}

int __nocfi ksu_synchronize_hooks(void)
{
    if (!synchronize_tasks_fn)
        return -EOPNOTSUPP;
    synchronize_tasks_fn();
    wait_event(hook_wait, atomic_read(&active_hooks) == 0);
    /* Cover the return instructions after the last guarded callback decrements. */
    synchronize_tasks_fn();
    return 0;
}

static int patch_syscall_table(int nr, syscall_fn_t expected, syscall_fn_t fn)
{
    if (ksu_syscall_table == NULL)
        return -ENOENT;
    if (nr < 0 || nr >= __NR_syscalls)
        return -EINVAL;

    pr_info("patch syscall %d, 0x%lx -> 0x%lx\n", nr,
            (unsigned long)READ_ONCE(ksu_syscall_table[nr]), (unsigned long)fn);

    return ksu_patch_text_checked(&ksu_syscall_table[nr], &expected, &fn,
                                  sizeof(fn), KSU_PATCH_TEXT_FLUSH_DCACHE);
}

// Direct syscall table patching: overwrite syscall_table[nr] with fn,
// save original to *old, and record for restoration at module exit.
int ksu_syscall_table_hook(int nr, syscall_fn_t fn, syscall_fn_t *old)
{
    syscall_fn_t orig;
    int i, ret;

    if (ksu_syscall_table == NULL)
        return -ENOENT;
    if (nr < 0 || nr >= __NR_syscalls || !fn)
        return -EINVAL;

    mutex_lock(&hooked_entries_lock);

    for (i = 0; i < hooked_count; i++) {
        if (hooked_entries[i].nr == nr) {
            ret = -EEXIST;
            goto unlock;
        }
    }
    if (hooked_count == ARRAY_SIZE(hooked_entries)) {
        ret = -ENOSPC;
        goto unlock;
    }
    orig = READ_ONCE(ksu_syscall_table[nr]);
    if (!orig || orig == fn) {
        ret = -EINVAL;
        goto unlock;
    }
    /* Publish a callable predecessor before exposing the wrapper. */
    if (old)
        WRITE_ONCE(*old, orig);
    smp_wmb();
    ret = patch_syscall_table(nr, orig, fn);
    /* Retain a record even on a partial write so rollback cannot forget it. */
    if (!ret || READ_ONCE(ksu_syscall_table[nr]) != orig) {
        hooked_entries[hooked_count].nr = nr;
        hooked_entries[hooked_count].orig = orig;
        hooked_entries[hooked_count].replacement = fn;
        hooked_count++;
    }
unlock:
    mutex_unlock(&hooked_entries_lock);
    return ret;
}

// Restore syscall_table[nr] to its original value and remove from tracking list.
int ksu_syscall_table_unhook(int nr)
{
    int i, ret = 0;

    if (ksu_syscall_table == NULL)
        return -ENOENT;
    if (nr < 0 || nr >= __NR_syscalls)
        return -EINVAL;

    mutex_lock(&hooked_entries_lock);

    for (i = 0; i < hooked_count; i++) {
        if (hooked_entries[i].nr == nr) {
            ret = patch_syscall_table(nr, hooked_entries[i].replacement,
                                      hooked_entries[i].orig);
            if (!ret) {
                memmove(&hooked_entries[i], &hooked_entries[i + 1],
                        (hooked_count - i - 1) * sizeof(hooked_entries[0]));
                hooked_count--;
            }
            mutex_unlock(&hooked_entries_lock);
            return ret;
        }
    }

    mutex_unlock(&hooked_entries_lock);
    return 0;
}

static int __init ksu_find_ni_syscall_slots(int *out_slots, int max_slots)
{
    unsigned long ni_syscall;
    int i, count = 0;

    if (!ksu_syscall_table || max_slots <= 0)
        return 0;

    ni_syscall = (unsigned long)ksu_resolve_symbol_for_functable_hook("__arm64_sys_ni_syscall");

    pr_info("sys_ni_syscall: 0x%lx\n", ni_syscall);

    if (!ni_syscall)
        return 0;

    for (i = 0; i < __NR_syscalls && count < max_slots; i++) {
        if ((unsigned long)ksu_syscall_table[i] == ni_syscall) {
            out_slots[count++] = i;
            pr_info("ni_syscall %d: %d\n", count, i);
        }
    }

    return count;
}

// Unified dispatcher: reads original NR from x8/orig_ax, dispatches to handler.
// Validates that syscallno matches our dispatcher slot (i.e. we redirected it),
// otherwise it's a spurious call — return -ENOSYS.
static long __nocfi ksu_syscall_dispatcher_body(const struct pt_regs *regs)
{
    if (regs->syscallno != ksu_dispatcher_nr)
        return -ENOSYS;

    int orig_nr = (int)PT_REGS_ORIG_SYSCALL(regs);

    if (regs->syscallno == orig_nr)
        return -ENOSYS;

    // Restore registers to original state before dispatching
    ((struct pt_regs *)regs)->syscallno = orig_nr;
    PT_REGS_ORIG_SYSCALL((struct pt_regs *)regs) = orig_nr;

    if (likely(orig_nr >= 0 && orig_nr < __NR_syscalls)) {
        ksu_syscall_hook_fn fn = READ_ONCE(syscall_hooks[orig_nr]);
        if (likely(fn))
            return fn(orig_nr, regs);
    }

    if (orig_nr >= 0 && orig_nr < __NR_syscalls)
        return ksu_syscall_table[orig_nr](regs);
    return -ENOSYS;
}

static long ksu_syscall_dispatcher(const struct pt_regs *regs)
{
    return ksu_call_hook_guarded(ksu_syscall_dispatcher_body, regs);
}

// Register a handler into the dispatcher's routing table.
// Does not modify the syscall table — the dispatcher slot is shared by all hooks.
int ksu_register_syscall_hook(int nr, ksu_syscall_hook_fn fn)
{
    if (nr < 0 || nr >= __NR_syscalls || !fn)
        return -EINVAL;
    if (READ_ONCE(syscall_hooks[nr])) {
        pr_warn("syscall hook for nr=%d already registered, skip\n", nr);
        return -EEXIST;
    }
    WRITE_ONCE(syscall_hooks[nr], fn);
    pr_info("registered syscall hook for nr=%d\n", nr);
    return 0;
}

// Remove a handler from the dispatcher's routing table.
// The syscall table is not touched — only the dispatcher stops routing this nr.
void ksu_unregister_syscall_hook(int nr)
{
    if (nr < 0 || nr >= __NR_syscalls)
        return;
    WRITE_ONCE(syscall_hooks[nr], NULL);
    pr_info("unregistered syscall hook for nr=%d\n", nr);
}

bool ksu_has_syscall_hook(int nr)
{
    if (nr < 0 || nr >= __NR_syscalls)
        return false;
    return READ_ONCE(syscall_hooks[nr]) != NULL;
}

int __init ksu_syscall_hook_init(void)
{
    int ni_slot, ret;

    memset(syscall_hooks, 0, sizeof(syscall_hooks));

    synchronize_tasks_fn = (void *)find_kernel_symbol_exact("synchronize_rcu_tasks");
    if (!synchronize_tasks_fn)
        return -EOPNOTSUPP;

    ksu_syscall_table = (syscall_fn_t *)ksu_resolve_symbol_for_functable_hook("sys_call_table");
    pr_info("sys_call_table=0x%lx", (unsigned long)ksu_syscall_table);

    if (!ksu_syscall_table)
        return -ENOENT;

    // Find one ni_syscall slot for the dispatcher
    if (ksu_find_ni_syscall_slots(&ni_slot, 1) < 1) {
        pr_err("failed to find ni_syscall slot for dispatcher\n");
        return -ENOSPC;
    }

    ksu_dispatcher_nr = ni_slot;
    ret = ksu_syscall_table_hook(ksu_dispatcher_nr,
                               (syscall_fn_t)ksu_syscall_dispatcher, NULL);
    if (ret)
        return ret;
    pr_info("dispatcher installed at slot %d\n", ksu_dispatcher_nr);
    return 0;
}

int ksu_syscall_hook_exit(void)
{
    int nr, ret;

    if (!ksu_syscall_table)
        return 0;
    while (hooked_count) {
        nr = hooked_entries[hooked_count - 1].nr;
        ret = ksu_syscall_table_unhook(nr);
        if (ret)
            return ret;
    }
    ret = ksu_synchronize_hooks();
    if (ret)
        return ret;
    memset(syscall_hooks, 0, sizeof(syscall_hooks));
    ksu_dispatcher_nr = -1;
    return 0;
}

bool ksu_syscall_hooks_live(void)
{
    return READ_ONCE(hooked_count) != 0;
}

#endif /* __aarch64__ */
