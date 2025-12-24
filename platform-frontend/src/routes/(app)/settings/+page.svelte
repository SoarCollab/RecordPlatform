<script lang="ts">
  import { useAuth } from "$stores/auth.svelte";
  import { useNotifications } from "$stores/notifications.svelte";
  import * as validation from "$utils/validation";
  import { formatDateTime } from "$utils/format";
  import { changePassword } from "$api/endpoints/auth";

  const auth = useAuth();
  const notifications = useNotifications();

  // Profile form
  let nickname = $state(auth.user?.nickname || "");
  let email = $state(auth.user?.email || "");
  let phone = $state(auth.user?.phone || "");
  let isSavingProfile = $state(false);

  // Password form
  let oldPassword = $state("");
  let newPassword = $state("");
  let confirmPwd = $state("");
  let isChangingPassword = $state(false);

  async function handleSaveProfile() {
    if (email) {
      const emailResult = validation.email(email);
      if (!emailResult.valid) {
        notifications.warning("邮箱格式错误", emailResult.message);
        return;
      }
    }

    if (phone) {
      const phoneResult = validation.phone(phone);
      if (!phoneResult.valid) {
        notifications.warning("手机号格式错误", phoneResult.message);
        return;
      }
    }

    isSavingProfile = true;
    try {
      // 后端 UpdateUserVO 仅支持 avatar 和 nickname
      await auth.updateProfile({ nickname });
      notifications.success("保存成功");
    } catch (err) {
      notifications.error(
        "保存失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    } finally {
      isSavingProfile = false;
    }
  }

  async function handleChangePassword() {
    const oldPwdResult = validation.required(oldPassword, "原密码");
    if (!oldPwdResult.valid) {
      notifications.warning("请填写原密码", oldPwdResult.message);
      return;
    }

    const newPwdResult = validation.password(newPassword);
    if (!newPwdResult.valid) {
      notifications.warning("新密码格式错误", newPwdResult.message);
      return;
    }

    const confirmResult = validation.confirmPassword(confirmPwd, newPassword);
    if (!confirmResult.valid) {
      notifications.warning("确认密码错误", confirmResult.message);
      return;
    }

    isChangingPassword = true;
    try {
      // 字段名与后端 ChangePasswordVO 对齐: password, new_password
      await changePassword({
        password: oldPassword,
        new_password: newPassword,
      });
      notifications.success("密码修改成功", "请使用新密码重新登录");
      oldPassword = "";
      newPassword = "";
      confirmPwd = "";
    } catch (err) {
      notifications.error(
        "修改失败",
        err instanceof Error ? err.message : "请检查原密码是否正确"
      );
    } finally {
      isChangingPassword = false;
    }
  }
</script>

<svelte:head>
  <title>个人设置 - 存证平台</title>
</svelte:head>

<div class="mx-auto max-w-2xl space-y-6">
  <div>
    <h1 class="text-2xl font-bold">个人设置</h1>
    <p class="text-muted-foreground">管理您的账户信息</p>
  </div>

  <!-- Profile -->
  <div class="rounded-lg border bg-card">
    <div class="border-b p-4">
      <h2 class="font-semibold">基本信息</h2>
    </div>
    <div class="space-y-4 p-4">
      <div>
        <label for="settings-username" class="mb-2 block text-sm font-medium"
          >用户名</label
        >
        <input
          id="settings-username"
          type="text"
          value={auth.username}
          disabled
          class="w-full rounded-lg border bg-muted px-3 py-2 text-sm"
        />
        <p class="mt-1 text-xs text-muted-foreground">用户名不可修改</p>
      </div>

      <div>
        <label for="settings-nickname" class="mb-2 block text-sm font-medium"
          >昵称</label
        >
        <input
          id="settings-nickname"
          type="text"
          bind:value={nickname}
          placeholder="设置昵称"
          class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none"
          disabled={isSavingProfile}
        />
      </div>

      <div>
        <label for="settings-email" class="mb-2 block text-sm font-medium"
          >邮箱</label
        >
        <input
          id="settings-email"
          type="email"
          bind:value={email}
          placeholder="设置邮箱"
          class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none"
          disabled={isSavingProfile}
        />
      </div>

      <div>
        <label for="settings-phone" class="mb-2 block text-sm font-medium"
          >手机号</label
        >
        <input
          id="settings-phone"
          type="tel"
          bind:value={phone}
          placeholder="设置手机号"
          class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none"
          disabled={isSavingProfile}
        />
      </div>

      <button
        class="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
        onclick={handleSaveProfile}
        disabled={isSavingProfile}
      >
        {isSavingProfile ? "保存中..." : "保存修改"}
      </button>
    </div>
  </div>

  <!-- Change Password -->
  <div class="rounded-lg border bg-card">
    <div class="border-b p-4">
      <h2 class="font-semibold">修改密码</h2>
    </div>
    <div class="space-y-4 p-4">
      <div>
        <label
          for="settings-old-password"
          class="mb-2 block text-sm font-medium">原密码</label
        >
        <input
          id="settings-old-password"
          type="password"
          bind:value={oldPassword}
          placeholder="请输入原密码"
          class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none"
          disabled={isChangingPassword}
        />
      </div>

      <div>
        <label
          for="settings-new-password"
          class="mb-2 block text-sm font-medium">新密码</label
        >
        <input
          id="settings-new-password"
          type="password"
          bind:value={newPassword}
          placeholder="至少8位，包含字母和数字"
          class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none"
          disabled={isChangingPassword}
        />
      </div>

      <div>
        <label
          for="settings-confirm-password"
          class="mb-2 block text-sm font-medium">确认新密码</label
        >
        <input
          id="settings-confirm-password"
          type="password"
          bind:value={confirmPwd}
          placeholder="再次输入新密码"
          class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none"
          disabled={isChangingPassword}
        />
      </div>

      <button
        class="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
        onclick={handleChangePassword}
        disabled={isChangingPassword}
      >
        {isChangingPassword ? "修改中..." : "修改密码"}
      </button>
    </div>
  </div>

  <!-- Account Info -->
  <div class="rounded-lg border bg-card">
    <div class="border-b p-4">
      <h2 class="font-semibold">账户信息</h2>
    </div>
    <div class="space-y-3 p-4 text-sm">
      <div class="flex justify-between">
        <span class="text-muted-foreground">账户角色</span>
        <span class="font-medium"
          >{auth.user?.role === "admin" ? "管理员" : "普通用户"}</span
        >
      </div>
      <div class="flex justify-between">
        <span class="text-muted-foreground">注册时间</span>
        <span>{auth.user?.registerTime ? formatDateTime(auth.user.registerTime) : "-"}</span>
      </div>
      <div class="flex justify-between">
        <span class="text-muted-foreground">账户状态</span>
        <span class="text-green-600">正常</span>
      </div>
    </div>
  </div>
</div>
