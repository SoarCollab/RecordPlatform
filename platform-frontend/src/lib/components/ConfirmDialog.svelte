<script lang="ts">
  import * as Dialog from "$lib/components/ui/dialog";
  import { Button } from "$lib/components/ui/button";

  let {
    open = $bindable(false),
    title = "确认操作",
    description = "",
    confirmText = "确认",
    cancelText = "取消",
    variant = "destructive" as "destructive" | "default",
    loading = false,
    onConfirm,
    onCancel,
  }: {
    open: boolean;
    title?: string;
    description?: string;
    confirmText?: string;
    cancelText?: string;
    variant?: "destructive" | "default";
    loading?: boolean;
    onConfirm: () => void | Promise<void>;
    onCancel?: () => void;
  } = $props();

  async function handleConfirm() {
    await onConfirm();
  }

  function handleCancel() {
    open = false;
    onCancel?.();
  }
</script>

<Dialog.Root bind:open>
  <Dialog.Content class="sm:max-w-md">
    <Dialog.Header>
      <Dialog.Title>{title}</Dialog.Title>
      {#if description}
        <Dialog.Description>{description}</Dialog.Description>
      {/if}
    </Dialog.Header>
    <Dialog.Footer>
      <Button variant="outline" onclick={handleCancel} disabled={loading}>
        {cancelText}
      </Button>
      <Button
        variant={variant}
        onclick={handleConfirm}
        disabled={loading}
      >
        {#if loading}
          <div class="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent"></div>
        {/if}
        {confirmText}
      </Button>
    </Dialog.Footer>
  </Dialog.Content>
</Dialog.Root>
