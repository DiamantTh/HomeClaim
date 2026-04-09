import { browser } from '$app/environment';
import { writable } from 'svelte/store';

export const wsConnected = writable(false);

let socket: WebSocket | null = null;

export function initWebSocket(): void {
  if (!browser || socket) {
    return;
  }

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  socket = new WebSocket(`${protocol}//${window.location.host}/ws`);

  socket.addEventListener('open', () => wsConnected.set(true));
  socket.addEventListener('close', () => {
    wsConnected.set(false);
    socket = null;
  });
  socket.addEventListener('error', () => wsConnected.set(false));
}
