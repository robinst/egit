/*
 *  Copyright (C) 2006  Shawn Pearce <spearce@spearce.org>
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public
 *  License, version 2, as published by the Free Software Foundation.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 */
package org.spearce.jgit.lib;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

public class RefLock {
	private final File ref;

	private final File lck;

	private FileLock fLck;

	private boolean haveLck;

	private OutputStream os;

	public RefLock(final Ref r) {
		ref = r.getFile();
		lck = new File(ref.getParentFile(), ref.getName() + ".lock");
	}

	public boolean lock() throws IOException {
		lck.getParentFile().mkdirs();
		if (lck.createNewFile()) {
			haveLck = true;
			try {
				final FileOutputStream f = new FileOutputStream(lck);
				try {
					fLck = f.getChannel().tryLock();
					if (fLck != null)
						os = new BufferedOutputStream(f,
								Constants.OBJECT_ID_LENGTH * 2 + 1);
					else
						throw new OverlappingFileLockException();
				} catch (OverlappingFileLockException ofle) {
					// We cannot use unlock() here as this file is not
					// held by us, but we thought we created it. We must
					// not delete it, as it belongs to some other process.
					//
					haveLck = false;
					f.close();
				}
			} catch (IOException ioe) {
				unlock();
				throw ioe;
			}
		}
		return haveLck;
	}

	public void write(final ObjectId id) throws IOException {
		try {
			id.copyTo(os);
			os.write('\n');
			os.flush();
			fLck.release();
			os.close();
			os = null;
		} catch (IOException ioe) {
			unlock();
			throw ioe;
		} catch (RuntimeException ioe) {
			unlock();
			throw ioe;
		}
	}

	public boolean commit() {
		if (lck.renameTo(ref))
			return true;
		unlock();
		return false;
	}

	public void unlock() {
		if (os != null) {
			try {
				os.close();
			} catch (IOException ioe) {
				// Ignore this
			}
			os = null;
		}

		if (haveLck) {
			haveLck = false;
			lck.delete();
		}
	}
}
