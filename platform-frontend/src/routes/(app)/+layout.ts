import { redirect } from '@sveltejs/kit';
import { browser } from '$app/environment';
import { getToken } from '$api/client';
import type { LayoutLoad } from './$types';

export const load: LayoutLoad = async () => {
	// Check authentication on client side
	// getToken() checks both localStorage and sessionStorage based on "remember me" preference
	if (browser && !getToken()) {
		throw redirect(302, '/login');
	}

	return {};
};
