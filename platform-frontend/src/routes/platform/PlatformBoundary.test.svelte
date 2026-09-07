<script lang="ts">
  import RootLayout from "../+layout.svelte";
  import HomePage from "../+page.svelte";
  import TenantLayout from "../(app)/+layout.svelte";
  import AuthLayout from "../(auth)/+layout.svelte";
  import LoginPage from "../(auth)/login/+page.svelte";
  import RegisterPage from "../(auth)/register/+page.svelte";
  import InvitationPage from "../invitations/accept/+page.svelte";
  import PlatformLayout from "./+layout.svelte";
  import OverviewPage from "./+page.svelte";
  import BoundaryChild from "./BoundaryChild.test.svelte";

  let {
    surface = "platform",
    onEnter,
  }: {
    surface?:
      | "platform"
      | "tenant"
      | "home"
      | "login"
      | "register"
      | "invitation"
      | "public";
    onEnter: () => void;
  } = $props();
</script>

<RootLayout>
  {#if surface === "platform"}
    <PlatformLayout>
      <BoundaryChild {onEnter} />
      <OverviewPage />
    </PlatformLayout>
  {:else if surface === "tenant"}
    <TenantLayout><BoundaryChild {onEnter} /></TenantLayout>
  {:else if surface === "home"}
    <HomePage />
  {:else if surface === "login"}
    <AuthLayout><LoginPage /></AuthLayout>
  {:else if surface === "register"}
    <AuthLayout><RegisterPage /></AuthLayout>
  {:else if surface === "invitation"}
    <InvitationPage />
  {:else}
    <p>Public share content</p>
  {/if}
</RootLayout>
