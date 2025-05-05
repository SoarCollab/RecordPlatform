import {createGlobalState, useStorage} from "@vueuse/core";

export const STORAGE_AUTHORIZE_KEY = 'Authorization'

export const useAuthorization = createGlobalState(
  () => {
    return {
      token: useStorage(STORAGE_AUTHORIZE_KEY, null),
      monitorAuth: useStorage('monitor_auth', null)
    }
  }
)
