/*
 *  Copyright (C) 2008  Shawn Pearce <spearce@spearce.org>
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
package org.spearce.jgit.revwalk;

import java.io.IOException;

import org.spearce.jgit.errors.IncorrectObjectTypeException;
import org.spearce.jgit.errors.MissingObjectException;

/**
 * Replaces a RevCommit's parents until not colored with REWRITE.
 * <p>
 * Before a RevCommit is returned to the caller its parents are updated to
 * create a dense DAG. Instead of reporting the actual parents as recorded when
 * the commit was created the returned commit will reflect the next closest
 * commit that matched the revision walker's filters.
 * <p>
 * This generator is the second phase of a path limited revision walk and
 * assumes it is receiving RevCommits from {@link RewriteTreeFilter},
 * after they have been fully buffered by {@link AbstractRevQueue}. The full
 * buffering is necessary to allow the simple loop used within our own
 * {@link #rewrite(RevCommit)} to pull completely through a strand of
 * {@link RevWalk#REWRITE} colored commits and come up with a simplification
 * that makes the DAG dense. Not fully buffering the commits first would cause
 * this loop to abort early, due to commits not being parsed and colored
 * correctly.
 * 
 * @see RewriteTreeFilter
 */
class RewriteGenerator extends Generator {
	private static final int REWRITE = RevWalk.REWRITE;

	/** For {@link #cleanup(RevCommit[])} to remove duplicate parents. */
	private static final int DUPLICATE = RevWalk.TEMP_MARK;

	private final Generator source;

	RewriteGenerator(final Generator s) {
		source = s;
	}

	@Override
	void shareFreeList(final BlockRevQueue q) {
		source.shareFreeList(q);
	}

	@Override
	int outputType() {
		return source.outputType() & ~NEEDS_REWRITE;
	}

	@Override
	RevCommit next() throws MissingObjectException,
			IncorrectObjectTypeException, IOException {
		for (;;) {
			final RevCommit c = source.next();
			if (c == null)
				return null;

			boolean rewrote = false;
			final RevCommit[] pList = c.parents;
			final int nParents = pList.length;
			for (int i = 0; i < nParents; i++) {
				final RevCommit oldp = pList[i];
				final RevCommit newp = rewrite(oldp);
				if (oldp != newp) {
					pList[i] = newp;
					rewrote = true;
				}
			}
			if (rewrote)
				c.parents = cleanup(pList);

			return c;
		}
	}

	private RevCommit rewrite(RevCommit p) {
		for (;;) {
			final RevCommit[] pList = p.parents;
			if (pList.length > 1) {
				// This parent is a merge, so keep it.
				//
				return p;
			}

			if ((p.flags & RevWalk.UNINTERESTING) != 0) {
				// Retain uninteresting parents. They show where the
				// DAG was cut off because it wasn't interesting.
				//
				return p;
			}

			if ((p.flags & REWRITE) == 0) {
				// This parent was not eligible for rewriting. We
				// need to keep it in the DAG.
				//
				return p;
			}

			if (pList.length == 0) {
				// We can't go back any further, other than to
				// just delete the parent entirely.
				//
				return null;
			}

			p = pList[0];
		}
	}

	private RevCommit[] cleanup(final RevCommit[] oldList) {
		// Remove any duplicate parents caused due to rewrites (e.g. a merge
		// with two sides that both simplified back into the merge base).
		// We also may have deleted a parent by marking it null.
		//
		int newCnt = 0;
		for (int o = 0; o < oldList.length; o++) {
			final RevCommit p = oldList[o];
			if (p == null)
				continue;
			if ((p.flags & DUPLICATE) != 0) {
				oldList[o] = null;
				continue;
			}
			p.flags |= DUPLICATE;
			newCnt++;
		}

		if (newCnt == oldList.length) {
			for (final RevCommit p : oldList)
				p.flags &= ~DUPLICATE;
			return oldList;
		}

		final RevCommit[] newList = new RevCommit[newCnt];
		newCnt = 0;
		for (final RevCommit p : oldList) {
			if (p != null) {
				newList[newCnt++] = p;
				p.flags &= ~DUPLICATE;
			}
		}

		return newList;
	}
}
