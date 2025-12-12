import type { PageLoad } from './$types';

export const load: PageLoad = ({ url }) => {
	return {
		receiverUsername: url.searchParams.get('to') || ''
	};
};
