<script lang="ts">
	import { goto } from "$app/navigation";
	import { useAuth } from "$stores/auth.svelte";

	let { children } = $props();

	const auth = useAuth();

	$effect(() => {
		if (auth.isAuthenticated) {
			goto("/dashboard", { replaceState: true });
		}
	});
</script>

{#if !auth.isAuthenticated}
	<div class="flex min-h-screen items-center justify-center bg-muted/50 px-4">
		<div class="w-full max-w-md">
			{@render children()}
		</div>
	</div>
{/if}
