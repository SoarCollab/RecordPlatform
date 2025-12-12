<script lang="ts">
	import { useNotifications } from '$stores/notifications.svelte';
	import { sendResetCode, confirmResetCode, resetPassword } from '$lib/api/endpoints/auth';
	import { goto } from '$app/navigation';

	const notifications = useNotifications();

	// 步骤: 1=输入邮箱, 2=输入验证码, 3=设置新密码, 4=完成
	let step = $state(1);
	let email = $state('');
	let code = $state('');
	let password = $state('');
	let confirmPassword = $state('');
	let isSubmitting = $state(false);

	// 倒计时
	let countdown = $state(0);
	let countdownTimer: ReturnType<typeof setInterval> | null = null;

	function startCountdown() {
		countdown = 60;
		countdownTimer = setInterval(() => {
			countdown--;
			if (countdown <= 0 && countdownTimer) {
				clearInterval(countdownTimer);
				countdownTimer = null;
			}
		}, 1000);
	}

	async function handleSendCode(e: Event) {
		e.preventDefault();

		if (!email) {
			notifications.warning('请输入邮箱', '邮箱地址不能为空');
			return;
		}

		isSubmitting = true;

		try {
			await sendResetCode(email);
			notifications.success('发送成功', '验证码已发送到您的邮箱');
			startCountdown();
			step = 2;
		} catch (err) {
			notifications.error('发送失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			isSubmitting = false;
		}
	}

	async function handleResendCode() {
		if (countdown > 0) return;

		isSubmitting = true;

		try {
			await sendResetCode(email);
			notifications.success('发送成功', '验证码已重新发送');
			startCountdown();
		} catch (err) {
			notifications.error('发送失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			isSubmitting = false;
		}
	}

	async function handleConfirmCode(e: Event) {
		e.preventDefault();

		if (!code || code.length !== 6) {
			notifications.warning('验证码错误', '请输入6位验证码');
			return;
		}

		isSubmitting = true;

		try {
			await confirmResetCode({ email, code });
			notifications.success('验证成功', '请设置新密码');
			step = 3;
		} catch (err) {
			notifications.error('验证失败', err instanceof Error ? err.message : '验证码错误或已过期');
		} finally {
			isSubmitting = false;
		}
	}

	async function handleResetPassword(e: Event) {
		e.preventDefault();

		if (!password || password.length < 6) {
			notifications.warning('密码太短', '密码长度至少6位');
			return;
		}

		if (password !== confirmPassword) {
			notifications.warning('密码不一致', '两次输入的密码不一致');
			return;
		}

		isSubmitting = true;

		try {
			await resetPassword({ email, code, password });
			notifications.success('重置成功', '密码已重置，请使用新密码登录');
			step = 4;
		} catch (err) {
			notifications.error('重置失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			isSubmitting = false;
		}
	}

	function handleGoLogin() {
		goto('/login');
	}
</script>

<svelte:head>
	<title>重置密码 - 存证平台</title>
</svelte:head>

<div class="rounded-lg border bg-card p-8 shadow-sm">
	<div class="mb-6 text-center">
		<div class="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-lg bg-primary text-primary-foreground">
			<svg class="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z" />
			</svg>
		</div>
		<h1 class="text-2xl font-bold">重置密码</h1>
		<p class="text-sm text-muted-foreground">
			{#if step === 1}
				输入您的注册邮箱
			{:else if step === 2}
				输入验证码
			{:else if step === 3}
				设置新密码
			{:else}
				重置完成
			{/if}
		</p>
	</div>

	<!-- 步骤指示器 -->
	{#if step < 4}
		<div class="mb-6 flex justify-center gap-2">
			{#each [1, 2, 3] as s}
				<div
					class="h-2 w-8 rounded-full transition-colors {s <= step ? 'bg-primary' : 'bg-muted'}"
				></div>
			{/each}
		</div>
	{/if}

	<!-- 步骤 1: 输入邮箱 -->
	{#if step === 1}
		<form onsubmit={handleSendCode} class="space-y-4">
			<div>
				<label for="email" class="mb-2 block text-sm font-medium">邮箱</label>
				<input
					type="email"
					id="email"
					bind:value={email}
					class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
					placeholder="请输入注册时使用的邮箱"
					disabled={isSubmitting}
				/>
			</div>

			<button
				type="submit"
				class="w-full rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
				disabled={isSubmitting}
			>
				{isSubmitting ? '发送中...' : '发送验证码'}
			</button>
		</form>

	<!-- 步骤 2: 输入验证码 -->
	{:else if step === 2}
		<form onsubmit={handleConfirmCode} class="space-y-4">
			<div class="rounded-lg bg-muted/50 p-3 text-center text-sm">
				验证码已发送至 <strong>{email}</strong>
			</div>

			<div>
				<label for="code" class="mb-2 block text-sm font-medium">验证码</label>
				<input
					type="text"
					id="code"
					bind:value={code}
					maxlength="6"
					class="w-full rounded-lg border bg-background px-3 py-2 text-center text-lg tracking-widest focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
					placeholder="请输入6位验证码"
					disabled={isSubmitting}
				/>
			</div>

			<button
				type="submit"
				class="w-full rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
				disabled={isSubmitting}
			>
				{isSubmitting ? '验证中...' : '验证'}
			</button>

			<div class="flex items-center justify-between text-sm">
				<button
					type="button"
					class="text-muted-foreground hover:text-foreground"
					onclick={() => (step = 1)}
				>
					修改邮箱
				</button>
				<button
					type="button"
					class="text-primary hover:underline disabled:text-muted-foreground disabled:no-underline"
					disabled={countdown > 0 || isSubmitting}
					onclick={handleResendCode}
				>
					{countdown > 0 ? `${countdown}s 后重发` : '重新发送'}
				</button>
			</div>
		</form>

	<!-- 步骤 3: 设置新密码 -->
	{:else if step === 3}
		<form onsubmit={handleResetPassword} class="space-y-4">
			<div>
				<label for="password" class="mb-2 block text-sm font-medium">新密码</label>
				<input
					type="password"
					id="password"
					bind:value={password}
					class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
					placeholder="请输入新密码（至少6位）"
					disabled={isSubmitting}
				/>
			</div>

			<div>
				<label for="confirmPassword" class="mb-2 block text-sm font-medium">确认密码</label>
				<input
					type="password"
					id="confirmPassword"
					bind:value={confirmPassword}
					class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
					placeholder="请再次输入新密码"
					disabled={isSubmitting}
				/>
			</div>

			<button
				type="submit"
				class="w-full rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
				disabled={isSubmitting}
			>
				{isSubmitting ? '重置中...' : '重置密码'}
			</button>
		</form>

	<!-- 步骤 4: 完成 -->
	{:else}
		<div class="space-y-4">
			<div class="rounded-lg bg-success/10 p-4 text-center">
				<svg class="mx-auto mb-2 h-12 w-12 text-success" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
				</svg>
				<p class="text-sm text-success">密码重置成功！</p>
			</div>

			<button
				type="button"
				class="w-full rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
				onclick={handleGoLogin}
			>
				前往登录
			</button>
		</div>
	{/if}

	<div class="mt-6 text-center text-sm">
		<a href="/login" class="text-primary hover:underline">返回登录</a>
	</div>
</div>
