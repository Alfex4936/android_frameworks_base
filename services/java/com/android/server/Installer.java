/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import android.content.pm.PackageStats;
import android.net.LocalSocketAddress;
import android.net.LocalSocket;
import android.util.Config;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;


class Installer {
    private static final String TAG = "Installer";
	InputStream mIn;
	OutputStream mOut;
	LocalSocket mSocket;

	byte buf[] = new byte[1024];
	int buflen = 0;

    private boolean connect() {
        if (mSocket != null) {
            return true;
        }
        Log.i(TAG, "connecting...");
        try {
            mSocket = new LocalSocket();

            LocalSocketAddress address = new LocalSocketAddress(
                "installd", LocalSocketAddress.Namespace.RESERVED);

            mSocket.connect(address);

            mIn = mSocket.getInputStream();
            mOut = mSocket.getOutputStream();
        } catch (IOException ex) {
            disconnect();
            return false;
        }
        return true;
    }

	private void disconnect() {
        Log.i(TAG,"disconnecting...");
		try {
			if (mSocket != null) mSocket.close();
		} catch (IOException ex) { }
		try {
			if (mIn != null) mIn.close();
		} catch (IOException ex) { }
		try {
			if (mOut != null) mOut.close();
		} catch (IOException ex) { }
		mSocket = null;
		mIn = null;
		mOut = null;
	}

	private boolean readBytes(byte buffer[], int len) {
		int off = 0, count;
        if (len < 0) return false;
		while (off != len) {
			try {
				count = mIn.read(buffer, off, len - off);
				if (count <= 0) {
                    Log.e(TAG, "read error " + count);
                    break;
                }
				off += count;
			} catch (IOException ex) {
                Log.e(TAG,"read exception");
				break;
			}
		}
//        Log.i(TAG, "read "+len+" bytes");
		if (off == len) return true;
		disconnect();
		return false;
	}

	private boolean readReply() {
		int len;
		buflen = 0;
		if (!readBytes(buf, 2)) return false;
		len = (((int) buf[0]) & 0xff) | ((((int) buf[1]) & 0xff) << 8);
		if ((len < 1) || (len > 1024)) {
            Log.e(TAG,"invalid reply length ("+len+")");
			disconnect();
			return false;
		}
		if (!readBytes(buf, len)) return false;
		buflen = len;
		return true;
	}

	private boolean writeCommand(String _cmd) {
		byte[] cmd = _cmd.getBytes();
		int len = cmd.length;
		if ((len < 1) || (len > 1024)) return false;
		buf[0] = (byte) (len & 0xff);
		buf[1] = (byte) ((len >> 8) & 0xff);
		try {
			mOut.write(buf, 0, 2);
			mOut.write(cmd, 0, len);
		} catch (IOException ex) {
            Log.e(TAG,"write error");
			disconnect();
			return false;
		}
		return true;
	}
		
	private synchronized String transaction(String cmd) {
		if (!connect()) {
            Log.e(TAG, "connection failed");
            return "-1";
        }

        if (!writeCommand(cmd)) {
                /* If installd died and restarted in the background
                 * (unlikely but possible) we'll fail on the next
                 * write (this one).  Try to reconnect and write
                 * the command one more time before giving up.
                 */
            Log.e(TAG, "write command failed? reconnect!");
            if (!connect() || !writeCommand(cmd)) {
                return "-1";
            }
        }
//        Log.i(TAG,"send: '"+cmd+"'");
		if (readReply()) {
            String s = new String(buf, 0, buflen);
//            Log.i(TAG,"recv: '"+s+"'");
			return s;
		} else {
//            Log.i(TAG,"fail");
			return "-1";
		}
	}

	private int execute(String cmd) {
		String res = transaction(cmd);
		try {
			return Integer.parseInt(res);
		} catch (NumberFormatException ex) {
			return -1;
		}
	}

    public int install(String name, boolean useEncryptedFilesystem, int uid, int gid) {
        StringBuilder builder = new StringBuilder("install");
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        if (useEncryptedFilesystem) {
            builder.append('1');
        } else {
            builder.append('0');
        }
        builder.append(' ');
        builder.append(uid);
        builder.append(' ');
        builder.append(gid);
        return execute(builder.toString());
    }

    public int dexopt(String apkPath, int uid, boolean isPublic) {
        StringBuilder builder = new StringBuilder("dexopt");
        builder.append(' ');
        builder.append(apkPath);
        builder.append(' ');
        builder.append(uid);
        builder.append(isPublic ? " 1" : " 0");
        return execute(builder.toString());
    }

    public int movedex(String srcPath, String dstPath) {
        StringBuilder builder = new StringBuilder("movedex");
        builder.append(' ');
        builder.append(srcPath);
        builder.append(' ');
        builder.append(dstPath);
        return execute(builder.toString());
    }

    public int rmdex(String codePath) {
        StringBuilder builder = new StringBuilder("rmdex");
        builder.append(' ');
        builder.append(codePath);
        return execute(builder.toString());
    }

    public int remove(String name, boolean useEncryptedFilesystem) {
        StringBuilder builder = new StringBuilder("remove");
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        if (useEncryptedFilesystem) {
            builder.append('1');
        } else {
            builder.append('0');
        }
        return execute(builder.toString());
    }

    public int deleteCacheFiles(String name, boolean useEncryptedFilesystem) {
        StringBuilder builder = new StringBuilder("rmcache");
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        if (useEncryptedFilesystem) {
            builder.append('1');
        } else {
            builder.append('0');
        }
        return execute(builder.toString());
    }
    
    public int clearUserData(String name, boolean useEncryptedFilesystem) {
        StringBuilder builder = new StringBuilder("rmuserdata");
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        if (useEncryptedFilesystem) {
            builder.append('1');
        } else {
            builder.append('0');
        }
        return execute(builder.toString());
    }
    
    public boolean ping() {
        if (execute("ping") < 0) {
            return false;
        } else {
            return true;
        }
    }
    
    public int freeCache(long freeStorageSize) {
        StringBuilder builder = new StringBuilder("freecache");
        builder.append(' ');
        builder.append(String.valueOf(freeStorageSize));
        return execute(builder.toString());
    }

    public int setForwardLockPerm(String packageName, int gid) {
        StringBuilder builder = new StringBuilder("protect");
        builder.append(' ');
        builder.append(packageName);
        builder.append(' ');
        builder.append(gid);
        return execute(builder.toString());
    }
    
    public int getSizeInfo(String pkgName, String apkPath,
            String fwdLockApkPath, PackageStats pStats, boolean useEncryptedFilesystem) {
        StringBuilder builder = new StringBuilder("getsize");
        builder.append(' ');
        builder.append(pkgName);
        builder.append(' ');
        builder.append(apkPath);
        builder.append(' ');
        builder.append(fwdLockApkPath != null ? fwdLockApkPath : "!");
        builder.append(' ');
        if (useEncryptedFilesystem) {
            builder.append('1');
        } else {
            builder.append('0');
        }

        String s = transaction(builder.toString());
        String res[] = s.split(" ");

        if((res == null) || (res.length != 4)) {
            return -1;
        }
        try {
            pStats.codeSize = Long.parseLong(res[1]);
            pStats.dataSize = Long.parseLong(res[2]);
            pStats.cacheSize = Long.parseLong(res[3]);
            return Integer.parseInt(res[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }    
}
