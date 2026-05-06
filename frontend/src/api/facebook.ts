declare global {
  interface Window {
    FB?: {
      init: (params: { appId: string; cookie?: boolean; xfbml?: boolean; version: string }) => void;
      login: (
        cb: (response: FacebookLoginResponse) => void,
        opts: { scope: string; return_scopes?: boolean },
      ) => void;
      api: (
        path: string,
        params: { fields: string },
        cb: (response: FacebookProfile) => void,
      ) => void;
    };
    fbAsyncInit?: () => void;
  }
}

export interface FacebookLoginResponse {
  status: "connected" | "not_authorized" | "unknown";
  authResponse?: {
    accessToken: string;
    userID: string;
    expiresIn: number;
    grantedScopes?: string;
  };
}

export interface FacebookProfile {
  id: string;
  name: string;
  email?: string;
  picture?: { data: { url: string } };
}

let initPromise: Promise<void> | null = null;

export function loadFacebookSdk(appId: string): Promise<void> {
  if (initPromise) return initPromise;
  initPromise = new Promise((resolve) => {
    const init = () => {
      window.FB!.init({ appId, cookie: true, xfbml: false, version: "v19.0" });
      resolve();
    };
    if (window.FB) {
      init();
    } else {
      window.fbAsyncInit = init;
    }
  });
  return initPromise;
}

export function fbLogin(scope: string): Promise<FacebookLoginResponse> {
  return new Promise((resolve) => {
    window.FB!.login(resolve, { scope, return_scopes: true });
  });
}

export function fbProfile(): Promise<FacebookProfile> {
  return new Promise((resolve) => {
    window.FB!.api("/me", { fields: "id,name,email,picture" }, resolve);
  });
}