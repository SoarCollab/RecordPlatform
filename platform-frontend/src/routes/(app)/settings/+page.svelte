<script lang="ts">
  import { useAuth } from "$stores/auth.svelte";
  import { useNotifications } from "$stores/notifications.svelte";
  import * as validation from "$utils/validation";
  import { getAvatarUrl } from "$utils/avatar";
  import { changePassword } from "$api/endpoints/auth";
  import { uploadAvatar } from "$api/endpoints/images"; // Keep this as it's used later

  // UI Components
  import { Button } from "$lib/components/ui/button";
  import { Input } from "$lib/components/ui/input";
  import { Label } from "$lib/components/ui/label";
  import * as Card from "$lib/components/ui/card";
  import * as Dialog from "$lib/components/ui/dialog";
  import * as Avatar from "$lib/components/ui/avatar";

  const auth = useAuth();
  const notifications = useNotifications();

  // Profile state
  let displayName = $state(auth.user?.nickname || "");
  let isProfileSubmitting = $state(false);

  // Avatar Upload State
  let isUploadingAvatar = $state(false);
  let fileInput: HTMLInputElement;

  // Dialog States
  let showPasswordDialog = $state(false);

  // Password Form State
  let oldPassword = $state("");
  let newPassword = $state("");
  let confirmPwd = $state("");
  let isChangingPassword = $state(false);

  $effect(() => {
    // Sync local state when auth user updates
    if (auth.user) {
      displayName = auth.user.nickname || "";
    }
  });

  async function handleAvatarClick() {
    fileInput?.click();
  }

  async function handleFileChange(event: Event) {
    const target = event.target as HTMLInputElement;
    const file = target.files?.[0];
    if (!file) return;

    // Validate size (100KB limit from backend)
    if (file.size > 100 * 1024) {
      notifications.warning("头像文件过大", "请上传小于 100KB 的图片");
      target.value = ""; // Reset input
      return;
    }

    isUploadingAvatar = true;
    try {
      await uploadAvatar(file);
      // Refresh the user profile to show the new avatar
      await auth.fetchUser();
      notifications.success("头像上传成功");
    } catch (err) {
      notifications.error(
        "头像上传失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    } finally {
      isUploadingAvatar = false;
      target.value = ""; // Reset input
    }
  }

  async function handleSaveProfile() {
    isProfileSubmitting = true;
    try {
      await auth.updateProfile({ nickname: displayName });
      notifications.success("保存成功", "个人信息已更新");
    } catch (err) {
      notifications.error(
        "保存失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    } finally {
      isProfileSubmitting = false;
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
      await changePassword({
        password: oldPassword,
        new_password: newPassword,
      });
      notifications.success("密码修改成功", "请使用新密码重新登录");
      showPasswordDialog = false;
      // Reset form
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

  // Helper to mask email
  function maskEmail(email?: string) {
    if (!email) return "未绑定";
    const [name, domain] = email.split("@");
    if (!domain) return email;
    return `${name.slice(0, 3)}****@${domain}`;
  }

</script>

<svelte:head>
  <title>个人设置 - 存证平台</title>
</svelte:head>

<div class="space-y-8 p-2">
  <!-- Header -->
  <div>
    <h1 class="text-3xl font-bold tracking-tight">个人信息</h1>
    <p class="mt-2 text-muted-foreground">更新您的基本信息和联系方式</p>
  </div>

  <div class="space-y-6">
    <!-- Basic Information Card -->
    <Card.Root
      class="overflow-hidden border-border/50 bg-card/50 backdrop-blur-sm"
    >
      <Card.Content class="p-8">
        <div class="flex flex-col gap-8 md:flex-row">
          <!-- Avatar Section -->
          <div class="flex flex-col items-center gap-4 md:w-64">
            <div class="relative group">
              <Avatar.Root
                class="h-32 w-32 border-4 border-background shadow-xl"
              >
                <Avatar.Image
                  src={getAvatarUrl(auth.user?.avatar)}
                  alt={auth.user?.nickname}
                />
                <Avatar.Fallback class="bg-primary/10 text-4xl text-primary">
                  {auth.user?.nickname?.charAt(0).toUpperCase() || "U"}
                </Avatar.Fallback>
              </Avatar.Root>
              <button
                type="button"
                class="absolute inset-0 flex items-center justify-center rounded-full bg-black/60 opacity-0 transition-opacity group-hover:opacity-100 focus:opacity-100 cursor-pointer border-none"
                onclick={handleAvatarClick}
                aria-label="更换头像"
              >
                <span class="text-white text-sm font-medium">
                  {isUploadingAvatar ? "上传中..." : "更换头像"}
                </span>
              </button>
              <input
                type="file"
                accept="image/*"
                class="hidden"
                bind:this={fileInput}
                onchange={handleFileChange}
              />
            </div>
            <div class="text-center">
              <p class="text-xs text-muted-foreground">支持 JPG, PNG, GIF</p>
              <p class="text-xs text-muted-foreground">最大 100KB</p>
            </div>
          </div>

          <!-- Form Section -->
          <div class="flex-1 space-y-6">
            <div class="grid gap-6">
              <!-- Nickname -->
              <div class="space-y-2">
                <Label for="displayName" class="text-base"
                  >昵称 <span class="text-destructive">*</span></Label
                >
                <Input
                  id="displayName"
                  bind:value={displayName}
                  class="max-w-md h-10 bg-background/50"
                />
              </div>

              <!-- Login Account (Read-only) -->
              <div class="space-y-2">
                <Label class="text-base text-muted-foreground">登录账号</Label>
                <div
                  class="flex h-10 w-full max-w-md items-center rounded-md border border-input bg-muted px-3 py-2 text-sm text-muted-foreground opacity-50"
                >
                  {auth.username}
                </div>
              </div>
            </div>

            <div class="pt-4">
              <Button
                onclick={handleSaveProfile}
                disabled={isProfileSubmitting}
                size="lg"
              >
                {isProfileSubmitting ? "保存中..." : "保存更改"}
              </Button>
            </div>
          </div>
        </div>
      </Card.Content>
    </Card.Root>

    <!-- Account Security Card -->
    <Card.Root
      class="overflow-hidden border-border/50 bg-card/50 backdrop-blur-sm"
    >
      <Card.Header class="px-8 pt-8 pb-4">
        <Card.Title>账户安全</Card.Title>
        <Card.Description>管理您的登录凭证和安全设置</Card.Description>
      </Card.Header>
      <Card.Content class="p-0">
        <div class="divide-y divide-border/50">
          <!-- Email Item -->
          <div
            class="flex items-center justify-between px-8 py-6 hover:bg-muted/30 transition-colors"
          >
            <div class="flex items-center gap-4">
              <div
                class="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10 text-primary"
              >
                <svg
                  class="h-5 w-5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
                  />
                </svg>
              </div>
              <div>
                <p class="font-medium">邮箱地址</p>
                <p class="text-sm text-muted-foreground">
                  {maskEmail(auth.user?.email)}
                </p>
              </div>
            </div>
            <!-- <Button variant="ghost" class="gap-2 text-muted-foreground hover:text-primary" onclick={() => showEmailDialog = true}>
                    修改 <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"/></svg>
                </Button> -->
            <div class="text-sm text-muted-foreground">此处暂不支持修改</div>
          </div>

          <!-- Password Item -->
          <div
            class="flex items-center justify-between px-8 py-6 hover:bg-muted/30 transition-colors"
          >
            <div class="flex items-center gap-4">
              <div
                class="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10 text-primary"
              >
                <svg
                  class="h-5 w-5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
                  />
                </svg>
              </div>
              <div>
                <p class="font-medium">登录密码</p>
                <p class="text-sm text-muted-foreground">
                  定期更换密码可以提高账户安全性
                </p>
              </div>
            </div>
            <Button
              variant="ghost"
              class="gap-2 text-muted-foreground hover:text-primary"
              onclick={() => (showPasswordDialog = true)}
            >
              修改 <svg
                class="h-4 w-4"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M9 5l7 7-7 7"
                /></svg
              >
            </Button>
          </div>
        </div>
      </Card.Content>
    </Card.Root>
  </div>
</div>

<!-- Change Password Dialog -->
<Dialog.Root bind:open={showPasswordDialog}>
  <Dialog.Content>
    <Dialog.Header>
      <Dialog.Title>修改密码</Dialog.Title>
      <Dialog.Description>请输入当前密码和新密码进行修改。</Dialog.Description>
    </Dialog.Header>
    <div class="space-y-4 py-4">
      <div class="space-y-2">
        <Label for="old-pwd">原密码</Label>
        <Input
          id="old-pwd"
          type="password"
          bind:value={oldPassword}
          placeholder="请输入原密码"
        />
      </div>
      <div class="space-y-2">
        <Label for="new-pwd">新密码</Label>
        <Input
          id="new-pwd"
          type="password"
          bind:value={newPassword}
          placeholder="8-20位，包含字母和数字"
        />
      </div>
      <div class="space-y-2">
        <Label for="confirm-pwd">确认新密码</Label>
        <Input
          id="confirm-pwd"
          type="password"
          bind:value={confirmPwd}
          placeholder="再次输入新密码"
        />
      </div>
    </div>
    <Dialog.Footer>
      <Button variant="outline" onclick={() => (showPasswordDialog = false)}
        >取消</Button
      >
      <Button onclick={handleChangePassword} disabled={isChangingPassword}>
        {isChangingPassword ? "提交中..." : "确认修改"}
      </Button>
    </Dialog.Footer>
  </Dialog.Content>
</Dialog.Root>
