const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('tmam', {
  platform: process.platform,
  selectDirectory: (defaultPath) => ipcRenderer.invoke('dialog:selectDirectory', defaultPath),
})
