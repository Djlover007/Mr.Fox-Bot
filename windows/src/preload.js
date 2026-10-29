'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('bot', {
  call: (method, ...args) => ipcRenderer.invoke('call', method, args),
  onChanged: (cb) => ipcRenderer.on('changed', () => cb()),
  onToast: (cb) => ipcRenderer.on('toast', (_e, msg) => cb(msg)),
});
