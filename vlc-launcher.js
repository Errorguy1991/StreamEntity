import fs from 'fs';
import path from 'path';
import { spawn } from 'child_process';

const VLC_CANDIDATES = [
  'C:\\Program Files\\VideoLAN\\VLC\\vlc.exe',
  'C:\\Program Files (x86)\\VideoLAN\\VLC\\vlc.exe',
  path.join(process.env.LOCALAPPDATA || '', 'Programs\\VideoLAN\\VLC\\vlc.exe')
];

export function getVlcPath() {
  for (const candidate of VLC_CANDIDATES) {
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }
  return null;
}

export function launchVlc(streamUrl, title = 'StreamEntity Live Stream') {
  const vlcPath = getVlcPath();
  if (!vlcPath) {
    return { success: false, error: 'VLC Media Player executable not found in default paths.' };
  }

  try {
    const args = [
      '--meta-title', title,
      '--network-caching=1000',
      '--no-video-title-show',
      streamUrl
    ];

    const child = spawn(vlcPath, args, {
      detached: true,
      stdio: 'ignore'
    });
    child.unref();

    return { success: true, path: vlcPath };
  } catch (err) {
    return { success: false, error: err.message };
  }
}
