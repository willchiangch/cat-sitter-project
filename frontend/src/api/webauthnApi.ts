import axiosClient from './axiosClient';

// --- base64url <-> ArrayBuffer helpers ---
// 刻意手刻，不依賴瀏覽器較新的 PublicKeyCredential.parseCreationOptionsFromJSON()／toJSON()，
// 因為 NFR-005 要求相容 Safari (iOS 15+)，這兩個較新的 WebAuthn Level 3 API 在舊版 Safari 尚不支援。

function base64UrlToArrayBuffer(base64Url: string): ArrayBuffer {
  const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
  const binary = window.atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes.buffer;
}

function arrayBufferToBase64Url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return window.btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function isWebAuthnSupported(): boolean {
  return typeof window !== 'undefined' && typeof window.PublicKeyCredential !== 'undefined';
}

// 後端直接回傳 Yubico 函式庫的 toCredentialsCreateJson()/toCredentialsGetJson()，
// 格式已經是 { publicKey: {...} }，正好對應 navigator.credentials.create()/get() 的參數形狀，
// 這裡只需要把裡面 base64url 字串欄位轉回 ArrayBuffer。

function optionsToCreationOptions(json: any): CredentialCreationOptions {
  const publicKey = json.publicKey;
  publicKey.challenge = base64UrlToArrayBuffer(publicKey.challenge);
  publicKey.user.id = base64UrlToArrayBuffer(publicKey.user.id);
  if (Array.isArray(publicKey.excludeCredentials)) {
    publicKey.excludeCredentials = publicKey.excludeCredentials.map((cred: any) => ({
      ...cred,
      id: base64UrlToArrayBuffer(cred.id)
    }));
  }
  return json as CredentialCreationOptions;
}

function optionsToRequestOptions(json: any): CredentialRequestOptions {
  const publicKey = json.publicKey;
  publicKey.challenge = base64UrlToArrayBuffer(publicKey.challenge);
  if (Array.isArray(publicKey.allowCredentials)) {
    publicKey.allowCredentials = publicKey.allowCredentials.map((cred: any) => ({
      ...cred,
      id: base64UrlToArrayBuffer(cred.id)
    }));
  }
  return json as CredentialRequestOptions;
}

function registrationCredentialToJson(credential: PublicKeyCredential): string {
  const response = credential.response as AuthenticatorAttestationResponse;
  return JSON.stringify({
    type: credential.type,
    id: credential.id,
    rawId: arrayBufferToBase64Url(credential.rawId),
    response: {
      clientDataJSON: arrayBufferToBase64Url(response.clientDataJSON),
      attestationObject: arrayBufferToBase64Url(response.attestationObject)
    },
    clientExtensionResults: credential.getClientExtensionResults()
  });
}

function assertionCredentialToJson(credential: PublicKeyCredential): string {
  const response = credential.response as AuthenticatorAssertionResponse;
  return JSON.stringify({
    type: credential.type,
    id: credential.id,
    rawId: arrayBufferToBase64Url(credential.rawId),
    response: {
      clientDataJSON: arrayBufferToBase64Url(response.clientDataJSON),
      authenticatorData: arrayBufferToBase64Url(response.authenticatorData),
      signature: arrayBufferToBase64Url(response.signature),
      userHandle: response.userHandle ? arrayBufferToBase64Url(response.userHandle) : null
    },
    clientExtensionResults: credential.getClientExtensionResults()
  });
}

export async function registerBiometricCredential(): Promise<void> {
  const { data: optionsJson } = await axiosClient.post('/auth/webauthn/register/options');
  const creationOptions = optionsToCreationOptions(optionsJson);

  const credential = (await navigator.credentials.create(creationOptions)) as PublicKeyCredential | null;
  if (!credential) {
    throw new Error('生物辨識裝置註冊已取消');
  }

  await axiosClient.post('/auth/webauthn/register/verify', {
    credentialJson: registrationCredentialToJson(credential)
  });
}

export interface WebAuthnCredentialSummary {
  id: string;
  createdAt: string;
  lastUsedAt: string | null;
}

export async function listBiometricCredentials(): Promise<WebAuthnCredentialSummary[]> {
  const { data } = await axiosClient.get('/auth/webauthn/credentials');
  return data;
}

export async function deleteBiometricCredential(id: string): Promise<void> {
  await axiosClient.delete(`/auth/webauthn/credentials/${id}`);
}

export interface WebAuthnLoginResult {
  accessToken: string;
  refreshToken: string;
  userId: string;
  email: string;
  fullName: string;
  role: string;
}

export async function loginWithBiometricCredential(email: string): Promise<WebAuthnLoginResult> {
  const { data: optionsJson } = await axiosClient.post('/auth/webauthn/login/options', { email });
  const requestOptions = optionsToRequestOptions(optionsJson);

  const credential = (await navigator.credentials.get(requestOptions)) as PublicKeyCredential | null;
  if (!credential) {
    throw new Error('生物辨識登入已取消');
  }

  const { data } = await axiosClient.post('/auth/webauthn/login/verify', {
    email,
    credentialJson: assertionCredentialToJson(credential)
  });
  return data;
}
